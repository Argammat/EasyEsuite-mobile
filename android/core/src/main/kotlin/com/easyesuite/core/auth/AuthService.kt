package com.easyesuite.core.auth

import com.easyesuite.core.Endpoints
import com.easyesuite.core.model.TenantInfo
import com.easyesuite.core.model.UserProfile
import com.easyesuite.core.net.ApiClient
import com.easyesuite.core.net.ApiException
import kotlinx.serialization.json.JsonElement

/**
 * Login / 2FA / refresh against the tenant-scoped `auth/…` endpoints.
 *
 * The web app stores `accessToken`, `refreshToken`, `access_expiration` and `refresh_expiration`
 * after login, which is exactly dj-rest-auth's JWT response, so that is what we expect here.
 * If the captured login response uses different keys, change [TokenResponse] only.
 */
class AuthService(private val client: ApiClient) {

    suspend fun login(email: String, password: String): LoginResult {
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
