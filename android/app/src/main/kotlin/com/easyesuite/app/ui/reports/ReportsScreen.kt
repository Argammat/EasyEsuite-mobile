package com.easyesuite.app.ui.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.easyesuite.app.di.AppContainer
import com.easyesuite.app.ui.Routes
import com.easyesuite.app.ui.common.ChipRow
import com.easyesuite.app.ui.common.PagedList
import com.easyesuite.app.ui.common.PagedListViewModel
import com.easyesuite.app.ui.common.RowDivider
import com.easyesuite.app.ui.common.SearchField
import com.easyesuite.app.ui.common.Thumb
import com.easyesuite.app.ui.dashboard.BarChart
import com.easyesuite.core.Marketplaces
import com.easyesuite.core.model.Page
import com.easyesuite.core.model.PageQuery
import com.easyesuite.core.model.SalesByItemRow
import com.easyesuite.core.model.SalesOrdering
import com.easyesuite.core.model.SeriesPoint
import com.easyesuite.core.util.DateRange
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class ReportsViewModel(private val graph: AppContainer.Graph) : PagedListViewModel<SalesByItemRow>() {
    val range = MutableStateFlow(DateRange.thisMonth())
    val ordering = MutableStateFlow(SalesOrdering.REVENUE)
    val byMarketplace = MutableStateFlow(false)
    val query = MutableStateFlow("")
    val totals = MutableStateFlow<Triple<Double, Int, Double>?>(null)   // revenue, units, profit for loaded rows
    val carrierSpend = MutableStateFlow<List<SeriesPoint>>(emptyList())

    init {
        refresh()
        viewModelScope.launch { query.drop(1).debounce(350).distinctUntilChanged().collect { refresh() } }
        loadCarrierSpend()
    }

    fun setRange(r: DateRange) { range.value = r; refresh(); loadCarrierSpend() }
    fun setOrdering(o: String) { ordering.value = o; refresh() }
    fun setByMarketplace(v: Boolean) { byMarketplace.value = v; refresh() }

    private fun loadCarrierSpend() {
        viewModelScope.launch { carrierSpend.value = runCatching { graph.reports.carrierSpend(range.value) }.getOrDefault(emptyList()) }
    }

    override suspend fun fetch(page: PageQuery): Page<SalesByItemRow> {
        val p = graph.reports.salesByItem(range.value, ordering.value, limit = page.limit, offset = page.offset, search = query.value, splitByMarketplace = byMarketplace.value)
        if (page.offset == 0) totals.value = Triple(p.results.sumOf { it.revenue.amount }, p.results.sumOf { it.units }, p.results.sumOf { it.profit?.amount ?: 0.0 })
        return p
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(graph: AppContainer.Graph, nav: NavHostController) {
    val vm: ReportsViewModel = viewModel { ReportsViewModel(graph) }
    val state by vm.state.collectAsState()
    val range by vm.range.collectAsState()
    val ordering by vm.ordering.collectAsState()
    val byMarketplace by vm.byMarketplace.collectAsState()
    val query by vm.query.collectAsState()
    val carrier by vm.carrierSpend.collectAsState()
    val presets = remember { DateRange.presets.map { it() } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Sales by item") }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ChipRow(options = presets.map { it.label to it.label }, selected = range.label, onSelect = { l -> presets.firstOrNull { it.label == l }?.let(vm::setRange) })
            Spacer(Modifier.height(4.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = ordering == SalesOrdering.REVENUE, onClick = { vm.setOrdering(SalesOrdering.REVENUE) }, label = { Text("Revenue") })
                FilterChip(selected = ordering == SalesOrdering.UNITS, onClick = { vm.setOrdering(SalesOrdering.UNITS) }, label = { Text("Units") })
                FilterChip(selected = ordering == SalesOrdering.PROFIT, onClick = { vm.setOrdering(SalesOrdering.PROFIT) }, label = { Text("Profit") })
                FilterChip(selected = byMarketplace, onClick = { vm.setByMarketplace(!byMarketplace) }, label = { Text("Per marketplace") })
            }
            SearchField(
                value = query, onValueChange = { vm.query.value = it }, placeholder = "Filter by SKU or UPC",
                trailing = if (query.isNotEmpty()) ({ IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") } }) else null,
            )
            Text(
                "Recognised sales from invoices (sales orders over-count). ${"%,d".format(state.total)} rows.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 20.dp),
            )
            PagedList(
                state = state, onRefresh = vm::refresh, onLoadMore = vm::loadMore, emptyTitle = "No invoiced sales in this period",
                header = {
                    if (carrier.isNotEmpty()) item {
                        Text("Carrier spend", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
                        BarChart(carrier, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(120.dp))
                    }
                },
            ) { rows ->
                items(rows.size, key = { "${rows[it].itemId}-${rows[it].marketplace}" }) { i ->
                    val r = rows[i]
                    Row(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.item(r.itemId)) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(28.dp))
                        Thumb(r.primaryImage, size = 44); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.itemName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    if (byMarketplace) Marketplaces.name(r.marketplace) else null,
                                    "${r.units} units",
                                    r.averagePrice?.let { "avg $${"%.2f".format(it)}" },
                                    r.marginPercent?.let { "${"%.0f".format(it)}% margin" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(r.revenue.formatted(), fontWeight = FontWeight.SemiBold)
                            r.profit?.let { Text("profit ${it.formatted()}", style = MaterialTheme.typography.labelSmall, color = if (it.amount < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary) }
                        }
                    }
                    RowDivider()
                }
            }
        }
    }
}
