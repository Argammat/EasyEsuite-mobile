import Foundation

/// One Firestore document with its typed values flattened to plain `JSONValue`s.
public struct FirestoreDocument: Hashable, Sendable, Identifiable {
    /// Full resource name `projects/…/documents/tenants/42/…/abc`.
    public var name: String
    public var fields: [String: JSONValue]
    public var createTime: String?
    public var updateTime: String?

    public var id: String { name.split(separator: "/").last.map(String.init) ?? name }
    public subscript(key: String) -> JSONValue? { fields[key] }
}

/// Firestore's typed value encoding (`{"stringValue": "x"}`, `{"mapValue": {"fields": …}}` …) → `JSONValue`.
public enum FirestoreValue {
    public static func decode(_ value: JSONValue) -> JSONValue {
        guard let o = value.objectValue, o.count == 1, let first = o.first else { return value }
        let kind = first.key, raw = first.value
        switch kind {
        case "stringValue", "referenceValue", "timestampValue", "bytesValue": return raw
        case "integerValue": return raw.stringValue.flatMap { Double($0) }.map { JSONValue.number($0) } ?? raw   // integers arrive as strings
        case "doubleValue": return raw
        case "booleanValue": return raw
        case "nullValue": return .null
        case "mapValue": return .object(fields(raw["fields"]))
        case "arrayValue": return .array((raw["values"]?.arrayValue ?? []).map(decode))
        case "geoPointValue": return raw
        default: return value
        }
    }

    public static func fields(_ value: JSONValue?) -> [String: JSONValue] {
        guard let o = value?.objectValue else { return [:] }
        var out: [String: JSONValue] = [:]
        for (k, v) in o { out[k] = decode(v) }
        return out
    }

    static func document(_ json: JSONValue) -> FirestoreDocument? {
        guard let name = json["name"]?.stringValue else { return nil }
        return FirestoreDocument(name: name, fields: fields(json["fields"]), createTime: json["createTime"]?.stringValue, updateTime: json["updateTime"]?.stringValue)
    }
}

/// Read-only Firestore REST client for the screens the web app serves from Firestore (Copilot conversations,
/// connector status — "connector" database; in-app notifications — "notifications" database). Documents live
/// under `tenants/{session.tenantId}/…`; the exact paths come from the backend team per screen.
/// Authenticates with the Firebase ID token from the custom-token sign-in (refreshed by `AuthService`).
public final class FirestoreClient: @unchecked Sendable {
    public let config: FirebaseConfig
    public let database: String
    private let auth: AuthService
    private let session: URLSession

    public init(config: FirebaseConfig, database: String, auth: AuthService, urlSession: URLSession = .shared) {
        self.config = config
        self.database = database
        self.auth = auth
        self.session = urlSession
    }

    /// `GET …/documents/{path}` — one document.
    public func document(_ path: String) async throws -> FirestoreDocument {
        let json = try await get(url(path, query: []))
        guard let doc = FirestoreValue.document(json) else { throw APIError.decoding("not a Firestore document", body: nil) }
        return doc
    }

    /// `GET …/documents/{collectionPath}?pageSize=&orderBy=&pageToken=` — a page of a collection.
    public func list(_ collectionPath: String, pageSize: Int = 50, orderBy: String? = nil, pageToken: String? = nil) async throws -> (documents: [FirestoreDocument], nextPageToken: String?) {
        var q = [URLQueryItem(name: "pageSize", value: String(pageSize))]
        if let orderBy { q.append(URLQueryItem(name: "orderBy", value: orderBy)) }
        if let pageToken { q.append(URLQueryItem(name: "pageToken", value: pageToken)) }
        let json = try await get(url(collectionPath, query: q))
        let docs = (json["documents"]?.arrayValue ?? []).compactMap(FirestoreValue.document)
        return (docs, json["nextPageToken"]?.stringValue)
    }

    private func url(_ path: String, query: [URLQueryItem]) -> URL {
        let trimmed = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        var comps = URLComponents(url: config.firestoreRoot(database: database).appendingPathComponent(trimmed), resolvingAgainstBaseURL: false)!
        comps.queryItems = query.isEmpty ? nil : query
        return comps.url!
    }

    private func get(_ url: URL) async throws -> JSONValue {
        guard let token = await auth.firebaseIdToken() else {
            throw APIError.http(status: 401, detail: "Live data is unavailable for this workspace (no Firebase sign-in).", body: nil)
        }
        var req = URLRequest(url: url)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        let data: Data, status: Int
        do {
            let (d, r) = try await session.data(for: req)
            data = d; status = (r as? HTTPURLResponse)?.statusCode ?? 0
        } catch { throw APIError.network(error.localizedDescription) }
        guard (200..<300).contains(status) else {
            let message = (try? JSONDecoder().decode(JSONValue.self, from: data))?["error"]?["message"]?.stringValue
            throw APIError.http(status: status, detail: message ?? "Firestore request failed (HTTP \(status)).", body: String(data: data, encoding: .utf8))
        }
        do { return try JSONDecoder().decode(JSONValue.self, from: data) } catch {
            throw APIError.decoding(String(describing: error).prefix(300).description, body: String(data: data, encoding: .utf8))
        }
    }
}
