package com.easyesuite.core.auth

import com.easyesuite.core.FirebaseConfig
import com.easyesuite.core.net.ApiException
import com.easyesuite.core.util.DateText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant

/** Tokens minted by Identity Platform. `idToken` is the Bearer for Firestore; the REST API never sees it. */
data class FirebaseTokens(val idToken: String, val refreshToken: String, val expiresAt: Instant, val localId: String?) {
    val expiresAtIso: String get() = DateText.iso8601(expiresAt)
    val session: FirebaseSession get() = FirebaseSession(idToken, refreshToken, expiresAtIso)
}

@Serializable
private data class CustomTokenRequest(
    val token: String,
    val returnSecureToken: Boolean = true,
    val tenantId: String? = null,
)

@Serializable
private data class SignInResponse(
    val idToken: String? = null,
    val refreshToken: String? = null,
    val expiresIn: String? = null,
    val localId: String? = null,
)

@Serializable
private data class RefreshResponse(
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: String? = null,
    @SerialName("user_id") val userId: String? = null,
)

/**
 * Minimal Firebase Identity Platform client (REST, no SDK). The backend's tenant login returns a
 * `firebase_custom_token`; this exchanges it for an ID token (`accounts:signInWithCustomToken`, with the
 * workspace's Identity Platform tenant set) and renews it with the refresh token. There is deliberately no
 * email/password sign-in: the Firebase users have no passwords (backend team, 2026-10-09).
 */
class FirebaseAuth(
    val config: FirebaseConfig,
    private val http: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true },
) {
    /** Step 3 of sign-in. `tenantId` is sent with the request (set before signing in, as the web SDK requires). */
    suspend fun signIn(customToken: String, tenantId: String?): FirebaseTokens = withContext(Dispatchers.IO) {
        val body = json.encodeToString(CustomTokenRequest.serializer(), CustomTokenRequest(customToken, tenantId = tenantId?.takeIf { it.isNotBlank() }))
        val res = call(Request.Builder().url(config.customTokenSignInUrl).post(body.toRequestBody(JSON)).build())
        val parsed = json.decodeFromString(SignInResponse.serializer(), res)
        val id = parsed.idToken ?: throw ApiException.Decoding(IllegalStateException("No idToken in Firebase response"), res)
        FirebaseTokens(id, parsed.refreshToken ?: "", expiry(parsed.expiresIn), parsed.localId)
    }

    suspend fun refresh(refreshToken: String): FirebaseTokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refreshToken).build()
        val res = call(Request.Builder().url(config.refreshUrl).post(form).build())
        val parsed = json.decodeFromString(RefreshResponse.serializer(), res)
        val id = parsed.idToken ?: throw ApiException.Decoding(IllegalStateException("No id_token in refresh response"), res)
        FirebaseTokens(id, parsed.refreshToken ?: refreshToken, expiry(parsed.expiresIn), parsed.userId)
    }

    private fun expiry(expiresIn: String?): Instant = Instant.now().plusSeconds(expiresIn?.toLongOrNull() ?: 3600L)

    private fun call(request: Request): String {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiException.Network(e)
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code !in 200..299) throw ApiException.Http(r.code, text, friendly(text, r.code))
            return text
        }
    }

    /** Firebase error bodies look like {"error":{"code":400,"message":"INVALID_CUSTOM_TOKEN"}}. */
    private fun friendly(body: String, status: Int): String {
        val code = runCatching {
            ((json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull }
        }.getOrNull() ?: return ApiException.defaultMessage(status)
        return when {
            code.startsWith("INVALID_CUSTOM_TOKEN") || code.startsWith("CREDENTIAL_MISMATCH") -> "The Firebase sign-in token was rejected (project mismatch)."
            code.startsWith("INVALID_TENANT_ID") || code.startsWith("TENANT_ID") || code.startsWith("MISSING_TENANT_ID") -> "This workspace's Firebase tenant is not configured."
            code.startsWith("TOKEN_EXPIRED") || code.startsWith("INVALID_REFRESH_TOKEN") || code.startsWith("USER_NOT_FOUND") || code.startsWith("USER_DISABLED") -> "The Firebase session has expired. Sign in again to refresh it."
            code.startsWith("TOO_MANY_ATTEMPTS") -> "Too many attempts. Try again in a few minutes."
            code.startsWith("API_KEY") || code.startsWith("INVALID_API_KEY") -> "The app's Firebase configuration is invalid."
            else -> "Firebase sign-in failed: " + code.lowercase().replace('_', ' ')
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
