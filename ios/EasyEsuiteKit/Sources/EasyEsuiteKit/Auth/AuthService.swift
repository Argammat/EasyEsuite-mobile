import Foundation

/// Sign-in exactly as the backend team specified (answers of 2026-10-09):
///
/// 1. `workspaces(email:password:)` — `POST {global}auth/login/tenants/` lists the user's workspaces (no token).
///    One workspace → log straight in; several → the app shows a picker.
/// 2. `login(email:password:)` on a client scoped to the chosen `schema_name` — `POST auth/login/` returns the
///    backend JWTs (access 5 min, refresh 1 day, rotated on every refresh) plus `user` and, when the workspace
///    has a Firebase tenant, a `firebase_custom_token`.
/// 3. The custom token is exchanged for a Firebase ID token (`attachFirebase`) — only the Firestore-backed
///    screens need it; if it fails the REST API keeps working.
///
/// Special cases: `needs_password_reset` blocks the app (`.passwordResetRequired`); MFA adds a one-time-code
/// step (`.secondFactorRequired`) before the session is stored.
public final class AuthService: @unchecked Sendable {
    private let client: APIClient
    public let firebase: FirebaseAuth?

    public init(client: APIClient, firebase: FirebaseAuth? = nil) {
        self.client = client
        self.firebase = firebase ?? client.config.firebase.map { FirebaseAuth(config: $0, urlSession: client.urlSession) }
    }

    // MARK: Step 1

    /// The workspaces the credentials unlock, most recently used first. Never returns a token.
    public func workspaces(email: String, password: String) async throws -> [Workspace] {
        do {
            let list: [Workspace] = try await client.post(client.globalPath(Endpoints.loginTenants), body: LoginRequest(email: trim(email), password: password), authenticated: false)
            return list
        } catch APIError.http(let status, _, let body) where status == 401 {
            // The backend returns the same body for a wrong password, unknown email or locked account — on purpose.
            throw APIError.http(status: 401, detail: "Email or password is incorrect.", body: body)
        } catch APIError.http(let status, _, let body) where status == 429 {
            throw APIError.http(status: 429, detail: "Too many sign-in attempts. Wait a minute and try again.", body: body)
        }
    }

    // MARK: Step 2

    /// Log in to the workspace this client is scoped to. The password is only held in memory between step 1 and here.
    public func login(email: String, password: String) async throws -> LoginResult {
        try requireTenant()
        let res: TokenResponse = try await client.post(Endpoints.login, body: LoginRequest(email: trim(email), password: password), authenticated: false)
        return try await handle(res, email: trim(email), afterSecondFactor: false)
    }

    /// The one-time-code step for MFA users. Body ASSUMED (`{email, ephemeral_token, code}`).
    public func completeSecondFactor(email: String, challengeToken: String?, pending: Session?, code: String) async throws -> LoginResult {
        try requireTenant()
        if let pending { client.tokenStore.save(pending) }   // lets the 2FA call authenticate if the backend expects it
        let res: TokenResponse = try await client.post(Endpoints.login2FA, body: TwoFactorRequest(email: trim(email), ephemeralToken: challengeToken, code: code.trimmingCharacters(in: .whitespaces)), authenticated: pending != nil)
        if res.resolvedAccess == nil, let pending {
            // The code was accepted but no new tokens came back: the tokens from the login step are the session.
            return .success(await finish(pending, customToken: nil))
        }
        return try await handle(res, email: trim(email), afterSecondFactor: true)
    }

    private func handle(_ res: TokenResponse, email: String, afterSecondFactor: Bool) async throws -> LoginResult {
        if res.user?.needsPasswordReset == true { return .passwordResetRequired(email: res.user?.email ?? email) }
        guard let access = res.resolvedAccess else {
            if res.needsSecondFactor { return .secondFactorRequired(pending: nil, challengeToken: res.challengeToken, message: res.detail) }
            throw APIError.decoding("login response had no access token", body: nil)
        }
        let session = Session(
            tenant: client.config.tenant, access: access, refresh: res.resolvedRefresh,
            accessExpiration: res.accessExpiration ?? JWT.expirationIso(access),
            refreshExpiration: res.refreshExpiration ?? JWT.expirationIso(res.resolvedRefresh),
            email: res.user?.email ?? email, tenantId: res.user?.tenantId, userPk: res.user?.pk,
            roles: res.user?.allRoleNames ?? [], firebaseTenantId: res.user?.firebaseTenantId
        )
        if !afterSecondFactor, res.user?.requiresSecondFactor == true {
            return .secondFactorRequired(pending: session, challengeToken: res.challengeToken, message: res.detail)
        }
        return .success(await finish(session, customToken: res.firebaseCustomToken))
    }

    private func finish(_ session: Session, customToken: String?) async -> Session {
        client.tokenStore.save(session)
        return await attachFirebase(session, customToken: customToken)
    }

    // MARK: Step 3

    /// Exchange the backend's custom token for Firebase tokens and store them with the session. Failure is
    /// tolerated: the app carries on with REST only (the web app shows a small warning in that case).
    public func attachFirebase(_ session: Session, customToken: String?) async -> Session {
        guard let firebase, let customToken = customToken?.nilIfBlank, let tenantId = session.firebaseTenantId?.nilIfBlank,
              let tokens = try? await firebase.signIn(customToken: customToken, tenantId: tenantId) else { return session }
        var updated = session
        updated.firebase = tokens.session
        client.tokenStore.save(updated)
        return updated
    }

    /// A valid Firebase ID token for Firestore calls, refreshed when it is about to expire. Nil = no Firestore access.
    public func firebaseIdToken() async -> String? {
        guard let session = client.tokenStore.load(), let fb = session.firebase else { return nil }
        if !fb.isExpiring() { return fb.idToken }
        guard let firebase, !fb.refreshToken.isEmpty, let tokens = try? await firebase.refresh(fb.refreshToken) else { return nil }
        var updated = session
        updated.firebase = tokens.session
        client.tokenStore.save(updated)
        return tokens.idToken
    }

    // MARK: Misc

    public func me() async throws -> UserProfile { try await client.get(Endpoints.me) }

    /// Signing out: drop both JWTs and the Firebase tokens (nothing to revoke server-side for the custom-token sign-in).
    public func logout() { client.tokenStore.clear() }

    public var isSignedIn: Bool { client.tokenStore.load() != nil }

    private func trim(_ email: String) -> String { email.trimmingCharacters(in: .whitespacesAndNewlines) }

    private func requireTenant() throws {
        if client.config.tenant.isEmpty { throw APIError.decoding("login needs a workspace: build the client with the chosen schema_name", body: nil) }
    }
}
