package com.easyesuite.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Sales order statuses as the API spells them (filter values and record values match). */
object OrderStatus {
    const val OPEN = "Open"
    const val PENDING_FULFILLMENT = "Pending Fulfillment"
    const val PARTIAL_FULFILLED = "Partial Fulfilled"
    const val PARTIAL_FULFILLED_PENDING_INVOICE = "Partial Fulfilled/Pending Invoice"
    const val FULFILLED_PENDING_INVOICE = "Fulfilled/Pending Invoice"
    const val INVOICED = "Invoiced"
    const val VOIDED = "Voided"

    val all = listOf(
        OPEN, PENDING_FULFILLMENT, PARTIAL_FULFILLED, PARTIAL_FULFILLED_PENDING_INVOICE,
        FULFILLED_PENDING_INVOICE, INVOICED, VOIDED,
    )
}

/** Row of `sales_orders/sales_orders/`. Note: `marketplace` is the NAME here, the id on the detail record. */
@Serializable
data class SalesOrderSummary(
    val id: Long,
    val number: String = "",
    val status: String = "",
    val marketplace: String? = null,
    val company: String? = null,
    val customer: String? = null,
    val warehouse: String? = null,
    @SerialName("shipping_method") val shippingMethod: String? = null,
    @SerialName("total_quantity") val totalQuantity: Int? = null,
    @SerialName("total_items") val totalItems: Int? = null,
    @SerialName("total_amount_new") val totalAmount: Money? = null,
    @SerialName("total_amount") val totalAmountDisplay: Money? = null,
    val date: String? = null,
    @SerialName("ship_date") val shipDate: String? = null,
    @SerialName("deliver_by_date") val deliverByDate: String? = null,
    @SerialName("po_number") val poNumber: String? = null,
    @SerialName("ready_to_ship") val readyToShip: Boolean? = null,
    @SerialName("ship_complete") val shipComplete: Boolean? = null,
    @SerialName("connector_sync_status") val connectorSyncStatus: String? = null,
    @SerialName("connector_sync_status_last_sync_error") val connectorSyncError: String? = null,
    val memo: String? = null,
    @SerialName("is_connector_order") val isConnectorOrder: Boolean? = null,
    @SerialName("created_date") val createdDate: String? = null,
) {
    val amount: Money get() = totalAmount ?: totalAmountDisplay ?: Money.ZERO
}

@Serializable
data class Address(
    val id: Long? = null,
    val country: String? = null,
    val address1: String? = null,
    val address2: String? = null,
    val address3: String? = null,
    val city: String? = null,
    val state: String? = null,
    @SerialName("zip_code") val zipCode: String? = null,
    @SerialName("company_name") val companyName: String? = null,
    @SerialName("contact_name") val contactName: String? = null,
    val email: String? = null,
    @SerialName("cell_phone") val cellPhone: String? = null,
    @SerialName("work_phone") val workPhone: String? = null,
) {
    val lines: List<String>
        get() = listOfNotNull(
            contactName?.takeIf { it.isNotBlank() },
            companyName?.takeIf { it.isNotBlank() },
            address1?.takeIf { it.isNotBlank() },
            address2?.takeIf { it.isNotBlank() },
            address3?.takeIf { it.isNotBlank() },
            listOfNotNull(city, state, zipCode).filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() },
            country?.takeIf { it.isNotBlank() },
        )
}

@Serializable
data class SalesOrderLine(
    val id: Long,
    val name: String = "",
    @SerialName("item_type") val itemType: String? = null,
    @SerialName("upc_code") val upcCode: String? = null,
    @SerialName("warehouse_name") val warehouseName: String? = null,
    val quantity: Int = 0,
    val price: Money? = null,
    val description: String? = null,
    @SerialName("fulfilled_quantity") val fulfilledQuantity: Int = 0,
    @SerialName("invoiced_quantity") val invoicedQuantity: Int = 0,
    @SerialName("unfulfilled_quantity") val unfulfilledQuantity: Int = 0,
    @SerialName("cancelled_quantity") val cancelledQuantity: Int = 0,
    @SerialName("available_quantity") val availableQuantity: Int? = null,
    @SerialName("marketplace_sku") val marketplaceSku: String? = null,
    val item: Long? = null,
    val warehouse: Int? = null,
    val status: String? = null,
    @SerialName("serial_required") val serialRequired: Boolean? = null,
    @SerialName("tax_amount") val taxAmount: Money? = null,
    @SerialName("item_image1_url") val image1: String? = null,
    @SerialName("item_images") val images: List<ItemImage> = emptyList(),
) {
    val primaryImage: String? get() = images.firstOrNull()?.imageUrl ?: image1
    val lineTotal: Money get() = Money((price?.amount ?: 0.0) * quantity)
}

