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

    func testCreateItemBodyCarriesRequiredMarketplaceBlocks() async throws {
        let detail = try fixture("item_detail")
        MockURLProtocol.handler = { _ in (200, detail) }
        var body = CreateItemRequest(name: "TCG-Destined-ETB")
        body.upcCode = "196214152045"
        body.purchasePrice = "140.00"
        body.itemImages = [ItemImage(imageUrl: "https://example.com/a.png")]
        let _: ItemDetail = try await client.post("items/inventory_items/", body: body)
        let req = try XCTUnwrap(MockURLProtocol.requests.first)
        let bodyData = try XCTUnwrap(req.bodyData)
        let sent = try XCTUnwrap(JSONSerialization.jsonObject(with: bodyData) as? [String: Any])
        XCTAssertEqual(sent["name"] as? String, "TCG-Destined-ETB")
        XCTAssertEqual(sent["upc_code"] as? String, "196214152045")
        XCTAssertEqual(sent["purchase_price"] as? String, "140.00")
        XCTAssertEqual(sent["item_type"] as? String, "INV")
        XCTAssertEqual(sent["weight_unit"] as? String, "Pounds")
        XCTAssertNil(sent["description"])   // nulls omitted, never ""
        // The backend requires all seven marketplace blocks and marketplace_pricing, even when unused.
        for key in ["amazon_marketplace", "ebay_marketplace", "walmart_marketplace", "target_marketplace", "best_buy_marketplace", "macys_marketplace", "mercado_marketplace"] {
            XCTAssertNotNil(sent[key] as? [String: Any], "missing \(key)")
        }
        XCTAssertEqual((sent["amazon_marketplace"] as? [String: Any])?["amazon_fba_account_1"] as? Bool, false)
        XCTAssertEqual((sent["ebay_marketplace"] as? [String: Any])?.count, 0)
        XCTAssertEqual((sent["marketplace_pricing"] as? [Any])?.count, 0)
        XCTAssertEqual(((sent["item_images"] as? [[String: Any]])?.first?["image_url"]) as? String, "https://example.com/a.png")
    }

    func testUpcLookupIsAPathSegmentAndDetailRoutesByType() {
        XCTAssertEqual(Endpoints.upcLookup(" 196214152045 "), "items/get_item_upc/196214152045/")
        XCTAssertEqual(Endpoints.itemDetail(6690, type: "INV"), "items/inventory_items/6690/")
        XCTAssertEqual(Endpoints.itemDetail(7, type: "KIT"), "items/kit_package_items/7/")
        XCTAssertEqual(Endpoints.itemHistory(8, type: "VAR"), "items/variant_items/8/history/")
        XCTAssertEqual(Endpoints.itemDetail(9, type: nil), "items/inventory_items/9/")
        XCTAssertNil(ItemTypes.badge("INV"))
        XCTAssertEqual(ItemTypes.badge("kit_package"), "Kit")
    }

    func testExpiringAccessTokenIsRefreshedBeforeTheRequestAndBothTokensRotate() async throws {
        store.save(Session(tenant: "demo", access: "stale", refresh: "refresh-1", accessExpiration: "2020-01-01T00:00:00Z"))
        let warehouses = try fixture("warehouses")
        MockURLProtocol.handler = { req in
            if req.url!.absoluteString.hasSuffix("/auth/token/refresh/") { return (200, Data(#"{"access":"fresh","refresh":"refresh-2","access_expiration":"2099-01-01T00:00:00Z"}"#.utf8)) }
            return (200, warehouses)
        }
        let page: Page<Warehouse> = try await client.get("items/warehouses/")
        XCTAssertEqual(page.results.count, 3)
        XCTAssertEqual(MockURLProtocol.requests.count, 2)
        XCTAssertTrue(MockURLProtocol.requests[0].url!.absoluteString.hasSuffix("/clients/demo/api/v1/auth/token/refresh/"))
        XCTAssertEqual(MockURLProtocol.requests[1].value(forHTTPHeaderField: "Authorization"), "Bearer fresh")
        XCTAssertEqual(store.load()?.access, "fresh")
        XCTAssertEqual(store.load()?.refresh, "refresh-2")   // each refresh token works once: the new one must be kept
    }

    func testOlderSessionsStillDecode() throws {
        let legacy = Data(#"{"tenant":"demo","access":"a","refresh":"r","email":"x@y.z","provider":"backend"}"#.utf8)
        let s = try JSONDecoder().decode(Session.self, from: legacy)
        XCTAssertNil(s.firebaseTenantId)
        XCTAssertEqual(s.roles, [])
        XCTAssertFalse(s.hasFirestore)
        XCTAssertFalse(s.isAccessExpiring())   // unknown expiry → not expiring
        XCTAssertTrue(Session(tenant: "d", access: "a", accessExpiration: "2020-01-01T00:00:00Z").isAccessExpiring())
    }

    /// A JWT whose payload says `exp` = 2099-01-01 (no signature check; only the claim is read).
    private static func jwt(exp: TimeInterval) -> String {
        let payload = try! JSONSerialization.data(withJSONObject: ["exp": exp, "token_type": "access"])
        let b64 = { (d: Data) in d.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") }
        return "\(b64(Data("{\"alg\":\"HS256\"}".utf8))).\(b64(payload)).sig"
    }

    func testAccessExpiryFallsBackToTheJWTExpClaim() throws {
        let token = Self.jwt(exp: 4070908800)   // 2099-01-01T00:00:00Z
        XCTAssertEqual(JWT.expiration(token), Date(timeIntervalSince1970: 4070908800))
        XCTAssertEqual(JWT.expirationIso(token), "2099-01-01T00:00:00Z")
        XCTAssertNil(JWT.expiration("not-a-jwt"))
    }

    private func globalClient() -> APIClient {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [MockURLProtocol.self]
        return APIClient(config: ApiConfig(tenant: ""), tokenStore: store, urlSession: URLSession(configuration: cfg))
    }

    private func tenantClient(_ tenant: String, firebase: FirebaseConfig? = nil) -> APIClient {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [MockURLProtocol.self]
        return APIClient(config: ApiConfig(tenant: tenant, firebase: firebase), tokenStore: store, urlSession: URLSession(configuration: cfg))
    }

    /// Step 1 — `POST api/v1/auth/login/tenants/` lists the workspaces and never returns a token.
    func testWorkspacesAreListedFromTheGlobalRootWithoutAToken() async throws {
        store.clear()
        MockURLProtocol.handler = { req in
            guard req.url!.absoluteString == "https://api-new.easyesuite.com/api/v1/auth/login/tenants/" else { return (404, Data()) }
            return (200, Data(#"[{"tenant_id":42,"schema_name":"nationwide","display_name":"Nationwide","company_name":"Nationwide","logo_url":null,"initials":"NW","last_active_at":"2026-10-08T14:03:11Z"},{"tenant_id":7,"schema_name":"ama","display_name":"AMA Investment Group LLC"}]"#.utf8))
        }
        let list = try await AuthService(client: globalClient()).workspaces(email: " am@example.com ", password: "pw")
        XCTAssertEqual(list.map(\.schemaName), ["nationwide", "ama"])
        XCTAssertEqual(list[0].name, "Nationwide"); XCTAssertEqual(list[0].monogram, "NW"); XCTAssertEqual(list[0].tenantId, 42)
        XCTAssertEqual(list[1].monogram, "AM")
        let req = try XCTUnwrap(MockURLProtocol.requests.first)
        XCTAssertNil(req.value(forHTTPHeaderField: "Authorization"))
        let body = try XCTUnwrap(req.bodyData.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] })
        XCTAssertEqual(body["email"] as? String, "am@example.com")
        XCTAssertEqual(body["password"] as? String, "pw")
        XCTAssertNil(store.load())
    }

    func testWrongPasswordAndRateLimitBecomeFriendlyErrors() async {
        store.clear()
        MockURLProtocol.handler = { _ in (401, Data(#"{"errors":{"detail":"Unable to log in with provided credentials."}}"#.utf8)) }
        do { _ = try await AuthService(client: globalClient()).workspaces(email: "a@b.c", password: "x"); XCTFail("expected 401") }
        catch APIError.http(let status, let detail, _) { XCTAssertEqual(status, 401); XCTAssertEqual(detail, "Email or password is incorrect.") }
        catch { XCTFail("unexpected \(error)") }
        MockURLProtocol.handler = { _ in (429, Data()) }
        do { _ = try await AuthService(client: globalClient()).workspaces(email: "a@b.c", password: "x"); XCTFail("expected 429") }
        catch APIError.http(let status, let detail, _) { XCTAssertEqual(status, 429); XCTAssertTrue(detail.contains("Too many")) }
        catch { XCTFail("unexpected \(error)") }
    }

    /// Step 2 + 3 — tenant login stores the JWTs (expiry from the JWT when the body omits it) and exchanges the
    /// custom token for Firebase tokens with the workspace's Identity Platform tenant.
    func testTenantLoginStoresTokensAndSignsInToFirebaseWithTheCustomToken() async throws {
        store.clear()
        let access = Self.jwt(exp: 4070908800)
        let firebase = FirebaseConfig(apiKey: "key-1", projectId: "proj", connectorDatabase: "connector-db", notificationsDatabase: "notifications-db")
        MockURLProtocol.handler = { req in
            let u = req.url!.absoluteString
            if u == "https://api-new.easyesuite.com/clients/nationwide/api/v1/auth/login/" {
                return (200, Data("""
                {"access":"\(access)","refresh":"r1","firebase_custom_token":"custom-1",
                 "user":{"pk":7,"email":"am@example.com","tenant_id":42,"firebase_tenant_id":"nationwide-x1y2z","is_mfa_enabled":false,"is_mfa_mandatory":false,"needs_password_reset":false,"all_role_names":["ADMIN"],"plan_name":"Pro"}}
                """.utf8))
            }
            if u.hasPrefix("https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=key-1") {
                return (200, Data(#"{"idToken":"fb-id","refreshToken":"fb-refresh","expiresIn":"3600"}"#.utf8))
            }
            return (404, Data())
        }
        let auth = AuthService(client: tenantClient("Nationwide", firebase: firebase))
        guard case .success(let session) = try await auth.login(email: "am@example.com", password: "pw") else { return XCTFail("expected success") }
        XCTAssertEqual(session.tenant, "nationwide")
        XCTAssertEqual(session.tenantId, 42); XCTAssertEqual(session.userPk, 7); XCTAssertEqual(session.roles, ["ADMIN"]); XCTAssertTrue(session.isAdmin)
        XCTAssertEqual(session.accessExpiration, "2099-01-01T00:00:00Z")   // from the JWT exp claim
        XCTAssertEqual(session.firebaseTenantId, "nationwide-x1y2z")
        XCTAssertEqual(session.firebase?.idToken, "fb-id")
        XCTAssertTrue(session.hasFirestore)
        XCTAssertEqual(store.load(), session)
        XCTAssertTrue(auth.isSignedIn)
        let fbReq = try XCTUnwrap(MockURLProtocol.requests.first { $0.url!.host == "identitytoolkit.googleapis.com" })
        let body = try XCTUnwrap(fbReq.bodyData.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] })
        XCTAssertEqual(body["token"] as? String, "custom-1")
        XCTAssertEqual(body["tenantId"] as? String, "nationwide-x1y2z")
        XCTAssertEqual(body["returnSecureToken"] as? Bool, true)
        let idToken = await auth.firebaseIdToken()
        XCTAssertEqual(idToken, "fb-id")
    }

    func testFirebaseFailureIsToleratedAndNoFirebaseTenantSkipsStep3() async throws {
        store.clear()
        let firebase = FirebaseConfig(apiKey: "key-1", projectId: "proj")
        MockURLProtocol.handler = { req in
            if req.url!.host == "identitytoolkit.googleapis.com" { return (400, Data(#"{"error":{"message":"INVALID_CUSTOM_TOKEN"}}"#.utf8)) }
            return (200, Data(#"{"access":"a1","refresh":"r1","firebase_custom_token":"custom-1","user":{"pk":1,"tenant_id":42,"firebase_tenant_id":"t-1"}}"#.utf8))
        }
        guard case .success(let s1) = try await AuthService(client: tenantClient("demo", firebase: firebase)).login(email: "a@b.c", password: "pw") else { return XCTFail() }
        XCTAssertNil(s1.firebase); XCTAssertEqual(s1.access, "a1"); XCTAssertFalse(s1.hasFirestore)

        store.clear(); MockURLProtocol.requests = []
        MockURLProtocol.handler = { _ in (200, Data(#"{"access":"a2","refresh":"r2","user":{"pk":1,"tenant_id":42,"firebase_tenant_id":null}}"#.utf8)) }
        guard case .success(let s2) = try await AuthService(client: tenantClient("demo", firebase: firebase)).login(email: "a@b.c", password: "pw") else { return XCTFail() }
        XCTAssertNil(s2.firebase)
        XCTAssertEqual(MockURLProtocol.requests.count, 1)   // no Firebase call without a tenant
    }

    func testPasswordResetAndMfaBlockTheSession() async throws {
        store.clear()
        MockURLProtocol.handler = { _ in (200, Data(#"{"access":"a1","refresh":"r1","user":{"pk":1,"email":"a@b.c","needs_password_reset":true}}"#.utf8)) }
        guard case .passwordResetRequired(let email) = try await AuthService(client: tenantClient("demo")).login(email: "a@b.c", password: "pw") else { return XCTFail("expected reset") }
        XCTAssertEqual(email, "a@b.c")
        XCTAssertNil(store.load())

        MockURLProtocol.handler = { _ in (200, Data(#"{"access":"a1","refresh":"r1","user":{"pk":1,"is_mfa_enabled":true}}"#.utf8)) }
        guard case .secondFactorRequired(let pending, _, _) = try await AuthService(client: tenantClient("demo")).login(email: "a@b.c", password: "pw") else { return XCTFail("expected 2fa") }
        XCTAssertEqual(pending?.access, "a1")
        XCTAssertNil(store.load())   // not stored until the code is verified

        MockURLProtocol.handler = { _ in (200, Data(#"{"mfa_required":true,"ephemeral_token":"eph-1"}"#.utf8)) }
        guard case .secondFactorRequired(let none, let token, _) = try await AuthService(client: tenantClient("demo")).login(email: "a@b.c", password: "pw") else { return XCTFail("expected 2fa") }
        XCTAssertNil(none); XCTAssertEqual(token, "eph-1")
    }

    func testLoginNeedsAWorkspace() async {
        do { _ = try await AuthService(client: globalClient()).login(email: "a@b.c", password: "pw"); XCTFail("expected an error") }
        catch APIError.decoding(let msg, _) { XCTAssertTrue(msg.contains("workspace")) }
        catch { XCTFail("unexpected \(error)") }
    }
}
