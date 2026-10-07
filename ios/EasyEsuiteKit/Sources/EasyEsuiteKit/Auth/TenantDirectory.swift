import Foundation

/// A workspace as the global directory describes it.
public struct TenantLookup: Equatable, Sendable {
    public var slug: String
    public var displayName: String?
    public var firebaseTenantId: String?
}

/// Resolves the company name typed at login to its workspace and Identity Platform tenant id.
/// The web app stores the result as `tenantId` / `firebaseTenantId` / `companyDisplayName`; the endpoint that
/// produces it is ASSUMED (`{global root}clients/clients/?name=`) — see docs/API_MAP.md. Any failure returns nil
/// so the login screen can fall back to the build-time tenant id.
public final class TenantDirectory: @unchecked Sendable {
    private let apiRoot: URL
    private let session: URLSession

    public init(apiRoot: URL = ApiConfig.defaultApiRoot, urlSession: URLSession = .shared) {
        self.apiRoot = apiRoot
        self.session = urlSession
    }

    public func lookup(_ companyName: String) async -> TenantLookup? {
        let slug = companyName.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !slug.isEmpty else { return nil }
        let base = apiRoot.appendingPathComponent("api/v1", isDirectory: true).appendingPathComponent(Endpoints.tenantLookup, isDirectory: true)
        guard var comps = URLComponents(url: base, resolvingAgainstBaseURL: false) else { return nil }
        comps.queryItems = [URLQueryItem(name: "name", value: slug), URLQueryItem(name: "search", value: slug)]
        guard let url = comps.url else { return nil }
        var req = URLRequest(url: url)
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        guard let result = try? await session.data(for: req),
              (200..<300).contains((result.1 as? HTTPURLResponse)?.statusCode ?? 0),
              let root = try? JSONDecoder().decode(JSONValue.self, from: result.0) else { return nil }

        let candidates: [JSONValue] = root.arrayValue ?? root["results"]?.arrayValue ?? [root]
        let objects = candidates.filter { $0.objectValue != nil }
        let match = objects.first { o in
            ["slug", "schema_name", "name", "company_name", "tenant_id"].contains { o[$0]?.stringValue?.lowercased() == slug }
        } ?? (objects.count == 1 ? objects[0] : nil)
        guard let match else { return nil }
        func str(_ keys: [String]) -> String? { keys.lazy.compactMap { match[$0]?.stringValue?.nilIfBlank }.first }
        return TenantLookup(
            slug: str(["slug", "schema_name", "tenant_id", "name"]) ?? slug,
            displayName: str(["display_name", "company_display_name", "company_name", "name"]),
            firebaseTenantId: str(["firebase_tenant_id", "firebaseTenantId", "identity_tenant_id"])
        )
    }
}
