package com.easyesuite.core.auth

import com.easyesuite.core.Endpoints
import com.easyesuite.core.model.TenantInfo
import com.easyesuite.core.model.UserProfile
import com.easyesuite.core.net.ApiClient
import com.easyesuite.core.net.ApiException
import kotlinx.serialization.json.JsonElement

/**
 * Login / 2FA / me for the tenant.
 *
 * Two strategies, chosen by `ApiConfig.firebase`:
 *  - **Firebase direct** (backend team's description): email/password → Identity Platform sign-in against the
 *    workspace's tenant id; the Firebase ID token is sent as `Authorization: Bearer`.
 *  - **Backend-proxied** (what the web app's stored `accessToken`/`refreshToken`/`access_expiration` keys suggest):
 *    `POST auth/login/` returns the tokens; refresh is `POST auth/token/refresh/`.
 * Switching is a config change; nothing in the UI knows which one is in use.
 */
class AuthService(
    private val client: ApiClient,
    private val firebase: FirebaseAuth? = client.config.firebase?.let { FirebaseAuth(it) },
) {

    suspend fun login(email: String, password: String): LoginResult {
        val fb = firebase
        if (fb != null) {
            val tokens = fb.signIn(email, password)
            val session = Session(
                tenant = client.config.tenant,
                access = tokens.idToken,
                refresh = tokens.refreshToken.ifBlank { null },
                accessExpiration = tokens.expiresAtIso,
                email = email.trim(),
                provider = Session.PROVIDER_FIREBASE,
                firebaseTenantId = fb.config.tenantId,
            )
            client.tokenStore.save(session)
            return LoginResult.Success(session)
        }
        val res: TokenResponse = client.post(Endpoints.LOGIN, LoginRequest(email.trim(), password), authenticated = false)
        return handle(res, email)
    }

    suspend fun completeSecondFactor(email: String, challengeToken: String?, code: String): LoginResult {
        val res: TokenResponse = client.post(
            Endpoints.LOGIN_2FA,
            TwoFactorRequest(email = email.trim(), ephemeralToken = challengeToken, code = code.trim()),
            authenticated = false,
        )
        return handle(res, email)
    }

    private fun handle(res: TokenResponse, email: String): LoginResult {
        if (res.needsSecondFactor) return LoginResult.SecondFactorRequired(res.challengeToken, res.detail)
        val access = res.resolvedAccess
            ?: throw ApiException.Decoding(IllegalStateException("Login response had no access token"), null)
        val session = Session(
            tenant = client.config.tenant,
            access = access,
            refresh = res.resolvedRefresh,
            accessExpiration = res.accessExpiration,
            refreshExpiration = res.refreshExpiration,
            email = email.trim(),
            provider = Session.PROVIDER_BACKEND,
        )
        client.tokenStore.save(session)
        return LoginResult.Success(session)
    }

    suspend fun me(): UserProfile = client.get(Endpoints.ME)

    suspend fun myTenants(): List<TenantInfo> {
        val raw: JsonElement = client.get(Endpoints.MY_TENANTS)
        return TenantInfo.listFrom(raw)
    }

    fun logout() {
        client.tokenStore.clear()
    }

    val isSignedIn: Boolean get() = client.tokenStore.load() != null
}
