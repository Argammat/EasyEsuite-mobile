package com.easyesuite.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ItemImage(@SerialName("image_url") val imageUrl: String? = null)

/** Row of `items/items/` (catalog, all item types). */
@Serializable
data class ItemSummary(
    val id: Long,
    val name: String = "",
    @SerialName("upc_code") val upcCode: String? = null,
    @SerialName("marketplace_title") val marketplaceTitle: String? = null,
    @SerialName("marketplace_brand") val brand: String? = null,
    @SerialName("item_type") val itemType: String? = null,
    @SerialName("item_condition_name") val conditionName: String? = null,
    @SerialName("total_quantity_on_hand") val onHand: Int? = null,
    @SerialName("average_cost") val averageCost: Money? = null,
    @SerialName("last_purchase_price") val lastPurchasePrice: Money? = null,
    @SerialName("last_selling_price") val lastSellingPrice: Money? = null,
    @SerialName("purchase_price") val purchasePrice: Money? = null,
    @SerialName("total_value") val totalValue: Money? = null,
    @SerialName("item_image1_url") val image1: String? = null,
    @SerialName("item_images") val images: List<ItemImage> = emptyList(),
    @SerialName("created_date") val createdDate: String? = null,
    @SerialName("modified_date") val modifiedDate: String? = null,
) {
    val primaryImage: String? get() = images.firstOrNull()?.imageUrl ?: image1
    val displayTitle: String get() = marketplaceTitle?.takeIf { it.isNotBlank() } ?: name
}

@Serializable
data class MarketplacePrice(
    val id: Long? = null,
    val price: Money? = null,
    val marketplace: Int? = null,
    @SerialName("price_currency") val currency: String? = null,
)

/** `items/items/{id}/` — the parts of the (very large) record the app shows. */
@Serializable
data class ItemDetail(
    val id: Long,
    val name: String = "",
    @SerialName("upc_code") val upcCode: String? = null,
    @SerialName("marketplace_title") val marketplaceTitle: String? = null,
    @SerialName("marketplace_brand") val brand: String? = null,
    @SerialName("marketplace_platform") val platform: String? = null,
    val manufacturer: String? = null,
    val description: String? = null,
    @SerialName("sales_description") val salesDescription: String? = null,
    @SerialName("purchase_description") val purchaseDescription: String? = null,
    @SerialName("gaming_name") val gamingName: String? = null,
    @SerialName("item_type") val itemType: String? = null,
    @SerialName("item_condition") val conditionId: Int? = null,
    @SerialName("item_condition_name") val conditionName: String? = null,
    @SerialName("total_quantity_on_hand") val onHand: Int? = null,
    @SerialName("average_cost") val averageCost: Money? = null,
    @SerialName("last_purchase_price") val lastPurchasePrice: Money? = null,
    @SerialName("last_selling_price") val lastSellingPrice: Money? = null,
    @SerialName("purchase_price") val purchasePrice: Money? = null,
    @SerialName("total_value") val totalValue: Money? = null,
    val weight: String? = null,
    @SerialName("weight_unit") val weightUnit: String? = null,
    val length: String? = null,
    val width: String? = null,
    val height: String? = null,
    @SerialName("dimension_unit") val dimensionUnit: String? = null,
    val taxable: Boolean? = null,
    @SerialName("item_image1_url") val image1: String? = null,
    @SerialName("item_images") val images: List<ItemImage> = emptyList(),
    @SerialName("marketplace_pricing") val marketplacePricing: List<MarketplacePrice> = emptyList(),
    @SerialName("amazon_sku") val amazonSku: String? = null,
    @SerialName("walmart_sku") val walmartSku: String? = null,
    @SerialName("ebay_sku") val ebaySku: String? = null,
    @SerialName("target_sku") val targetSku: String? = null,
    @SerialName("best_buy_sku") val bestBuySku: String? = null,
    @SerialName("macys_sku") val macysSku: String? = null,
    @SerialName("mercado_sku") val mercadoSku: String? = null,
    @SerialName("amazon_category") val amazonCategory: String? = null,
    @SerialName("walmart_category") val walmartCategory: String? = null,
    @SerialName("ebay_category") val ebayCategory: String? = null,
    @SerialName("target_category") val targetCategory: String? = null,
    @SerialName("best_buy_category") val bestBuyCategory: String? = null,
    @SerialName("macys_category") val macysCategory: String? = null,
    @SerialName("created_date") val createdDate: String? = null,
    @SerialName("modified_date") val modifiedDate: String? = null,
) {
    val allImages: List<String>
        get() = (images.mapNotNull { it.imageUrl } + listOfNotNull(image1)).distinct()

    val displayTitle: String get() = marketplaceTitle?.takeIf { it.isNotBlank() } ?: name

    /** (marketplace label, sku) pairs that are set. */
    val marketplaceSkus: List<Pair<String, String>>
        get() = listOfNotNull(
            amazonSku?.let { "Amazon" to it },
            walmartSku?.let { "Walmart" to it },
            ebaySku?.let { "eBay" to it },
            targetSku?.let { "Target" to it },
            bestBuySku?.let { "Best Buy" to it },
            macysSku?.let { "Macy's" to it },
            mercadoSku?.let { "Mercado Libre" to it },
        )

    val dimensionsText: String?
        get() {
            val l = length ?: return null
            val w = width ?: return null
            val h = height ?: return null
            return "$l × $w × $h ${dimensionUnit ?: ""}".trim()
        }
}

