import Foundation

/// Sales order statuses as the API spells them (filter values and record values match).
public enum OrderStatus {
    public static let open = "Open"
    public static let pendingFulfillment = "Pending Fulfillment"
    public static let partialFulfilled = "Partial Fulfilled"
    public static let partialFulfilledPendingInvoice = "Partial Fulfilled/Pending Invoice"
    public static let fulfilledPendingInvoice = "Fulfilled/Pending Invoice"
    public static let invoiced = "Invoiced"
    public static let voided = "Voided"
    public static let all = [open, pendingFulfillment, partialFulfilled, partialFulfilledPendingInvoice, fulfilledPendingInvoice, invoiced, voided]
}

/// Row of `sales_orders/sales_orders/`. Note: `marketplace` is the NAME here and the id on the detail record.
public struct SalesOrderSummary: Decodable, Identifiable, Hashable, Sendable {
    public var id: Int64
    public var number: String
    public var status: String
    public var marketplace: String?
    public var company: String?
    public var customer: String?
    public var warehouse: String?
    public var shippingMethod: String?
    public var totalQuantity: Int?
    public var totalItems: Int?
    public var totalAmountNew: Money?
    public var totalAmount: Money?
    public var date: String?
    public var shipDate: String?
    public var deliverByDate: String?
    public var poNumber: String?
    public var readyToShip: Bool?
    public var shipComplete: Bool?
    public var connectorSyncStatus: String?
    public var connectorSyncStatusLastSyncError: String?
    public var memo: String?
    public var isConnectorOrder: Bool?
    public var createdDate: String?

    public var amount: Money { totalAmountNew ?? totalAmount ?? .zero }
}

public struct Address: Decodable, Hashable, Sendable {
    public var id: Int64?
    public var country: String?
    public var address1: String?
    public var address2: String?
    public var address3: String?
    public var city: String?
    public var state: String?
    public var zipCode: String?
    public var companyName: String?
    public var contactName: String?
    public var email: String?
    public var cellPhone: String?
    public var workPhone: String?

    public var lines: [String] {
        let cityLine = [city, state, zipCode].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
        return [contactName, companyName, address1, address2, address3, cityLine, country]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
    }
    public var phone: String? { [cellPhone, workPhone].compactMap { $0 }.first { !$0.isEmpty } }
}

public struct SalesOrderLine: Decodable, Identifiable, Hashable, Sendable {
    public var id: Int64
    public var name: String
    public var itemType: String?
    public var upcCode: String?
    public var warehouseName: String?
    public var quantity: Int?
    public var price: Money?
    public var description: String?
    public var fulfilledQuantity: Int?
    public var invoicedQuantity: Int?
    public var unfulfilledQuantity: Int?
    public var cancelledQuantity: Int?
    public var availableQuantity: Int?
    public var marketplaceSku: String?
    public var item: Int64?
    public var warehouse: Int?
    public var status: String?
    public var serialRequired: Bool?
    public var taxAmount: Money?
    public var itemImage1Url: String?
    public var itemImages: [ItemImage]?

    public var qty: Int { quantity ?? 0 }
    public var fulfilled: Int { fulfilledQuantity ?? 0 }
    public var unfulfilled: Int { unfulfilledQuantity ?? 0 }
    public var primaryImage: String? { itemImages?.first?.imageUrl ?? itemImage1Url }
    public var lineTotal: Money { (price ?? .zero) * qty }
}

/// `sales_orders/sales_orders/{id}/`
public struct SalesOrderDetail: Decodable, Identifiable, Hashable, Sendable {
    public var id: Int64
    public var number: String
    public var status: String
    public var marketplace: Int?
    public var marketplaceName: String?
    public var items: [SalesOrderLine]?
    public var shipTo: Address?
    public var shippingMethodName: String?
    public var totalAmountNew: Money?
    public var subTotal: Money?
    public var totalTaxesAmount: Money?
    public var shippedAmount: Money?
    public var shippingCost: Money?
    public var totalQuantity: Int?
    public var date: String?
    public var shipDate: String?
    public var deliverByDate: String?
    public var poNumber: String?
    public var memo: String?
    public var warehouse: Int?
    public var shippingCarrier: Int?
    public var shippingMethod: Int?
    public var customerAccount: Int64?
    public var readyToShip: Bool?
    public var shipComplete: Bool?
    public var connectorSyncStatus: String?
    public var isConnectorOrder: Bool?
    public var createdDate: String?

