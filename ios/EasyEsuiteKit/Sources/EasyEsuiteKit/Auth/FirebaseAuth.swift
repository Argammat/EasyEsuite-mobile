import Foundation

/// Tokens minted by Identity Platform. `idToken` is what the API wants as `Authorization: Bearer`.
public struct FirebaseTokens: Equatable, Sendable {
    public var idToken: String
    public var refreshToken: String
    public var expiresAt: Date
    public var localId: String?

    public var expiresAtIso: String { DateText.iso8601(expiresAt) }
}

/// Minimal Firebase Identity Platform client (REST, no SDK) — email/password sign-in against the
/// workspace's tenant plus refresh-token exchange. Errors are mapped to the same `APIError` family the
/// rest of the kit throws so the UI needs no special cases.
public final class FirebaseAuth: @unchecked Sendable {
    public let config: FirebaseConfig
    private let session: URLSession

    public init(config: FirebaseConfig, urlSession: URLSession = .shared) {
        self.config = config
        self.session = urlSession
    }

    private struct SignInRequest: Encodable {
        var email: String
        var password: String
        var returnSecureToken = true
        var tenantId: String?
    }

    private struct SignInResponse: Decodable {
        var idToken: String?
        var refreshToken: String?
        var expiresIn: String?
        var localId: String?
        var mfaPendingCredential: String?
    }

    private struct RefreshResponse: Decodable {
        var id_token: String?
        var refresh_token: String?
        var expires_in: String?
        var user_id: String?
    }

    /// `accounts:signInWithPassword` — `{email, password, returnSecureToken, tenantId}`.
    public func signIn(email: String, password: String) async throws -> FirebaseTokens {
        let body = SignInRequest(email: email.trimmingCharacters(in: .whitespaces), password: password, tenantId: config.tenantId)
        let encoder = JSONEncoder()   // Firebase keys are camelCase — no snake_case strategy here.
        let (status, data) = try await send(config.signInURL, body: try encoder.encode(body), contentType: "application/json")
        guard (200..<300).contains(status) else { throw FirebaseAuth.mapError(status: status, data: data) }
        let res = try JSONDecoder().decode(SignInResponse.self, from: data)
        if res.mfaPendingCredential != nil, res.idToken == nil {
            throw APIError.http(status: 401, detail: "This account requires a second factor, which the mobile app does not support yet. Sign in on the web to manage it.", body: nil)
        }
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

    /// Firebase error envelope: `{"error":{"code":400,"message":"INVALID_PASSWORD",...}}` → a sentence the user can act on.
    static func mapError(status: Int, data: Data) -> APIError {
        let code = (try? JSONDecoder().decode(JSONValue.self, from: data))?["error"]?["message"]?.stringValue ?? ""
        let base = code.split(separator: " ").first.map(String.init) ?? code
        let detail: String
        switch base {
        case "INVALID_PASSWORD", "INVALID_LOGIN_CREDENTIALS", "EMAIL_NOT_FOUND", "INVALID_EMAIL": detail = "Email or password is incorrect."
        case "USER_DISABLED": detail = "This account has been disabled."
        case "TOO_MANY_ATTEMPTS_TRY_LATER": detail = "Too many attempts. Try again in a few minutes."
        case "TENANT_ID_MISMATCH", "INVALID_TENANT_ID", "MISSING_TENANT_ID": detail = "This email does not belong to that workspace."
        case "TOKEN_EXPIRED", "INVALID_REFRESH_TOKEN", "USER_NOT_FOUND": detail = "Your session has expired. Please sign in again."
        case "API_KEY_INVALID", "INVALID_API_KEY": detail = "The app's Firebase configuration is invalid."
        default: detail = code.isEmpty ? "Sign-in failed (HTTP \(status))." : "Sign-in failed: \(code)"
        }
        return APIError.http(status: status, detail: detail, body: String(data: data, encoding: .utf8))
    }
}