/** Row of `items/inventory_items/` — the web "Inventory" page. */
@Serializable
data class InventoryItem(
    val id: Long,
    val name: String = "",
    @SerialName("upc_code") val upcCode: String? = null,
    @SerialName("marketplace_title") val marketplaceTitle: String? = null,
    @SerialName("marketplace_brand") val brand: String? = null,
    @SerialName("item_type") val itemType: String? = null,
    @SerialName("available_quantity") val available: Int? = null,
    @SerialName("total_quantity_on_hand") val onHand: Int? = null,
    @SerialName("total_quantity_on_order") val onOrder: Int? = null,
    @SerialName("total_quantity_on_purchase") val onPurchase: Int? = null,
    @SerialName("reorder_point") val reorderPoint: Int? = null,
    @SerialName("average_cost") val averageCost: Money? = null,
    @SerialName("last_purchase_price") val lastPurchasePrice: Money? = null,
    @SerialName("selling_price") val sellingPrice: Money? = null,
    @SerialName("total_value") val totalValue: Money? = null,
    @SerialName("item_image1_url") val image1: String? = null,
    @SerialName("item_images") val images: List<ItemImage> = emptyList(),
    val serialized: Boolean? = null,
    @SerialName("created_date") val createdDate: String? = null,
) {
    val primaryImage: String? get() = images.firstOrNull()?.imageUrl ?: image1
    val displayTitle: String get() = marketplaceTitle?.takeIf { it.isNotBlank() } ?: name
    val belowReorderPoint: Boolean
        get() = reorderPoint != null && reorderPoint > 0 && (available ?: 0) < reorderPoint
}

