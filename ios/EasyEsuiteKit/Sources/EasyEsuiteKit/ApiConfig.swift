import Foundation

/// Where the API lives. Every EasyEsuite tenant (company) has its own URL prefix:
/// `https://api-new.easyesuite.com/clients/{tenant}/api/v1/`
public struct ApiConfig: Equatable, Sendable {
    public static let defaultApiRoot = URL(string: "https://api-new.easyesuite.com/")!

    public let tenant: String
    public let apiRoot: URL
    /// Authorization header scheme; the backend expects `Bearer <token>`.
    public let authScheme: String
    /// Firebase / Identity Platform settings. When set, the app signs in against Firebase directly (the
    /// mechanism the backend team describes) and sends the Firebase ID token as Bearer. When nil it uses the
    /// backend-proxied `auth/login/` + `auth/token/refresh/` flow. See docs/API_MAP.md → Auth.
    public let firebase: FirebaseConfig?

    public init(tenant: String, apiRoot: URL = ApiConfig.defaultApiRoot, authScheme: String = "Bearer", firebase: FirebaseConfig? = nil) {
        self.tenant = tenant.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        self.apiRoot = apiRoot
        self.authScheme = authScheme
        self.firebase = firebase
    }

    public var tenantRoot: URL { apiRoot.appendingPathComponent("clients/\(tenant)/api/v1", isDirectory: true) }
    public var globalRoot: URL { apiRoot.appendingPathComponent("api/v1", isDirectory: true) }
}

/// Firebase Identity Platform configuration. The web API key is public (it ships in the web bundle);
/// the tenant id is the workspace's Identity Platform tenant (the web app stores it as `firebaseTenantId`).
public struct FirebaseConfig: Equatable, Sendable {
    public let apiKey: String
    /// Identity Platform tenant id for this workspace. Nil = project-level users (no multi-tenancy).
    public let tenantId: String?

    public init(apiKey: String, tenantId: String? = nil) {
        self.apiKey = apiKey
        self.tenantId = tenantId?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
    }

    public var signInURL: URL { URL(string: "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=\(apiKey)")! }
    public var refreshURL: URL { URL(string: "https://securetoken.googleapis.com/v1/token?key=\(apiKey)")! }
}

/// Relative API paths, kept in one place so docs/API_MAP.md and the code agree.
public enum Endpoints {
    // Auth (tenant scoped) — backend-proxied flow
    public static let login = "auth/login/"
    public static let login2FA = "auth/login/2fa/"
    public static let tokenRefresh = "auth/token/refresh/"
    public static let me = "users/users/me/"
    public static let myTenants = "users/me/tenants/"
    /// Global (no tenant prefix): resolves a company name to its tenant + Firebase tenant id. ASSUMED.
    public static let tenantLookup = "clients/clients/"

    // Items (VERIFIED against the web app source, Oct 2026)
    public static let items = "items/items/"
    public static let inventoryItems = "items/inventory_items/"
    public static let kitItems = "items/kit_package_items/"
    public static let variantItems = "items/variant_items/"
    public static let itemConditions = "items/item_conditions/"
    public static let taxSchedules = "items/tax_schedules/"
    public static let variationOptions = "items/variation_options/"
    public static let warehouseStock = "items/warehouse_inventory_items/"
    public static let warehouseStockTotals = "items/warehouse_inventory_items/totalization/"
    public static let inventoryTotals = "items/inventory_items/get_inventory_totalization/"
    public static let warehouses = "items/warehouses/"
    public static let imageUpload = "files/images/"
    public static let globalSearch = "core/global_search/"

    /// `items/get_item_upc/{upc}/` — the UPC is a path segment, not a query parameter.
    public static func upcLookup(_ upc: String) -> String { "items/get_item_upc/\(upc.trimmingCharacters(in: .whitespacesAndNewlines))/" }
    /// Type-specific detail endpoints; `items/items/{id}/` stays as the fallback.
    public static func itemDetail(_ id: Int64, type: String?) -> String { "\(collection(for: type))\(id)/" }
    public static func itemHistory(_ id: Int64, type: String?) -> String { "\(collection(for: type))\(id)/history/" }
    public static func availableQuantityInWarehouses(_ id: Int64) -> String { "items/items/\(id)/available_quantity_in_warehouses/" }
    public static func kitQuantityInWarehouses(_ id: Int64) -> String { "items/kit_package_items/\(id)/kit_package_item_quantity_in_warehouses/" }

