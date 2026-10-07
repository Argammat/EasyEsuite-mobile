package com.easyesuite.core.repo

import com.easyesuite.core.Endpoints
import com.easyesuite.core.ItemTypes
import com.easyesuite.core.model.CreateItemRequest
import com.easyesuite.core.model.DashboardCard
import com.easyesuite.core.model.DashboardCards
import com.easyesuite.core.model.ImageUploadResponse
import com.easyesuite.core.model.InventoryItem
import com.easyesuite.core.model.ItemCondition
import com.easyesuite.core.model.ItemDetail
import com.easyesuite.core.model.ItemHistoryEntry
import com.easyesuite.core.model.ItemSummary
import com.easyesuite.core.model.OpeningStockRequest
import com.easyesuite.core.model.Page
import com.easyesuite.core.model.PageQuery
import com.easyesuite.core.model.TaxSchedule
import com.easyesuite.core.model.UpcLookupResponse
import com.easyesuite.core.model.UpcProduct
import com.easyesuite.core.model.Warehouse
import com.easyesuite.core.model.WarehouseStock
import com.easyesuite.core.net.ApiClient
import com.easyesuite.core.net.ApiException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Catalog, inventory levels, item creation. Endpoints and query parameters follow the backend
 * team's "Items & Inventory" notes (docs/API_MAP.md) — all VERIFIED unless a method says otherwise.
 */
class ItemsRepository(private val client: ApiClient) {

    // ---- catalog ------------------------------------------------------------------------------

    /** `items/items/` — every item type in one list. Filters: search, item_type, item_condition, is_available. */
    suspend fun catalog(
        search: String? = null,
        itemType: String? = null,
        conditionId: Int? = null,
        availableOnly: Boolean = false,
        page: PageQuery = PageQuery(),
    ): Page<ItemSummary> = client.get(
        Endpoints.ITEMS,
        page.asMap() + mapOf(
            "search" to search?.takeIf { it.isNotBlank() },
            "item_type" to itemType,
            "item_condition" to conditionId,
            "is_available" to if (availableOnly) true else null,
            "ordering" to "-created_date",
        ),
    )

    /**
     * Item detail from the type-specific endpoint (`inventory_items` / `kit_package_items` / `variant_items`).
     * Falls back to `items/items/{id}/` when the type is unknown or the typed endpoint 404s.
     */
    suspend fun item(id: Long, itemType: String? = ItemTypes.INVENTORY): ItemDetail {
        return try {
            client.get(Endpoints.itemDetail(id, itemType))
        } catch (e: ApiException.Http) {
            if (e.isNotFound) client.get("${Endpoints.ITEMS}$id/") else throw e
        }
    }

