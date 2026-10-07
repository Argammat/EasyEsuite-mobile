package com.easyesuite.core.auth

import com.easyesuite.core.Endpoints
import com.easyesuite.core.net.ApiClient
import com.easyesuite.core.net.DefaultJson

/** Renews an expired access token. Implementations must not throw for ordinary failures — return null. */
interface TokenRefresher {
    suspend fun refresh(current: Session): Session?
}

/** Identity Platform: exchange the Firebase refresh token for a new ID token. */
class FirebaseTokenRefresher(private val firebase: FirebaseAuth) : TokenRefresher {
    override suspend fun refresh(current: Session): Session? {
        val refresh = current.refresh ?: return null
        val tokens = runCatching { firebase.refresh(refresh) }.getOrNull() ?: return null
        return current.copy(
            access = tokens.idToken,
            refresh = tokens.refreshToken.ifBlank { refresh },
            accessExpiration = tokens.expiresAtIso,
            provider = Session.PROVIDER_FIREBASE,
        )
    }
}

/** Backend-proxied flow: `POST auth/token/refresh/ {refresh}` → `{access, access_expiration}` (the call the web app makes). */
class BackendTokenRefresher(private val client: ApiClient) : TokenRefresher {
    override suspend fun refresh(current: Session): Session? {
        val refresh = current.refresh ?: return null
        val body = DefaultJson.encodeToString(RefreshRequest.serializer(), RefreshRequest(refresh))
        val path = if (current.isPending) client.globalPath(Endpoints.TOKEN_REFRESH) else Endpoints.TOKEN_REFRESH
        val res = runCatching { client.rawPost(path, body) }.getOrNull() ?: return null
        if (res.status !in 200..299 || res.body.isNullOrBlank()) return null
        val parsed = runCatching { DefaultJson.decodeFromString(TokenResponse.serializer(), res.body) }.getOrNull() ?: return null
        val access = parsed.resolvedAccess ?: return null
        return current.copy(
            access = access,
            refresh = parsed.resolvedRefresh ?: current.refresh,
            accessExpiration = parsed.accessExpiration ?: current.accessExpiration,
            refreshExpiration = parsed.refreshExpiration ?: current.refreshExpiration,
            provider = Session.PROVIDER_BACKEND,
        )
    }
}
