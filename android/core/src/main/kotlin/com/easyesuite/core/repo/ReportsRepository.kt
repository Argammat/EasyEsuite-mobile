package com.easyesuite.core.repo

import com.easyesuite.core.Endpoints
import com.easyesuite.core.model.DashboardCard
import com.easyesuite.core.model.DashboardCards
import com.easyesuite.core.model.Page
import com.easyesuite.core.model.SalesByItemRow
import com.easyesuite.core.model.SalesOrdering
import com.easyesuite.core.model.SeriesParser
import com.easyesuite.core.model.SeriesPoint
import com.easyesuite.core.net.ApiClient
import com.easyesuite.core.util.DateRange
import kotlinx.serialization.json.JsonElement

class ReportsRepository(private val client: ApiClient) {

    /** Recognised sales per item (from invoices — never from sales orders, which over-count). */
    suspend fun salesByItem(
        range: DateRange,
        ordering: String = SalesOrdering.REVENUE,
        limit: Int = 25,
        offset: Int = 0,
        search: String? = null,
        splitByMarketplace: Boolean = false,
        itemId: Long? = null,
    ): Page<SalesByItemRow> = client.get(
        Endpoints.INVOICE_ANALYTICS,
        range.toQuery() + mapOf(
            "split_by_marketplace" to splitByMarketplace,
            "ordering" to ordering,
            "limit" to limit,
            "offset" to offset,
            "search" to search?.takeIf { it.isNotBlank() },
            "item" to itemId,
        ),
    )

    /** The same cards the web dashboard shows for the period. */
    suspend fun overviewCards(range: DateRange): List<DashboardCard> {
        val raw: JsonElement = client.get(Endpoints.OVERVIEW_CARDS, range.toQuery())
        return DashboardCards.fromJson(raw)
    }

    suspend fun bestSellers(range: DateRange, limit: Int = 5): JsonElement =
        client.get(Endpoints.BEST_SELLERS, range.toQuery() + mapOf("limit" to limit))

    suspend fun ordersByMarketplaceOverTime(range: DateRange): List<SeriesPoint> =
        SeriesParser.parse(client.get<JsonElement>(Endpoints.MARKETPLACE_ORDER_INTERVALS, range.toQuery()))

    suspend fun unitsSoldOverTime(range: DateRange): List<SeriesPoint> =
        SeriesParser.parse(client.get<JsonElement>(Endpoints.UNITS_SOLD_INTERVALS, range.toQuery()))

    suspend fun carrierSpend(range: DateRange): List<SeriesPoint> =
        SeriesParser.parse(client.get<JsonElement>(Endpoints.CARRIER_SPEND, range.toQuery()))

    suspend fun costPerShipment(range: DateRange): JsonElement = client.get(Endpoints.COST_PER_SHIPMENT, range.toQuery())
}
