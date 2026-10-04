package com.easyesuite.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Row of `sales_orders/invoice_items/invoice_analytics/` — recognised sales per item. */
@Serializable
data class SalesByItemRow(
    @SerialName("item_id") val itemId: Long,
    @SerialName("item_name") val itemName: String = "",
    @SerialName("item_upc") val itemUpc: String? = null,
    @SerialName("total_amount") val revenue: Money = Money.ZERO,
    @SerialName("total_quantity") val unitsSold: Double = 0.0,
    @SerialName("total_per_qty") val averagePrice: Double? = null,
    @SerialName("total_profit") val profit: Money? = null,
    val marketplace: Int? = null,
    @SerialName("item_img_url_1") val image1: String? = null,
    @SerialName("item_images") val images: List<ItemImage> = emptyList(),
) {
    val primaryImage: String? get() = images.firstOrNull()?.imageUrl ?: image1
    val units: Int get() = unitsSold.toInt()
    val marginPercent: Double?
        get() = profit?.let { p -> if (revenue.isZero) null else p.amount / revenue.amount * 100.0 }
}

object SalesOrdering {
    const val REVENUE = "-total_amount"
    const val UNITS = "-total_quantity"
    const val PROFIT = "-total_profit"
}

/**
 * The dashboard endpoints return provider-shaped JSON. We flatten whatever comes back into
 * (label, value) cards so the UI never breaks when the backend adds a field.
 */
data class DashboardCard(val key: String, val label: String, val value: String, val numeric: Double?)

object DashboardCards {
    fun fromJson(element: JsonElement?): List<DashboardCard> {
        val obj = when (element) {
            is JsonObject -> element
            is JsonArray -> element.firstOrNull() as? JsonObject
            else -> null
        } ?: return emptyList()
        val out = mutableListOf<DashboardCard>()
        for ((key, value) in obj) {
            when (value) {
                is JsonPrimitive -> out += DashboardCard(key, humanize(key), display(value), value.contentOrNull?.toDoubleOrNull())
                is JsonObject -> {
                    // {"label":..,"value":..} or {"total":..,"count":..}
                    val v = value["value"] ?: value["total"] ?: value["amount"] ?: value["count"]
                    if (v is JsonPrimitive) {
                        val label = (value["label"] as? JsonPrimitive)?.contentOrNull ?: humanize(key)
                        out += DashboardCard(key, label, display(v), v.contentOrNull?.toDoubleOrNull())
                    } else {
                        for ((k2, v2) in value) if (v2 is JsonPrimitive) {
                            out += DashboardCard("$key.$k2", humanize(key) + " · " + humanize(k2), display(v2), v2.contentOrNull?.toDoubleOrNull())
                        }
                    }
                }
                else -> Unit
            }
        }
        return out
    }

    private fun display(p: JsonPrimitive): String {
        val c = p.contentOrNull ?: return "—"
        val d = c.toDoubleOrNull() ?: return c
        return if (d == Math.floor(d) && Math.abs(d) < 1e12) String.format(java.util.Locale.US, "%,d", d.toLong())
        else String.format(java.util.Locale.US, "%,.2f", d)
    }

    fun humanize(key: String): String =
        key.replace('_', ' ').replace(Regex("([a-z])([A-Z])"), "$1 $2").trim().replaceFirstChar { it.uppercase() }
}

/** A single (x, y) point for the simple bar/line charts on the dashboard. */
data class SeriesPoint(val label: String, val value: Double, val series: String? = null)

object SeriesParser {
    /**
     * Accepts the common shapes:
     *  [{"date":"2026-10-01","total":123}, ...]
     *  {"labels":[...], "data":[...]}  /  {"labels":[...], "datasets":[{"label":..,"data":[..]}]}
     *  {"Amazon":[{"date":..,"total":..}], "eBay":[...]}
     */
    fun parse(element: JsonElement?): List<SeriesPoint> {
        if (element == null) return emptyList()
        return when (element) {
            is JsonArray -> element.mapNotNull { pointOf(it) }
            is JsonObject -> {
                val labels = (element["labels"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: "" }
                when {
                    labels != null && element["datasets"] is JsonArray -> (element["datasets"] as JsonArray).flatMap { ds ->
                        val o = ds as? JsonObject ?: return@flatMap emptyList()
                        val name = (o["label"] as? JsonPrimitive)?.contentOrNull
                        (o["data"] as? JsonArray)?.mapIndexedNotNull { i, v ->
                            (v as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.let { SeriesPoint(labels.getOrElse(i) { "$i" }, it, name) }
                        } ?: emptyList()
                    }
                    labels != null && element["data"] is JsonArray -> (element["data"] as JsonArray).mapIndexedNotNull { i, v ->
                        (v as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.let { SeriesPoint(labels.getOrElse(i) { "$i" }, it) }
                    }
                    else -> element.entries.flatMap { (series, v) ->
                        if (v is JsonArray) v.mapNotNull { pointOf(it)?.copy(series = series) } else emptyList()
                    }
                }
            }
            else -> emptyList()
        }
    }

    private fun pointOf(e: JsonElement): SeriesPoint? {
        val o = e as? JsonObject ?: return null
        val label = listOf("date", "label", "name", "interval", "day", "marketplace", "x")
            .firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.contentOrNull } ?: return null
        val value = listOf("total", "total_amount", "value", "amount", "quantity", "count", "y")
            .firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.let { p -> Money.parse(p)?.amount } } ?: return null
        val series = (o["marketplace_name"] as? JsonPrimitive)?.contentOrNull ?: (o["series"] as? JsonPrimitive)?.contentOrNull
        return SeriesPoint(label, value, series)
    }
}
