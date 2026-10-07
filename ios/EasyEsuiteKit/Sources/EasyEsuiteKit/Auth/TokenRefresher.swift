import Foundation

/// Renews an expired access token. Implementations must not throw for ordinary failures — return nil.
public protocol TokenRefresher: Sendable {
    func refresh(_ current: Session) async -> Session?
}

/// Identity Platform: exchange the Firebase refresh token for a new ID token.
public struct FirebaseTokenRefresher: TokenRefresher {
    let firebase: FirebaseAuth
    public init(firebase: FirebaseAuth) { self.firebase = firebase }

    public func refresh(_ current: Session) async -> Session? {
        guard let refresh = current.refresh, let tokens = try? await firebase.refresh(refresh) else { return nil }
        var updated = current
        updated.access = tokens.idToken
        updated.refresh = tokens.refreshToken.isEmpty ? refresh : tokens.refreshToken
        updated.accessExpiration = tokens.expiresAtIso
        updated.provider = Session.providerFirebase
        return updated
    }
}

/// Backend-proxied flow: `POST auth/token/refresh/ {refresh}` → `{access, access_expiration}` (the call the web app makes).
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
        updated.accessExpiration = parsed.accessExpiration ?? current.accessExpiration
        updated.refreshExpiration = parsed.refreshExpiration ?? current.refreshExpiration
        updated.provider = Session.providerBackend
        return updated
    }
}
