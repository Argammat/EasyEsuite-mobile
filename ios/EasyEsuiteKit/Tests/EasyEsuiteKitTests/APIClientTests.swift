import XCTest
@testable import EasyEsuiteKit

/// Routes every request to a per-test handler so we can assert headers/paths and script responses.
final class MockURLProtocol: URLProtocol {
    static var handler: ((URLRequest) -> (Int, Data))?
    static var requests: [URLRequest] = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        // Capture the body now: URLSession hands us a stream, which is gone by the time a test inspects the request.
        var captured = request
        if captured.httpBody == nil, let body = request.bodyData { captured.httpBody = body }
        MockURLProtocol.requests.append(captured)
        let (status, data) = MockURLProtocol.handler?(request) ?? (500, Data())
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

extension URLRequest {
    /// `httpBody` is nil on requests seen by URLProtocol; read the stream instead.
    var bodyData: Data? {
        if let b = httpBody { return b }
        guard let stream = httpBodyStream else { return nil }
        stream.open(); defer { stream.close() }
        var data = Data(); let size = 4096; var buf = [UInt8](repeating: 0, count: size)
        while stream.hasBytesAvailable { let n = stream.read(&buf, maxLength: size); if n <= 0 { break }; data.append(buf, count: n) }
        return data
    }
}

final class APIClientTests: XCTestCase {
    private var store: InMemoryTokenStore!
    private var client: APIClient!

    override func setUp() {
        super.setUp()
        MockURLProtocol.requests = []
        store = InMemoryTokenStore(Session(tenant: "demo", access: "old-access", refresh: "refresh-1"))
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [MockURLProtocol.self]
        client = APIClient(config: ApiConfig(tenant: "Demo"), tokenStore: store, urlSession: URLSession(configuration: cfg))
    }

    private func fixture(_ name: String) throws -> Data {
        try Data(contentsOf: XCTUnwrap(Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures")))
    }

    func testTenantRootAndQueryEncoding() {
        XCTAssertEqual(ApiConfig(tenant: " Nationwide ").tenantRoot.absoluteString, "https://api-new.easyesuite.com/clients/nationwide/api/v1/")
        let url = client.url("purchase_orders/purchase_orders/", query: ["status": ["Open", "Partial Received"], "is_available": true, "search": nil, "limit": 25])
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)!.queryItems!
        XCTAssertEqual(items.filter { $0.name == "status" }.map(\.value), ["Open", "Partial Received"])
        XCTAssertEqual(items.first { $0.name == "is_available" }?.value, "true")
        XCTAssertNil(items.first { $0.name == "search" })
        // URL.path strips the trailing slash; DRF needs it, so assert on the real string.
        XCTAssertTrue(url.absoluteString.hasPrefix("https://api-new.easyesuite.com/clients/demo/api/v1/purchase_orders/purchase_orders/?"))
    }

    func testGetSendsBearerAndDecodesPage() async throws {
        let body = try fixture("sales_orders_list")
        MockURLProtocol.handler = { _ in (200, body) }
        let page: Page<SalesOrderSummary> = try await client.get("sales_orders/sales_orders/", query: ["limit": 1])
        XCTAssertEqual(page.count, 226956)
        let req = try XCTUnwrap(MockURLProtocol.requests.first)
        XCTAssertEqual(req.value(forHTTPHeaderField: "Authorization"), "Bearer old-access")
        XCTAssertEqual(req.url?.absoluteString, "https://api-new.easyesuite.com/clients/demo/api/v1/sales_orders/sales_orders/?limit=1")
    }

    func testRefreshOn401ThenRetry() async throws {
        let warehouses = try fixture("warehouses")
        var calls = 0
        MockURLProtocol.handler = { req in
            calls += 1
            if req.url!.absoluteString.hasSuffix("/auth/token/refresh/") { return (200, Data(#"{"access":"new-access"}"#.utf8)) }
            if req.value(forHTTPHeaderField: "Authorization") == "Bearer old-access" { return (401, Data(#"{"detail":"expired"}"#.utf8)) }
            return (200, warehouses)
        }
        let page: Page<Warehouse> = try await client.get("items/warehouses/")
        XCTAssertEqual(page.results.count, 3)
        XCTAssertEqual(calls, 3)
        let refresh = try XCTUnwrap(MockURLProtocol.requests.first { $0.url!.absoluteString.hasSuffix("/auth/token/refresh/") })
        XCTAssertNil(refresh.value(forHTTPHeaderField: "Authorization"))
        XCTAssertEqual(String(data: refresh.bodyData ?? Data(), encoding: .utf8), #"{"refresh":"refresh-1"}"#)
        XCTAssertEqual(store.load()?.access, "new-access")
        XCTAssertEqual(store.load()?.refresh, "refresh-1")
    }

    func testFailedRefreshClearsSession() async throws {
        let expired = expectation(description: "expired callback")
        client.onSessionExpired = { expired.fulfill() }
        MockURLProtocol.handler = { _ in (401, Data(#"{"detail":"nope"}"#.utf8)) }
        do {
            let _: Page<SalesOrderSummary> = try await client.get("sales_orders/sales_orders/")
            XCTFail("expected unauthorized")
        } catch APIError.unauthorized {
            XCTAssertNil(store.load())
        }
        await fulfillment(of: [expired], timeout: 1)
    }

    func testValidationErrorsAreSummarised() async throws {
        MockURLProtocol.handler = { _ in (400, Data(#"{"name":["This field is required."],"upc_code":["Too long."]}"#.utf8)) }
        do {
            let _: ItemDetail = try await client.post("items/inventory_items/", body: CreateItemRequest(name: ""))
            XCTFail("expected http error")
        } catch let APIError.http(status, detail, _) {
            XCTAssertEqual(status, 400)
            XCTAssertEqual(detail, "name: This field is required.; upc_code: Too long.")
        }
    }

    func testLoginStoresSessionAndSecondFactorIsSurfaced() async throws {
        store.clear()
        let me = try fixture("users_me")
        MockURLProtocol.handler = { req in
            if req.url!.absoluteString.hasSuffix("/auth/login/") { return (200, Data(#"{"access":"a1","refresh":"r1","access_expiration":"2026-10-04T01:00:00Z"}"#.utf8)) }
            return (200, me)
        }
        let auth = AuthService(client: client)
        guard case .success(let session) = try await auth.login(email: "user@example.com", password: "pw") else { return XCTFail("expected success") }
        XCTAssertEqual(session.access, "a1")
        XCTAssertEqual(store.load()?.refresh, "r1")
        let loginReq = try XCTUnwrap(MockURLProtocol.requests.first)
        XCTAssertEqual(loginReq.url?.absoluteString, "https://api-new.easyesuite.com/clients/demo/api/v1/auth/login/")
        XCTAssertNil(loginReq.value(forHTTPHeaderField: "Authorization"))
        let profile = try await auth.me()
        XCTAssertEqual(profile.displayName, "Test Test")

        store.clear()
        MockURLProtocol.handler = { _ in (200, Data(#"{"mfa_required":true,"ephemeral_token":"eph-1"}"#.utf8)) }
        guard case .secondFactorRequired(let token, _) = try await auth.login(email: "user@example.com", password: "pw") else { return XCTFail("expected 2fa") }
        XCTAssertEqual(token, "eph-1")
        XCTAssertNil(store.load())
    }
}
