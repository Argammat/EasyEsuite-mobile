import Foundation

/// What we persist between launches (Keychain on iOS).
public struct Session: Codable, Equatable, Sendable {
    public var tenant: String
    public var access: String
    public var refresh: String?
    public var accessExpiration: String?
    public var refreshExpiration: String?
    public var email: String?

    public init(tenant: String, access: String, refresh: String? = nil, accessExpiration: String? = nil, refreshExpiration: String? = nil, email: String? = nil) {
        self.tenant = tenant; self.access = access; self.refresh = refresh
        self.accessExpiration = accessExpiration; self.refreshExpiration = refreshExpiration; self.email = email
    }
}

/// Storage abstraction so the kit stays platform-free (Keychain in the app, memory in tests).
public protocol TokenStore: AnyObject, Sendable {
    func load() -> Session?
    func save(_ session: Session)
    func clear()
}

public final class InMemoryTokenStore: TokenStore, @unchecked Sendable {
    private let lock = NSLock()
    private var session: Session?
    public init(_ session: Session? = nil) { self.session = session }
    public func load() -> Session? { lock.lock(); defer { lock.unlock() }; return session }
    public func save(_ session: Session) { lock.lock(); self.session = session; lock.unlock() }
    public func clear() { lock.lock(); session = nil; lock.unlock() }
}

public struct LoginRequest: Encodable, Sendable {
    public var email: String
    public var password: String
}

/// dj-rest-auth style token response, decoded leniently (see docs/API_MAP.md → Auth).
public struct TokenResponse: Decodable, Sendable {
    public var access: String?
    public var refresh: String?
    public var accessToken: String?
    public var refreshToken: String?
    public var token: String?
    public var accessExpiration: String?
    public var refreshExpiration: String?
    public var mfaRequired: Bool?
    public var requires2Fa: Bool?   // convertFromSnakeCase maps requires_2fa → requires2Fa
    public var ephemeralToken: String?
    public var mfaToken: String?
    public var detail: String?

    public var resolvedAccess: String? { access ?? accessToken ?? token }
    public var resolvedRefresh: String? { refresh ?? refreshToken }
    public var needsSecondFactor: Bool { resolvedAccess == nil && (mfaRequired == true || requires2Fa == true || ephemeralToken != nil || mfaToken != nil) }
    public var challengeToken: String? { ephemeralToken ?? mfaToken }
}

/// Body for `auth/login/2fa/`. Field names ASSUMED; adjust once captured from the web app.
public struct TwoFactorRequest: Encodable, Sendable {
    public var email: String?
    public var ephemeralToken: String?
    public var code: String
    public var otp: String
    public init(email: String?, ephemeralToken: String?, code: String) { self.email = email; self.ephemeralToken = ephemeralToken; self.code = code; self.otp = code }
}

public struct RefreshRequest: Encodable, Sendable { public var refresh: String }

public enum LoginResult: Sendable {
    case success(Session)
    case secondFactorRequired(challengeToken: String?, message: String?)
}
