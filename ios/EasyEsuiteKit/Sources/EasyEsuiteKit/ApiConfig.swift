import Foundation

/// Where the API lives. Every EasyEsuite tenant (company) has its own URL prefix:
/// `https://api-new.easyesuite.com/clients/{tenant}/api/v1/`
public struct ApiConfig: Equatable, Sendable {
    public static let defaultApiRoot = URL(string: "https://api-new.easyesuite.com/")!

    public let tenant: String
    public let apiRoot: URL
    /// dj-rest-auth / simplejwt default.
    public let authScheme: String

    public init(tenant: String, apiRoot: URL = ApiConfig.defaultApiRoot, authScheme: String = "Bearer") {
        self.tenant = tenant.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        self.apiRoot = apiRoot
        self.authScheme = authScheme
    }

    public var tenantRoot: URL { apiRoot.appendingPathComponent("clients/\(tenant)/api/v1", isDirectory: true) }
    public var globalRoot: URL { apiRoot.appendingPathComponent("api/v1", isDirectory: true) }
}

/// Relative API paths, kept in one place so docs/API_MAP.md and the code agree.
public enum Endpoints {
    // Auth (tenant scoped)
    public static let login = "auth/login/"
    public static let login2FA = "auth/login/2fa/"
    public static let tokenRefresh = "auth/token/refresh/"
    public static let me = "users/users/me/"
    public static let myTenants = "users/me/tenants/"

    // Items
    public static let items = "items/items/"
    public static let inventoryItems = "items/inventory_items/"
    public static let warehouseStock = "items/warehouse_inventory_items/"
    public static let upcLookup = "items/get_item_upc/"
    public static let warehouses = "items/warehouses/"
    public static let imageUpload = "files/images/"
    public static let globalSearch = "core/global_search/"

    // Inventory operations
    public static let transfers = "inventory_transfers/inventory_transfers/"
    public static let adjustments = "inventory_transfers/manual_adjustments/"
    public static let purchaseOrders = "purchase_orders/purchase_orders/"
    public static let purchaseOrderItems = "purchase_orders/purchase_order_items/"
    public static let receipts = "purchase_orders/receipts/"

    // Orders & shipping
    public static let salesOrders = "sales_orders/sales_orders/"
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
