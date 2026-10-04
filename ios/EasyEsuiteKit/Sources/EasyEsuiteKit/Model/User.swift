import Foundation

public struct UserGroup: Decodable, Hashable, Sendable {
    public var id: Int?
    public var name: String?
}

/// `users/users/me/`
public struct UserProfile: Decodable, Hashable, Sendable {
    public var id: Int64?
    public var firstName: String?
    public var lastName: String?
    public var email: String?
    public var image: String?
    public var isSuperuser: Bool?
    public var isStaff: Bool?
    public var groups: [UserGroup]?
    public var finishedOnboarding: Bool?
    public var phoneNumber: String?

    public var displayName: String {
        let n = [firstName, lastName].compactMap { $0 }.joined(separator: " ").trimmingCharacters(in: .whitespaces)
        return n.isEmpty ? (email ?? "") : n
    }
    public var isAdmin: Bool { isSuperuser == true || (groups ?? []).contains { $0.name?.caseInsensitiveCompare("Admin") == .orderedSame } }
    public var roleText: String? { (groups ?? []).compactMap(\.name).joined(separator: ", ").nilIfEmpty ?? (isSuperuser == true ? "Superuser" : nil) }
}

/// One workspace from `users/me/tenants/`. The shape is unconfirmed, so it is parsed leniently.
public struct TenantInfo: Hashable, Identifiable, Sendable {
    public var slug: String
    public var name: String
    public var id: String { slug }

    public static func list(from json: JSONValue?) -> [TenantInfo] {
        let arr: [JSONValue] = json?.arrayValue ?? json?["results"]?.arrayValue ?? json?["tenants"]?.arrayValue ?? []
        return arr.compactMap { e in
            if let s = e.stringValue, e.objectValue == nil { return TenantInfo(slug: s, name: s) }
            guard let slug = ["slug", "schema_name", "tenant", "tenant_id", "company_name", "name"].lazy.compactMap({ e[$0]?.stringValue }).first else { return nil }
            let name = ["display_name", "company_display_name", "name", "company_name"].lazy.compactMap({ e[$0]?.stringValue }).first ?? slug
            return TenantInfo(slug: slug, name: name)
        }
    }
}
