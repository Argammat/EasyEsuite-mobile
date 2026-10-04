package com.easyesuite.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object PurchaseOrderStatus {
    const val OPEN = "Open"
    const val PARTIAL_RECEIVED = "Partial Received"
    const val PARTIALLY_RECEIVED_PENDING_BILLING = "Partially Received/Pending Billing"
    const val RECEIVED_PENDING_BILLING = "Received/Pending Billing"
    const val BILLED = "Billed"
    const val VOIDED = "Voided"

    /** POs that still have something to receive. */
    val receivable = listOf(OPEN, PARTIAL_RECEIVED, PARTIALLY_RECEIVED_PENDING_BILLING)
}

/** Row of `purchase_orders/purchase_orders/`. */
@Serializable
data class PurchaseOrder(
    val id: Long,
    val number: String = "",
    val status: String = "",
    val company: String? = null,
    val vendor: String? = null,
    val warehouse: String? = null,
    val date: String? = null,
    @SerialName("expected_date") val expectedDate: String? = null,
    @SerialName("received_date") val receivedDate: String? = null,
    val memo: String? = null,
    @SerialName("invoice_number") val invoiceNumber: String? = null,
    @SerialName("total_amount_new") val totalAmount: Money? = null,
    @SerialName("received_amount_new") val receivedAmount: Money? = null,
    @SerialName("open_amount") val openAmount: Money? = null,
    @SerialName("total_quantity_ordered_new") val totalQuantityOrdered: Int? = null,
    @SerialName("receiving_closed") val receivingClosed: Boolean = false,
    val items: List<Long> = emptyList(),
    @SerialName("created_date") val createdDate: String? = null,
) {
    val canReceive: Boolean get() = !receivingClosed && status in PurchaseOrderStatus.receivable
}

/** Row of `purchase_orders/purchase_order_items/?purchase_order=`. Shape ASSUMED (lenient). */
@Serializable
data class PurchaseOrderLine(
    val id: Long,
    val item: Long? = null,
    val name: String? = null,
    @SerialName("item_name") val itemName: String? = null,
    @SerialName("upc_code") val upcCode: String? = null,
    @SerialName("quantity_ordered") val quantityOrdered: Int? = null,
    val quantity: Int? = null,
    @SerialName("quantity_received") val quantityReceived: Int? = null,
    @SerialName("received_quantity") val receivedQuantity: Int? = null,
    val cost: Money? = null,
    val rate: Money? = null,
    @SerialName("item_image1_url") val image1: String? = null,
) {
    val displayName: String get() = name ?: itemName ?: "Item #${item ?: id}"
    val ordered: Int get() = quantityOrdered ?: quantity ?: 0
    val received: Int get() = quantityReceived ?: receivedQuantity ?: 0
    val remaining: Int get() = (ordered - received).coerceAtLeast(0)
    val unitCost: Money? get() = cost ?: rate
}

@Serializable
data class ReceiptLine(
    @SerialName("purchase_order_item") val purchaseOrderItem: Long,
    val quantity: Int,
)

/** Body for `POST purchase_orders/receipts/`. ASSUMED — mirrors MCP `receive_po`. */
@Serializable
data class ReceiveRequest(
    @SerialName("purchase_order") val purchaseOrder: Long,
    val warehouse: Int? = null,
    @SerialName("received_date") val receivedDate: String? = null,
    val memo: String? = null,
    val items: List<ReceiptLine> = emptyList(),
)

object TransferStatus {
    const val IN_TRANSIT = "In Transit"
    const val COMPLETED = "Completed"
    const val VOIDED = "Voided"
    val all = listOf(IN_TRANSIT, COMPLETED, VOIDED)
}

/** Row of `inventory_transfers/inventory_transfers/`. */
@Serializable
data class Transfer(
    val id: Long,
    val number: String = "",
    @SerialName("from_warehouse") val fromWarehouse: String? = null,
    @SerialName("to_warehouse") val toWarehouse: String? = null,
    @SerialName("total_items") val totalItems: Int? = null,
    @SerialName("total_quantity_new") val totalQuantity: Int? = null,
    val date: String? = null,
    val remarks: String? = null,
    @SerialName("transfer_status") val status: String? = null,
    @SerialName("added_by") val addedBy: String? = null,
    @SerialName("created_date") val createdDate: String? = null,
    val items: List<TransferLine> = emptyList(),
) {
    val canComplete: Boolean get() = status == TransferStatus.IN_TRANSIT
}

@Serializable
data class TransferLine(
    val id: Long? = null,
    val item: Long? = null,
    val name: String? = null,
    @SerialName("item_name") val itemName: String? = null,
    @SerialName("upc_code") val upcCode: String? = null,
    val quantity: Int = 0,
) {
    val displayName: String get() = name ?: itemName ?: "Item #${item ?: id}"
}

@Serializable
data class TransferLineRequest(val item: Long, val quantity: Int)

/** Body for `POST inventory_transfers/inventory_transfers/`. ASSUMED. */
@Serializable
data class CreateTransferRequest(
    @SerialName("from_warehouse") val fromWarehouse: Int,
    @SerialName("to_warehouse") val toWarehouse: Int,
    val date: String,
    val remarks: String? = null,
    val items: List<TransferLineRequest>,
)

@Serializable
data class AdjustmentLineRequest(
    val item: Long,
    /** Positive adds stock, negative removes. */
    val quantity: Int,
    val reason: String? = null,
)

/** Body for `POST inventory_transfers/manual_adjustments/`. ASSUMED. */
@Serializable
data class CreateAdjustmentRequest(
    val warehouse: Int,
    val date: String,
    val memo: String? = null,
    val items: List<AdjustmentLineRequest>,
)

@Serializable
data class Adjustment(
    val id: Long,
    val number: String? = null,
    val warehouse: String? = null,
    val date: String? = null,
    val memo: String? = null,
    val status: String? = null,
    @SerialName("total_quantity") val totalQuantity: Int? = null,
    @SerialName("created_date") val createdDate: String? = null,
)
