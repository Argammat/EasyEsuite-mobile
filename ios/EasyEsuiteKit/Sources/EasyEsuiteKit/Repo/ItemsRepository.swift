import Foundation

/// Catalog, inventory levels, item creation. Endpoints and query parameters follow the backend
/// team's "Items & Inventory" notes (docs/API_MAP.md) — all VERIFIED unless a method says otherwise.
public final class ItemsRepository: @unchecked Sendable {
    private let client: APIClient
    public init(client: APIClient) { self.client = client }

    // MARK: catalog

    /// `items/items/` — every item type in one list. Filters: search, item_type, item_condition, is_available.
    public func catalog(search: String? = nil, itemType: String? = nil, conditionId: Int? = nil, availableOnly: Bool = false, page: PageQuery = PageQuery()) async throws -> Page<ItemSummary> {
        try await client.get(Endpoints.items, query: page.query.merging([
            "search": search?.nilIfBlank,
            "item_type": itemType,
            "item_condition": conditionId,
            "is_available": availableOnly ? true : nil,
            "ordering": "-created_date",
        ]) { $1 })
    }

    /// Item detail from the type-specific endpoint (`inventory_items` / `kit_package_items` / `variant_items`).
    /// Falls back to `items/items/{id}/` when the type is unknown or the typed endpoint 404s.
    public func item(_ id: Int64, type: String? = ItemTypes.inventory) async throws -> ItemDetail {
        do {
            return try await client.get(Endpoints.itemDetail(id, type: type))
        } catch APIError.http(let status, _, _) where status == 404 {
            return try await client.get("\(Endpoints.items)\(id)/")
        }
    }

    /// `items/{type}/{id}/history/` — stock movements for the item (shape decoded leniently, bad rows skipped).
    public func history(_ id: Int64, type: String?, page: PageQuery = PageQuery(limit: 50)) async throws -> [ItemHistoryEntry] {
        let raw: JSONValue = try await client.get(Endpoints.itemHistory(id, type: type), query: page.query)
        let rows = raw.arrayValue ?? raw["results"]?.arrayValue ?? []
        return rows.compactMap { row in
            guard let data = try? JSONEncoder().encode(row) else { return nil }
            return try? client.decoder.decode(ItemHistoryEntry.self, from: data)
        }
    }

    public func conditions() async throws -> [ItemCondition] {
        let page: Page<ItemCondition> = try await client.get(Endpoints.itemConditions, query: ["limit": 100])
        return page.results
    }

    public func taxSchedules() async throws -> [TaxSchedule] {
        let page: Page<TaxSchedule> = try await client.get(Endpoints.taxSchedules, query: ["limit": 100])
        return page.results
    }

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

    /// `items/inventory_items/` — the web "Inventory" page (per item, all warehouses summed).
    public func inventory(search: String? = nil, availableOnly: Bool = false, itemId: Int64? = nil, page: PageQuery = PageQuery()) async throws -> Page<InventoryItem> {
        try await client.get(Endpoints.inventoryItems, query: page.query.merging(["search": search?.nilIfBlank, "is_available": availableOnly ? true : nil, "item": itemId]) { $1 })
    }

    public func inventoryItem(_ id: Int64) async throws -> InventoryItem { try await client.get("\(Endpoints.inventoryItems)\(id)/") }

    /// `items/inventory_items/get_inventory_totalization/` — company-wide totals, rendered as cards.
    public func inventoryTotals(search: String? = nil, availableOnly: Bool = false) async throws -> [DashboardCard] {
        let raw: JSONValue = try await client.get(Endpoints.inventoryTotals, query: ["search": search?.nilIfBlank, "is_available": availableOnly ? true : nil])
        return DashboardCard.cards(from: raw)
    }

    /// Stock of one item in every warehouse (`items/warehouse_inventory_items/?item=`).
    public func stockByWarehouse(itemId: Int64) async throws -> [WarehouseStock] {
        let page: Page<WarehouseStock> = try await client.get(Endpoints.warehouseStock, query: ["item": itemId, "limit": 100])
        return page.results.sorted { $0.onHand > $1.onHand }
    }

    /// Everything in one warehouse (`?warehouse_id=`), optionally only rows with stock.
    public func stockInWarehouse(_ warehouseId: Int, search: String? = nil, availableOnly: Bool = false, page: PageQuery = PageQuery()) async throws -> Page<WarehouseStock> {
        try await client.get(Endpoints.warehouseStock, query: page.query.merging(["warehouse_id": warehouseId, "search": search?.nilIfBlank, "is_available": availableOnly ? true : nil]) { $1 })
    }

    /// `items/warehouse_inventory_items/totalization/` for one warehouse (or all when nil).
    public func warehouseTotals(warehouseId: Int?, availableOnly: Bool = false) async throws -> [DashboardCard] {
        let raw: JSONValue = try await client.get(Endpoints.warehouseStockTotals, query: ["warehouse_id": warehouseId, "is_available": availableOnly ? true : nil])
        return DashboardCard.cards(from: raw)
    }

    public func warehouses(activeOnly: Bool = true) async throws -> [Warehouse] {
        let page: Page<Warehouse> = try await client.get(Endpoints.warehouses, query: ["limit": 1000])
        return page.results.filter { !activeOnly || $0.active }.sorted { a, b in
            if a.default != b.default { return a.default }
            return a.name.localizedCaseInsensitiveCompare(b.name) == .orderedAscending
        }
    }

    // MARK: create

    /// Product lookup (`items/get_item_upc/{upc}/`) to prefill the "new item" form from a barcode.
    public func lookupUpc(_ upc: String) async throws -> UpcProduct? {
        let res: UpcLookupResponse = try await client.get(Endpoints.upcLookup(upc))
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

    /// `POST files/images/` (multipart field `image`); the response's `image` field is the URL.
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
