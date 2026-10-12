import Foundation

// All models decode with `.convertFromSnakeCase`, so property names are the camelCase form of the API keys.

public struct ItemImage: Codable, Hashable, Sendable {
    public var imageUrl: String?
    public init(imageUrl: String?) { self.imageUrl = imageUrl }
}

/// Row of `items/items/` (catalog, all item types).
public struct ItemSummary: Decodable, Identifiable, Hashable, Sendable {
    public var id: Int64
    public var name: String
    public var upcCode: String?
    public var marketplaceTitle: String?
    public var marketplaceBrand: String?
    public var itemType: String?
    public var itemConditionName: String?
    public var totalQuantityOnHand: Int?
    public var averageCost: Money?
    public var lastPurchasePrice: Money?
    public var lastSellingPrice: Money?
    public var purchasePrice: Money?
    public var totalValue: Money?
    public var itemImage1Url: String?
    public var itemImages: [ItemImage]?
    public var createdDate: String?
    public var modifiedDate: String?

    public init(id: Int64, name: String, upcCode: String? = nil, marketplaceTitle: String? = nil, totalQuantityOnHand: Int? = nil, itemImage1Url: String? = nil, itemImages: [ItemImage]? = nil) {
        self.id = id; self.name = name; self.upcCode = upcCode; self.marketplaceTitle = marketplaceTitle
        self.totalQuantityOnHand = totalQuantityOnHand; self.itemImage1Url = itemImage1Url; self.itemImages = itemImages
    }

    public var onHand: Int { totalQuantityOnHand ?? 0 }
    public var primaryImage: String? { itemImages?.first?.imageUrl ?? itemImage1Url }
    public var displayTitle: String { (marketplaceTitle?.isEmpty == false ? marketplaceTitle : nil) ?? name }
}

public struct MarketplacePrice: Decodable, Hashable, Sendable {
    public var id: Int64?
    public var price: Money?
    public var marketplace: Int?
}

/// `items/items/{id}/` — the parts of the (very large) record the app shows.
public struct ItemDetail: Decodable, Identifiable, Hashable, Sendable {
    public var id: Int64
    public var name: String
    public var upcCode: String?
    public var marketplaceTitle: String?
    public var marketplaceBrand: String?
    public var marketplacePlatform: String?
    public var manufacturer: String?
    public var description: String?
    public var salesDescription: String?
    public var purchaseDescription: String?
    public var gamingName: String?
    public var itemType: String?
    public var itemCondition: Int?
    public var itemConditionName: String?
    public var totalQuantityOnHand: Int?
    public var averageCost: Money?
    public var lastPurchasePrice: Money?
    public var lastSellingPrice: Money?
    public var purchasePrice: Money?
    public var totalValue: Money?
    public var weight: String?
    public var weightUnit: String?
    public var length: String?
    public var width: String?
    public var height: String?
    public var dimensionUnit: String?
    public var taxable: Bool?
    public var itemImage1Url: String?
    public var itemImages: [ItemImage]?
    public var marketplacePricing: [MarketplacePrice]?
    public var amazonSku: String?, walmartSku: String?, ebaySku: String?, targetSku: String?, bestBuySku: String?, macysSku: String?, mercadoSku: String?
    public var amazonCategory: String?, walmartCategory: String?, ebayCategory: String?, targetCategory: String?, bestBuyCategory: String?, macysCategory: String?
    public var createdDate: String?
    public var modifiedDate: String?

    public var onHand: Int { totalQuantityOnHand ?? 0 }
    public var displayTitle: String { (marketplaceTitle?.isEmpty == false ? marketplaceTitle : nil) ?? name }
    public var allImages: [String] {
        var out: [String] = (itemImages ?? []).compactMap(\.imageUrl)
        if let i1 = itemImage1Url, !out.contains(i1) { out.append(i1) }
        return out
    }
    public var marketplaceSkus: [(String, String)] {
        [("Amazon", amazonSku), ("Walmart", walmartSku), ("eBay", ebaySku), ("Target", targetSku), ("Best Buy", bestBuySku), ("Macy's", macysSku), ("Mercado Libre", mercadoSku)]
            .compactMap { name, sku in sku.map { (name, $0) } }
    }
    public var dimensionsText: String? {
        guard let l = length, let w = width, let h = height else { return nil }
        return "\(l) × \(w) × \(h) \(dimensionUnit ?? "")".trimmingCharacters(in: .whitespaces)
    }
    /// Lightweight summary for lists that mix detail and summary records.
    public var summary: ItemSummary { ItemSummary(id: id, name: name, upcCode: upcCode, marketplaceTitle: marketplaceTitle, totalQuantityOnHand: totalQuantityOnHand, itemImage1Url: itemImage1Url, itemImages: itemImages) }
}

