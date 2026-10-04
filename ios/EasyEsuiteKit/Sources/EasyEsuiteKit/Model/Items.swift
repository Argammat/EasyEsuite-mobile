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

/// `items/get_item_upc/?upc=` — external product-database lookup used to prefill a new item.
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

/// Body for `POST items/inventory_items/`. Keys mirror the web "Add item" form (verified);
/// the endpoint itself is still to be confirmed (docs/API_MAP.md).
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
    public var itemCondition: Int?
    public var salesDescription: String?
    public var purchaseDescription: String?
    public var costingMethod: String = "AVG"
    public var costEstimationType: String = "AVG"
    public var calculateQuantityDiscountsType: String = "BLQ"
    public var taxable: Bool = true
    public var itemType: String = "INV"
    public var itemImages: [ItemImage] = []

    public init(name: String) { self.name = name }
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
