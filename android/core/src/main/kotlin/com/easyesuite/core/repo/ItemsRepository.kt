package com.easyesuite.core.repo

import com.easyesuite.core.Endpoints
import com.easyesuite.core.model.CreateItemRequest
import com.easyesuite.core.model.ImageUploadResponse
import com.easyesuite.core.model.InventoryItem
import com.easyesuite.core.model.ItemDetail
import com.easyesuite.core.model.ItemSummary
import com.easyesuite.core.model.OpeningStockRequest
import com.easyesuite.core.model.Page
import com.easyesuite.core.model.PageQuery
import com.easyesuite.core.model.UpcLookupResponse
import com.easyesuite.core.model.UpcProduct
import com.easyesuite.core.model.Warehouse
import com.easyesuite.core.model.WarehouseStock
import com.easyesuite.core.net.ApiClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Catalog, inventory levels, item creation. */
class ItemsRepository(private val client: ApiClient) {

    // ---- catalog ------------------------------------------------------------------------------

    suspend fun catalog(search: String? = null, page: PageQuery = PageQuery()): Page<ItemSummary> =
        client.get(Endpoints.ITEMS, page.asMap() + mapOf("search" to search?.takeIf { it.isNotBlank() }, "ordering" to "-created_date"))

    suspend fun item(id: Long): ItemDetail = client.get("${Endpoints.ITEMS}$id/")

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

    suspend fun inventory(
        search: String? = null,
        availableOnly: Boolean = false,
        page: PageQuery = PageQuery(),
    ): Page<InventoryItem> = client.get(
        Endpoints.INVENTORY_ITEMS,
        page.asMap() + mapOf(
            "search" to search?.takeIf { it.isNotBlank() },
            "is_available" to if (availableOnly) true else null,
        ),
    )

    suspend fun inventoryItem(id: Long): InventoryItem = client.get("${Endpoints.INVENTORY_ITEMS}$id/")

    suspend fun stockByWarehouse(itemId: Long): List<WarehouseStock> {
        val page: Page<WarehouseStock> = client.get(Endpoints.WAREHOUSE_STOCK, mapOf("item" to itemId, "limit" to 100))
        return page.results.sortedByDescending { it.onHand }
    }

    suspend fun stockInWarehouse(warehouseId: Int, search: String? = null, page: PageQuery = PageQuery()): Page<WarehouseStock> =
        client.get(Endpoints.WAREHOUSE_STOCK, page.asMap() + mapOf("warehouse" to warehouseId, "search" to search?.takeIf { it.isNotBlank() }))

    suspend fun warehouses(activeOnly: Boolean = true): List<Warehouse> {
        val page: Page<Warehouse> = client.get(Endpoints.WAREHOUSES, mapOf("limit" to 200))
        return page.results.filter { !activeOnly || it.isActive }.sortedWith(compareByDescending<Warehouse> { it.isDefault }.thenBy { it.name })
    }

    // ---- create -------------------------------------------------------------------------------

    /** External product-database lookup to prefill the "new item" form from a barcode. */
    suspend fun lookupUpc(upc: String): UpcProduct? {
        val res: UpcLookupResponse = client.get(Endpoints.UPC_LOOKUP, mapOf("upc" to upc.trim()))
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

    suspend fun uploadImage(bytes: ByteArray, fileName: String = "photo.jpg", mimeType: String = "image/jpeg"): String? {
        val res: ImageUploadResponse = client.upload(Endpoints.IMAGE_UPLOAD, "image", fileName, mimeType, bytes)
        return res.resolvedUrl
    }

    suspend fun globalSearch(query: String): JsonElement = client.get(Endpoints.GLOBAL_SEARCH, mapOf("query" to query))
}