/// Row of `items/inventory_items/` — the web "Inventory" page.
public struct InventoryItem: Decodable, Identifiable, Hashable, Sendable {
    public var id: Int64
    public var name: String
    public var upcCode: String?
    public var marketplaceTitle: String?
    public var marketplaceBrand: String?
    public var itemType: String?
    public var availableQuantity: Int?
    public var totalQuantityOnHand: Int?
    public var totalQuantityOnOrder: Int?
    public var totalQuantityOnPurchase: Int?
    public var reorderPoint: Int?
    public var averageCost: Money?
    public var lastPurchasePrice: Money?
    public var sellingPrice: Money?
    public var totalValue: Money?
    public var itemImage1Url: String?
    public var itemImages: [ItemImage]?
    public var serialized: Bool?
    public var createdDate: String?

    public var available: Int { availableQuantity ?? 0 }
    public var onHand: Int { totalQuantityOnHand ?? 0 }
    public var onOrder: Int { totalQuantityOnOrder ?? 0 }
    public var primaryImage: String? { itemImages?.first?.imageUrl ?? itemImage1Url }
    public var displayTitle: String { (marketplaceTitle?.isEmpty == false ? marketplaceTitle : nil) ?? name }
    public var belowReorderPoint: Bool { (reorderPoint ?? 0) > 0 && available < (reorderPoint ?? 0) }
}

/// Row of `items/warehouse_inventory_items/` — one item in one warehouse.
public struct WarehouseStock: Decodable, Identifiable, Hashable, Sendable {
    public var id: Int64
    public var name: String
    public var upcCode: String?
    public var warehouse: String
    public var inventoryItemId: Int64?
    public var quantityOnHand: Int?
    public var quantityAvailable: Int?
    public var quantityCommitted: Int?
    public var quantityOnOrder: Int?
    public var quantityInTransit: Int?
    public var quantityOnPurchaseOrder: Int?
    public var quantityOnFulfill: Int?
    public var quantityBackOrdered: Int?
    public var reorderPoint: Int?
    public var preferredStockLevel: Int?
    public var averageCost: Money?
    public var lastPurchasePrice: Money?
    public var sellingPriceNew: Money?
    public var totalValNew: Money?
    public var marketplaceTitle: String?
    public var itemImage1Url: String?
    public var itemImages: [ItemImage]?
    public var itemConditionName: String?

    public var onHand: Int { quantityOnHand ?? 0 }
    public var available: Int { quantityAvailable ?? 0 }
    public var committed: Int { quantityCommitted ?? 0 }
    public var onOrder: Int { quantityOnOrder ?? 0 }
    public var inTransit: Int { quantityInTransit ?? 0 }
    public var primaryImage: String? { itemImages?.first?.imageUrl ?? itemImage1Url }
    public var belowReorderPoint: Bool { (reorderPoint ?? 0) > 0 && available < (reorderPoint ?? 0) }
}

public struct Warehouse: Decodable, Identifiable, Hashable, Sendable {
    public var id: Int
    public var name: String
    public var isActive: Bool?
    public var isDefault: Bool?
    public var warehouseType: String?
    public var priority: Int?
    public var active: Bool { isActive ?? true }
    public var `default`: Bool { isDefault ?? false }
}

/// `items/get_item_upc/{upc}/` — product lookup used to prefill a new item.
public struct UpcLookupResponse: Decodable, Sendable { public var data: UpcProduct? }

public struct UpcProduct: Decodable, Hashable, Sendable {
    public var upc: String?
    public var title: String?
    public var description: String?
    public var brand: String?
    public var color: String?
    public var size: String?
    public var weight: String?
    public var dimension: String?
    public var images: [String]?
}

/// `items/item_conditions/` row (form dropdown + list filter).
public struct ItemCondition: Decodable, Identifiable, Hashable, Sendable {
    public var id: Int
    public var name: String?
    public var displayName: String { name?.nilIfBlank ?? "Condition #\(id)" }
}

