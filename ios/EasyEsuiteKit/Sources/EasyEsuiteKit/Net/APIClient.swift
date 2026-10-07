import Foundation

/// Thin URLSession wrapper that knows about the tenant URL prefix, the JWT header and token refresh.
public final class APIClient: @unchecked Sendable {
    public let config: ApiConfig
    public let tokenStore: TokenStore
    public let decoder: JSONDecoder
    public let encoder: JSONEncoder
    /// Called once when a refresh fails and the user must sign in again.
    public var onSessionExpired: (@Sendable () -> Void)?

    private let session: URLSession
    private let coordinator = RefreshCoordinator()
    /// Explicit refresh strategy (tests); nil = follow the session's provider.
    private let refresherOverride: TokenRefresher?
    private let firebaseRefresher: TokenRefresher?

    /// - Parameter refresher: how an expired access token is renewed. When nil the strategy follows the session:
    ///   tokens minted by Firebase (`Session.provider == firebase`) go through the securetoken exchange
    ///   (needs `config.firebase`), everything else through the backend's `auth/token/refresh/`.
    public init(config: ApiConfig, tokenStore: TokenStore, urlSession: URLSession? = nil, refresher: TokenRefresher? = nil) {
        self.config = config
        self.tokenStore = tokenStore
        self.refresherOverride = refresher
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        self.decoder = d
        let e = JSONEncoder()
        e.keyEncodingStrategy = .convertToSnakeCase
        self.encoder = e
        let resolved: URLSession
        if let urlSession { resolved = urlSession } else {
            let cfg = URLSessionConfiguration.default
            cfg.timeoutIntervalForRequest = 60
            cfg.waitsForConnectivity = true
            resolved = URLSession(configuration: cfg)
        }
        self.session = resolved
        self.firebaseRefresher = config.firebase.map { FirebaseTokenRefresher(firebase: FirebaseAuth(config: $0, urlSession: resolved)) as TokenRefresher }
    }

    private func refresher(for session: Session) -> TokenRefresher {
        if let refresherOverride { return refresherOverride }
        if session.provider == Session.providerFirebase, let firebaseRefresher { return firebaseRefresher }
        return BackendTokenRefresher(client: self)
    }

    // MARK: - Public API

    public func get<T: Decodable>(_ path: String, query: [String: Any?] = [:], authenticated: Bool = true) async throws -> T {
        try await execute("GET", path, query: query, body: nil, authenticated: authenticated)
    }

    public func post<B: Encodable, T: Decodable>(_ path: String, body: B, query: [String: Any?] = [:], authenticated: Bool = true) async throws -> T {
        try await execute("POST", path, query: query, body: try encoder.encode(body), authenticated: authenticated)
    }

    public func postEmpty<T: Decodable>(_ path: String, query: [String: Any?] = [:]) async throws -> T {
        try await execute("POST", path, query: query, body: Data("{}".utf8), authenticated: true)
    }

    public func patch<B: Encodable, T: Decodable>(_ path: String, body: B) async throws -> T {
        try await execute("PATCH", path, query: [:], body: try encoder.encode(body), authenticated: true)
    }

    public func delete(_ path: String) async throws {
        _ = try await raw("DELETE", url: url(path, query: [:]), body: nil, contentType: nil, authenticated: true)
    }

