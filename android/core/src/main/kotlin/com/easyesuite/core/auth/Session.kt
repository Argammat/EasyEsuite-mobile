package com.easyesuite.core.auth

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** What we persist between launches (in EncryptedSharedPreferences on Android, Keychain on iOS). */
@Serializable
data class Session(
    val tenant: String,
    val access: String,
    val refresh: String? = null,
    @SerialName("access_expiration") val accessExpiration: String? = null,
    @SerialName("refresh_expiration") val refreshExpiration: String? = null,
    val email: String? = null,
    /** Who minted the tokens: [PROVIDER_FIREBASE] (Identity Platform) or [PROVIDER_BACKEND] (auth/login/). */
    val provider: String = PROVIDER_BACKEND,
    /** Identity Platform tenant the user signed in against (Firebase flow only); kept so the next launch reuses it. */
    @SerialName("firebase_tenant_id") val firebaseTenantId: String? = null,
) {
    /** True when the access token expires within [skewSeconds] (or already has). Unknown expiry → false. */
    fun isAccessExpiring(skewSeconds: Long = 60, now: java.time.Instant = java.time.Instant.now()): Boolean {
        val exp = com.easyesuite.core.util.DateText.parse(accessExpiration) ?: return false
        return !exp.isAfter(now.plusSeconds(skewSeconds))
    }

    companion object {
        const val PROVIDER_FIREBASE = "firebase"
        const val PROVIDER_BACKEND = "backend"
    }
}

/** Storage abstraction so the core stays platform-free. */
interface TokenStore {
    fun load(): Session?
    fun save(session: Session)
    fun clear()
}

class InMemoryTokenStore(private var session: Session? = null) : TokenStore {
    override fun load(): Session? = session
    override fun save(session: Session) { this.session = session }
    override fun clear() { session = null }
}

@Serializable
data class LoginRequest(val email: String, val password: String)

/**
 * dj-rest-auth style token response. Field names are decoded leniently because the exact
 * login payload has not been captured yet (see docs/API_MAP.md → Auth).
 */
@Serializable
data class TokenResponse(
    val access: String? = null,
    val refresh: String? = null,
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val token: String? = null,
    @SerialName("access_expiration") val accessExpiration: String? = null,
    @SerialName("refresh_expiration") val refreshExpiration: String? = null,
    val user: JsonElement? = null,
    // Two-factor challenge markers (any one of them means "ask for a code")
    @SerialName("mfa_required") val mfaRequired: Boolean? = null,
    @SerialName("requires_2fa") val requires2fa: Boolean? = null,
    @SerialName("ephemeral_token") val ephemeralToken: String? = null,
    @SerialName("mfa_token") val mfaToken: String? = null,
    val detail: String? = null,
) {
    val resolvedAccess: String? get() = access ?: accessToken ?: token
    val resolvedRefresh: String? get() = refresh ?: refreshToken
    val needsSecondFactor: Boolean
        get() = resolvedAccess == null && (mfaRequired == true || requires2fa == true || ephemeralToken != null || mfaToken != null)
    val challengeToken: String? get() = ephemeralToken ?: mfaToken
}

/** Body for `auth/login/2fa/`. Field names ASSUMED; adjust once captured from the web app. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TwoFactorRequest(
    val email: String? = null,
    @SerialName("ephemeral_token") val ephemeralToken: String? = null,
    val code: String,
    /** Same value under the other common key, always sent. */
    @EncodeDefault val otp: String = code,
)

@Serializable
data class RefreshRequest(val refresh: String)

sealed class LoginResult {
    data class Success(val session: Session) : LoginResult()
    data class SecondFactorRequired(val challengeToken: String?, val message: String?) : LoginResult()
}