/** `sales_orders/sales_orders/{id}/` */
@Serializable
data class SalesOrderDetail(
    val id: Long,
    val number: String = "",
    val status: String = "",
    val marketplace: Int? = null,
    @SerialName("marketplace_name") val marketplaceName: String? = null,
    val items: List<SalesOrderLine> = emptyList(),
    @SerialName("ship_to") val shipTo: Address? = null,
    @SerialName("shipping_method_name") val shippingMethodName: String? = null,
    @SerialName("total_amount_new") val totalAmount: Money? = null,
    @SerialName("sub_total") val subTotal: Money? = null,
    @SerialName("total_taxes_amount") val totalTaxes: Money? = null,
    @SerialName("shipped_amount") val shippedAmount: Money? = null,
    @SerialName("shipping_cost") val shippingCost: Money? = null,
    @SerialName("total_quantity") val totalQuantity: Int? = null,
    val date: String? = null,
    @SerialName("ship_date") val shipDate: String? = null,
    @SerialName("deliver_by_date") val deliverByDate: String? = null,
    @SerialName("po_number") val poNumber: String? = null,
    val memo: String? = null,
    val warehouse: Int? = null,
    @SerialName("shipping_carrier") val shippingCarrier: Int? = null,
    @SerialName("shipping_method") val shippingMethod: Int? = null,
    @SerialName("customer_account") val customerAccount: Long? = null,
    @SerialName("ready_to_ship") val readyToShip: Boolean? = null,
    @SerialName("ship_complete") val shipComplete: Boolean? = null,
    @SerialName("connector_sync_status") val connectorSyncStatus: String? = null,
    @SerialName("is_connector_order") val isConnectorOrder: Boolean? = null,
    @SerialName("created_date") val createdDate: String? = null,
) {
    val amount: Money get() = totalAmount ?: subTotal ?: Money.ZERO
    val canFulfill: Boolean get() = items.any { it.unfulfilledQuantity > 0 } && status != OrderStatus.VOIDED
}

/** Body for `PATCH sales_orders/sales_orders/{id}/` — only the fields the app edits. */
@Serializable
data class UpdateOrderRequest(
    val memo: String? = null,
    @SerialName("po_number") val poNumber: String? = null,
    val status: String? = null,
)

@Serializable
data class FulfillLine(
    @SerialName("sales_order_item") val salesOrderItem: Long,
    val quantity: Int,
)

/** Body for `POST sales_orders/fulfillments/` (mirrors the MCP `fulfill_sales_order` task). ASSUMED — see API_MAP. */
@Serializable
data class FulfillRequest(
    @SerialName("sales_order") val salesOrder: Long,
    val warehouse: Int? = null,
    @SerialName("tracking_number") val trackingNumber: String? = null,
    @SerialName("shipping_carrier") val shippingCarrier: Int? = null,
    @SerialName("shipping_method") val shippingMethod: Int? = null,
    @SerialName("shipping_cost") val shippingCost: String? = null,
    val memo: String? = null,
    val items: List<FulfillLine> = emptyList(),
)

// ---------------------------------------------------------------------------------------------
// Shipping
// ---------------------------------------------------------------------------------------------

object ShipmentStatus {
    const val TO_SHIP = "to_ship"
    const val ON_HOLD = "on_hold"
    const val SHIPPED = "shipped"
    const val VOIDED = "voided"
    const val EXCEPTIONS = "exceptions"
    val tabs = listOf(TO_SHIP, ON_HOLD, SHIPPED, EXCEPTIONS, VOIDED)

