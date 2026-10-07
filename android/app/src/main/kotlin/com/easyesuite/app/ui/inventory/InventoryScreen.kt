package com.easyesuite.app.ui.inventory

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
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.easyesuite.app.di.AppContainer
import com.easyesuite.app.ui.Routes
import com.easyesuite.app.ui.common.EntityRow
import com.easyesuite.app.ui.common.PagedList
import com.easyesuite.app.ui.common.PagedListViewModel
import com.easyesuite.app.ui.common.SearchField
import com.easyesuite.app.ui.theme.Amber
import com.easyesuite.app.ui.theme.Green
import com.easyesuite.core.model.DashboardCard
import com.easyesuite.core.model.InventoryItem
import com.easyesuite.core.model.Page
import com.easyesuite.core.model.PageQuery
import com.easyesuite.core.model.Warehouse
import com.easyesuite.core.model.WarehouseStock
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Either a company-wide stock row or a per-warehouse row, so one list can show both views. */
sealed interface StockRowModel {
    val key: String
    data class Company(val item: InventoryItem) : StockRowModel { override val key get() = "c-${item.id}" }
    data class PerWarehouse(val stock: WarehouseStock) : StockRowModel { override val key get() = "w-${stock.id}" }
}

/**
 * Company view = `items/inventory_items/` (+ `get_inventory_totalization/`);
 * warehouse view = `items/warehouse_inventory_items/?warehouse_id=` (+ `totalization/`).
 * `search` and `is_available` are server-side; "below reorder point" is the only client-side filter
 * (the API has no parameter for it).
 */
@OptIn(FlowPreview::class)
class InventoryViewModel(private val graph: AppContainer.Graph) : PagedListViewModel<StockRowModel>() {
    val query = MutableStateFlow("")
    val availableOnly = MutableStateFlow(true)
    val lowStockOnly = MutableStateFlow(false)
    val warehouses = MutableStateFlow<List<Warehouse>>(emptyList())
    val warehouseId = MutableStateFlow<Int?>(null)
    /** Totals strip above the list; null while loading, empty when the endpoint returned nothing usable. */
    val totals = MutableStateFlow<List<DashboardCard>?>(null)

    init {
        refresh()
        viewModelScope.launch { query.drop(1).debounce(350).distinctUntilChanged().collect { refresh() } }
        viewModelScope.launch { runCatching { graph.items.warehouses() }.onSuccess { warehouses.value = it } }
    }

    fun setAvailableOnly(v: Boolean) { availableOnly.value = v; refresh() }
    fun setLowStockOnly(v: Boolean) { lowStockOnly.value = v; refresh() }
    fun setWarehouse(id: Int?) { warehouseId.value = id; refresh() }

    override fun refresh() {
        super.refresh()
        loadTotals()
    }

    private fun loadTotals() {
        totals.value = null
        viewModelScope.launch {
            val wh = warehouseId.value
            val result = runCatching {
                if (wh == null) graph.items.inventoryTotals(search = query.value, availableOnly = availableOnly.value)
                else graph.items.warehouseTotals(wh, availableOnly = availableOnly.value)
            }
            totals.value = result.getOrDefault(emptyList()).filter { it.numeric != null }.take(6)
        }
    }

