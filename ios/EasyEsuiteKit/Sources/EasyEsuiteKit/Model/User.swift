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
