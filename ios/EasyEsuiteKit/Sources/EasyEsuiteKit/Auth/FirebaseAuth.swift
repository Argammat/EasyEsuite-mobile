import Foundation

/// Tokens minted by Identity Platform. `idToken` is the Bearer for Firestore; the REST API never sees it.
public struct FirebaseTokens: Equatable, Sendable {
    public var idToken: String
    public var refreshToken: String
    public var expiresAt: Date
    public var localId: String?

    public var expiresAtIso: String { DateText.iso8601(expiresAt) }
    public var session: FirebaseSession { FirebaseSession(idToken: idToken, refreshToken: refreshToken, expiresAt: expiresAtIso) }
}

/// Minimal Firebase Identity Platform client (REST, no SDK). The backend's tenant login returns a
/// `firebase_custom_token`; this exchanges it for an ID token (`accounts:signInWithCustomToken`, with the
/// workspace's Identity Platform tenant set) and renews it with the refresh token. There is deliberately no
/// email/password sign-in: the Firebase users have no passwords (backend team, 2026-10-09).
public final class FirebaseAuth: @unchecked Sendable {
    public let config: FirebaseConfig
    private let session: URLSession

    public init(config: FirebaseConfig, urlSession: URLSession = .shared) {
        self.config = config
        self.session = urlSession
    }

    private struct CustomTokenRequest: Encodable {
        var token: String
        var returnSecureToken = true
        var tenantId: String?
    }

    private struct SignInResponse: Decodable {
        var idToken: String?
        var refreshToken: String?
        var expiresIn: String?
        var localId: String?
    }

    private struct RefreshResponse: Decodable {
        var id_token: String?
        var refresh_token: String?
        var expires_in: String?
        var user_id: String?
    }

    /// Step 3 of sign-in. `tenantId` must be set before signing in (it is sent with the request).
    public func signIn(customToken: String, tenantId: String?) async throws -> FirebaseTokens {
        let body = CustomTokenRequest(token: customToken, tenantId: tenantId?.nilIfBlank)
        let (status, data) = try await send(config.customTokenSignInURL, body: try JSONEncoder().encode(body), contentType: "application/json")   // Firebase keys are camelCase
        guard (200..<300).contains(status) else { throw FirebaseAuth.mapError(status: status, data: data) }
        let res = try JSONDecoder().decode(SignInResponse.self, from: data)
        guard let idToken = res.idToken else { throw APIError.decoding("Firebase sign-in returned no idToken", body: String(data: data, encoding: .utf8)) }
        let seconds = TimeInterval(res.expiresIn ?? "") ?? 3600
        return FirebaseTokens(idToken: idToken, refreshToken: res.refreshToken ?? "", expiresAt: Date().addingTimeInterval(seconds), localId: res.localId)
    }

    /// `securetoken.googleapis.com/v1/token` — form-encoded `grant_type=refresh_token&refresh_token=…`.
    public func refresh(_ refreshToken: String) async throws -> FirebaseTokens {
        var comps = URLComponents()
        comps.queryItems = [URLQueryItem(name: "grant_type", value: "refresh_token"), URLQueryItem(name: "refresh_token", value: refreshToken)]
        let form = Data((comps.percentEncodedQuery ?? "").utf8)
        let (status, data) = try await send(config.refreshURL, body: form, contentType: "application/x-www-form-urlencoded")
        guard (200..<300).contains(status) else { throw FirebaseAuth.mapError(status: status, data: data) }
        let res = try JSONDecoder().decode(RefreshResponse.self, from: data)
        guard let idToken = res.id_token else { throw APIError.decoding("Firebase refresh returned no id_token", body: String(data: data, encoding: .utf8)) }
        let seconds = TimeInterval(res.expires_in ?? "") ?? 3600
        return FirebaseTokens(idToken: idToken, refreshToken: res.refresh_token ?? refreshToken, expiresAt: Date().addingTimeInterval(seconds), localId: res.user_id)
    }

    private func send(_ url: URL, body: Data, contentType: String) async throws -> (Int, Data) {
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = body
        req.setValue(contentType, forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        do {
            let (data, response) = try await session.data(for: req)
            return ((response as? HTTPURLResponse)?.statusCode ?? 0, data)
        } catch {
            throw APIError.network(error.localizedDescription)
        }
    }

    /// Firebase error envelope: `{"error":{"code":400,"message":"INVALID_CUSTOM_TOKEN",...}}` → a sentence the user can act on.
    static func mapError(status: Int, data: Data) -> APIError {
        let code = (try? JSONDecoder().decode(JSONValue.self, from: data))?["error"]?["message"]?.stringValue ?? ""
        let base = code.split(separator: " ").first.map(String.init) ?? code
        let detail: String
        switch base {
        case "INVALID_CUSTOM_TOKEN", "CREDENTIAL_MISMATCH": detail = "The Firebase sign-in token was rejected (project mismatch)."
        case "TENANT_ID_MISMATCH", "INVALID_TENANT_ID", "MISSING_TENANT_ID": detail = "This workspace's Firebase tenant is not configured."
        case "TOKEN_EXPIRED", "INVALID_REFRESH_TOKEN", "USER_NOT_FOUND", "USER_DISABLED": detail = "The Firebase session has expired. Sign in again to refresh it."
        case "TOO_MANY_ATTEMPTS_TRY_LATER": detail = "Too many attempts. Try again in a few minutes."
        case "API_KEY_INVALID", "INVALID_API_KEY": detail = "The app's Firebase configuration is invalid."
        default: detail = code.isEmpty ? "Firebase sign-in failed (HTTP \(status))." : "Firebase sign-in failed: \(code)"
        }
        return APIError.http(status: status, detail: detail, body: String(data: data, encoding: .utf8))
    }
}