    override suspend fun fetch(page: PageQuery): Page<StockRowModel> {
        val wh = warehouseId.value
        return if (wh == null) {
            val p = graph.items.inventory(search = query.value, availableOnly = availableOnly.value, page = page)
            val rows = p.results.filter { !lowStockOnly.value || it.belowReorderPoint }.map { StockRowModel.Company(it) }
            Page(count = p.count, next = p.next, previous = p.previous, results = rows)
        } else {
            val p = graph.items.stockInWarehouse(wh, search = query.value, availableOnly = availableOnly.value, page = page)
            val rows = p.results
                .filter { !lowStockOnly.value || ((it.reorderPoint ?: 0) > 0 && it.available < (it.reorderPoint ?: 0)) }
                .map { StockRowModel.PerWarehouse(it) }
            Page(count = p.count, next = p.next, previous = p.previous, results = rows)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(graph: AppContainer.Graph, nav: NavHostController) {
    val vm: InventoryViewModel = viewModel { InventoryViewModel(graph) }
    val state by vm.state.collectAsState()
    val query by vm.query.collectAsState()
    val availableOnly by vm.availableOnly.collectAsState()
    val lowOnly by vm.lowStockOnly.collectAsState()
    val warehouses by vm.warehouses.collectAsState()
    val warehouseId by vm.warehouseId.collectAsState()
    val totals by vm.totals.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Inventory") },
                actions = { IconButton(onClick = { nav.navigate(Routes.scanner()) }) { Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Quick actions
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("Receive PO", Icons.Default.CallReceived) { nav.navigate(Routes.RECEIVE) }
                Action("Transfer", Icons.Default.SwapHoriz) { nav.navigate(Routes.TRANSFERS) }
                Action("Adjust", Icons.Default.Tune) { nav.navigate(Routes.adjust()) }
            }
            SearchField(
                value = query, onValueChange = { vm.query.value = it }, placeholder = "Search SKU or UPC",
                trailing = if (query.isNotEmpty()) ({ IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") } }) else null,
            )
            androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = warehouseId == null, onClick = { vm.setWarehouse(null) }, label = { Text("All warehouses") }) }
                items(warehouses.size) { i -> val w = warehouses[i]; FilterChip(selected = warehouseId == w.id, onClick = { vm.setWarehouse(w.id) }, label = { Text(w.name) }) }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = availableOnly, onClick = { vm.setAvailableOnly(!availableOnly) }, label = { Text("In stock") })
                FilterChip(selected = lowOnly, onClick = { vm.setLowStockOnly(!lowOnly) }, label = { Text("Below reorder point") })
            }
            TotalsStrip(totals)
            Spacer(Modifier.height(4.dp))
            PagedList(
                state = state, onRefresh = vm::refresh, onLoadMore = vm::loadMore,
                emptyTitle = "No stock to show", emptySubtitle = "Try clearing the filters or pick another warehouse.",
            ) { rows ->
                items(rows.size, key = { rows[it].key }) { i ->
                    when (val r = rows[i]) {
                        is StockRowModel.Company -> CompanyStockRow(r.item) { nav.navigate(Routes.item(r.item.id, r.item.itemType)) }
                        is StockRowModel.PerWarehouse -> WarehouseStockRow(r.stock) { r.stock.inventoryItemId?.let { id -> nav.navigate(Routes.item(id)) } }
                    }
                }
            }
        }
    }
}

/** Company-wide or per-warehouse totals (`get_inventory_totalization/` / `totalization/`), rendered as small cards. */
@Composable
private fun TotalsStrip(cards: List<DashboardCard>?) {
    if (cards == null || cards.isEmpty()) return
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(cards.size, key = { cards[it].key }) { i ->
            val c = cards[i]
            Card {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(c.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                    Text(c.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Action(label: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) }, leadingIcon = { Icon(icon, contentDescription = null, Modifier.width(18.dp)) })
}

@Composable
private fun CompanyStockRow(item: InventoryItem, onClick: () -> Unit) {
    EntityRow(
        imageUrl = item.primaryImage, title = item.name,
        subtitle = listOfNotNull(item.marketplaceTitle, item.upcCode).joinToString(" · "),
        onClick = onClick,
        trailing = {
            Text("${item.available ?: 0}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (item.belowReorderPoint) Amber else Green)
            Text("available", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            if ((item.onOrder ?: 0) > 0) Text("+${item.onOrder} on order", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        },
        badge = if (item.belowReorderPoint) ({ Text("Below reorder point (${item.reorderPoint})", style = MaterialTheme.typography.labelSmall, color = Amber) }) else null,
    )
}

@Composable
private fun WarehouseStockRow(s: WarehouseStock, onClick: () -> Unit) {
    EntityRow(
        imageUrl = s.primaryImage, title = s.name,
        subtitle = listOfNotNull(s.marketplaceTitle, s.upcCode).joinToString(" · "),
        onClick = onClick,
        trailing = {
            Text("${s.available}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (s.available > 0) Green else MaterialTheme.colorScheme.outline)
            Text("avail · ${s.onHand} on hand", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            if (s.committed > 0) Text("${s.committed} committed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        },
    )
}