    public static func collection(for itemType: String?) -> String {
        switch ItemTypes.normalize(itemType) {
        case ItemTypes.kit: return kitItems
        case ItemTypes.variant: return variantItems
        default: return inventoryItems
        }
    }

    // Inventory operations
    public static let transfers = "inventory_transfers/inventory_transfers/"
    public static let adjustments = "inventory_transfers/manual_adjustments/"
    public static let purchaseOrders = "purchase_orders/purchase_orders/"
    public static let purchaseOrderItems = "purchase_orders/purchase_order_items/"
    public static let receipts = "purchase_orders/receipts/"

    // Orders & shipping
    public static let salesOrders = "sales_orders/sales_orders/"
    public static let invoices = "sales_orders/invoices/"
    public static let payments = "sales_orders/payments/"
    public static let fulfillments = "sales_orders/fulfillments/"
    public static let shipments = "shipping/shipments/"
    public static let shipmentResolveBarcode = "shipping/shipments/resolve_barcode/"
    public static let shippingBalance = "shipping/balance/"

    // Dashboard & reports
    public static let overviewCards = "dashboard/overview/get_overview_cards_data/"
    public static let bestSellers = "dashboard/items/get_best_sellers/"
    public static let marketplaceOrderIntervals = "dashboard/marketplace_orders/get_marketplace_orders_total_amount_date_intervals/"
    public static let unitsSoldIntervals = "dashboard/invoice_dashboards/get_invoice_items_quantity_sold_date_intervals/"
    public static let invoiceAnalytics = "sales_orders/invoice_items/invoice_analytics/"
    public static let carrierSpend = "shipping/reports/carrier_spend/"
    public static let costPerShipment = "shipping/reports/cost_per_shipment/"

    // AI
    public static let copilot = "ai/ai_copilot_agent_v2/"
    public static let aiFeatureSettings = "ai/ai_feature_settings/"
    public static let aiCredits = "ai/ai_credits/status/"
}

/// `item_type` codes. "INV" is verified; the kit and variant codes are the web app's
/// `item_type` filter values and still need confirming (an unknown code just filters to nothing).
public enum ItemTypes {
    public static let inventory = "INV"
    public static let kit = "KIT"
    public static let variant = "VAR"

    public static func normalize(_ raw: String?) -> String? {
        guard let u = raw?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(), !u.isEmpty else { return nil }
        if u.hasPrefix("INV") { return inventory }
        if u.hasPrefix("KIT") || u.contains("PACKAGE") { return kit }
        if u.hasPrefix("VAR") { return variant }
        return u
    }

    public static func label(_ raw: String?) -> String {
        switch normalize(raw) {
        case inventory: return "Item"
        case kit: return "Kit"
        case variant: return "Variant"
        case nil: return "—"
        case let other?: return raw ?? other
        }
    }

    /// Short badge for lists: nil for plain inventory items (the default), "Kit"/"Variant" otherwise.
    public static func badge(_ raw: String?) -> String? {
        let n = normalize(raw)
        guard let n, n != inventory else { return nil }
        return label(n)
    }
}

/// Marketplace ids as the ERP uses them (verified on the Nationwide tenant).
public enum Marketplaces {
    public static let amazon = 2, ebay = 4, target = 5, bestBuy = 6, macys = 7, temu = 12

    public static func name(_ id: Int?) -> String {
        switch id {
        case amazon: return "Amazon"
        case ebay: return "eBay"
        case target: return "Target"
        case bestBuy: return "Best Buy"
        case macys: return "Macy's"
        case temu: return "Temu"
        case nil: return "—"
        case let other?: return "Marketplace #\(other)"
        }
    }
}
