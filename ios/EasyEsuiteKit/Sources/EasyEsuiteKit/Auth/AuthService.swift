import Foundation

/// Login / 2FA / me for the tenant.
///
/// Two strategies, chosen by `ApiConfig.firebase`:
///  - **Firebase direct** (backend team's description): email/password → Identity Platform sign-in against the
///    workspace's tenant id; the Firebase ID token is sent as `Authorization: Bearer`.
///  - **Backend-proxied** (what the web app's stored `accessToken`/`refreshToken`/`access_expiration` keys suggest):
///    `POST auth/login/` returns the tokens; refresh is `POST auth/token/refresh/`.
/// Switching is a config change; nothing in the UI knows which one is in use.
public final class AuthService: @unchecked Sendable {
    private let client: APIClient
    private let firebase: FirebaseAuth?

    public init(client: APIClient, firebase: FirebaseAuth? = nil) {
        self.client = client
        self.firebase = firebase ?? client.config.firebase.map { FirebaseAuth(config: $0) }
    }

    public func login(email: String, password: String) async throws -> LoginResult {
        let trimmed = email.trimmingCharacters(in: .whitespaces)
        if let firebase {
            let tokens = try await firebase.signIn(email: trimmed, password: password)
            let session = Session(
                tenant: client.config.tenant,
                access: tokens.idToken,
                refresh: tokens.refreshToken.nilIfEmpty,
                accessExpiration: tokens.expiresAtIso,
                email: trimmed,
                provider: Session.providerFirebase,
                firebaseTenantId: firebase.config.tenantId
            )
            client.tokenStore.save(session)
            return .success(session)
        }
        let res: TokenResponse = try await client.post(Endpoints.login, body: LoginRequest(email: trimmed, password: password), authenticated: false)
        return try handle(res, email: trimmed)
    }

    public func completeSecondFactor(email: String, challengeToken: String?, code: String) async throws -> LoginResult {
        let res: TokenResponse = try await client.post(Endpoints.login2FA, body: TwoFactorRequest(email: email.trimmingCharacters(in: .whitespaces), ephemeralToken: challengeToken, code: code.trimmingCharacters(in: .whitespaces)), authenticated: false)
        return try handle(res, email: email)
    }

    private func handle(_ res: TokenResponse, email: String) throws -> LoginResult {
        if res.needsSecondFactor { return .secondFactorRequired(challengeToken: res.challengeToken, message: res.detail) }
        guard let access = res.resolvedAccess else { throw APIError.decoding("login response had no access token", body: nil) }
        let session = Session(
            tenant: client.config.tenant, access: access, refresh: res.resolvedRefresh,
            accessExpiration: res.accessExpiration, refreshExpiration: res.refreshExpiration,
            email: email.trimmingCharacters(in: .whitespaces), provider: Session.providerBackend
        )
        client.tokenStore.save(session)
        return .success(session)
    }

    public func me() async throws -> UserProfile { try await client.get(Endpoints.me) }

    public func myTenants() async throws -> [TenantInfo] {
        let raw: JSONValue = try await client.get(Endpoints.myTenants)
        return TenantInfo.list(from: raw)
    }

    public func logout() { client.tokenStore.clear() }
    public var isSignedIn: Bool { client.tokenStore.load() != nil }
}
