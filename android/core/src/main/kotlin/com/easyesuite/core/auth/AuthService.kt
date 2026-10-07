package com.easyesuite.core.auth

import com.easyesuite.core.Endpoints
import com.easyesuite.core.model.TenantInfo
import com.easyesuite.core.model.UserProfile
import com.easyesuite.core.net.ApiClient
import com.easyesuite.core.net.ApiException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Sign-in the way the web app does it: **email + password first, then pick the company (workspace)**.
 *
 * 1. [login] authenticates without a tenant (global root, or Identity Platform at project level) and returns
 *    [LoginResult.WorkspaceRequired] with the user's workspaces; with exactly one workspace it signs straight in.
 * 2. [selectWorkspace] turns the pending session into a tenant-scoped one and stores it.
 *
 * Two token strategies, chosen by `ApiConfig.firebase`:
 *  - **Firebase direct** (backend team's description): email/password → Identity Platform; the ID token is the Bearer.
 *  - **Backend-proxied**: `POST auth/login/` returns the tokens; refresh is `POST auth/token/refresh/`.
 * When the client already has a tenant (re-authentication inside a workspace) the tenant-scoped paths are used.
 */
class AuthService(
    private val client: ApiClient,
    private val firebase: FirebaseAuth? = client.config.firebase?.let { FirebaseAuth(it) },
) {
    private val scopedToTenant: Boolean get() = client.config.tenant.isNotBlank()

    suspend fun login(email: String, password: String): LoginResult {
        val trimmed = email.trim()
        val fb = firebase
        if (fb != null) {
            val tokens = fb.signIn(trimmed, password)
            val pending = Session(
                tenant = client.config.tenant,
                access = tokens.idToken,
                refresh = tokens.refreshToken.ifBlank { null },
                accessExpiration = tokens.expiresAtIso,
                email = trimmed,
                provider = Session.PROVIDER_FIREBASE,
                firebaseTenantId = fb.config.tenantId,
            )
            return finish(pending, fromLogin = null)
        }
        val res: TokenResponse = client.post(path(Endpoints.LOGIN), LoginRequest(trimmed, password), authenticated = false)
        return handle(res, trimmed)
    }

    suspend fun completeSecondFactor(email: String, challengeToken: String?, code: String): LoginResult {
        val res: TokenResponse = client.post(
            path(Endpoints.LOGIN_2FA),
            TwoFactorRequest(email = email.trim(), ephemeralToken = challengeToken, code = code.trim()),
            authenticated = false,
        )
        return handle(res, email.trim())
    }

    /** The workspaces the signed-in user can open. Empty when the backend offers no list (UI then asks for the slug). */
    suspend fun workspaces(): List<TenantInfo> {
        val attempts = buildList {
            add(client.globalPath(Endpoints.MY_TENANTS))               // ASSUMED: global `users/me/tenants/`
            if (scopedToTenant) add(Endpoints.MY_TENANTS)              // tenant-scoped variant (VERIFIED path)
        }
        for (p in attempts) {
            try {
                val raw: JsonElement = client.get(p)
                val list = TenantInfo.listFrom(raw)
                if (list.isNotEmpty()) return list
            } catch (e: ApiException.Http) {
                if (e.status != 404 && e.status != 405) throw e
            }
        }
        return emptyList()
    }

    /** Pick the company: the pending tokens become the session for `tenant`, and it is persisted. */
    fun selectWorkspace(pending: Session, tenant: String): Session {
        val session = pending.copy(tenant = tenant.trim().lowercase())
        client.tokenStore.save(session)
        return session
    }

    private suspend fun handle(res: TokenResponse, email: String): LoginResult {
        if (res.needsSecondFactor) return LoginResult.SecondFactorRequired(res.challengeToken, res.detail)
        val access = res.resolvedAccess
            ?: throw ApiException.Decoding(IllegalStateException("Login response had no access token"), null)
        val pending = Session(
            tenant = client.config.tenant,
            access = access,
            refresh = res.resolvedRefresh,
            accessExpiration = res.accessExpiration,
            refreshExpiration = res.refreshExpiration,
            email = email,
            provider = Session.PROVIDER_BACKEND,
        )
        return finish(pending, fromLogin = res.user)
    }

    /** Already inside a workspace → done. Otherwise store the pending tokens and resolve the workspace list. */
    private suspend fun finish(pending: Session, fromLogin: JsonElement?): LoginResult {
        if (!pending.isPending) {
            client.tokenStore.save(pending)
            return LoginResult.Success(pending)
        }
        client.tokenStore.save(pending)   // authenticates the workspaces call; replaced by selectWorkspace()
        val fromUser = (fromLogin as? JsonObject)?.let { u ->
            TenantInfo.listFrom(u["tenants"] ?: u["workspaces"] ?: u["companies"] ?: u["clients"])
        }.orEmpty()
        val list = fromUser.ifEmpty { runCatching { workspaces() }.getOrDefault(emptyList()) }
        return if (list.size == 1) LoginResult.Success(selectWorkspace(pending, list.single().slug))
        else LoginResult.WorkspaceRequired(pending, list)
    }

    private fun path(tenantScoped: String): String = if (scopedToTenant) tenantScoped else client.globalPath(tenantScoped)

    suspend fun me(): UserProfile = client.get(Endpoints.ME)

    suspend fun myTenants(): List<TenantInfo> = workspaces()

    fun logout() {
        client.tokenStore.clear()
    }

    val isSignedIn: Boolean get() = client.tokenStore.load()?.isPending == false
}