/** Row of `items/warehouse_inventory_items/` — one item in one warehouse. */
@Serializable
data class WarehouseStock(
    val id: Long,
    val name: String = "",
    @SerialName("upc_code") val upcCode: String? = null,
    val warehouse: String = "",
    @SerialName("inventory_item_id") val inventoryItemId: Long? = null,
    @SerialName("quantity_on_hand") val onHand: Int = 0,
    @SerialName("quantity_available") val available: Int = 0,
    @SerialName("quantity_committed") val committed: Int = 0,
    @SerialName("quantity_on_order") val onOrder: Int = 0,
    @SerialName("quantity_in_transit") val inTransit: Int = 0,
    @SerialName("quantity_on_purchase_order") val onPurchaseOrder: Int = 0,
    @SerialName("quantity_on_fulfill") val onFulfill: Int = 0,
    @SerialName("quantity_back_ordered") val backOrdered: Int = 0,
    @SerialName("reorder_point") val reorderPoint: Int? = null,
    @SerialName("preferred_stock_level") val preferredStockLevel: Int? = null,
    @SerialName("average_cost") val averageCost: Money? = null,
    @SerialName("last_purchase_price") val lastPurchasePrice: Money? = null,
    @SerialName("selling_price_new") val sellingPrice: Money? = null,
    @SerialName("total_val_new") val totalValue: Money? = null,
    @SerialName("marketplace_title") val marketplaceTitle: String? = null,
    @SerialName("item_image1_url") val image1: String? = null,
    @SerialName("item_images") val images: List<ItemImage> = emptyList(),
    @SerialName("item_condition_name") val conditionName: String? = null,
) {
    val primaryImage: String? get() = images.firstOrNull()?.imageUrl ?: image1
}

@Serializable
data class Warehouse(
    val id: Int,
    val name: String = "",
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("warehouse_type") val warehouseType: String? = null,
    val priority: Int? = null,
)

/** `items/get_item_upc/?upc=` — external product-database lookup used to prefill a new item. */
@Serializable
data class UpcLookupResponse(val data: UpcProduct? = null)

@Serializable
data class UpcProduct(
    val upc: String? = null,
    val title: String? = null,
    val description: String? = null,
    val brand: String? = null,
    val color: String? = null,
    val size: String? = null,
    val weight: String? = null,
    val dimension: String? = null,
    val images: List<String> = emptyList(),
)

@Serializable
data class ItemCondition(val id: Int, val name: String = "")

@Serializable
data class TaxSchedule(val id: Int, val name: String = "", @SerialName("is_default") val isDefault: Boolean? = null)

/** One row of `marketplace_pricing` on create/update. */
@Serializable
data class MarketplacePriceRequest(
    val marketplace: Int,
    /** Decimal string, e.g. "9.99". */
    val price: String,
    @SerialName("price_currency") val priceCurrency: String = "USD",
)