    public var lines: [SalesOrderLine] { items ?? [] }
    public var amount: Money { totalAmountNew ?? subTotal ?? .zero }
    public var canFulfill: Bool { lines.contains { $0.unfulfilled > 0 } && status != OrderStatus.voided }
}

/// Body for `PATCH sales_orders/sales_orders/{id}/`.
public struct UpdateOrderRequest: Encodable, Sendable {
    public var memo: String?
    public var poNumber: String?
    public var status: String?
    public init(memo: String? = nil, poNumber: String? = nil, status: String? = nil) { self.memo = memo; self.poNumber = poNumber; self.status = status }
}

public struct FulfillLine: Encodable, Sendable {
    public var salesOrderItem: Int64
    public var quantity: Int
    public init(salesOrderItem: Int64, quantity: Int) { self.salesOrderItem = salesOrderItem; self.quantity = quantity }
}

/// Body for `POST sales_orders/fulfillments/` (mirrors the MCP `fulfill_sales_order` task). ASSUMED.
public struct FulfillRequest: Encodable, Sendable {
    public var salesOrder: Int64
    public var warehouse: Int?
    public var trackingNumber: String?
    public var shippingCarrier: Int?
    public var shippingMethod: Int?
    public var shippingCost: String?
    public var memo: String?
    public var items: [FulfillLine]
    public init(salesOrder: Int64, warehouse: Int?, trackingNumber: String?, shippingCarrier: Int?, shippingMethod: Int?, items: [FulfillLine]) {
        self.salesOrder = salesOrder; self.warehouse = warehouse; self.trackingNumber = trackingNumber
        self.shippingCarrier = shippingCarrier; self.shippingMethod = shippingMethod; self.items = items
    }
}

// MARK: - Shipping

public enum ShipmentStatus {
    public static let toShip = "to_ship", onHold = "on_hold", shipped = "shipped", voided = "voided", exceptions = "exceptions"
    public static let tabs = [toShip, onHold, shipped, exceptions, voided]
    public static func label(_ status: String?) -> String {
        switch status {
        case toShip: return "To ship"
        case onHold: return "On hold"
        case shipped: return "Shipped"
        case voided: return "Voided"
        case exceptions: return "Exceptions"
        case nil: return "—"
        case let s?: return s.replacingOccurrences(of: "_", with: " ").capitalized
        }
    }
}

public struct ShipmentItem: Decodable, Hashable, Sendable {
    public var upc: String?
    public var title: String?
    /// The ERP SKU (the API calls it `item_id`).
    public var itemId: String?
    public var itemImageUrl: String?
    public var quantity: Int?
    public var kitItemLabel: String?
    public var qty: Int { quantity ?? 0 }
}

/// A carrier rate as returned inside a shipment; provider-specific so everything is optional.
public struct ShipmentRate: Decodable, Hashable, Identifiable, Sendable {
    @FlexibleOptionalString public var id: String?
    public var carrier: String?
    public var service: String?
    public var rate: Money?
    public var currency: String?
    public var deliveryDays: Int?
    public var estDeliveryDays: Int?
    public var label: String { [carrier, service].compactMap { $0 }.joined(separator: " · ").isEmpty ? (id ?? "Rate") : [carrier, service].compactMap { $0 }.joined(separator: " · ") }
    public var days: Int? { deliveryDays ?? estDeliveryDays }
}

/// Row of `shipping/shipments/` and `shipping/shipments/{id}/`. Ids are strings on this endpoint.
public struct Shipment: Decodable, Identifiable, Hashable, Sendable {
    @FlexibleString public var id: String
    public var status: String?
    public var trackingCode: String?
    /// The sales order number (`order_id` in the API).
    public var orderId: String?
    @FlexibleOptionalString public var salesOrderId: String?
    public var poNumber: String?
    public var marketplaceName: String?
    public var orderShippingMethod: String?
    public var orderShippingCarrier: String?
    public var fulfillmentSource: String?
    public var labelProvider: String?
    public var isReturn: Bool?
    public var fromAddressName: String?
    public var toAddressName: String?
    public var toAddressState: String?
    public var toAddressCountry: String?
    public var selectedRate: ShipmentRate?
    public var rates: [ShipmentRate]?
    @FlexibleOptionalString public var batchId: String?
    public var items: [ShipmentItem]?
    public var itemCount: Int?
    public var unitCount: Int?
    public var orderTotal: Money?
    public var shipmentCost: Money?
    public var labelUrl: String?
    public var purchasedAt: String?
    public var shipByDate: String?
    public var deliverByDate: String?
    public var deliveredAt: String?
    public var packVerificationStatus: String?
    public var manualHold: Bool?
    public var holdReason: String?
    public var exceptionCode: String?
    public var exceptionDetail: String?
    public var insured: Bool?
    public var claimStatus: String?
    public var createdDate: String?

