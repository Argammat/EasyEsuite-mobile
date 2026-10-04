import Foundation

/// Login / 2FA / me against the tenant-scoped `auth/…` endpoints (dj-rest-auth JWT conventions).
public final class AuthService: @unchecked Sendable {
    private let client: APIClient
    public init(client: APIClient) { self.client = client }

    public func login(email: String, password: String) async throws -> LoginResult {
        let res: TokenResponse = try await client.post(Endpoints.login, body: LoginRequest(email: email.trimmingCharacters(in: .whitespaces), password: password), authenticated: false)
        return try handle(res, email: email)
    }

    public func completeSecondFactor(email: String, challengeToken: String?, code: String) async throws -> LoginResult {
        let res: TokenResponse = try await client.post(Endpoints.login2FA, body: TwoFactorRequest(email: email.trimmingCharacters(in: .whitespaces), ephemeralToken: challengeToken, code: code.trimmingCharacters(in: .whitespaces)), authenticated: false)
        return try handle(res, email: email)
    }

    private func handle(_ res: TokenResponse, email: String) throws -> LoginResult {
        if res.needsSecondFactor { return .secondFactorRequired(challengeToken: res.challengeToken, message: res.detail) }
        guard let access = res.resolvedAccess else { throw APIError.decoding("login response had no access token", body: nil) }
        let session = Session(tenant: client.config.tenant, access: access, refresh: res.resolvedRefresh, accessExpiration: res.accessExpiration, refreshExpiration: res.refreshExpiration, email: email.trimmingCharacters(in: .whitespaces))
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