/**
 * Body for `POST items/inventory_items/` (VERIFIED against the web app source, Oct 2026).
 *
 * Rules from the backend team:
 *  - the seven `*_marketplace` objects are required — send `{}` for the ones you don't configure
 *    (Amazon wants its two FBA flags present);
 *  - `marketplace_pricing` is required — `[]` or `{marketplace, price, price_currency}` rows;
 *  - empty optional fields must be omitted/null, never `""` (`purchase_price: ""` is a 400);
 *  - `name` is unique per item type (400 "An item with the same name and type already exists").
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CreateItemRequest(
    /** Item/SKU name — required, the ERP's primary identifier. */
    val name: String,
    @SerialName("upc_code") val upcCode: String? = null,
    @SerialName("marketplace_title") val marketplaceTitle: String? = null,
    val description: String? = null,
    @SerialName("marketplace_brand") val brand: String? = null,
    @SerialName("marketplace_platform") val platform: String? = null,
    val manufacturer: String? = null,
    /** Cost (the web form labels it "Price" under Commercial Summary). Decimal string. */
    @SerialName("purchase_price") val purchasePrice: String? = null,
    val weight: String? = null,
    @EncodeDefault @SerialName("weight_unit") val weightUnit: String = "Pounds",
    val length: String? = null,
    val width: String? = null,
    val height: String? = null,
    @SerialName("dimension_unit") val dimensionUnit: String? = null,
    @SerialName("reorder_point") val reorderPoint: Int? = null,
    /** Id from `items/item_conditions/`. */
    @SerialName("item_condition") val conditionId: Int? = null,
    /** Id from `items/tax_schedules/`. */
    @SerialName("tax_schedule") val taxSchedule: Int? = null,
    @SerialName("sales_description") val salesDescription: String? = null,
    @SerialName("purchase_description") val purchaseDescription: String? = null,
    @EncodeDefault @SerialName("costing_method") val costingMethod: String = "AVG",
    @EncodeDefault @SerialName("cost_estimation_type") val costEstimationType: String = "AVG",
    @EncodeDefault @SerialName("calculate_quantity_discounts_type") val quantityDiscountType: String = "BLQ",
    @EncodeDefault val taxable: Boolean = true,
    @EncodeDefault @SerialName("item_type") val itemType: String = "INV",
    @EncodeDefault @SerialName("item_images") val images: List<ItemImage> = emptyList(),
    @EncodeDefault @SerialName("marketplace_pricing") val marketplacePricing: List<MarketplacePriceRequest> = emptyList(),
    // Required marketplace configuration blocks — empty unless the user configures a channel.
    @EncodeDefault @SerialName("amazon_marketplace") val amazonMarketplace: JsonObject = AMAZON_DEFAULT,
    @EncodeDefault @SerialName("ebay_marketplace") val ebayMarketplace: JsonObject = EMPTY,
    @EncodeDefault @SerialName("walmart_marketplace") val walmartMarketplace: JsonObject = EMPTY,
    @EncodeDefault @SerialName("target_marketplace") val targetMarketplace: JsonObject = EMPTY,
    @EncodeDefault @SerialName("best_buy_marketplace") val bestBuyMarketplace: JsonObject = EMPTY,
    @EncodeDefault @SerialName("macys_marketplace") val macysMarketplace: JsonObject = EMPTY,
    @EncodeDefault @SerialName("mercado_marketplace") val mercadoMarketplace: JsonObject = EMPTY,
) {
    companion object {
        val EMPTY = JsonObject(emptyMap())
        val AMAZON_DEFAULT = JsonObject(
            mapOf("amazon_fba_account_1" to JsonPrimitive(false), "amazon_fba_account_2" to JsonPrimitive(false)),
        )
    }
}

/** One row of `items/{type}/{id}/history/` — shape not captured yet, so every field is optional. */
@Serializable
data class ItemHistoryEntry(
    val id: Long? = null,
    val date: String? = null,
    @SerialName("created_date") val createdDate: String? = null,
    val type: String? = null,
    @SerialName("transaction_type") val transactionType: String? = null,
    val action: String? = null,
    val quantity: Double? = null,
    @SerialName("quantity_change") val quantityChange: Double? = null,
    val warehouse: String? = null,
    @SerialName("warehouse_name") val warehouseName: String? = null,
    val reference: String? = null,
    val number: String? = null,
    val memo: String? = null,
    val description: String? = null,
    @SerialName("added_by") val addedBy: String? = null,
    val user: String? = null,
) {
    val timestamp: String? get() = date ?: createdDate
    val label: String get() = (transactionType ?: type ?: action ?: "Movement").replace('_', ' ').replaceFirstChar { it.uppercase() }
    val delta: Double? get() = quantityChange ?: quantity
    val detail: String
        get() = listOfNotNull(warehouseName ?: warehouse, reference ?: number, memo ?: description, addedBy ?: user)
            .filter { it.isNotBlank() }.joinToString(" · ")
}

/** Body for `POST items/warehouse_inventory_items/` (initial stocking). ASSUMED. */
@Serializable
data class OpeningStockRequest(
    @SerialName("inventory_item") val inventoryItem: Long,
    val warehouse: Int,
    @SerialName("quantity_on_hand") val quantityOnHand: Int,
)

@Serializable
data class ImageUploadResponse(
    @SerialName("image_url") val imageUrl: String? = null,
    val url: String? = null,
    val image: String? = null,
) {
    val resolvedUrl: String? get() = imageUrl ?: url ?: image
}
