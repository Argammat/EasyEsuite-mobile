package com.easyesuite.core

import com.easyesuite.core.auth.AuthService
import com.easyesuite.core.auth.InMemoryTokenStore
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

    @Test fun `expired access tokens are refreshed before the request`() = runTest {
        store.save(Session(tenant = "demo", access = "stale", refresh = "refresh-1", accessExpiration = "2020-01-01T00:00:00Z"))
        server.enqueue(MockResponse().setBody("""{"access":"fresh","access_expiration":"2099-01-01T00:00:00Z"}"""))
        server.enqueue(MockResponse().setBody(Fixtures.read("warehouses.json")))
        val page: Page<com.easyesuite.core.model.Warehouse> = client.get("items/warehouses/")
        assertEquals(3, page.results.size)
        assertEquals("/clients/demo/api/v1/auth/token/refresh/", server.takeRequest().path)
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
        assertEquals("fresh", store.load()!!.access)
    }

    @Test fun `login stores a session and me() decodes`() = runTest {
        store.clear()
        server.enqueue(MockResponse().setBody("""{"access":"a1","refresh":"r1","access_expiration":"2099-01-01T00:00:00Z","refresh_expiration":"2099-01-08T00:00:00Z","user":{"pk":1}}"""))
        server.enqueue(MockResponse().setBody(Fixtures.read("users_me.json")))
        val auth = AuthService(client)
        val result = auth.login("user@example.com", "pw")
        assertTrue(result is LoginResult.Success)
        assertEquals("a1", store.load()!!.access)
        assertEquals("r1", store.load()!!.refresh)
        val loginReq = server.takeRequest()
        assertEquals("/clients/demo/api/v1/auth/login/", loginReq.path)
        assertNull(loginReq.getHeader("Authorization"))
        assertEquals("""{"email":"user@example.com","password":"pw"}""", loginReq.body.readUtf8())
        assertEquals("Test Test", auth.me().displayName)
    }

    @Test fun `email-password login without a tenant leads to the workspace picker`() = runTest {
        store.clear()
        val global = ApiClient(ApiConfig(tenant = "", apiRoot = server.url("/").toString()), store)
        server.enqueue(MockResponse().setBody("""{"access":"a1","refresh":"r1","access_expiration":"2099-01-01T00:00:00Z"}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"slug":"nationwide","display_name":"Nationwide"},{"slug":"ama","display_name":"AMA invesment Group LLC"}]}"""))
        val auth = AuthService(global)
        val result = auth.login("am@example.com", "pw")
        assertEquals("/api/v1/auth/login/", server.takeRequest().path)          // global root — no tenant yet
        val tenants = server.takeRequest()
        assertEquals("/api/v1/users/me/tenants/", tenants.path)
        assertEquals("Bearer a1", tenants.getHeader("Authorization"))             // pending tokens authenticate the list
        assertTrue(result is LoginResult.WorkspaceRequired)
        val choose = result as LoginResult.WorkspaceRequired
        assertEquals(listOf("nationwide", "ama"), choose.workspaces.map { it.slug })
        assertTrue(store.load()!!.isPending)
        assertFalse(auth.isSignedIn)

        val session = auth.selectWorkspace(choose.pending, "Nationwide")
        assertEquals("nationwide", session.tenant)
        assertEquals("nationwide", store.load()!!.tenant)
        assertTrue(auth.isSignedIn)
    }

    @Test fun `a single workspace signs straight in`() = runTest {
        store.clear()
        val global = ApiClient(ApiConfig(tenant = "", apiRoot = server.url("/").toString()), store)
        server.enqueue(MockResponse().setBody("""{"access":"a1","refresh":"r1","access_expiration":"2099-01-01T00:00:00Z"}"""))
        server.enqueue(MockResponse().setBody("""[{"slug":"nationwide","name":"Nationwide"}]"""))
        val result = AuthService(global).login("am@example.com", "pw")
        assertTrue(result is LoginResult.Success)
        assertEquals("nationwide", (result as LoginResult.Success).session.tenant)
    }

    @Test fun `login surfaces a second-factor challenge`() = runTest {
        store.clear()
        server.enqueue(MockResponse().setBody("""{"mfa_required":true,"ephemeral_token":"eph-1","detail":"Enter the code from your authenticator"}"""))
        val result = AuthService(client).login("user@example.com", "pw")
        assertTrue(result is LoginResult.SecondFactorRequired)
        assertEquals("eph-1", (result as LoginResult.SecondFactorRequired).challengeToken)
        assertNull(store.load())
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