/// `items/tax_schedules/` row (form dropdown).
public struct TaxSchedule: Decodable, Identifiable, Hashable, Sendable {
    public var id: Int
    public var name: String?
    public var isDefault: Bool?
    public var displayName: String { name?.nilIfBlank ?? "Schedule #\(id)" }
}

/// One row of `marketplace_pricing` on create/update.
public struct MarketplacePriceRequest: Encodable, Hashable, Sendable {
    public var marketplace: Int
    /// Decimal string, e.g. "9.99".
    public var price: String
    public var priceCurrency: String
    public init(marketplace: Int, price: String, priceCurrency: String = "USD") { self.marketplace = marketplace; self.price = price; self.priceCurrency = priceCurrency }
}

/// Body for `POST items/inventory_items/` (VERIFIED against the web app source, Oct 2026).
///
/// Rules from the backend team:
///  - the seven `*_marketplace` objects are required — send `{}` for the ones you don't configure
///    (Amazon wants its two FBA flags present);
///  - `marketplace_pricing` is required — `[]` or `{marketplace, price, price_currency}` rows;
///  - empty optional fields must be omitted/null, never `""` (`purchase_price: ""` is a 400);
///  - `name` is unique per item type (400 "An item with the same name and type already exists").
public struct CreateItemRequest: Encodable, Sendable {
    public var name: String
    public var upcCode: String?
    public var marketplaceTitle: String?
    public var description: String?
    public var marketplaceBrand: String?
    public var marketplacePlatform: String?
    public var manufacturer: String?
    /// Cost (the web form labels it "Price" under Commercial Summary). Decimal string.
    public var purchasePrice: String?
    public var weight: String?
    public var weightUnit: String = "Pounds"
    public var length: String?
    public var width: String?
    public var height: String?
    public var dimensionUnit: String?
    public var reorderPoint: Int?
    /// Id from `items/item_conditions/`.
    public var itemCondition: Int?
    /// Id from `items/tax_schedules/`.
    public var taxSchedule: Int?
    public var salesDescription: String?
    public var purchaseDescription: String?
    public var costingMethod: String = "AVG"
    public var costEstimationType: String = "AVG"
    public var calculateQuantityDiscountsType: String = "BLQ"
    public var taxable: Bool = true
    public var itemType: String = "INV"
    public var itemImages: [ItemImage] = []
    public var marketplacePricing: [MarketplacePriceRequest] = []
    // Required marketplace configuration blocks — empty unless the user configures a channel.
    public var amazonMarketplace: JSONValue = CreateItemRequest.amazonDefault
    public var ebayMarketplace: JSONValue = .object([:])
    public var walmartMarketplace: JSONValue = .object([:])
    public var targetMarketplace: JSONValue = .object([:])
    public var bestBuyMarketplace: JSONValue = .object([:])
    public var macysMarketplace: JSONValue = .object([:])
    public var mercadoMarketplace: JSONValue = .object([:])

    public static let amazonDefault: JSONValue = .object(["amazon_fba_account_1": .bool(false), "amazon_fba_account_2": .bool(false)])

    public init(name: String) { self.name = name }
}

/// One audit entry of `items/{inventory_items|kit_package_items|variant_items}/{id}/history/` (VERIFIED shape,
/// backend answers 2026-10-09). The endpoint is paginated like every other list; `changes` maps a field name to
/// `[old, new]`. Unknown keys (request IP and other audit fields) are ignored.
public struct ItemAuditEntry: Decodable, Hashable, Sendable, Identifiable {
    public var id: Int64?
    /// `create` / `update` / `delete` / `access` (lowercase strings; the filter takes integers 0–3).
    public var action: String?
    /// Who made the change; nil for system or automated changes.
    public var actor: String?
    /// ISO-8601, UTC.
    public var timestamp: String?
    public var contentType: String?
    public var changes: [String: JSONValue]?

