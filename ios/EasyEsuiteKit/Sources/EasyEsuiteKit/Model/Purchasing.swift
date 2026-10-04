import Foundation

public enum PurchaseOrderStatus {
    public static let open = "Open"
    public static let partialReceived = "Partial Received"
    public static let partiallyReceivedPendingBilling = "Partially Received/Pending Billing"
    public static let receivedPendingBilling = "Received/Pending Billing"
    public static let billed = "Billed"
    public static let voided = "Voided"
    /// POs that still have something to receive.
    public static let receivable = [open, partialReceived, partiallyReceivedPendingBilling]
}

/// Row of `purchase_orders/purchase_orders/`.
public struct PurchaseOrder: Decodable, Identifiable, Hashable, Sendable {
    public var id: Int64
    public var number: String
    public var status: String
    public var company: String?
    public var vendor: String?
    public var warehouse: String?
    public var date: String?
    public var expectedDate: String?
    public var receivedDate: String?
    public var memo: String?
    public var invoiceNumber: String?
    public var totalAmountNew: Money?
    public var receivedAmountNew: Money?
    public var openAmount: Money?
    public var totalQuantityOrderedNew: Int?
    public var receivingClosed: Bool?
    public var items: [Int64]?
    public var createdDate: String?

    public var canReceive: Bool { receivingClosed != true && PurchaseOrderStatus.receivable.contains(status) }
    public var totalQuantityOrdered: Int { totalQuantityOrderedNew ?? 0 }
}

/// Row of `purchase_orders/purchase_order_items/?purchase_order=`. Shape ASSUMED (lenient).
public struct PurchaseOrderLine: Decodable, Identifiable, Hashable, Sendable {
    public var id: Int64
    public var item: Int64?
    public var name: String?
    public var itemName: String?
    public var upcCode: String?
    public var quantityOrdered: Int?
    public var quantity: Int?
    public var quantityReceived: Int?
    public var receivedQuantity: Int?
    public var cost: Money?
    public var rate: Money?
    public var itemImage1Url: String?

    public var displayName: String { name ?? itemName ?? "Item #\(item ?? id)" }
    public var ordered: Int { quantityOrdered ?? quantity ?? 0 }
    public var received: Int { quantityReceived ?? receivedQuantity ?? 0 }
    public var remaining: Int { max(ordered - received, 0) }
    public var unitCost: Money? { cost ?? rate }
}

public struct ReceiptLine: Encodable, Sendable {
    public var purchaseOrderItem: Int64
    public var quantity: Int
    public init(purchaseOrderItem: Int64, quantity: Int) { self.purchaseOrderItem = purchaseOrderItem; self.quantity = quantity }
}

/// Body for `POST purchase_orders/receipts/`. ASSUMED — mirrors MCP `receive_po`.
public struct ReceiveRequest: Encodable, Sendable {
    public var purchaseOrder: Int64
    public var warehouse: Int?
    public var receivedDate: String?
    public var memo: String?
    public var items: [ReceiptLine]
    public init(purchaseOrder: Int64, warehouse: Int? = nil, receivedDate: String?, memo: String?, items: [ReceiptLine]) {
        self.purchaseOrder = purchaseOrder; self.warehouse = warehouse; self.receivedDate = receivedDate; self.memo = memo; self.items = items
    }
}

public enum TransferStatus {
    public static let inTransit = "In Transit", completed = "Completed", voided = "Voided"
    public static let all = [inTransit, completed, voided]
}

/// Row of `inventory_transfers/inventory_transfers/`.
public struct Transfer: Decodable, Identifiable, Hashable, Sendable {
    public var id: Int64
    public var number: String
    public var fromWarehouse: String?
    public var toWarehouse: String?
    public var totalItems: Int?
    public var totalQuantityNew: Int?
    public var date: String?
    public var remarks: String?
    public var transferStatus: String?
    public var addedBy: String?
    public var createdDate: String?
    public var items: [TransferLine]?

    public var status: String? { transferStatus }
    public var totalQuantity: Int { totalQuantityNew ?? 0 }
    public var canComplete: Bool { transferStatus == TransferStatus.inTransit }
}

public struct TransferLine: Decodable, Hashable, Sendable {
    public var id: Int64?
    public var item: Int64?
    public var name: String?
    public var itemName: String?
    public var upcCode: String?
    public var quantity: Int?
    public var displayName: String { name ?? itemName ?? "Item #\(item ?? id ?? 0)" }
}

public struct TransferLineRequest: Encodable, Sendable {
    public var item: Int64
    public var quantity: Int
    public init(item: Int64, quantity: Int) { self.item = item; self.quantity = quantity }
}

/// Body for `POST inventory_transfers/inventory_transfers/`. ASSUMED.
public struct CreateTransferRequest: Encodable, Sendable {
    public var fromWarehouse: Int
    public var toWarehouse: Int
    public var date: String
    public var remarks: String?
    public var items: [TransferLineRequest]
    public init(fromWarehouse: Int, toWarehouse: Int, date: String, remarks: String?, items: [TransferLineRequest]) {
        self.fromWarehouse = fromWarehouse; self.toWarehouse = toWarehouse; self.date = date; self.remarks = remarks; self.items = items
    }
}

public struct AdjustmentLineRequest: Encodable, Sendable {
    public var item: Int64
    /// Positive adds stock, negative removes.
    public var quantity: Int
    public var reason: String?
    public init(item: Int64, quantity: Int, reason: String?) { self.item = item; self.quantity = quantity; self.reason = reason }
}

/// Body for `POST inventory_transfers/manual_adjustments/`. ASSUMED.
public struct CreateAdjustmentRequest: Encodable, Sendable {
    public var warehouse: Int
    public var date: String
    public var memo: String?
    public var items: [AdjustmentLineRequest]
    public init(warehouse: Int, date: String, memo: String?, items: [AdjustmentLineRequest]) { self.warehouse = warehouse; self.date = date; self.memo = memo; self.items = items }
}

public struct Adjustment: Decodable, Identifiable, Hashable, Sendable {
    public var id: Int64
    public var number: String?
    public var warehouse: String?
    public var date: String?
    public var memo: String?
    public var status: String?
    public var totalQuantity: Int?
    public var createdDate: String?
}
