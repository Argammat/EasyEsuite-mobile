import Foundation

/// What we persist between launches (Keychain on iOS).
public struct Session: Codable, Equatable, Sendable {
    public static let providerFirebase = "firebase"
    public static let providerBackend = "backend"

    public var tenant: String
    public var access: String
    public var refresh: String?
    public var accessExpiration: String?
    public var refreshExpiration: String?
    public var email: String?
    /// Who minted the tokens: `providerFirebase` (Identity Platform) or `providerBackend` (`auth/login/`).
    public var provider: String
    /// Identity Platform tenant the user signed in against (Firebase flow only); kept so the next launch reuses it.
    public var firebaseTenantId: String?

    public init(tenant: String, access: String, refresh: String? = nil, accessExpiration: String? = nil, refreshExpiration: String? = nil,
                email: String? = nil, provider: String = Session.providerBackend, firebaseTenantId: String? = nil) {
        self.tenant = tenant; self.access = access; self.refresh = refresh
        self.accessExpiration = accessExpiration; self.refreshExpiration = refreshExpiration; self.email = email
        self.provider = provider; self.firebaseTenantId = firebaseTenantId
    }

    // Sessions saved by older builds have no `provider`; default it instead of failing to decode (which would sign the user out).
    private enum CodingKeys: String, CodingKey { case tenant, access, refresh, accessExpiration, refreshExpiration, email, provider, firebaseTenantId }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        tenant = try c.decode(String.self, forKey: .tenant)
        access = try c.decode(String.self, forKey: .access)
        refresh = try c.decodeIfPresent(String.self, forKey: .refresh)
        accessExpiration = try c.decodeIfPresent(String.self, forKey: .accessExpiration)
        refreshExpiration = try c.decodeIfPresent(String.self, forKey: .refreshExpiration)
        email = try c.decodeIfPresent(String.self, forKey: .email)
        provider = try c.decodeIfPresent(String.self, forKey: .provider) ?? Session.providerBackend
        firebaseTenantId = try c.decodeIfPresent(String.self, forKey: .firebaseTenantId)
    }

    /// Tokens minted but no workspace chosen yet (login is email + password; the company comes after).
    public var isPending: Bool { tenant.trimmingCharacters(in: .whitespaces).isEmpty }

    /// True when the access token expires within `skew` seconds (or already has). Unknown expiry → false.
    public func isAccessExpiring(skew: TimeInterval = 60, now: Date = Date()) -> Bool {
        guard let exp = DateText.parse(accessExpiration) else { return false }
        return exp <= now.addingTimeInterval(skew)
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
    /// The user object some login responses carry (may list the user's tenants).
    public var user: JSONValue?
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
    /// Signed in, now pick the company. `pending` holds the tokens (tenant blank); `workspaces` is what the
    /// backend listed — empty when the list endpoint is unknown, in which case the UI asks for the slug.
    case workspaceRequired(pending: Session, workspaces: [TenantInfo])
}
