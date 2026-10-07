package com.easyesuite.core.repo

import com.easyesuite.core.Endpoints
import com.easyesuite.core.model.BuyLabelRequest
import com.easyesuite.core.model.FulfillRequest
import com.easyesuite.core.model.HoldRequest
import com.easyesuite.core.model.Invoice
import com.easyesuite.core.model.Page
import com.easyesuite.core.model.Payment
import com.easyesuite.core.model.PageQuery
import com.easyesuite.core.model.SalesOrderDetail
import com.easyesuite.core.model.SalesOrderSummary
import com.easyesuite.core.model.Shipment
import com.easyesuite.core.model.TrackingDetails
import com.easyesuite.core.model.UpdateOrderRequest
import com.easyesuite.core.model.VerifyScanRequest
import com.easyesuite.core.net.ApiClient
import com.easyesuite.core.util.DateRange
import kotlinx.serialization.json.JsonElement

class OrdersRepository(private val client: ApiClient) {

    suspend fun orders(
        status: String? = null,
        marketplaceId: Int? = null,
        search: String? = null,
        range: DateRange? = null,
        page: PageQuery = PageQuery(),
    ): Page<SalesOrderSummary> = client.get(
        Endpoints.SALES_ORDERS,
        page.asMap() + mapOf(
            "status" to status,
            "marketplace" to marketplaceId,
            "search" to search?.takeIf { it.isNotBlank() },
            "ordering" to "-date",
        ) + (range?.toQuery() ?: emptyMap()),
    )

    /** Cheap count: list with limit=1 and read `count`. */
    suspend fun count(status: String? = null, marketplaceId: Int? = null, range: DateRange? = null): Int {
        val p: Page<SalesOrderSummary> = client.get(
            Endpoints.SALES_ORDERS,
            mapOf("limit" to 1, "status" to status, "marketplace" to marketplaceId) + (range?.toQuery() ?: emptyMap()),
        )
        return p.count
    }

    suspend fun order(id: Long): SalesOrderDetail = client.get("${Endpoints.SALES_ORDERS}$id/")

    // ---- Sales finance (VERIFIED): invoices and customer payments -------------------------------

    /** `sales_orders/invoices/` — filters: status (Open|Paid|Partial Paid|Voided), marketplace, sales_order, search (IN-…), date_after/before. */
    suspend fun invoices(
        status: String? = null,
        search: String? = null,
        salesOrderId: Long? = null,
        range: DateRange? = null,
        page: PageQuery = PageQuery(),
    ): Page<Invoice> = client.get(
        Endpoints.INVOICES,
        page.asMap() + mapOf(
            "status" to status,
            "search" to search?.takeIf { it.isNotBlank() },
            "sales_order" to salesOrderId,
            "ordering" to "-date",
        ) + (range?.toQuery() ?: emptyMap()),
    )

    suspend fun invoice(id: Long): Invoice = client.get("${Endpoints.INVOICES}$id/")

    /** `sales_orders/payments/` — customer payments, newest first. `search` is ASSUMED (payment number / ref). */
    suspend fun payments(search: String? = null, range: DateRange? = null, page: PageQuery = PageQuery()): Page<Payment> = client.get(
        Endpoints.PAYMENTS,
        page.asMap() + mapOf("search" to search?.takeIf { it.isNotBlank() }, "ordering" to "-date") + (range?.toQuery() ?: emptyMap()),
    )

    suspend fun payment(id: Long): Payment = client.get("${Endpoints.PAYMENTS}$id/")

    suspend fun findByNumber(number: String): SalesOrderSummary? {
        val p: Page<SalesOrderSummary> = client.get(Endpoints.SALES_ORDERS, mapOf("number" to number.trim(), "limit" to 1))
        return p.results.firstOrNull()
    }

    suspend fun update(id: Long, request: UpdateOrderRequest): SalesOrderDetail =
        client.patch("${Endpoints.SALES_ORDERS}$id/", request)

    suspend fun fulfill(request: FulfillRequest): JsonElement = client.post(Endpoints.FULFILLMENTS, request)
}

class ShippingRepository(private val client: ApiClient) {

    suspend fun shipments(status: String? = null, search: String? = null, page: PageQuery = PageQuery()): Page<Shipment> =
        client.get(Endpoints.SHIPMENTS, page.asMap() + mapOf("status" to status, "search" to search?.takeIf { it.isNotBlank() }))

    suspend fun count(status: String): Int {
        val p: Page<Shipment> = client.get(Endpoints.SHIPMENTS, mapOf("status" to status, "limit" to 1))
        return p.count
    }

    suspend fun shipment(id: String): Shipment = client.get("${Endpoints.SHIPMENTS}$id/")

    suspend fun rerate(id: String): Shipment = client.postEmpty("${Endpoints.SHIPMENTS}$id/rerate/")

    suspend fun buyLabel(id: String, rateId: String?): Shipment =
        client.post("${Endpoints.SHIPMENTS}$id/buy/", BuyLabelRequest(rateId))

    suspend fun label(id: String): JsonElement = client.get("${Endpoints.SHIPMENTS}$id/label/")

    suspend fun tracking(id: String): TrackingDetails = client.get("${Endpoints.SHIPMENTS}$id/tracking_details/")

    suspend fun hold(id: String, reason: String?): Shipment = client.post("${Endpoints.SHIPMENTS}$id/hold/", HoldRequest(reason))

    suspend fun unhold(id: String): Shipment = client.postEmpty("${Endpoints.SHIPMENTS}$id/unhold/")

    /** Scanning a label / packing slip barcode in the warehouse resolves to the shipment. */
    suspend fun resolveBarcode(code: String): Shipment = client.get(Endpoints.SHIPMENT_RESOLVE_BARCODE, mapOf("barcode" to code.trim()))

    suspend fun verifyScan(id: String, upc: String): JsonElement =
        client.post("${Endpoints.SHIPMENTS}$id/verify_scan/", VerifyScanRequest(upc.trim()))

    suspend fun packVerification(id: String): JsonElement = client.get("${Endpoints.SHIPMENTS}$id/pack_verification/")

    suspend fun balance(): JsonElement = client.get(Endpoints.SHIPPING_BALANCE)
}
