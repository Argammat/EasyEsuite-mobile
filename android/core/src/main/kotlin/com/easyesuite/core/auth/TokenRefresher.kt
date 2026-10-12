package com.easyesuite.core.auth

import com.easyesuite.core.Endpoints
import com.easyesuite.core.net.ApiClient
import com.easyesuite.core.net.DefaultJson

/** Renews an expired access token. Implementations must not throw for ordinary failures — return null. */
interface TokenRefresher {
    suspend fun refresh(current: Session): Session?
}

/**
 * `POST /clients/{tenant}/api/v1/auth/token/refresh/ {refresh}` → a **new access and a new refresh token**
 * (VERIFIED). Each refresh token works once, so both are stored; [ApiClient] serialises the calls so only one
 * refresh is ever in flight.
 */
class BackendTokenRefresher(private val client: ApiClient) : TokenRefresher {
    override suspend fun refresh(current: Session): Session? {
        val refresh = current.refresh ?: return null
        val body = DefaultJson.encodeToString(RefreshRequest.serializer(), RefreshRequest(refresh))
        val res = runCatching { client.rawPost(Endpoints.TOKEN_REFRESH, body) }.getOrNull() ?: return null
        if (res.status !in 200..299 || res.body.isNullOrBlank()) return null
        val parsed = runCatching { DefaultJson.decodeFromString(TokenResponse.serializer(), res.body) }.getOrNull() ?: return null
        val access = parsed.resolvedAccess ?: return null
        val newRefresh = parsed.resolvedRefresh ?: current.refresh
        return current.copy(
            access = access,
            refresh = newRefresh,
            accessExpiration = parsed.accessExpiration ?: Jwt.expirationIso(access) ?: current.accessExpiration,
            refreshExpiration = parsed.refreshExpiration ?: Jwt.expirationIso(newRefresh) ?: current.refreshExpiration,
        )
    }
}
