import Foundation

/// Firebase / Identity Platform tokens obtained from the backend's custom token (step 3 of sign-in).
/// Only the Firestore-backed screens use them; every REST call uses the backend JWT.
public struct FirebaseSession: Codable, Equatable, Sendable {
    public var idToken: String
    public var refreshToken: String
    /// ISO-8601.
    public var expiresAt: String

    public init(idToken: String, refreshToken: String, expiresAt: String) {
        self.idToken = idToken; self.refreshToken = refreshToken; self.expiresAt = expiresAt
    }

    public func isExpiring(skew: TimeInterval = 60, now: Date = Date()) -> Bool {
        guard let exp = DateText.parse(expiresAt) else { return true }
        return exp <= now.addingTimeInterval(skew)
    }
}

/// What we persist between launches (Keychain on iOS). One session = one user inside one workspace.
public struct Session: Codable, Equatable, Sendable {
    /// The workspace's `schema_name` — part of every REST URL.
    public var tenant: String
    public var access: String
    public var refresh: String?
    public var accessExpiration: String?
    public var refreshExpiration: String?
    public var email: String?
    /// Numeric tenant id (`user.tenant_id`) — Firestore documents live under `tenants/{tenantId}/…`.
    public var tenantId: Int?
    /// `user.pk` — notifications live under `tenants/{tenantId}/users/{userPk}/notifications`.
    public var userPk: Int?
    public var roles: [String]
    /// Identity Platform tenant for this workspace (`user.firebase_tenant_id`); nil = no Firestore screens.
    public var firebaseTenantId: String?
    public var firebase: FirebaseSession?

    public init(tenant: String, access: String, refresh: String? = nil, accessExpiration: String? = nil, refreshExpiration: String? = nil,
                email: String? = nil, tenantId: Int? = nil, userPk: Int? = nil, roles: [String] = [], firebaseTenantId: String? = nil,
                firebase: FirebaseSession? = nil) {
        self.tenant = tenant; self.access = access; self.refresh = refresh
        self.accessExpiration = accessExpiration; self.refreshExpiration = refreshExpiration; self.email = email
        self.tenantId = tenantId; self.userPk = userPk; self.roles = roles; self.firebaseTenantId = firebaseTenantId; self.firebase = firebase
    }

    // Sessions saved by older builds lack some keys; default them instead of failing to decode (which would sign the user out).
    private enum CodingKeys: String, CodingKey { case tenant, access, refresh, accessExpiration, refreshExpiration, email, tenantId, userPk, roles, firebaseTenantId, firebase }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        tenant = try c.decode(String.self, forKey: .tenant)
        access = try c.decode(String.self, forKey: .access)
        refresh = try c.decodeIfPresent(String.self, forKey: .refresh)
        accessExpiration = try c.decodeIfPresent(String.self, forKey: .accessExpiration)
        refreshExpiration = try c.decodeIfPresent(String.self, forKey: .refreshExpiration)
        email = try c.decodeIfPresent(String.self, forKey: .email)
        tenantId = try c.decodeIfPresent(Int.self, forKey: .tenantId)
        userPk = try c.decodeIfPresent(Int.self, forKey: .userPk)
        roles = try c.decodeIfPresent([String].self, forKey: .roles) ?? []
        firebaseTenantId = try c.decodeIfPresent(String.self, forKey: .firebaseTenantId)
        firebase = try c.decodeIfPresent(FirebaseSession.self, forKey: .firebase)
    }

    /// True when the access token expires within `skew` seconds (or already has). Unknown expiry → false.
    public func isAccessExpiring(skew: TimeInterval = 60, now: Date = Date()) -> Bool {
        guard let exp = DateText.parse(accessExpiration) else { return false }
        return exp <= now.addingTimeInterval(skew)
    }

    public var isAdmin: Bool { roles.contains { $0.caseInsensitiveCompare("ADMIN") == .orderedSame } }
    /// Firestore screens need a Firebase tenant and a custom-token sign-in that succeeded.
    public var hasFirestore: Bool { firebaseTenantId != nil && firebase != nil }
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

