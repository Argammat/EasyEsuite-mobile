import Foundation

/// Catalog, inventory levels, item creation.
public final class ItemsRepository: @unchecked Sendable {
    private let client: APIClient
    public init(client: APIClient) { self.client = client }

    // MARK: catalog

    public func catalog(search: String? = nil, page: PageQuery = PageQuery()) async throws -> Page<ItemSummary> {
        try await client.get(Endpoints.items, query: page.query.merging(["search": search?.nilIfBlank, "ordering": "-created_date"]) { $1 })
    }

    public func item(_ id: Int64) async throws -> ItemDetail { try await client.get("\(Endpoints.items)\(id)/") }

    /// Resolve a scanned barcode to catalog items: exact UPC first (with EAN-13 ⇄ UPC-A variants), then free text.
    public func findByBarcode(_ code: String) async throws -> [ItemSummary] {
        let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines)
        var candidates: [String] = [trimmed]
        let digits = trimmed.filter(\.isNumber)
        if digits.count == 13, digits.hasPrefix("0") { candidates.append(String(digits.dropFirst())) }
        if digits.count == 12 { candidates.append("0" + digits) }
        for c in candidates {
            let exact: Page<ItemSummary> = try await client.get(Endpoints.items, query: ["upc_code": c, "limit": 10])
            if !exact.results.isEmpty { return exact.results }
        }
        let fuzzy: Page<ItemSummary> = try await client.get(Endpoints.items, query: ["search": trimmed, "limit": 10])
        return fuzzy.results.filter { item in
            guard let upc = item.upcCode else { return true }
            return candidates.contains { c in upc.hasSuffix(String(c.drop(while: { $0 == "0" }))) } || item.name.localizedCaseInsensitiveContains(trimmed)
        }
    }

    // MARK: inventory

    public func inventory(search: String? = nil, availableOnly: Bool = false, page: PageQuery = PageQuery()) async throws -> Page<InventoryItem> {
        try await client.get(Endpoints.inventoryItems, query: page.query.merging(["search": search?.nilIfBlank, "is_available": availableOnly ? true : nil]) { $1 })
    }

    public func inventoryItem(_ id: Int64) async throws -> InventoryItem { try await client.get("\(Endpoints.inventoryItems)\(id)/") }

    public func stockByWarehouse(itemId: Int64) async throws -> [WarehouseStock] {
        let page: Page<WarehouseStock> = try await client.get(Endpoints.warehouseStock, query: ["item": itemId, "limit": 100])
        return page.results.sorted { $0.onHand > $1.onHand }
    }

    public func stockInWarehouse(_ warehouseId: Int, search: String? = nil, page: PageQuery = PageQuery()) async throws -> Page<WarehouseStock> {
        try await client.get(Endpoints.warehouseStock, query: page.query.merging(["warehouse": warehouseId, "search": search?.nilIfBlank]) { $1 })
    }

    public func warehouses(activeOnly: Bool = true) async throws -> [Warehouse] {
        let page: Page<Warehouse> = try await client.get(Endpoints.warehouses, query: ["limit": 200])
        return page.results.filter { !activeOnly || $0.active }.sorted { a, b in
            if a.default != b.default { return a.default }
            return a.name.localizedCaseInsensitiveCompare(b.name) == .orderedAscending
        }
    }

    // MARK: create

    /// External product-database lookup to prefill the "new item" form from a barcode.
    public func lookupUpc(_ upc: String) async throws -> UpcProduct? {
        let res: UpcLookupResponse = try await client.get(Endpoints.upcLookup, query: ["upc": upc.trimmingCharacters(in: .whitespaces)])
        guard let p = res.data, (p.title?.isEmpty == false) || !(p.images ?? []).isEmpty else { return nil }
        return p
    }

    public func createItem(_ request: CreateItemRequest) async throws -> ItemDetail {
        try await client.post(Endpoints.inventoryItems, body: request)
    }

    /// Partial update; keys are raw API field names.
    public func updateItem(_ id: Int64, fields: [String: JSONValue]) async throws -> ItemDetail {
        try await client.patch("\(Endpoints.inventoryItems)\(id)/", body: fields)
    }

    /// Opening stock for a brand-new item (`POST items/warehouse_inventory_items/`). Body ASSUMED.
    public func createOpeningStock(itemId: Int64, warehouseId: Int, quantity: Int) async throws -> WarehouseStock {
        try await client.post(Endpoints.warehouseStock, body: OpeningStockRequest(inventoryItem: itemId, warehouse: warehouseId, quantityOnHand: quantity))
    }

    public func uploadImage(_ data: Data, fileName: String = "photo.jpg", mimeType: String = "image/jpeg") async throws -> String? {
        let res: ImageUploadResponse = try await client.upload(Endpoints.imageUpload, field: "image", fileName: fileName, mimeType: mimeType, data: data)
        return res.resolvedUrl
    }

    public func globalSearch(_ query: String) async throws -> JSONValue { try await client.get(Endpoints.globalSearch, query: ["query": query]) }
}

/// Transfers, adjustments and receiving.
public final class InventoryRepository: @unchecked Sendable {
    private let client: APIClient
    public init(client: APIClient) { self.client = client }

    public func transfers(status: String? = nil, page: PageQuery = PageQuery()) async throws -> Page<Transfer> {
        try await client.get(Endpoints.transfers, query: page.query.merging(["transfer_status": status, "ordering": "-date"]) { $1 })
    }
    public func transfer(_ id: Int64) async throws -> Transfer { try await client.get("\(Endpoints.transfers)\(id)/") }
    public func createTransfer(_ req: CreateTransferRequest) async throws -> Transfer { try await client.post(Endpoints.transfers, body: req) }
    public func completeTransfer(_ id: Int64) async throws -> JSONValue { try await client.postEmpty("\(Endpoints.transfers)\(id)/complete/") }

    public func adjustments(page: PageQuery = PageQuery()) async throws -> Page<Adjustment> {
        try await client.get(Endpoints.adjustments, query: page.query.merging(["ordering": "-date"]) { $1 })
    }
    public func createAdjustment(_ req: CreateAdjustmentRequest) async throws -> Adjustment { try await client.post(Endpoints.adjustments, body: req) }

    public func purchaseOrders(statuses: [String] = PurchaseOrderStatus.receivable, search: String? = nil, page: PageQuery = PageQuery()) async throws -> Page<PurchaseOrder> {
        try await client.get(Endpoints.purchaseOrders, query: page.query.merging(["status": statuses, "search": search?.nilIfBlank, "ordering": "-date"]) { $1 })
    }
    public func purchaseOrder(_ id: Int64) async throws -> PurchaseOrder { try await client.get("\(Endpoints.purchaseOrders)\(id)/") }
    public func purchaseOrderLines(_ poId: Int64) async throws -> [PurchaseOrderLine] {
        let page: Page<PurchaseOrderLine> = try await client.get(Endpoints.purchaseOrderItems, query: ["purchase_order": poId, "limit": 200])
        return page.results
    }
    public func receive(_ req: ReceiveRequest) async throws -> JSONValue { try await client.post(Endpoints.receipts, body: req) }
}