    private enum CodingKeys: String, CodingKey { case id, action, actor, timestamp, contentType, changes }
    public init(id: Int64? = nil, action: String? = nil, actor: String? = nil, timestamp: String? = nil, contentType: String? = nil, changes: [String: JSONValue]? = nil) {
        self.id = id; self.action = action; self.actor = actor; self.timestamp = timestamp; self.contentType = contentType; self.changes = changes
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try? c.decodeIfPresent(Int64.self, forKey: .id)
        action = try? c.decodeIfPresent(String.self, forKey: .action)
        actor = try? c.decodeIfPresent(String.self, forKey: .actor)
        timestamp = try? c.decodeIfPresent(String.self, forKey: .timestamp)
        contentType = try? c.decodeIfPresent(String.self, forKey: .contentType)
        changes = try? c.decodeIfPresent([String: JSONValue].self, forKey: .changes)
    }

    public var actionLabel: String { (action ?? "change").prefix(1).uppercased() + (action ?? "change").dropFirst() }
    public var actorLabel: String { actor?.nilIfBlank ?? "System" }

    /// The web app shows one table row per changed field: Action · Field · Old value · New value · User · Date.
    public var rows: [ItemHistoryRow] {
        guard let changes, !changes.isEmpty else {
            return [ItemHistoryRow(id: "\(id ?? 0)", entryId: id, action: actionLabel, field: nil, oldValue: nil, newValue: nil, actor: actorLabel, timestamp: timestamp)]
        }
        return changes.keys.sorted().map { key in
            let pair = changes[key]?.arrayValue ?? []
            return ItemHistoryRow(id: "\(id ?? 0):\(key)", entryId: id, action: actionLabel, field: key,
                                  oldValue: pair.indices.contains(0) ? ItemHistoryRow.text(pair[0]) : nil,
                                  newValue: pair.indices.contains(1) ? ItemHistoryRow.text(pair[1]) : ItemHistoryRow.text(changes[key]),
                                  actor: actorLabel, timestamp: timestamp)
        }
    }
}

/// A flattened audit row (one changed field).
public struct ItemHistoryRow: Hashable, Sendable, Identifiable {
    public var id: String
    public var entryId: Int64?
    public var action: String
    public var field: String?
    public var oldValue: String?
    public var newValue: String?
    public var actor: String
    public var timestamp: String?

    /// `sale_price` → "Sale price".
    public var fieldLabel: String {
        guard let field else { return "" }
        let spaced = field.replacingOccurrences(of: "_", with: " ")
        return spaced.prefix(1).uppercased() + spaced.dropFirst()
    }

    /// Values are usually strings or null; nested JSON is shown as compact text.
    public static func text(_ v: JSONValue?) -> String? {
        guard let v else { return nil }
        switch v {
        case .null: return nil
        case .string(let s): return s
        case .number, .bool: return v.stringValue
        case .array, .object:
            let enc = JSONEncoder(); enc.outputFormatting = [.sortedKeys]
            return (try? enc.encode(v)).flatMap { String(data: $0, encoding: .utf8) }
        }
    }
}

/// Query parameters of the history endpoint (all optional).
public struct HistoryFilter: Equatable, Sendable {
    /// 0 = create, 1 = update, 2 = delete, 3 = access.
    public var action: Int?
    public var field: String?
    /// A user id.
    public var actor: Int?
    public var search: String?
    public var startDate: String?
    public var endDate: String?
    /// `asc` or `desc` by timestamp.
    public var sortBy: String? = "desc"

    public init(action: Int? = nil, field: String? = nil, actor: Int? = nil, search: String? = nil, startDate: String? = nil, endDate: String? = nil, sortBy: String? = "desc") {
        self.action = action; self.field = field; self.actor = actor; self.search = search; self.startDate = startDate; self.endDate = endDate; self.sortBy = sortBy
    }

    public var query: [String: Any?] {
        ["action": action, "field": field?.nilIfBlank, "actor": actor, "search_history": search?.nilIfBlank,
         "start_date_history": startDate, "end_date_history": endDate, "sort_by": sortBy]
    }
}

/// Body for `POST items/warehouse_inventory_items/` (initial stocking). ASSUMED.
public struct OpeningStockRequest: Encodable, Sendable {
    public var inventoryItem: Int64
    public var warehouse: Int
    public var quantityOnHand: Int
    public init(inventoryItem: Int64, warehouse: Int, quantityOnHand: Int) { self.inventoryItem = inventoryItem; self.warehouse = warehouse; self.quantityOnHand = quantityOnHand }
}

public struct ImageUploadResponse: Decodable, Sendable {
    public var imageUrl: String?
    public var url: String?
    public var image: String?
    public var resolvedUrl: String? { imageUrl ?? url ?? image }
}
