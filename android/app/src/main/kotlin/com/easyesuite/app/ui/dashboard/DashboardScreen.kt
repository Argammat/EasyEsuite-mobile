package com.easyesuite.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.easyesuite.app.di.AppContainer
import com.easyesuite.app.ui.Routes
import com.easyesuite.app.ui.common.ChipRow
import com.easyesuite.app.ui.common.SectionTitle
import com.easyesuite.app.ui.common.Thumb
import com.easyesuite.app.ui.common.userMessage
import com.easyesuite.core.model.DashboardCard
import com.easyesuite.core.model.OrderStatus
import com.easyesuite.core.model.SalesByItemRow
import com.easyesuite.core.model.SeriesPoint
import com.easyesuite.core.model.ShipmentStatus
import com.easyesuite.core.util.DateRange
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardUi(
    val range: DateRange = DateRange.today(),
    val loading: Boolean = true,
    val ordersInRange: Int? = null,
    val pendingFulfillment: Int? = null,
    val toShip: Int? = null,
    val onHold: Int? = null,
    val exceptions: Int? = null,
    val cards: List<DashboardCard> = emptyList(),
    val revenueSeries: List<SeriesPoint> = emptyList(),
    val topItems: List<SalesByItemRow> = emptyList(),
    val errors: List<String> = emptyList(),
)

class DashboardViewModel(private val graph: AppContainer.Graph) : ViewModel() {
    val ui = MutableStateFlow(DashboardUi())

    init { load() }

    fun setRange(r: DateRange) { ui.update { it.copy(range = r) }; load() }

    fun load() {
        val range = ui.value.range
        ui.update { it.copy(loading = true, errors = emptyList()) }
        viewModelScope.launch {
            val errors = mutableListOf<String>()
            suspend fun <T> safe(label: String, block: suspend () -> T): T? =
                runCatching { block() }.onFailure { errors += "$label: ${it.userMessage()}" }.getOrNull()

            val orders = async { safe("Orders") { graph.orders.count(range = range) } }
            val pending = async { safe("Pending") { graph.orders.count(status = OrderStatus.PENDING_FULFILLMENT) } }
            val toShip = async { safe("To ship") { graph.shipping.count(ShipmentStatus.TO_SHIP) } }
            val onHold = async { safe("On hold") { graph.shipping.count(ShipmentStatus.ON_HOLD) } }
            val exceptions = async { safe("Exceptions") { graph.shipping.count(ShipmentStatus.EXCEPTIONS) } }
            val cards = async { safe("Overview") { graph.reports.overviewCards(range) } }
            val series = async { safe("Revenue") { graph.reports.ordersByMarketplaceOverTime(range) } }
            val top = async { safe("Top items") { graph.reports.salesByItem(range, limit = 5).results } }

            ui.update {
                it.copy(
                    loading = false,
                    ordersInRange = orders.await(), pendingFulfillment = pending.await(), toShip = toShip.await(), onHold = onHold.await(), exceptions = exceptions.await(),
                    cards = cards.await() ?: emptyList(), revenueSeries = series.await() ?: emptyList(), topItems = top.await() ?: emptyList(),
                    errors = errors,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(graph: AppContainer.Graph, nav: NavHostController) {
    val vm: DashboardViewModel = viewModel { DashboardViewModel(graph) }
    val ui by vm.ui.collectAsState()
    val presets = remember { DateRange.presets.map { it() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(graph.tenant.replaceFirstChar { it.uppercase() }) },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.ASSISTANT) }) { Icon(Icons.Default.SmartToy, contentDescription = "Assistant") }
                    IconButton(onClick = { nav.navigate(Routes.scanner()) }) { Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan") }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = ui.loading, onRefresh = vm::load, modifier = Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ChipRow(options = presets.map { it.label to it.label }, selected = ui.range.label, onSelect = { l -> presets.firstOrNull { it.label == l }?.let(vm::setRange) })
                Spacer(Modifier.height(8.dp))

                // Operational tiles — these always work because they are plain list counts.
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("Orders · ${ui.range.label.lowercase()}", ui.ordersInRange, Modifier.weight(1f)) { nav.navigate(Routes.ORDERS) }
                    Tile("Pending fulfillment", ui.pendingFulfillment, Modifier.weight(1f)) { nav.navigate(Routes.ORDERS) }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("To ship", ui.toShip, Modifier.weight(1f)) { nav.navigate(Routes.ORDERS) }
                    Tile("On hold", ui.onHold, Modifier.weight(1f)) { nav.navigate(Routes.ORDERS) }
                    Tile("Exceptions", ui.exceptions, Modifier.weight(1f), alert = (ui.exceptions ?: 0) > 0) { nav.navigate(Routes.ORDERS) }
                }

                // Web-dashboard cards for the period (whatever the backend returns).
                if (ui.cards.isNotEmpty()) {
                    SectionTitle("Overview", Modifier.padding(horizontal = 16.dp))
                    LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.height(((ui.cards.size + 1) / 2 * 84).dp).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), userScrollEnabled = false) {
                        items(ui.cards.size) { i ->
                            val c = ui.cards[i]
                            Card { Column(Modifier.padding(10.dp).height(56.dp)) { Text(c.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(c.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                        }
                    }
                }

                if (ui.revenueSeries.isNotEmpty()) {
                    SectionTitle("Order value by marketplace", Modifier.padding(horizontal = 16.dp))
                    BarChart(ui.revenueSeries, Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(160.dp))
                }

                SectionTitle("Top sellers · ${ui.range.label.lowercase()}", Modifier.padding(horizontal = 16.dp))
                if (ui.topItems.isEmpty() && !ui.loading) Text("No invoiced sales in this period.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp))
                ui.topItems.forEachIndexed { i, r ->
                    Row(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.item(r.itemId)) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(24.dp))
                        Thumb(r.primaryImage, size = 44); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) { Text(r.itemName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${r.units} sold" + (r.profit?.let { " · profit ${it.formatted()}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                        Text(r.revenue.formatted(), fontWeight = FontWeight.SemiBold)
                    }
                }

                ui.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: Int?, modifier: Modifier = Modifier, alert: Boolean = false, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value?.let { "%,d".format(it) } ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Tiny dependency-free bar chart: one bar per label, stacked by series when present. */
@Composable
fun BarChart(points: List<SeriesPoint>, modifier: Modifier = Modifier) {
    val byLabel = points.groupBy { it.label }
    val labels = byLabel.keys.toList().takeLast(14)
    val max = labels.maxOfOrNull { l -> byLabel[l]!!.sumOf { it.value } }?.takeIf { it > 0 } ?: 1.0
    val palette = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.outline)
    val seriesNames = points.mapNotNull { it.series }.distinct()
    Column(modifier) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            labels.forEach { l ->
                val parts = byLabel[l]!!
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                    parts.forEach { p ->
                        val frac = (p.value / max).toFloat().coerceIn(0f, 1f)
                        val color = palette[(seriesNames.indexOf(p.series).takeIf { it >= 0 } ?: 0) % palette.size]
                        Box(Modifier.fillMaxWidth().fillMaxHeight(frac).clip(RoundedCornerShape(2.dp)).background(color))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(labels.firstOrNull()?.takeLast(5) ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(labels.lastOrNull()?.takeLast(5) ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        if (seriesNames.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            seriesNames.take(5).forEachIndexed { i, n -> Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(2.dp)).background(palette[i % palette.size])); Spacer(Modifier.width(4.dp)); Text(n, style = MaterialTheme.typography.labelSmall) } }
        }
    }
}
