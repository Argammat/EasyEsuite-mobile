package com.easyesuite.core.net

import com.easyesuite.core.ApiConfig
import com.easyesuite.core.auth.BackendTokenRefresher
import com.easyesuite.core.auth.FirebaseAuth
import com.easyesuite.core.auth.FirebaseTokenRefresher
import com.easyesuite.core.auth.Session
import com.easyesuite.core.auth.TokenRefresher
import com.easyesuite.core.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

val DefaultJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
}

/**
 * Thin OkHttp wrapper that knows about the tenant URL prefix, the JWT header and token refresh.
 * All calls are suspending and run on Dispatchers.IO.
 */
class ApiClient(
    val config: ApiConfig,
    val tokenStore: TokenStore,
    val json: Json = DefaultJson,
    baseClient: OkHttpClient = OkHttpClient(),
    /** Called once when a refresh fails and the user must sign in again. */
    var onSessionExpired: (() -> Unit)? = null,
    /**
     * How an expired access token is renewed. When null the strategy follows the session: tokens minted by
     * Firebase (`Session.provider == firebase`) go through the securetoken exchange (needs `config.firebase`),
     * everything else through the backend's `auth/token/refresh/`.
     */
    private val refresherOverride: TokenRefresher? = null,
) {
    private val firebaseRefresher: TokenRefresher? = config.firebase?.let { FirebaseTokenRefresher(FirebaseAuth(it, baseClient)) }
    private val backendRefresher: TokenRefresher = BackendTokenRefresher(this)

    private fun refresherFor(session: Session): TokenRefresher = refresherOverride
        ?: if (session.provider == Session.PROVIDER_FIREBASE) (firebaseRefresher ?: backendRefresher) else backendRefresher

    private val http: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val refreshMutex = Mutex()

    // ---- public API --------------------------------------------------------------------------

    suspend inline fun <reified T> get(path: String, query: Map<String, Any?> = emptyMap(), authenticated: Boolean = true): T =
        execute("GET", path, query, null, serializer<T>(), authenticated)

    suspend inline fun <reified B, reified T> post(path: String, body: B, query: Map<String, Any?> = emptyMap(), authenticated: Boolean = true): T =
        execute("POST", path, query, encodeBody(body, serializer<B>()), serializer<T>(), authenticated)

    suspend inline fun <reified T> postEmpty(path: String, query: Map<String, Any?> = emptyMap()): T =
        execute("POST", path, query, "{}", serializer<T>(), true)

    suspend inline fun <reified B, reified T> patch(path: String, body: B, query: Map<String, Any?> = emptyMap()): T =
        execute("PATCH", path, query, encodeBody(body, serializer<B>()), serializer<T>(), true)

    suspend inline fun <reified B, reified T> put(path: String, body: B): T =
        execute("PUT", path, emptyMap(), encodeBody(body, serializer<B>()), serializer<T>(), true)

    suspend fun delete(path: String) {
        execute("DELETE", path, emptyMap(), null, serializer<JsonElement>(), true, allowEmptyBody = true)
    }

    fun <B> encodeBody(body: B, strategy: SerializationStrategy<B>): String = json.encodeToString(strategy, body)

    /** Multipart upload (item photos). Returns the decoded JSON body. */
    suspend inline fun <reified T> upload(path: String, fieldName: String, fileName: String, mimeType: String, bytes: ByteArray): T =
        executeMultipart(path, fieldName, fileName, mimeType, bytes, serializer<T>())

    // ---- core --------------------------------------------------------------------------------

    suspend fun <T> execute(
        method: String,
        path: String,
        query: Map<String, Any?>,
        bodyJson: String?,
        deserializer: DeserializationStrategy<T>,
        authenticated: Boolean,
        allowEmptyBody: Boolean = false,
    ): T = withContext(Dispatchers.IO) {
        val url = buildUrl(path, query)
        val requestBody: RequestBody? = when {
            bodyJson != null -> bodyJson.toRequestBody(JSON_MEDIA)
            method == "POST" || method == "PUT" || method == "PATCH" -> "".toRequestBody(JSON_MEDIA)
            else -> null
        }
        val response = send(method, url, requestBody, authenticated)
        decode(response, deserializer, allowEmptyBody)
    }

    suspend fun <T> executeMultipart(
        path: String,
        fieldName: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        deserializer: DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(fieldName, fileName, bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        val response = send("POST", buildUrl(path, emptyMap()), body, authenticated = true)
        decode(response, deserializer, allowEmptyBody = false)
    }

    private data class RawResponse(val status: Int, val body: String?)

    private suspend fun send(method: String, url: HttpUrl, body: RequestBody?, authenticated: Boolean): RawResponse {
        var token = if (authenticated) tokenStore.load()?.access else null
        if (authenticated && tokenStore.load()?.isAccessExpiring() == true) {
            // Firebase ID tokens live an hour; renew before the call instead of eating a 401 round-trip.
            refreshAccessToken()?.let { token = it }
        }
        var response = call(method, url, body, token)
        if (response.status == 401 && authenticated) {
            val refreshed = refreshAccessToken()
            if (refreshed != null) {
                response = call(method, url, body, refreshed)
            }
            if (response.status == 401) {
                tokenStore.clear()
                onSessionExpired?.invoke()
                throw ApiException.Unauthorized()
            }
        }
        return response
    }

    private fun call(method: String, url: HttpUrl, body: RequestBody?, accessToken: String?): RawResponse {
        val builder = Request.Builder().url(url).method(method, body)
            .header("Accept", "application/json")
        if (accessToken != null) builder.header("Authorization", "${config.authScheme} $accessToken")
        try {
            http.newCall(builder.build()).execute().use { res ->
                return RawResponse(res.code, res.body?.string())
            }
        } catch (e: IOException) {
            throw ApiException.Network(e)
        }
    }

    private fun <T> decode(response: RawResponse, deserializer: DeserializationStrategy<T>, allowEmptyBody: Boolean): T {
        if (response.status !in 200..299) {
            throw ApiException.Http(response.status, response.body, ApiException.describe(response.status, response.body, json))
        }
        val text = response.body
        if (text.isNullOrBlank()) {
            // 204-style replies are fine when the caller only asked for loose JSON (JsonElement → JsonNull).
            return try {
                json.decodeFromString(deserializer, "null")
            } catch (e: Exception) {
                throw ApiException.Decoding(IllegalStateException("Empty body (allowEmptyBody=$allowEmptyBody)"), text)
            }
        }
        return try {
            json.decodeFromString(deserializer, text)
        } catch (e: Exception) {
            throw ApiException.Decoding(e, text)
        }
    }

    /** Serialised so that concurrent 401s trigger a single refresh. Returns the new access token or null. */
    private suspend fun refreshAccessToken(): String? = refreshMutex.withLock {
        val current = tokenStore.load() ?: return null
        if (current.refresh.isNullOrBlank()) return null
        val renewed = runCatching { refresherFor(current).refresh(current) }.getOrNull() ?: return null
        tokenStore.save(renewed)
        renewed.access
    }

    /** Raw call helper for refresh strategies (no auth header, no retry). */
    internal fun rawPost(path: String, bodyJson: String): RawResult {
        val res = call("POST", buildUrl(path, emptyMap()), bodyJson.toRequestBody(JSON_MEDIA), accessToken = null)
        return RawResult(res.status, res.body)
    }

    data class RawResult(val status: Int, val body: String?)

    /** Absolute URL under the global (tenant-less) root — for the calls made before a workspace is chosen. */
    fun globalPath(path: String): String = config.globalRoot + path.trimStart('/')

    fun buildUrl(path: String, query: Map<String, Any?>): HttpUrl {
        val base = if (path.startsWith("http")) path else config.tenantRoot + path.trimStart('/')
        val builder = base.toHttpUrl().newBuilder()
        for ((key, value) in query) {
            when (value) {
                null -> Unit
                is Iterable<*> -> value.forEach { v -> if (v != null) builder.addQueryParameter(key, v.toString()) }
                is Array<*> -> value.forEach { v -> if (v != null) builder.addQueryParameter(key, v.toString()) }
                is Boolean -> builder.addQueryParameter(key, if (value) "true" else "false")
                else -> builder.addQueryParameter(key, value.toString())
            }
        }
        return builder.build()
    }

    companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** Convenience for code that wants the session without touching the store directly. */
val ApiClient.session: Session? get() = tokenStore.load()
