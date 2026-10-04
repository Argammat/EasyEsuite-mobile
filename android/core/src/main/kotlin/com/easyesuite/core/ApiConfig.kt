package com.easyesuite.core

/**
 * Where the API lives. Every EasyEsuite tenant (company) has its own URL prefix:
 *   https://api-new.easyesuite.com/clients/{tenant}/api/v1/
 *
 * `tenant` is the company slug the user enters at login (the web app calls it companyName).
 */
data class ApiConfig(
    val tenant: String,
    val apiRoot: String = DEFAULT_API_ROOT,
    /** Authorization header scheme. dj-rest-auth/simplejwt default is "Bearer". */
    val authScheme: String = "Bearer",
) {
    val tenantRoot: String
        get() = apiRoot.trimEnd('/') + "/clients/" + tenant.trim().lowercase() + "/api/v1/"

    val globalRoot: String
        get() = apiRoot.trimEnd('/') + "/api/v1/"

    companion object {
        const val DEFAULT_API_ROOT = "https://api-new.easyesuite.com/"
    }
}

/** Relative API paths, kept in one place so both the docs and the code agree. See docs/API_MAP.md. */
object Endpoints {
    // Auth (tenant scoped)
    const val LOGIN = "auth/login/"
    const val LOGIN_2FA = "auth/login/2fa/"
    const val TOKEN_REFRESH = "auth/token/refresh/"
    const val ME = "users/users/me/"
    const val MY_TENANTS = "users/me/tenants/"

    // Items
    const val ITEMS = "items/items/"
    const val INVENTORY_ITEMS = "items/inventory_items/"
    const val WAREHOUSE_STOCK = "items/warehouse_inventory_items/"
    const val UPC_LOOKUP = "items/get_item_upc/"
    const val WAREHOUSES = "items/warehouses/"
    const val IMAGE_UPLOAD = "files/images/"
    const val GLOBAL_SEARCH = "core/global_search/"

    // Inventory operations
    const val TRANSFERS = "inventory_transfers/inventory_transfers/"
    const val ADJUSTMENTS = "inventory_transfers/manual_adjustments/"
    const val PURCHASE_ORDERS = "purchase_orders/purchase_orders/"
    const val PURCHASE_ORDER_ITEMS = "purchase_orders/purchase_order_items/"
    const val RECEIPTS = "purchase_orders/receipts/"

    // Orders & shipping
    const val SALES_ORDERS = "sales_orders/sales_orders/"
    const val FULFILLMENTS = "sales_orders/fulfillments/"
    const val SHIPMENTS = "shipping/shipments/"
    const val SHIPMENT_RESOLVE_BARCODE = "shipping/shipments/resolve_barcode/"
    const val SHIPPING_BALANCE = "shipping/balance/"

    // Dashboard & reports
    const val OVERVIEW_CARDS = "dashboard/overview/get_overview_cards_data/"
    const val BEST_SELLERS = "dashboard/items/get_best_sellers/"
    const val MARKETPLACE_ORDER_INTERVALS = "dashboard/marketplace_orders/get_marketplace_orders_total_amount_date_intervals/"
    const val UNITS_SOLD_INTERVALS = "dashboard/invoice_dashboards/get_invoice_items_quantity_sold_date_intervals/"
    const val INVOICE_ANALYTICS = "sales_orders/invoice_items/invoice_analytics/"
    const val CARRIER_SPEND = "shipping/reports/carrier_spend/"
    const val COST_PER_SHIPMENT = "shipping/reports/cost_per_shipment/"

    // AI
    const val COPILOT = "ai/ai_copilot_agent_v2/"
    const val AI_FEATURE_SETTINGS = "ai/ai_feature_settings/"
    const val AI_CREDITS = "ai/ai_credits/status/"
}

/** Marketplace ids as the ERP uses them (verified on the Nationwide tenant). */
object Marketplaces {
    const val AMAZON = 2
    const val EBAY = 4
    const val TARGET = 5
    const val BEST_BUY = 6
    const val MACYS = 7
    const val TEMU = 12

    fun name(id: Int?): String = when (id) {
        AMAZON -> "Amazon"
        EBAY -> "eBay"
        TARGET -> "Target"
        BEST_BUY -> "Best Buy"
        MACYS -> "Macy's"
        TEMU -> "Temu"
        null -> "—"
        else -> "Marketplace #$id"
    }
}