    fun label(status: String?): String = when (status) {
        TO_SHIP -> "To ship"
        ON_HOLD -> "On hold"
        SHIPPED -> "Shipped"
        VOIDED -> "Voided"
        EXCEPTIONS -> "Exceptions"
        null -> "—"
        else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

@Serializable
data class ShipmentItem(
    val upc: String? = null,
    val title: String? = null,
    @SerialName("item_id") val sku: String? = null,
    @SerialName("item_image_url") val imageUrl: String? = null,
    val quantity: Int = 0,
    @SerialName("kit_item_label") val kitItemLabel: String? = null,
)

/** A carrier rate as returned inside a shipment; the exact shape is provider-specific so most fields are optional. */
@Serializable
data class ShipmentRate(
    val id: String? = null,
    val carrier: String? = null,
    val service: String? = null,
    val rate: Money? = null,
    val currency: String? = null,
    @SerialName("delivery_days") val deliveryDays: Int? = null,
    @SerialName("est_delivery_days") val estDeliveryDays: Int? = null,
) {
    val label: String get() = listOfNotNull(carrier, service).joinToString(" · ").ifBlank { id ?: "Rate" }
}

/** Row of `shipping/shipments/` and `shipping/shipments/{id}/`. Ids are strings on this endpoint. */
@Serializable
data class Shipment(
    val id: String,
    val status: String? = null,
    @SerialName("tracking_code") val trackingCode: String? = null,
    @SerialName("order_id") val orderNumber: String? = null,
    @SerialName("sales_order_id") val salesOrderId: String? = null,
    @SerialName("po_number") val poNumber: String? = null,
    @SerialName("marketplace_name") val marketplaceName: String? = null,
    @SerialName("order_shipping_method") val orderShippingMethod: String? = null,
    @SerialName("order_shipping_carrier") val orderShippingCarrier: String? = null,
    @SerialName("fulfillment_source") val fulfillmentSource: String? = null,
    @SerialName("label_provider") val labelProvider: String? = null,
    @SerialName("is_return") val isReturn: Boolean = false,
    @SerialName("from_address_name") val fromAddressName: String? = null,
    @SerialName("to_address_name") val toAddressName: String? = null,
    @SerialName("to_address_state") val toAddressState: String? = null,
    @SerialName("to_address_country") val toAddressCountry: String? = null,
    @SerialName("selected_rate") val selectedRate: ShipmentRate? = null,
    val rates: List<ShipmentRate> = emptyList(),
    @SerialName("batch_id") val batchId: String? = null,
    val items: List<ShipmentItem> = emptyList(),
    @SerialName("item_count") val itemCount: Int? = null,
    @SerialName("unit_count") val unitCount: Int? = null,
    @SerialName("order_total") val orderTotal: Money? = null,
    @SerialName("shipment_cost") val shipmentCost: Money? = null,
    @SerialName("label_url") val labelUrl: String? = null,
    @SerialName("purchased_at") val purchasedAt: String? = null,
    @SerialName("ship_by_date") val shipByDate: String? = null,
    @SerialName("deliver_by_date") val deliverByDate: String? = null,
    @SerialName("delivered_at") val deliveredAt: String? = null,
    @SerialName("pack_verification_status") val packVerificationStatus: String? = null,
    @SerialName("manual_hold") val manualHold: Boolean = false,
    @SerialName("hold_reason") val holdReason: String? = null,
    @SerialName("exception_code") val exceptionCode: String? = null,
    @SerialName("exception_detail") val exceptionDetail: String? = null,
    val insured: Boolean = false,
    @SerialName("claim_status") val claimStatus: String? = null,
    @SerialName("created_date") val createdDate: String? = null,
) {
    val hasLabel: Boolean get() = !labelUrl.isNullOrBlank()
    val canBuyLabel: Boolean get() = status == ShipmentStatus.TO_SHIP && !hasLabel
    val destination: String get() = listOfNotNull(toAddressName, toAddressState).joinToString(", ")
}

@Serializable
data class BuyLabelRequest(@SerialName("rate_id") val rateId: String? = null)

@Serializable
data class HoldRequest(val reason: String? = null)

@Serializable
data class VerifyScanRequest(val upc: String)

/** `shipping/shipments/{id}/tracking_details/` — carrier-specific, kept as raw JSON plus the common fields. */
@Serializable
data class TrackingDetails(
    val status: String? = null,
    @SerialName("tracking_code") val trackingCode: String? = null,
    val carrier: String? = null,
    @SerialName("est_delivery_date") val estDeliveryDate: String? = null,
    val events: List<TrackingEvent> = emptyList(),
    @SerialName("tracking_details") val trackingDetails: List<TrackingEvent> = emptyList(),
    val raw: JsonElement? = null,
) {
    val timeline: List<TrackingEvent> get() = events.ifEmpty { trackingDetails }
}

@Serializable
data class TrackingEvent(
    val status: String? = null,
    val message: String? = null,
    val description: String? = null,
    val datetime: String? = null,
    val date: String? = null,
    val location: String? = null,
    val city: String? = null,
    val state: String? = null,
) {
    val text: String get() = message ?: description ?: status ?: ""
    val timestamp: String? get() = datetime ?: date
    val place: String? get() = location ?: listOfNotNull(city, state).joinToString(", ").ifBlank { null }
}

// ---- Sales finance (VERIFIED against the live API, Oct 2026) -----------------------------------

/** Statuses of `sales_orders/invoices/`. */
object InvoiceStatus {
    const val OPEN = "Open"
    const val PAID = "Paid"
    const val PARTIAL_PAID = "Partial Paid"
    const val VOIDED = "Voided"
    val all = listOf(OPEN, PAID, PARTIAL_PAID, VOIDED)
}

/** Row of `sales_orders/invoices/` — the billing document for a sales order. */
@Serializable
data class Invoice(
    val id: Long,
    val number: String = "",
    val status: String = "",
    @SerialName("invoice_type") val invoiceType: String? = null,
    @SerialName("return_status") val returnStatus: String? = null,
    @SerialName("company_name") val companyName: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("sales_order_number") val salesOrderNumber: String? = null,
    @SerialName("sales_order_id") val salesOrderId: Long? = null,
    @SerialName("marketplace_name") val marketplaceName: String? = null,
    @SerialName("warehouse_name") val warehouseName: String? = null,
    @SerialName("shipping_method_name") val shippingMethodName: String? = null,
    @SerialName("tracking_number") val trackingNumber: String? = null,
    @SerialName("terms_name") val termsName: String? = null,
    @SerialName("po_number") val poNumber: String? = null,
    val date: String? = null,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("total_quantity") val totalQuantity: Int? = null,
    @SerialName("total_amount") val totalAmount: Money? = null,
    @SerialName("open_amount") val openAmount: Money? = null,
    @SerialName("paid_amount_new") val paidAmount: Money? = null,
    @SerialName("total_tax_amount") val taxAmount: Money? = null,
    @SerialName("shipping_cost") val shippingCost: Money? = null,
) {
    val displayCustomer: String get() = companyName?.takeIf { it.isNotBlank() } ?: customerName ?: "—"
    val isOpen: Boolean get() = status == InvoiceStatus.OPEN || status == InvoiceStatus.PARTIAL_PAID
}

/** Row of `sales_orders/payments/` — a customer payment (wire, check, card) and how much of it is applied. */
@Serializable
data class Payment(
    val id: Long,
    val number: String = "",
    val status: String = "",
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("payment_method_name") val paymentMethodName: String? = null,
    @SerialName("bank_name") val bankName: String? = null,
    @SerialName("ref_number") val refNumber: String? = null,
    @SerialName("check_number") val checkNumber: String? = null,
    val memo: String? = null,
    val date: String? = null,
    val amount: Money? = null,
    @SerialName("applied_amount_new") val appliedAmount: Money? = null,
    @SerialName("un_applied_amount_new") val unappliedAmount: Money? = null,
) {
    val hasUnapplied: Boolean get() = unappliedAmount?.let { !it.isZero } == true
}
