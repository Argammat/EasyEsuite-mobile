import Foundation

/// Sign-in the way the web app does it: **email + password first, then pick the company (workspace)**.
///
/// 1. `login` authenticates without a tenant (global root, or Identity Platform at project level) and returns
///    `.workspaceRequired` with the user's workspaces; with exactly one workspace it signs straight in.
/// 2. `selectWorkspace` turns the pending session into a tenant-scoped one and stores it.
///
/// Two token strategies, chosen by `ApiConfig.firebase`:
///  - **Firebase direct** (backend team's description): email/password → Identity Platform; the ID token is the Bearer.
///  - **Backend-proxied**: `POST auth/login/` returns the tokens; refresh is `POST auth/token/refresh/`.
/// When the client already has a tenant (re-authentication inside a workspace) the tenant-scoped paths are used.
public final class AuthService: @unchecked Sendable {
    private let client: APIClient
    private let firebase: FirebaseAuth?

    public init(client: APIClient, firebase: FirebaseAuth? = nil) {
        self.client = client
        self.firebase = firebase ?? client.config.firebase.map { FirebaseAuth(config: $0) }
    }

    private var scopedToTenant: Bool { !client.config.tenant.isEmpty }

    public func login(email: String, password: String) async throws -> LoginResult {
        let trimmed = email.trimmingCharacters(in: .whitespaces)
        if let firebase {
            let tokens = try await firebase.signIn(email: trimmed, password: password)
            let pending = Session(
                tenant: client.config.tenant, access: tokens.idToken, refresh: tokens.refreshToken.nilIfEmpty,
                accessExpiration: tokens.expiresAtIso, email: trimmed, provider: Session.providerFirebase, firebaseTenantId: firebase.config.tenantId
            )
            return try await finish(pending, fromLogin: nil)
        }
        let res: TokenResponse = try await client.post(path(Endpoints.login), body: LoginRequest(email: trimmed, password: password), authenticated: false)
        return try await handle(res, email: trimmed)
    }

    public func completeSecondFactor(email: String, challengeToken: String?, code: String) async throws -> LoginResult {
        let res: TokenResponse = try await client.post(
            path(Endpoints.login2FA),
            body: TwoFactorRequest(email: email.trimmingCharacters(in: .whitespaces), ephemeralToken: challengeToken, code: code.trimmingCharacters(in: .whitespaces)),
            authenticated: false
        )
        return try await handle(res, email: email.trimmingCharacters(in: .whitespaces))
    }

    /// The workspaces the signed-in user can open. Empty when the backend offers no list (UI then asks for the slug).
    public func workspaces() async throws -> [TenantInfo] {
        var attempts = [client.globalPath(Endpoints.myTenants)]          // ASSUMED: global `users/me/tenants/`
        if scopedToTenant { attempts.append(Endpoints.myTenants) }       // tenant-scoped variant (VERIFIED path)
        for p in attempts {
            do {
                let raw: JSONValue = try await client.get(p)
                let list = TenantInfo.list(from: raw)
                if !list.isEmpty { return list }
            } catch APIError.http(let status, _, _) where status == 404 || status == 405 {
                continue
            }
        }
        return []
    }

    /// Pick the company: the pending tokens become the session for `tenant`, and it is persisted.
    public func selectWorkspace(_ pending: Session, tenant: String) -> Session {
        var session = pending
        session.tenant = tenant.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        client.tokenStore.save(session)
        return session
    }

    private func handle(_ res: TokenResponse, email: String) async throws -> LoginResult {
        if res.needsSecondFactor { return .secondFactorRequired(challengeToken: res.challengeToken, message: res.detail) }
        guard let access = res.resolvedAccess else { throw APIError.decoding("login response had no access token", body: nil) }
        let pending = Session(
            tenant: client.config.tenant, access: access, refresh: res.resolvedRefresh,
            accessExpiration: res.accessExpiration, refreshExpiration: res.refreshExpiration,
            email: email, provider: Session.providerBackend
        )
        return try await finish(pending, fromLogin: res.user)
    }

    /// Already inside a workspace → done. Otherwise store the pending tokens and resolve the workspace list.
    private func finish(_ pending: Session, fromLogin: JSONValue?) async throws -> LoginResult {
        if !pending.isPending {
            client.tokenStore.save(pending)
            return .success(pending)
        }
        client.tokenStore.save(pending)   // authenticates the workspaces call; replaced by selectWorkspace()
        let fromUser = TenantInfo.list(from: fromLogin?["tenants"] ?? fromLogin?["workspaces"] ?? fromLogin?["companies"] ?? fromLogin?["clients"])
        let list = fromUser.isEmpty ? ((try? await workspaces()) ?? []) : fromUser
        if list.count == 1 { return .success(selectWorkspace(pending, tenant: list[0].slug)) }
        return .workspaceRequired(pending: pending, workspaces: list)
    }

    private func path(_ tenantScoped: String) -> String { scopedToTenant ? tenantScoped : client.globalPath(tenantScoped) }

    public func me() async throws -> UserProfile { try await client.get(Endpoints.me) }

    public func myTenants() async throws -> [TenantInfo] { try await workspaces() }

    public func logout() { client.tokenStore.clear() }
    public var isSignedIn: Bool { client.tokenStore.load().map { !$0.isPending } ?? false }
}