    /// Multipart upload (item photos).
    public func upload<T: Decodable>(_ path: String, field: String, fileName: String, mimeType: String, data: Data) async throws -> T {
        let boundary = "Boundary-\(UUID().uuidString)"
        var body = Data()
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"\(field)\"; filename=\"\(fileName)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: \(mimeType)\r\n\r\n".data(using: .utf8)!)
        body.append(data)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        let res = try await raw("POST", url: url(path, query: [:]), body: body, contentType: "multipart/form-data; boundary=\(boundary)", authenticated: true)
        return try decode(res)
    }

    // MARK: - Core

    func execute<T: Decodable>(_ method: String, _ path: String, query: [String: Any?], body: Data?, authenticated: Bool) async throws -> T {
        let res = try await raw(method, url: url(path, query: query), body: body ?? (["POST", "PUT", "PATCH"].contains(method) ? Data("{}".utf8) : nil), contentType: "application/json", authenticated: authenticated)
        return try decode(res)
    }

    public struct Raw: Sendable { public let status: Int; public let data: Data }

    private func raw(_ method: String, url: URL, body: Data?, contentType: String?, authenticated: Bool) async throws -> Raw {
        var token = authenticated ? tokenStore.load()?.access : nil
        if authenticated, tokenStore.load()?.isAccessExpiring() == true, let fresh = await refreshAccessToken() {
            // Firebase ID tokens live an hour; renew before the call instead of eating a 401 round-trip.
            token = fresh
        }
        var res = try await send(method, url: url, body: body, contentType: contentType, token: token)
        if res.status == 401 && authenticated {
            if let fresh = await refreshAccessToken() {
                res = try await send(method, url: url, body: body, contentType: contentType, token: fresh)
            }
            if res.status == 401 {
                tokenStore.clear()
                onSessionExpired?()
                throw APIError.unauthorized
            }
        }
        guard (200..<300).contains(res.status) else {
            throw APIError.http(status: res.status, detail: APIError.describe(status: res.status, body: res.data), body: String(data: res.data, encoding: .utf8))
        }
        return res
    }

    private func send(_ method: String, url: URL, body: Data?, contentType: String?, token: String?) async throws -> Raw {
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.httpBody = body
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let contentType, body != nil { req.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        if let token { req.setValue("\(config.authScheme) \(token)", forHTTPHeaderField: "Authorization") }
        do {
            let (data, response) = try await session.data(for: req)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            return Raw(status: status, data: data)
        } catch let e as URLError {
            throw APIError.network(e.localizedDescription)
        } catch {
            throw APIError.network(error.localizedDescription)
        }
    }

    private func decode<T: Decodable>(_ res: Raw) throws -> T {
        if res.data.isEmpty {
            // 204-style replies are fine when the caller only asked for loose JSON.
            if let v = JSONValue.null as? T { return v }
            throw APIError.decoding("empty body", body: nil)
        }
        do { return try decoder.decode(T.self, from: res.data) } catch {
            throw APIError.decoding(String(describing: error).prefix(300).description, body: String(data: res.data, encoding: .utf8))
        }
    }

    /// Serialised so concurrent 401s trigger a single refresh. Returns the new access token or nil.
    private func refreshAccessToken() async -> String? {
        await coordinator.run { [weak self] in
            guard let self, let current = self.tokenStore.load(), current.refresh?.isEmpty == false else { return nil }
            guard let renewed = await self.refresher(for: current).refresh(current) else { return nil }
            self.tokenStore.save(renewed)
            return renewed.access
        }
    }

    /// Raw call helper for refresh strategies (no auth header, no retry).
    func rawPost(_ path: String, body: Data) async throws -> Raw {
        try await send("POST", url: url(path, query: [:]), body: body, contentType: "application/json", token: nil)
    }

    /// Absolute URL string under the global (tenant-less) root — for the calls made before a workspace is chosen.
    public func globalPath(_ path: String) -> String {
        // Plain string concatenation: `appendingPathComponent("auth/login/", isDirectory: true)` doubles the
        // trailing slash on Darwin, which the tests (and DRF's strict routing) reject.
        var root = config.globalRoot.absoluteString
        if !root.hasSuffix("/") { root += "/" }
        return root + (path.hasPrefix("/") ? String(path.dropFirst()) : path)
    }

    public func url(_ path: String, query: [String: Any?]) -> URL {
        let base: URL = path.hasPrefix("http") ? URL(string: path)! : URL(string: path, relativeTo: config.tenantRoot)!.absoluteURL
        var comps = URLComponents(url: base, resolvingAgainstBaseURL: false)!
        var items: [URLQueryItem] = comps.queryItems ?? []
        for key in query.keys.sorted() {
            guard let value = query[key] ?? nil else { continue }
            switch value {
            case let b as Bool: items.append(URLQueryItem(name: key, value: b ? "true" : "false"))
            case let arr as [Any]: for v in arr { items.append(URLQueryItem(name: key, value: "\(v)")) }
            default: items.append(URLQueryItem(name: key, value: "\(value)"))
            }
        }
        comps.queryItems = items.isEmpty ? nil : items
        return comps.url!
    }
}

/// Collapses concurrent refresh attempts into one in-flight task.
actor RefreshCoordinator {
    private var inflight: Task<String?, Never>?

    func run(_ op: @escaping @Sendable () async -> String?) async -> String? {
        if let inflight { return await inflight.value }
        let task = Task { await op() }
        inflight = task
        let result = await task.value
        inflight = nil
        return result
    }
}
