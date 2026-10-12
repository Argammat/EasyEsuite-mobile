import Foundation

/// Renews an expired access token. Implementations must not throw for ordinary failures — return nil.
public protocol TokenRefresher: Sendable {
    func refresh(_ current: Session) async -> Session?
}

/// `POST /clients/{tenant}/api/v1/auth/token/refresh/ {refresh}` → a **new access and a new refresh token**
/// (VERIFIED). Each refresh token works once, so both are stored; `APIClient` serialises the calls so only
/// one refresh is ever in flight.
public struct BackendTokenRefresher: TokenRefresher {
    let client: APIClient
    public init(client: APIClient) { self.client = client }

    public func refresh(_ current: Session) async -> Session? {
        guard let refresh = current.refresh,
              let body = try? client.encoder.encode(RefreshRequest(refresh: refresh)),
              let res = try? await client.rawPost(Endpoints.tokenRefresh, body: body),
              (200..<300).contains(res.status),
              let parsed = try? client.decoder.decode(TokenResponse.self, from: res.data),
              let access = parsed.resolvedAccess else { return nil }
        var updated = current
        updated.access = access
        updated.refresh = parsed.resolvedRefresh ?? current.refresh
        updated.accessExpiration = parsed.accessExpiration ?? JWT.expirationIso(access) ?? current.accessExpiration
        updated.refreshExpiration = parsed.refreshExpiration ?? JWT.expirationIso(updated.refresh) ?? current.refreshExpiration
        return updated
    }
}
