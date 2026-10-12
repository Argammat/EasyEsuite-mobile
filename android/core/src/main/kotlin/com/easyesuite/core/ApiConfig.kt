package com.easyesuite.core

/**
 * Where the API lives. Every EasyEsuite tenant (company) has its own URL prefix:
 *   https://api-new.easyesuite.com/clients/{tenant}/api/v1/
 *
 * `tenant` is the workspace's `schema_name`. Development and staging hosts are not committed to this (public)
 * repository; they come from build configuration.
 */
data class ApiConfig(
    val tenant: String,
    val apiRoot: String = DEFAULT_API_ROOT,
    /** Authorization header scheme; the backend expects `Bearer <token>`. */
    val authScheme: String = "Bearer",
    /**
     * Firebase project of this environment — only needed for the Firestore-backed screens (Copilot history,
     * notifications, connector status). Null = those screens are unavailable; the REST API still works.
     */
    val firebase: FirebaseConfig? = null,
) {
    /** The tenant as it appears in URLs and sessions: trimmed, lower-case. */
    val schemaName: String get() = tenant.trim().lowercase()

    val tenantRoot: String
        get() = apiRoot.trimEnd('/') + "/clients/" + schemaName + "/api/v1/"

    val globalRoot: String
        get() = apiRoot.trimEnd('/') + "/api/v1/"

    /** "Production" / "Staging" / "Development", derived from the host so Settings can show which backend a build talks to. */
    val environmentName: String
        get() {
            val host = runCatching { java.net.URI(apiRoot).host.orEmpty().lowercase() }.getOrDefault("")
            return when {
                host.startsWith("stg-") || host.contains("staging") -> "Staging"
                host.startsWith("dev-") || host.contains(".run.app") || host.contains("localhost") || host.contains("10.0.2.2") -> "Development"
                else -> "Production"
            }
        }

    companion object {
        const val DEFAULT_API_ROOT = "https://api-new.easyesuite.com/"
    }
}

/**
 * Firebase project settings (per environment, from build configuration — never hard-coded). The web API key
 * and project id identify the project and ship inside the app; they grant nothing by themselves. The named
 * Firestore databases hold the Copilot conversations / connector status and the in-app notifications.
 */
data class FirebaseConfig(
    val apiKey: String,
    val projectId: String,
    val connectorDatabase: String = "(default)",
    val notificationsDatabase: String = "(default)",
    /** Google endpoints; overridable so tests can point them at a mock server. */
    val identityToolkitBase: String = "https://identitytoolkit.googleapis.com",
    val secureTokenBase: String = "https://securetoken.googleapis.com",
    val firestoreBase: String = "https://firestore.googleapis.com",
) {
    /** `accounts:signInWithCustomToken` — the backend hands the app a custom token at login; there is no password sign-in. */
    val customTokenSignInUrl: String get() = "${identityToolkitBase.trimEnd('/')}/v1/accounts:signInWithCustomToken?key=$apiKey"
    val refreshUrl: String get() = "${secureTokenBase.trimEnd('/')}/v1/token?key=$apiKey"
    fun firestoreRoot(database: String): String = "${firestoreBase.trimEnd('/')}/v1/projects/$projectId/databases/$database/documents/"
}

/** Relative API paths, kept in one place so both the docs and the code agree. See docs/API_MAP.md. */
object Endpoints {
    // Auth — VERIFIED with the backend team (2026-10-09 answers)
    /** Global (no tenant prefix): `{email, password}` → the user's workspaces, most recently used first. Returns no token. */
    const val LOGIN_TENANTS = "auth/login/tenants/"
    /** Tenant-scoped: `{email, password}` → `{access (5 min), refresh (1 day), firebase_custom_token?, user}`. */
    const val LOGIN = "auth/login/"
    const val LOGIN_2FA = "auth/login/2fa/"
    /** Tenant-scoped: `{refresh}` → a NEW access and a NEW refresh (each refresh token works once). */
    const val TOKEN_REFRESH = "auth/token/refresh/"
    const val ME = "users/users/me/"
    const val MY_TENANTS = "users/me/tenants/"

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
    const val INVOICES = "sales_orders/invoices/"
    const val PAYMENTS = "sales_orders/payments/"
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
