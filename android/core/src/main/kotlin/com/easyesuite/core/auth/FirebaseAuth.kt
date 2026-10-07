package com.easyesuite.core.auth

import com.easyesuite.core.FirebaseConfig
import com.easyesuite.core.net.ApiException
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
import java.time.format.DateTimeFormatter

/** Tokens minted by Identity Platform. `idToken` is what the API wants as `Authorization: Bearer`. */
data class FirebaseTokens(val idToken: String, val refreshToken: String, val expiresAt: Instant, val localId: String?) {
    val expiresAtIso: String get() = DateTimeFormatter.ISO_INSTANT.format(expiresAt)
}

@Serializable
private data class SignInRequest(
    val email: String,
    val password: String,
    val returnSecureToken: Boolean = true,
    val tenantId: String? = null,
)

@Serializable
private data class SignInResponse(
    val idToken: String? = null,
    val refreshToken: String? = null,
    val expiresIn: String? = null,
    val localId: String? = null,
    val mfaPendingCredential: String? = null,
)

@Serializable
private data class RefreshResponse(
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: String? = null,
    @SerialName("user_id") val userId: String? = null,
)

/**
 * Minimal Firebase Identity Platform client (REST, no SDK) — email/password sign-in against the
 * workspace's tenant plus refresh-token exchange. Errors are mapped to the same [ApiException]
 * family the rest of the SDK throws so the UI needs no special cases.
 */
class FirebaseAuth(
    val config: FirebaseConfig,
    private val http: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true },
) {
    suspend fun signIn(email: String, password: String): FirebaseTokens = withContext(Dispatchers.IO) {
        val body = json.encodeToString(SignInRequest.serializer(), SignInRequest(email.trim(), password, tenantId = config.tenantId))
        val res = call(Request.Builder().url(config.signInUrl).post(body.toRequestBody(JSON)).build())
        val parsed = json.decodeFromString(SignInResponse.serializer(), res)
        if (parsed.mfaPendingCredential != null) {
            throw ApiException.Http(401, res, "This account requires multi-factor sign-in, which the mobile app does not support yet.")
        }
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

    /** Firebase error bodies look like {"error":{"code":400,"message":"INVALID_LOGIN_CREDENTIALS"}}. */
    private fun friendly(body: String, status: Int): String {
        val code = runCatching {
            ((json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull }
        }.getOrNull() ?: return ApiException.defaultMessage(status)
        return when {
            code.startsWith("INVALID_LOGIN_CREDENTIALS") || code.startsWith("INVALID_PASSWORD") || code.startsWith("EMAIL_NOT_FOUND") -> "Email or password is incorrect."
            code.startsWith("USER_DISABLED") -> "This account has been disabled."
            code.startsWith("TOO_MANY_ATTEMPTS") -> "Too many attempts. Try again in a few minutes."
            code.startsWith("INVALID_TENANT_ID") || code.startsWith("TENANT_ID") -> "Workspace sign-in is not configured correctly (Firebase tenant id)."
            code.startsWith("API_KEY") || code.startsWith("INVALID_API_KEY") -> "Sign-in is not configured (Firebase API key)."
            code.startsWith("TOKEN_EXPIRED") || code.startsWith("INVALID_REFRESH_TOKEN") || code.startsWith("USER_NOT_FOUND") -> "Your session expired. Please sign in again."
            else -> code.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