    public var lines: [ShipmentItem] { items ?? [] }
    public var orderNumber: String? { orderId }
    public var hasLabel: Bool { !(labelUrl ?? "").isEmpty }
    public var canBuyLabel: Bool { status == ShipmentStatus.toShip && !hasLabel }
    public var destination: String { [toAddressName, toAddressState].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: ", ") }
    public var onHold: Bool { manualHold == true || status == ShipmentStatus.onHold }
}

public struct BuyLabelRequest: Encodable, Sendable { public var rateId: String?; public init(rateId: String?) { self.rateId = rateId } }
public struct HoldRequest: Encodable, Sendable { public var reason: String?; public init(reason: String?) { self.reason = reason } }
public struct VerifyScanRequest: Encodable, Sendable { public var upc: String; public init(upc: String) { self.upc = upc } }

/// `shipping/shipments/{id}/tracking_details/` — carrier-specific; only the common fields are typed.
public struct TrackingDetails: Decodable, Hashable, Sendable {
    public var status: String?
    public var trackingCode: String?
    public var carrier: String?
    public var estDeliveryDate: String?
    public var events: [TrackingEvent]?
    public var trackingDetails: [TrackingEvent]?
    public var timeline: [TrackingEvent] { (events?.isEmpty == false ? events : trackingDetails) ?? [] }
}

public struct TrackingEvent: Decodable, Hashable, Sendable {
    public var status: String?
    public var message: String?
    public var description: String?
    public var datetime: String?
    public var date: String?
    public var location: String?
    public var city: String?
    public var state: String?
    public var text: String { message ?? description ?? status ?? "" }
    public var timestamp: String? { datetime ?? date }
    public var place: String? { location ?? ([city, state].compactMap { $0 }.joined(separator: ", ").nilIfEmpty) }
}

extension String {
    public var nilIfEmpty: String? { isEmpty ? nil : self }
    public var nilIfBlank: String? { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self }
}


// MARK: - Sales finance (VERIFIED against the live API, Oct 2026)

/// Statuses of `sales_orders/invoices/`.
public enum InvoiceStatus {
    public static let open = "Open", paid = "Paid", partialPaid = "Partial Paid", voided = "Voided"
    public static let all = [open, paid, partialPaid, voided]
}

/// Row of `sales_orders/invoices/` — the billing document for a sales order.
public struct Invoice: Decodable, Identifiable, Hashable, Sendable {
    public var id: Int64
    public var number: String
    public var status: String
    public var invoiceType: String?
    public var returnStatus: String?
    public var companyName: String?
    public var customerName: String?
    public var salesOrderNumber: String?
    public var salesOrderId: Int64?
    public var marketplaceName: String?
    public var warehouseName: String?
    public var shippingMethodName: String?
    public var trackingNumber: String?
    public var termsName: String?
    public var poNumber: String?
    public var date: String?
    public var dueDate: String?
    public var totalQuantity: Int?
    public var totalAmount: Money?
    public var openAmount: Money?
    public var paidAmountNew: Money?
    public var totalTaxAmount: Money?
    public var shippingCost: Money?

    public var displayCustomer: String { companyName?.nilIfBlank ?? customerName ?? "—" }
    public var isOpen: Bool { status == InvoiceStatus.open || status == InvoiceStatus.partialPaid }
}

/// Row of `sales_orders/payments/` — a customer payment (wire, check, card) and how much of it is applied.
public struct Payment: Decodable, Identifiable, Hashable, Sendable {
    public var id: Int64
    public var number: String
    public var status: String
    public var customerName: String?
    public var paymentMethodName: String?
    public var bankName: String?
    public var refNumber: String?
    public var checkNumber: String?
    public var memo: String?
    public var date: String?
    public var amount: Money?
    public var appliedAmountNew: Money?
    public var unAppliedAmountNew: Money?

    public var hasUnapplied: Bool { unAppliedAmountNew.map { !$0.isZero } ?? false }
}
