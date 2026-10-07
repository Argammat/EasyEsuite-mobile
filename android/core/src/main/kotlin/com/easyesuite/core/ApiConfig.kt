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
    /** Authorization header scheme; the backend expects `Bearer <token>`. */
    val authScheme: String = "Bearer",
    /**
     * Firebase / Identity Platform settings. When `apiKey` is set the apps sign in against Firebase
     * directly (the mechanism the backend team describes) and send the Firebase ID token as Bearer.
     * When it is null they fall back to the backend-proxied `auth/login/` + `auth/token/refresh/` flow
     * the web app's stored keys suggest. See docs/API_MAP.md → Auth.
     */
    val firebase: FirebaseConfig? = null,
) {
    val tenantRoot: String
        get() = apiRoot.trimEnd('/') + "/clients/" + tenant.trim().lowercase() + "/api/v1/"

    val globalRoot: String
        get() = apiRoot.trimEnd('/') + "/api/v1/"

    companion object {
        const val DEFAULT_API_ROOT = "https://api-new.easyesuite.com/"
    }
}

/**
 * Firebase Identity Platform configuration. The web API key is public (it ships in the web bundle);
 * the tenant id is the workspace's Identity Platform tenant (the web app stores it as `firebaseTenantId`).
 */
data class FirebaseConfig(
    val apiKey: String,
    /** Identity Platform tenant id for this workspace. Null = project-level users (no multi-tenancy). */
    val tenantId: String? = null,
) {
    val signInUrl: String get() = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$apiKey"
    val refreshUrl: String get() = "https://securetoken.googleapis.com/v1/token?key=$apiKey"
}

/** Relative API paths, kept in one place so both the docs and the code agree. See docs/API_MAP.md. */
object Endpoints {
    // Auth (tenant scoped) — backend-proxied flow
    const val LOGIN = "auth/login/"
    const val LOGIN_2FA = "auth/login/2fa/"
    const val TOKEN_REFRESH = "auth/token/refresh/"
    const val ME = "users/users/me/"
    const val MY_TENANTS = "users/me/tenants/"
    /** Global (no tenant prefix): resolves a company name to its tenant + Firebase tenant id. ASSUMED. */
    const val TENANT_LOOKUP = "clients/clients/"

    // Items (VERIFIED against the web app source, Oct 2026)
    const val ITEMS = "items/items/"
    const val INVENTORY_ITEMS = "items/inventory_items/"
    const val KIT_ITEMS = "items/kit_package_items/"
    const val VARIANT_ITEMS = "items/variant_items/"
    const val ITEM_CONDITIONS = "items/item_conditions/"
    const val TAX_SCHEDULES = "items/tax_schedules/"
    const val VARIATION_OPTIONS = "items/variation_options/"
    const val WAREHOUSE_STOCK = "items/warehouse_inventory_items/"
    const val WAREHOUSE_STOCK_TOTALS = "items/warehouse_inventory_items/totalization/"
    const val INVENTORY_TOTALS = "items/inventory_items/get_inventory_totalization/"
    const val WAREHOUSES = "items/warehouses/"
    const val IMAGE_UPLOAD = "files/images/"
    const val GLOBAL_SEARCH = "core/global_search/"

    /** `items/get_item_upc/{upc}/` — the UPC is a path segment, not a query parameter. */
    fun upcLookup(upc: String) = "items/get_item_upc/${upc.trim()}/"
    /** Type-specific detail endpoints; `items/items/{id}/` stays as the fallback. */
    fun itemDetail(id: Long, itemType: String?) = "${collectionFor(itemType)}$id/"
    fun itemHistory(id: Long, itemType: String?) = "${collectionFor(itemType)}$id/history/"
    fun availableQuantityInWarehouses(id: Long) = "items/items/$id/available_quantity_in_warehouses/"
    fun kitQuantityInWarehouses(id: Long) = "items/kit_package_items/$id/kit_package_item_quantity_in_warehouses/"

    fun collectionFor(itemType: String?): String = when (ItemTypes.normalize(itemType)) {
        ItemTypes.KIT -> KIT_ITEMS
        ItemTypes.VARIANT -> VARIANT_ITEMS
        else -> INVENTORY_ITEMS
    }

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

/**
 * `item_type` codes. "INV" is verified; the kit and variant codes are the web app's
 * `item_type` filter values and still need confirming (an unknown code just filters to nothing).
 */
object ItemTypes {
    const val INVENTORY = "INV"
    const val KIT = "KIT"
    const val VARIANT = "VAR"

    fun normalize(raw: String?): String? {
        val u = raw?.trim()?.uppercase() ?: return null
        return when {
            u.startsWith("INV") -> INVENTORY
            u.startsWith("KIT") || u.contains("PACKAGE") -> KIT
            u.startsWith("VAR") -> VARIANT
            else -> u
        }
    }

    fun label(raw: String?): String = when (normalize(raw)) {
        INVENTORY -> "Item"
        KIT -> "Kit"
        VARIANT -> "Variant"
        null -> "—"
        else -> raw ?: "—"
    }
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
