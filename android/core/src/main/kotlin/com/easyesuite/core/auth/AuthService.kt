package com.easyesuite.core.auth

import com.easyesuite.core.Endpoints
import com.easyesuite.core.model.UserProfile
import com.easyesuite.core.net.ApiClient
import com.easyesuite.core.net.ApiException

/**
 * Sign-in exactly as the backend team specified (answers of 2026-10-09):
 *
 * 1. [workspaces] — `POST {global}auth/login/tenants/` lists the user's workspaces (no token).
 *    One workspace → log straight in; several → the app shows a picker.
 * 2. [login] on a client scoped to the chosen `schema_name` — `POST auth/login/` returns the backend JWTs
 *    (access 5 min, refresh 1 day, rotated on every refresh) plus `user` and, when the workspace has a
 *    Firebase tenant, a `firebase_custom_token`.
 * 3. The custom token is exchanged for a Firebase ID token ([attachFirebase]) — only the Firestore-backed
 *    screens need it; if it fails the REST API keeps working.
 *
 * Special cases: `needs_password_reset` blocks the app ([LoginResult.PasswordResetRequired]); MFA adds a
 * one-time-code step ([LoginResult.SecondFactorRequired]) before the session is stored.
 */
class AuthService(
    private val client: ApiClient,
    val firebase: FirebaseAuth? = client.config.firebase?.let { FirebaseAuth(it, client.httpClient) },
) {
    // ---- step 1 --------------------------------------------------------------------------------

    /** The workspaces the credentials unlock, most recently used first. Never returns a token. */
    suspend fun workspaces(email: String, password: String): List<Workspace> = try {
        val list: List<Workspace> = client.post(client.globalPath(Endpoints.LOGIN_TENANTS), LoginRequest(email.trim(), password), authenticated = false)
        list
    } catch (e: ApiException.Http) {
        when (e.status) {
            // Same body for a wrong password, unknown email or locked account — on purpose.
            401 -> throw ApiException.Http(401, e.body, "Email or password is incorrect.")
            429 -> throw ApiException.Http(429, e.body, "Too many sign-in attempts. Wait a minute and try again.")
            else -> throw e
        }
    }

    // ---- step 2 --------------------------------------------------------------------------------

    /** Log in to the workspace this client is scoped to. The password is only held in memory between step 1 and here. */
    suspend fun login(email: String, password: String): LoginResult {
        requireTenant()
        val res: TokenResponse = client.post(Endpoints.LOGIN, LoginRequest(email.trim(), password), authenticated = false)
        return handle(res, email.trim(), afterSecondFactor = false)
    }

    /** The one-time-code step for MFA users. Body ASSUMED (`{email, ephemeral_token, code}`). */
    suspend fun completeSecondFactor(email: String, challengeToken: String?, pending: Session?, code: String): LoginResult {
        requireTenant()
        if (pending != null) client.tokenStore.save(pending)   // lets the 2FA call authenticate if the backend expects it
        val res: TokenResponse = client.post(
            Endpoints.LOGIN_2FA,
            TwoFactorRequest(email = email.trim(), ephemeralToken = challengeToken, code = code.trim()),
            authenticated = pending != null,
        )
        if (res.resolvedAccess == null && pending != null) {
            // The code was accepted but no new tokens came back: the tokens from the login step are the session.
            return LoginResult.Success(finish(pending, customToken = null))
        }
        return handle(res, email.trim(), afterSecondFactor = true)
    }

    private suspend fun handle(res: TokenResponse, email: String, afterSecondFactor: Boolean): LoginResult {
        if (res.user?.needsPasswordReset == true) return LoginResult.PasswordResetRequired(res.user?.email ?: email)
        val access = res.resolvedAccess ?: run {
            if (res.needsSecondFactor) return LoginResult.SecondFactorRequired(null, res.challengeToken, res.detail)
            throw ApiException.Decoding(IllegalStateException("Login response had no access token"), null)
        }
        val session = Session(
            tenant = client.config.tenant,
            access = access,
            refresh = res.resolvedRefresh,
            accessExpiration = res.accessExpiration ?: Jwt.expirationIso(access),
            refreshExpiration = res.refreshExpiration ?: Jwt.expirationIso(res.resolvedRefresh),
            email = res.user?.email ?: email,
            tenantId = res.user?.tenantId,
            userPk = res.user?.pk,
            roles = res.user?.allRoleNames.orEmpty(),
            firebaseTenantId = res.user?.firebaseTenantId,
        )
        if (!afterSecondFactor && res.user?.requiresSecondFactor == true) {
            return LoginResult.SecondFactorRequired(session, res.challengeToken, res.detail)
        }
        return LoginResult.Success(finish(session, res.firebaseCustomToken))
    }

    private suspend fun finish(session: Session, customToken: String?): Session {
        client.tokenStore.save(session)
        return attachFirebase(session, customToken)
    }

    // ---- step 3 --------------------------------------------------------------------------------

    /**
     * Exchange the backend's custom token for Firebase tokens and store them with the session. Failure is
     * tolerated: the app carries on with REST only (the web app shows a small warning in that case).
     */
    suspend fun attachFirebase(session: Session, customToken: String?): Session {
        val fb = firebase ?: return session
        val token = customToken?.takeIf { it.isNotBlank() } ?: return session
        val tenantId = session.firebaseTenantId?.takeIf { it.isNotBlank() } ?: return session
        val tokens = runCatching { fb.signIn(token, tenantId) }.getOrNull() ?: return session
        val updated = session.copy(firebase = tokens.session)
        client.tokenStore.save(updated)
        return updated
    }

    /** A valid Firebase ID token for Firestore calls, refreshed when it is about to expire. Null = no Firestore access. */
    suspend fun firebaseIdToken(): String? {
        val session = client.tokenStore.load() ?: return null
        val fb = session.firebase ?: return null
        if (!fb.isExpiring()) return fb.idToken
        val auth = firebase ?: return null
        if (fb.refreshToken.isBlank()) return null
        val tokens = runCatching { auth.refresh(fb.refreshToken) }.getOrNull() ?: return null
        client.tokenStore.save(session.copy(firebase = tokens.session))
        return tokens.idToken
    }

    // ---- misc ----------------------------------------------------------------------------------

    suspend fun me(): UserProfile = client.get(Endpoints.ME)

    /** Signing out: drop both JWTs and the Firebase tokens (nothing to revoke server-side for the custom-token sign-in). */
    fun logout() {
        client.tokenStore.clear()
    }

    val isSignedIn: Boolean get() = client.tokenStore.load() != null

    private fun requireTenant() {
        if (client.config.tenant.isBlank()) {
            throw ApiException.Decoding(IllegalStateException("login needs a workspace: build the client with the chosen schema_name"), null)
        }
    }
}