/// One workspace from `POST api/v1/auth/login/tenants/` (VERIFIED shape).
public struct Workspace: Decodable, Hashable, Identifiable, Sendable {
    public var tenantId: Int?
    public var schemaName: String
    public var displayName: String?
    /// Deprecated by the backend; `displayName` is the one to show.
    public var companyName: String?
    public var logoUrl: String?
    public var initials: String?
    public var lastActiveAt: String?

    public init(tenantId: Int? = nil, schemaName: String, displayName: String? = nil, companyName: String? = nil, logoUrl: String? = nil, initials: String? = nil, lastActiveAt: String? = nil) {
        self.tenantId = tenantId; self.schemaName = schemaName; self.displayName = displayName; self.companyName = companyName
        self.logoUrl = logoUrl; self.initials = initials; self.lastActiveAt = lastActiveAt
    }

    public var id: String { schemaName }
    public var name: String { displayName?.nilIfBlank ?? companyName?.nilIfBlank ?? schemaName }
    public var monogram: String { initials?.nilIfBlank ?? String(name.prefix(2)).uppercased() }
}

/// The `user` object in the tenant login response (VERIFIED fields; everything optional so extras never break decoding).
public struct LoginUser: Decodable, Hashable, Sendable {
    public var pk: Int?
    public var email: String?
    public var tenantId: Int?
    public var firebaseTenantId: String?
    public var isMfaEnabled: Bool?
    public var isMfaMandatory: Bool?
    public var needsPasswordReset: Bool?
    public var allRoleNames: [String]?
    public var planName: String?

    public var requiresSecondFactor: Bool { isMfaEnabled == true || isMfaMandatory == true }
}

/// Tenant login response, decoded leniently (see docs/API_MAP.md → Auth).
public struct TokenResponse: Decodable, Sendable {
    public var access: String?
    public var refresh: String?
    public var accessToken: String?
    public var refreshToken: String?
    public var token: String?
    public var accessExpiration: String?
    public var refreshExpiration: String?
    /// Present when the workspace has a Firebase tenant → `accounts:signInWithCustomToken`.
    public var firebaseCustomToken: String?
    public var user: LoginUser?
    public var mfaRequired: Bool?
    public var requires2Fa: Bool?   // convertFromSnakeCase maps requires_2fa → requires2Fa
    public var ephemeralToken: String?
    public var mfaToken: String?
    public var detail: String?

    public var resolvedAccess: String? { access ?? accessToken ?? token }
    public var resolvedRefresh: String? { refresh ?? refreshToken }
    public var challengeToken: String? { ephemeralToken ?? mfaToken }
    /// A one-time code must follow: either the backend withheld the tokens behind a challenge token, or the user has MFA on.
    public var needsSecondFactor: Bool {
        (resolvedAccess == nil && (mfaRequired == true || requires2Fa == true || challengeToken != nil)) || user?.requiresSecondFactor == true
    }
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
    /// `pending` holds tokens the backend already issued (MFA flags on the user) — they are not stored until the code is verified.
    case secondFactorRequired(pending: Session?, challengeToken: String?, message: String?)
    /// `user.needs_password_reset`: the app must not continue; the password is reset on the web.
    case passwordResetRequired(email: String)
}

/// Reads claims out of a JWT without verifying it — only used to learn when a token expires when the
/// backend omits `access_expiration`.
public enum JWT {
    public static func claims(_ token: String) -> [String: JSONValue]? {
        let parts = token.split(separator: ".")
        guard parts.count >= 2 else { return nil }
        var payload = String(parts[1]).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while payload.count % 4 != 0 { payload += "=" }
        guard let data = Data(base64Encoded: payload), let json = try? JSONDecoder().decode(JSONValue.self, from: data) else { return nil }
        return json.objectValue
    }

    public static func expiration(_ token: String?) -> Date? {
        guard let token, let exp = claims(token)?["exp"]?.doubleValue else { return nil }
        return Date(timeIntervalSince1970: exp)
    }

    public static func expirationIso(_ token: String?) -> String? { expiration(token).map { DateText.iso8601($0) } }
}
