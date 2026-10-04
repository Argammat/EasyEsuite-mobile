package com.easyesuite.core.repo

import com.easyesuite.core.Endpoints
import com.easyesuite.core.model.Adjustment
import com.easyesuite.core.model.CreateAdjustmentRequest
import com.easyesuite.core.model.CreateTransferRequest
import com.easyesuite.core.model.Page
import com.easyesuite.core.model.PageQuery
import com.easyesuite.core.model.PurchaseOrder
import com.easyesuite.core.model.PurchaseOrderLine
import com.easyesuite.core.model.PurchaseOrderStatus
import com.easyesuite.core.model.ReceiveRequest
import com.easyesuite.core.model.Transfer
import com.easyesuite.core.net.ApiClient
import kotlinx.serialization.json.JsonElement

/** Transfers, adjustments and receiving. */
class InventoryRepository(private val client: ApiClient) {

    // ---- transfers ----------------------------------------------------------------------------

    suspend fun transfers(status: String? = null, page: PageQuery = PageQuery()): Page<Transfer> =
        client.get(Endpoints.TRANSFERS, page.asMap() + mapOf("transfer_status" to status, "ordering" to "-date"))

    suspend fun transfer(id: Long): Transfer = client.get("${Endpoints.TRANSFERS}$id/")

    suspend fun createTransfer(request: CreateTransferRequest): Transfer = client.post(Endpoints.TRANSFERS, request)

    suspend fun completeTransfer(id: Long): JsonElement = client.postEmpty("${Endpoints.TRANSFERS}$id/complete/")

    // ---- adjustments --------------------------------------------------------------------------

    suspend fun adjustments(page: PageQuery = PageQuery()): Page<Adjustment> =
        client.get(Endpoints.ADJUSTMENTS, page.asMap() + mapOf("ordering" to "-date"))

    suspend fun createAdjustment(request: CreateAdjustmentRequest): Adjustment = client.post(Endpoints.ADJUSTMENTS, request)

    // ---- receiving ----------------------------------------------------------------------------

    suspend fun purchaseOrders(
        statuses: List<String> = PurchaseOrderStatus.receivable,
        search: String? = null,
        page: PageQuery = PageQuery(),
    ): Page<PurchaseOrder> = client.get(
        Endpoints.PURCHASE_ORDERS,
        page.asMap() + mapOf("status" to statuses, "search" to search?.takeIf { it.isNotBlank() }, "ordering" to "-date"),
    )

    suspend fun purchaseOrder(id: Long): PurchaseOrder = client.get("${Endpoints.PURCHASE_ORDERS}$id/")

    suspend fun purchaseOrderLines(poId: Long): List<PurchaseOrderLine> {
        val page: Page<PurchaseOrderLine> = client.get(Endpoints.PURCHASE_ORDER_ITEMS, mapOf("purchase_order" to poId, "limit" to 200))
        return page.results
    }

    suspend fun receive(request: ReceiveRequest): JsonElement = client.post(Endpoints.RECEIPTS, request)
}
