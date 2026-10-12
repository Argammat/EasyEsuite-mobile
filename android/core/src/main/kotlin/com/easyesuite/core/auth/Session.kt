package com.easyesuite.core.auth

import com.easyesuite.core.util.DateText
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.util.Base64

/**
 * Firebase / Identity Platform tokens obtained from the backend's custom token (step 3 of sign-in).
 * Only the Firestore-backed screens use them; every REST call uses the backend JWT.
 */
@Serializable
data class FirebaseSession(
    @SerialName("id_token") val idToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    /** ISO-8601. */
    @SerialName("expires_at") val expiresAt: String,
) {
    fun isExpiring(skewSeconds: Long = 60, now: Instant = Instant.now()): Boolean {
        val exp = DateText.parse(expiresAt) ?: return true
        return !exp.isAfter(now.plusSeconds(skewSeconds))
    }
}

/** What we persist between launches (EncryptedSharedPreferences). One session = one user inside one workspace. */
@Serializable
data class Session(
    /** The workspace's `schema_name` — part of every REST URL. */
    val tenant: String,
    val access: String,
    val refresh: String? = null,
    @SerialName("access_expiration") val accessExpiration: String? = null,
    @SerialName("refresh_expiration") val refreshExpiration: String? = null,
    val email: String? = null,
    /** Numeric tenant id (`user.tenant_id`) — Firestore documents live under `tenants/{tenantId}/…`. */
    @SerialName("tenant_id") val tenantId: Int? = null,
    /** `user.pk` — notifications live under `tenants/{tenantId}/users/{userPk}/notifications`. */
    @SerialName("user_pk") val userPk: Int? = null,
    val roles: List<String> = emptyList(),
    /** Identity Platform tenant for this workspace (`user.firebase_tenant_id`); null = no Firestore screens. */
    @SerialName("firebase_tenant_id") val firebaseTenantId: String? = null,
    val firebase: FirebaseSession? = null,
) {
    /** True when the access token expires within [skewSeconds] (or already has). Unknown expiry → false. */
    fun isAccessExpiring(skewSeconds: Long = 60, now: Instant = Instant.now()): Boolean {
        val exp = DateText.parse(accessExpiration) ?: return false
        return !exp.isAfter(now.plusSeconds(skewSeconds))
    }

    val isAdmin: Boolean get() = roles.any { it.equals("ADMIN", ignoreCase = true) }

    /** Firestore screens need a Firebase tenant and a custom-token sign-in that succeeded. */
    val hasFirestore: Boolean get() = firebaseTenantId != null && firebase != null
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

/** One workspace from `POST api/v1/auth/login/tenants/` (VERIFIED shape). */
@Serializable
data class Workspace(
    @SerialName("tenant_id") val tenantId: Int? = null,
    @SerialName("schema_name") val schemaName: String,
    @SerialName("display_name") val displayName: String? = null,
    /** Deprecated by the backend; [displayName] is the one to show. */
    @SerialName("company_name") val companyName: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    val initials: String? = null,
    @SerialName("last_active_at") val lastActiveAt: String? = null,
) {
    val name: String get() = displayName?.takeIf { it.isNotBlank() } ?: companyName?.takeIf { it.isNotBlank() } ?: schemaName
    val monogram: String get() = initials?.takeIf { it.isNotBlank() } ?: name.take(2).uppercase()
}

/** The `user` object in the tenant login response (VERIFIED fields; everything optional so extras never break decoding). */
@Serializable
data class LoginUser(
    val pk: Int? = null,
    val email: String? = null,
    @SerialName("tenant_id") val tenantId: Int? = null,
    @SerialName("firebase_tenant_id") val firebaseTenantId: String? = null,
    @SerialName("is_mfa_enabled") val isMfaEnabled: Boolean? = null,
    @SerialName("is_mfa_mandatory") val isMfaMandatory: Boolean? = null,
    @SerialName("needs_password_reset") val needsPasswordReset: Boolean? = null,
    @SerialName("all_role_names") val allRoleNames: List<String>? = null,
    @SerialName("plan_name") val planName: String? = null,
) {
    val requiresSecondFactor: Boolean get() = isMfaEnabled == true || isMfaMandatory == true
}

/** Tenant login response, decoded leniently (see docs/API_MAP.md → Auth). */
@Serializable
data class TokenResponse(
    val access: String? = null,
    val refresh: String? = null,
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val token: String? = null,
    @SerialName("access_expiration") val accessExpiration: String? = null,
    @SerialName("refresh_expiration") val refreshExpiration: String? = null,
    /** Present when the workspace has a Firebase tenant → `accounts:signInWithCustomToken`. */
    @SerialName("firebase_custom_token") val firebaseCustomToken: String? = null,
    val user: LoginUser? = null,
    // Two-factor challenge markers
    @SerialName("mfa_required") val mfaRequired: Boolean? = null,
    @SerialName("requires_2fa") val requires2fa: Boolean? = null,
    @SerialName("ephemeral_token") val ephemeralToken: String? = null,
    @SerialName("mfa_token") val mfaToken: String? = null,
    val detail: String? = null,
) {
    val resolvedAccess: String? get() = access ?: accessToken ?: token
    val resolvedRefresh: String? get() = refresh ?: refreshToken
    val challengeToken: String? get() = ephemeralToken ?: mfaToken
    /** A one-time code must follow: either the backend withheld the tokens behind a challenge token, or the user has MFA on. */
    val needsSecondFactor: Boolean
        get() = (resolvedAccess == null && (mfaRequired == true || requires2fa == true || challengeToken != null)) || user?.requiresSecondFactor == true
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
    /** [pending] holds tokens the backend already issued (MFA flags on the user) — not stored until the code is verified. */
    data class SecondFactorRequired(val pending: Session?, val challengeToken: String?, val message: String?) : LoginResult()
    /** `user.needs_password_reset`: the app must not continue; the password is reset on the web. */
    data class PasswordResetRequired(val email: String) : LoginResult()
}

/** Reads claims out of a JWT without verifying it — only to learn when a token expires when the backend omits `access_expiration`. */
object Jwt {
    private val json = Json { ignoreUnknownKeys = true }

    fun claims(token: String): JsonObject? {
        val parts = token.split('.')
        if (parts.size < 2) return null
        return runCatching {
            val payload = Base64.getUrlDecoder().decode(parts[1].padEnd((parts[1].length + 3) / 4 * 4, '='))
            json.parseToJsonElement(String(payload, Charsets.UTF_8)) as? JsonObject
        }.getOrNull()
    }

    fun expiration(token: String?): Instant? {
        val exp = (claims(token ?: return null)?.get("exp") as? JsonPrimitive)?.doubleOrNull ?: return null
        return Instant.ofEpochSecond(exp.toLong())
    }

    fun expirationIso(token: String?): String? = expiration(token)?.let { DateText.iso8601(it) }
}