    /** `items/{type}/{id}/history/` — stock movements for the item (shape decoded leniently). */
    suspend fun history(id: Long, itemType: String?, page: PageQuery = PageQuery(limit = 50)): List<ItemHistoryEntry> {
        val raw: JsonElement = client.get(Endpoints.itemHistory(id, itemType), page.asMap())
        val rows: JsonArray = when (raw) {
            is JsonArray -> raw
            is JsonObject -> (raw["results"] as? JsonArray) ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return rows.mapNotNull { runCatching { client.json.decodeFromJsonElement(ItemHistoryEntry.serializer(), it) }.getOrNull() }
    }

    suspend fun conditions(): List<ItemCondition> {
        val page: Page<ItemCondition> = client.get(Endpoints.ITEM_CONDITIONS, mapOf("limit" to 100))
        return page.results
    }

    suspend fun taxSchedules(): List<TaxSchedule> {
        val page: Page<TaxSchedule> = client.get(Endpoints.TAX_SCHEDULES, mapOf("limit" to 100))
        return page.results
    }

    /**
     * Resolve a scanned barcode to catalog items. Tries the exact UPC filter first, then free text
     * (some tenants store UPCs with leading zeros stripped, so we also try the 12-digit form).
     */
    suspend fun findByBarcode(code: String): List<ItemSummary> {
        val candidates = linkedSetOf(code.trim())
        val digits = code.filter { it.isDigit() }
        if (digits.length == 13 && digits.startsWith("0")) candidates += digits.drop(1)   // EAN-13 → UPC-A
        if (digits.length == 12) candidates += "0$digits"                                  // UPC-A → EAN-13
        for (c in candidates) {
            val exact: Page<ItemSummary> = client.get(Endpoints.ITEMS, mapOf("upc_code" to c, "limit" to 10))
            if (exact.results.isNotEmpty()) return exact.results
        }
        val fuzzy: Page<ItemSummary> = client.get(Endpoints.ITEMS, mapOf("search" to code.trim(), "limit" to 10))
        return fuzzy.results.filter { item ->
            val upc = item.upcCode
            upc == null || candidates.any { c -> upc.endsWith(c.trimStart('0')) } || item.name.contains(code, ignoreCase = true)
        }
    }

    // ---- inventory ----------------------------------------------------------------------------

    /** `items/inventory_items/` — the web "Inventory" page (per item, all warehouses summed). */
    suspend fun inventory(
        search: String? = null,
        availableOnly: Boolean = false,
        itemId: Long? = null,
        page: PageQuery = PageQuery(),
    ): Page<InventoryItem> = client.get(
        Endpoints.INVENTORY_ITEMS,
        page.asMap() + mapOf(
            "search" to search?.takeIf { it.isNotBlank() },
            "is_available" to if (availableOnly) true else null,
            "item" to itemId,
        ),
    )

    suspend fun inventoryItem(id: Long): InventoryItem = client.get("${Endpoints.INVENTORY_ITEMS}$id/")

    /** `items/inventory_items/get_inventory_totalization/` — company-wide totals, rendered as cards. */
    suspend fun inventoryTotals(search: String? = null, availableOnly: Boolean = false): List<DashboardCard> {
        val raw: JsonElement = client.get(
            Endpoints.INVENTORY_TOTALS,
            mapOf("search" to search?.takeIf { it.isNotBlank() }, "is_available" to if (availableOnly) true else null),
        )
        return DashboardCards.fromJson(raw)
    }

    /** Stock of one item in every warehouse (`items/warehouse_inventory_items/?item=`). */
    suspend fun stockByWarehouse(itemId: Long): List<WarehouseStock> {
        val page: Page<WarehouseStock> = client.get(Endpoints.WAREHOUSE_STOCK, mapOf("item" to itemId, "limit" to 100))
        return page.results.sortedByDescending { it.onHand }
    }

    /** Everything in one warehouse (`?warehouse_id=`), optionally only rows with stock. */
    suspend fun stockInWarehouse(
        warehouseId: Int,
        search: String? = null,
        availableOnly: Boolean = false,
        page: PageQuery = PageQuery(),
    ): Page<WarehouseStock> = client.get(
        Endpoints.WAREHOUSE_STOCK,
        page.asMap() + mapOf(
            "warehouse_id" to warehouseId,
            "search" to search?.takeIf { it.isNotBlank() },
            "is_available" to if (availableOnly) true else null,
        ),
    )

    /** `items/warehouse_inventory_items/totalization/` for one warehouse (or all when null). */
    suspend fun warehouseTotals(warehouseId: Int?, availableOnly: Boolean = false): List<DashboardCard> {
        val raw: JsonElement = client.get(
            Endpoints.WAREHOUSE_STOCK_TOTALS,
            mapOf("warehouse_id" to warehouseId, "is_available" to if (availableOnly) true else null),
        )
        return DashboardCards.fromJson(raw)
    }

    suspend fun warehouses(activeOnly: Boolean = true): List<Warehouse> {
        val page: Page<Warehouse> = client.get(Endpoints.WAREHOUSES, mapOf("limit" to 1000))
        return page.results.filter { !activeOnly || it.isActive }.sortedWith(compareByDescending<Warehouse> { it.isDefault }.thenBy { it.name })
    }

    // ---- create -------------------------------------------------------------------------------

    /** External product-database lookup (`items/get_item_upc/{upc}/`) to prefill the "new item" form. */
    suspend fun lookupUpc(upc: String): UpcProduct? {
        val res: UpcLookupResponse = client.get(Endpoints.upcLookup(upc))
        return res.data?.takeIf { !it.title.isNullOrBlank() || it.images.isNotEmpty() }
    }

    suspend fun createItem(request: CreateItemRequest): ItemDetail =
        client.post(Endpoints.INVENTORY_ITEMS, request)

    /** Partial update; `fields` are raw API keys (e.g. "reorder_point"). */
    suspend fun updateItem(id: Long, fields: JsonObject): ItemDetail =
        client.patch("${Endpoints.INVENTORY_ITEMS}$id/", fields)

    /**
     * Opening stock for a brand-new item: creates the item's warehouse inventory record
     * (`POST items/warehouse_inventory_items/`). Body ASSUMED — see docs/API_MAP.md.
     */
    suspend fun createOpeningStock(itemId: Long, warehouseId: Int, quantity: Int): WarehouseStock =
        client.post(Endpoints.WAREHOUSE_STOCK, OpeningStockRequest(itemId, warehouseId, quantity))

    /** `POST files/images/` (multipart field `image`); the response's `image` field is the URL. */
    suspend fun uploadImage(bytes: ByteArray, fileName: String = "photo.jpg", mimeType: String = "image/jpeg"): String? {
        val res: ImageUploadResponse = client.upload(Endpoints.IMAGE_UPLOAD, "image", fileName, mimeType, bytes)
        return res.resolvedUrl
    }

    suspend fun globalSearch(query: String): JsonElement = client.get(Endpoints.GLOBAL_SEARCH, mapOf("query" to query))
}
