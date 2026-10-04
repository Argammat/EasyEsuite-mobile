import Foundation
import Security

/// Keychain-backed session store (kSecClassGenericPassword, this-device-only, available after first unlock).
public final class KeychainTokenStore: TokenStore, @unchecked Sendable {
    private let service: String
    private let account = "session"
    private let lock = NSLock()
    private var cached: Session?

    public init(service: String = "com.easyesuite.mobile") { self.service = service }

    public func load() -> Session? {
        lock.lock(); defer { lock.unlock() }
        if let cached { return cached }
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        cached = try? JSONDecoder().decode(Session.self, from: data)
        return cached
    }

    public func save(_ session: Session) {
        lock.lock(); defer { lock.unlock() }
        cached = session
        guard let data = try? JSONEncoder().encode(session) else { return }
        let attrs: [String: Any] = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(baseQuery as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound {
            var add = baseQuery
            attrs.forEach { add[$0.key] = $0.value }
            SecItemAdd(add as CFDictionary, nil)
        }
    }

    public func clear() {
        lock.lock(); defer { lock.unlock() }
        cached = nil
        SecItemDelete(baseQuery as CFDictionary)
    }

    private var baseQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    // Non-secret conveniences for prefilling the login form live in UserDefaults.
    public var lastTenant: String? {
        get { UserDefaults.standard.string(forKey: "easyesuite.lastTenant") }
        set { UserDefaults.standard.set(newValue, forKey: "easyesuite.lastTenant") }
    }
    public var lastEmail: String? {
        get { UserDefaults.standard.string(forKey: "easyesuite.lastEmail") }
        set { UserDefaults.standard.set(newValue, forKey: "easyesuite.lastEmail") }
    }
}
