package com.easyesuite.core

import com.easyesuite.core.auth.AuthService
import com.easyesuite.core.auth.InMemoryTokenStore
import com.easyesuite.core.auth.Jwt
import com.easyesuite.core.auth.LoginResult
import com.easyesuite.core.auth.Session
import com.easyesuite.core.model.CreateItemRequest
import com.easyesuite.core.model.ItemDetail
import com.easyesuite.core.model.ItemImage
import com.easyesuite.core.model.Page
import com.easyesuite.core.model.SalesOrderSummary
import com.easyesuite.core.net.ApiClient
import com.easyesuite.core.net.ApiException
import com.easyesuite.core.net.DefaultJson
import com.easyesuite.core.repo.AssistantRepository
import com.easyesuite.core.util.DateRange
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryTokenStore
    private lateinit var client: ApiClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        store = InMemoryTokenStore(Session(tenant = "demo", access = "old-access", refresh = "refresh-1"))
        client = ApiClient(ApiConfig(tenant = "Demo", apiRoot = server.url("/").toString()), store)
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `tenant root is lower-cased and well formed`() {
        val cfg = ApiConfig(tenant = " Nationwide ")
        assertEquals("https://api-new.easyesuite.com/clients/nationwide/api/v1/", cfg.tenantRoot)
        assertEquals("https://api-new.easyesuite.com/api/v1/", cfg.globalRoot)
    }

    @Test fun `query encoding handles lists, booleans and nulls`() {
        val url = client.buildUrl("purchase_orders/purchase_orders/", mapOf(
            "status" to listOf("Open", "Partial Received"),
            "is_available" to true,
            "search" to null,
            "limit" to 25,
        ))
        assertEquals(listOf("Open", "Partial Received"), url.queryParameterValues("status"))
        assertEquals("true", url.queryParameter("is_available"))
        assertNull(url.queryParameter("search"))
        assertEquals("25", url.queryParameter("limit"))
        assertTrue(url.encodedPath.endsWith("/clients/demo/api/v1/purchase_orders/purchase_orders/"))
    }

    @Test fun `GET sends bearer token and decodes a page`() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.read("sales_orders_list.json")).setHeader("Content-Type", "application/json"))
        val page: Page<SalesOrderSummary> = client.get("sales_orders/sales_orders/", mapOf("limit" to 1))
        assertEquals(226956, page.count)
        val req = server.takeRequest()
        assertEquals("Bearer old-access", req.getHeader("Authorization"))
        assertEquals("/clients/demo/api/v1/sales_orders/sales_orders/?limit=1", req.path)
    }

    @Test fun `401 triggers refresh and retries once with the new token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"token expired"}"""))
        server.enqueue(MockResponse().setBody("""{"access":"new-access","access_expiration":"2026-10-04T00:00:00Z"}"""))
        server.enqueue(MockResponse().setBody(Fixtures.read("warehouses.json")))

        val page: Page<com.easyesuite.core.model.Warehouse> = client.get("items/warehouses/")
        assertEquals(3, page.results.size)

        val first = server.takeRequest(); assertEquals("Bearer old-access", first.getHeader("Authorization"))
        val refresh = server.takeRequest()
        assertEquals("/clients/demo/api/v1/auth/token/refresh/", refresh.path)
        assertEquals("""{"refresh":"refresh-1"}""", refresh.body.readUtf8())
        assertNull(refresh.getHeader("Authorization"))
        val retry = server.takeRequest(); assertEquals("Bearer new-access", retry.getHeader("Authorization"))
        assertEquals("new-access", store.load()!!.access)
        assertEquals("refresh-1", store.load()!!.refresh)   // refresh token kept when not rotated
    }

    @Test fun `failed refresh clears the session and reports Unauthorized`() = runTest {
        var expired = false
        client.onSessionExpired = { expired = true }
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"refresh invalid"}"""))
        try {
            client.get<Page<SalesOrderSummary>>("sales_orders/sales_orders/")
            fail("expected Unauthorized")
        } catch (e: ApiException.Unauthorized) {
            assertTrue(expired)
            assertNull(store.load())
        }
    }

    @Test fun `DRF validation errors are summarised`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"name":["This field is required."],"upc_code":["Ensure this field has no more than 14 characters."]}"""))
        try {
            client.post<CreateItemRequest, ItemDetail>("items/inventory_items/", CreateItemRequest(name = ""))
            fail("expected Http")
        } catch (e: ApiException.Http) {
            assertEquals(400, e.status)
            assertTrue(e.isValidation)
            assertEquals("name: This field is required.; upc_code: Ensure this field has no more than 14 characters.", e.detail)
        }
    }

    @Test fun `create item body only contains set fields plus ERP defaults`() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.read("item_detail.json")))
        val body = CreateItemRequest(
            name = "TCG-Destined-ETB", upcCode = "196214152045", purchasePrice = "140.00",
            images = listOf(ItemImage("https://example.com/a.png")),
        )
        client.post<CreateItemRequest, ItemDetail>("items/inventory_items/", body)
        val sent = DefaultJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("TCG-Destined-ETB", sent["name"]!!.jsonPrimitive.content)
        assertEquals("196214152045", sent["upc_code"]!!.jsonPrimitive.content)
        assertEquals("140.00", sent["purchase_price"]!!.jsonPrimitive.content)
        assertEquals("INV", sent["item_type"]!!.jsonPrimitive.content)
        assertEquals("AVG", sent["costing_method"]!!.jsonPrimitive.content)
        assertEquals("Pounds", sent["weight_unit"]!!.jsonPrimitive.content)
        assertFalse(sent.containsKey("description"))   // nulls omitted, never ""
        assertFalse(sent.containsKey("manufacturer"))
        // The backend requires all seven marketplace blocks and marketplace_pricing, even when unused.
        for (key in listOf("amazon_marketplace", "ebay_marketplace", "walmart_marketplace", "target_marketplace", "best_buy_marketplace", "macys_marketplace", "mercado_marketplace")) {
            assertTrue("missing $key", sent.containsKey(key))
        }
        assertEquals(false, sent["amazon_marketplace"]!!.jsonObject["amazon_fba_account_1"]!!.jsonPrimitive.boolean)
        assertEquals(0, sent["marketplace_pricing"]!!.jsonArray.size)
        assertEquals(0, sent["ebay_marketplace"]!!.jsonObject.size)
    }

    @Test fun `upc lookup uses a path segment and typed detail endpoints route by item type`() {
        assertEquals("items/get_item_upc/196214152045/", Endpoints.upcLookup(" 196214152045 "))
        assertEquals("items/inventory_items/6690/", Endpoints.itemDetail(6690, "INV"))
        assertEquals("items/kit_package_items/7/", Endpoints.itemDetail(7, "KIT"))
        assertEquals("items/variant_items/8/history/", Endpoints.itemHistory(8, "VAR"))
        assertEquals("items/inventory_items/9/", Endpoints.itemDetail(9, null))
    }

    @Test fun `expired access tokens are refreshed before the request and both tokens rotate`() = runTest {
        store.save(Session(tenant = "demo", access = "stale", refresh = "refresh-1", accessExpiration = "2020-01-01T00:00:00Z"))
        server.enqueue(MockResponse().setBody("""{"access":"fresh","refresh":"refresh-2","access_expiration":"2099-01-01T00:00:00Z"}"""))
        server.enqueue(MockResponse().setBody(Fixtures.read("warehouses.json")))
        val page: Page<com.easyesuite.core.model.Warehouse> = client.get("items/warehouses/")
        assertEquals(3, page.results.size)
        assertEquals("/clients/demo/api/v1/auth/token/refresh/", server.takeRequest().path)
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
        assertEquals("fresh", store.load()!!.access)
        assertEquals("refresh-2", store.load()!!.refresh)   // each refresh token works once: the new one must be kept
    }

    @Test fun `older sessions still decode`() {
        val s = DefaultJson.decodeFromString(Session.serializer(), """{"tenant":"demo","access":"a","refresh":"r","email":"x@y.z","provider":"backend"}""")
        assertNull(s.firebaseTenantId)
        assertTrue(s.roles.isEmpty())
        assertFalse(s.hasFirestore)
        assertFalse(s.isAccessExpiring())
    }

    /** A JWT whose payload carries `exp` (no signature check; only the claim is read). */
    private fun jwt(exp: Long): String {
        val b64 = { bytes: ByteArray -> java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) }
        return b64("""{"alg":"HS256"}""".toByteArray()) + "." + b64("""{"exp":$exp,"token_type":"access"}""".toByteArray()) + ".sig"
    }

    @Test fun `access expiry falls back to the JWT exp claim`() {
        val token = jwt(4070908800)   // 2099-01-01T00:00:00Z
        assertEquals(java.time.Instant.ofEpochSecond(4070908800), Jwt.expiration(token))
        assertEquals("2099-01-01T00:00:00Z", Jwt.expirationIso(token))
        assertNull(Jwt.expiration("not-a-jwt"))
    }

    private fun globalClient() = ApiClient(ApiConfig(tenant = "", apiRoot = server.url("/").toString()), store)
    private fun tenantClient(tenant: String, firebase: FirebaseConfig? = null) =
        ApiClient(ApiConfig(tenant = tenant, apiRoot = server.url("/").toString(), firebase = firebase), store)
    private fun firebaseOnMock() = FirebaseConfig(
        apiKey = "key-1", projectId = "proj", connectorDatabase = "connector-db", notificationsDatabase = "notifications-db",
        identityToolkitBase = server.url("/").toString(), secureTokenBase = server.url("/").toString(), firestoreBase = server.url("/").toString(),
    )

    /** Step 1 — `POST api/v1/auth/login/tenants/` lists the workspaces and never returns a token. */
    @Test fun `workspaces are listed from the global root without a token`() = runTest {
        store.clear()
        server.enqueue(MockResponse().setBody("""[{"tenant_id":42,"schema_name":"nationwide","display_name":"Nationwide","company_name":"Nationwide","logo_url":null,"initials":"NW","last_active_at":"2026-10-08T14:03:11Z"},{"tenant_id":7,"schema_name":"ama","display_name":"AMA Investment Group LLC"}]"""))
        val list = AuthService(globalClient()).workspaces(" am@example.com ", "pw")
        assertEquals(listOf("nationwide", "ama"), list.map { it.schemaName })
        assertEquals("Nationwide", list[0].name); assertEquals("NW", list[0].monogram); assertEquals(42, list[0].tenantId)
        assertEquals("AM", list[1].monogram)
        val req = server.takeRequest()
        assertEquals("/api/v1/auth/login/tenants/", req.path)
        assertNull(req.getHeader("Authorization"))
        assertEquals("""{"email":"am@example.com","password":"pw"}""", req.body.readUtf8())
        assertNull(store.load())
    }

    @Test fun `wrong password and rate limit become friendly errors`() = runTest {
        store.clear()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"errors":{"detail":"Unable to log in with provided credentials."}}"""))
        try { AuthService(globalClient()).workspaces("a@b.c", "x"); fail("expected 401") }
        catch (e: ApiException.Http) { assertEquals(401, e.status); assertEquals("Email or password is incorrect.", e.detail) }
        server.enqueue(MockResponse().setResponseCode(429))
        try { AuthService(globalClient()).workspaces("a@b.c", "x"); fail("expected 429") }
        catch (e: ApiException.Http) { assertEquals(429, e.status); assertTrue(e.detail.contains("Too many")) }
    }

    /** Step 2 + 3 — tenant login stores the JWTs (expiry from the JWT when the body omits it) and exchanges the custom token. */
    @Test fun `tenant login stores tokens and signs in to Firebase with the custom token`() = runTest {
        store.clear()
        val access = jwt(4070908800)
        server.enqueue(MockResponse().setBody("""{"access":"$access","refresh":"r1","firebase_custom_token":"custom-1","user":{"pk":7,"email":"am@example.com","tenant_id":42,"firebase_tenant_id":"nationwide-x1y2z","is_mfa_enabled":false,"is_mfa_mandatory":false,"needs_password_reset":false,"all_role_names":["ADMIN"],"plan_name":"Pro"}}"""))
        server.enqueue(MockResponse().setBody("""{"idToken":"fb-id","refreshToken":"fb-refresh","expiresIn":"3600"}"""))
        val auth = AuthService(tenantClient("Nationwide", firebaseOnMock()))
        val result = auth.login("am@example.com", "pw")
        assertTrue(result is LoginResult.Success)
        val session = (result as LoginResult.Success).session
        assertEquals("nationwide", session.tenant)
        assertEquals(42, session.tenantId); assertEquals(7, session.userPk); assertEquals(listOf("ADMIN"), session.roles); assertTrue(session.isAdmin)
        assertEquals("2099-01-01T00:00:00Z", session.accessExpiration)   // from the JWT exp claim
        assertEquals("nationwide-x1y2z", session.firebaseTenantId)
        assertEquals("fb-id", session.firebase?.idToken)
        assertTrue(session.hasFirestore)
        assertEquals(session, store.load())
        assertTrue(auth.isSignedIn)
        assertEquals("/clients/nationwide/api/v1/auth/login/", server.takeRequest().path)
        val fbReq = server.takeRequest()
        assertEquals("/v1/accounts:signInWithCustomToken?key=key-1", fbReq.path)
        val body = DefaultJson.parseToJsonElement(fbReq.body.readUtf8()).jsonObject
        assertEquals("custom-1", body["token"]!!.jsonPrimitive.content)
        assertEquals("nationwide-x1y2z", body["tenantId"]!!.jsonPrimitive.content)
        assertTrue(body["returnSecureToken"]!!.jsonPrimitive.boolean)
        assertEquals("fb-id", auth.firebaseIdToken())
    }

    @Test fun `firebase failure is tolerated and no firebase tenant skips step 3`() = runTest {
        store.clear()
        server.enqueue(MockResponse().setBody("""{"access":"a1","refresh":"r1","firebase_custom_token":"custom-1","user":{"pk":1,"tenant_id":42,"firebase_tenant_id":"t-1"}}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"INVALID_CUSTOM_TOKEN"}}"""))
        val s1 = (AuthService(tenantClient("demo", firebaseOnMock())).login("a@b.c", "pw") as LoginResult.Success).session
        assertNull(s1.firebase); assertEquals("a1", s1.access); assertFalse(s1.hasFirestore)
        server.takeRequest(); server.takeRequest()

        store.clear()
        server.enqueue(MockResponse().setBody("""{"access":"a2","refresh":"r2","user":{"pk":1,"tenant_id":42,"firebase_tenant_id":null}}"""))
        val s2 = (AuthService(tenantClient("demo", firebaseOnMock())).login("a@b.c", "pw") as LoginResult.Success).session
        assertNull(s2.firebase)
        assertEquals(1, server.requestCount - 2)   // no Firebase call without a tenant
    }

    @Test fun `password reset and MFA block the session`() = runTest {
        store.clear()
        server.enqueue(MockResponse().setBody("""{"access":"a1","refresh":"r1","user":{"pk":1,"email":"a@b.c","needs_password_reset":true}}"""))
        val reset = AuthService(tenantClient("demo")).login("a@b.c", "pw")
        assertTrue(reset is LoginResult.PasswordResetRequired)
        assertEquals("a@b.c", (reset as LoginResult.PasswordResetRequired).email)
        assertNull(store.load())

        server.enqueue(MockResponse().setBody("""{"access":"a1","refresh":"r1","user":{"pk":1,"is_mfa_enabled":true}}"""))
        val mfa = AuthService(tenantClient("demo")).login("a@b.c", "pw")
        assertTrue(mfa is LoginResult.SecondFactorRequired)
        assertEquals("a1", (mfa as LoginResult.SecondFactorRequired).pending?.access)
        assertNull(store.load())   // not stored until the code is verified

        server.enqueue(MockResponse().setBody("""{"mfa_required":true,"ephemeral_token":"eph-1","detail":"Enter the code from your authenticator"}"""))
        val challenge = AuthService(tenantClient("demo")).login("a@b.c", "pw") as LoginResult.SecondFactorRequired
        assertNull(challenge.pending); assertEquals("eph-1", challenge.challengeToken)
    }

    @Test fun `login needs a workspace`() = runTest {
        try { AuthService(globalClient()).login("a@b.c", "pw"); fail("expected an error") }
        catch (e: ApiException.Decoding) { assertTrue(e.message!!.contains("workspace")) }
    }

    @Test fun `assistant replies are parsed leniently`() {
        val a = AssistantRepository.parse(DefaultJson.parseToJsonElement("""{"answer":"You have 12 open orders.","thread_id":"t-9","suggestions":["Show them","Ship them"]}"""), null)
        assertEquals("You have 12 open orders.", a.text)
        assertEquals("t-9", a.threadId)
        assertEquals(2, a.suggestions.size)
        val b = AssistantRepository.parse(DefaultJson.parseToJsonElement("""{"data":{"response":"hi"}}"""), "t-1")
        assertEquals("hi", b.text)
        assertEquals("t-1", b.threadId)
    }

    @Test fun `date ranges produce inclusive UTC bounds in the user's zone`() {
        val la = ZoneId.of("America/Los_Angeles")
        val q = DateRange(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 3), "day").toQuery(la)
        assertEquals("2026-10-03T07:00:00Z", q["date_after"])
        assertEquals("2026-10-04T06:59:59Z", q["date_before"])
    }
}
