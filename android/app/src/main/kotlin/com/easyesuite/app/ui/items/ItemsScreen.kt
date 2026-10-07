package com.easyesuite.app.ui.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.easyesuite.app.di.AppContainer
import com.easyesuite.app.ui.Routes
import com.easyesuite.app.ui.common.EntityRow
import com.easyesuite.app.ui.common.MoneyText
import com.easyesuite.app.ui.common.PagedList
import com.easyesuite.app.ui.common.PagedListViewModel
import com.easyesuite.app.ui.common.SearchField
import com.easyesuite.core.ItemTypes
import com.easyesuite.core.model.ItemCondition
import com.easyesuite.core.model.ItemSummary
import com.easyesuite.core.model.Page
import com.easyesuite.core.model.PageQuery
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Catalog (`items/items/`): every item type in one list, filtered server-side by
 * `search`, `item_type`, `item_condition` and `is_available`.
 */
@OptIn(FlowPreview::class)
class ItemsViewModel(private val graph: AppContainer.Graph) : PagedListViewModel<ItemSummary>() {
    val query = MutableStateFlow("")
    /** null = all types; otherwise an [ItemTypes] code. */
    val itemType = MutableStateFlow<String?>(null)
    val conditionId = MutableStateFlow<Int?>(null)
    val availableOnly = MutableStateFlow(false)
    val conditions = MutableStateFlow<List<ItemCondition>>(emptyList())

    init {
        refresh()
        viewModelScope.launch { query.drop(1).debounce(350).distinctUntilChanged().collect { refresh() } }
        viewModelScope.launch { runCatching { graph.items.conditions() }.onSuccess { conditions.value = it } }
    }

    fun setType(type: String?) { itemType.value = type; refresh() }
    fun setCondition(id: Int?) { conditionId.value = id; refresh() }
    fun setAvailableOnly(v: Boolean) { availableOnly.value = v; refresh() }

    override suspend fun fetch(page: PageQuery): Page<ItemSummary> = graph.items.catalog(
        search = query.value,
        itemType = itemType.value,
        conditionId = conditionId.value,
        availableOnly = availableOnly.value,
        page = page,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsScreen(graph: AppContainer.Graph, nav: NavHostController) {
    val vm: ItemsViewModel = viewModel { ItemsViewModel(graph) }
    val state by vm.state.collectAsState()
    val query by vm.query.collectAsState()
    val itemType by vm.itemType.collectAsState()
    val conditionId by vm.conditionId.collectAsState()
    val availableOnly by vm.availableOnly.collectAsState()
    val conditions by vm.conditions.collectAsState()

    // Refresh after returning from "new item".
    val created = nav.currentBackStackEntry?.savedStateHandle?.get<Boolean>("item_created")
    LaunchedEffect(created) { if (created == true) { vm.refresh(); nav.currentBackStackEntry?.savedStateHandle?.set("item_created", false) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Items") },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.scanner()) }) { Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan barcode") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.newItem()) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New item") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchField(
                value = query, onValueChange = { vm.query.value = it }, placeholder = "Search SKU, title or UPC",
                trailing = if (query.isNotEmpty()) ({ IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") } }) else null,
            )
            // Type + availability filters (all server-side).
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = itemType == null, onClick = { vm.setType(null) }, label = { Text("All") }) }
                item { FilterChip(selected = itemType == ItemTypes.INVENTORY, onClick = { vm.setType(ItemTypes.INVENTORY) }, label = { Text("Items") }) }
                item { FilterChip(selected = itemType == ItemTypes.KIT, onClick = { vm.setType(ItemTypes.KIT) }, label = { Text("Kits") }) }
                item { FilterChip(selected = itemType == ItemTypes.VARIANT, onClick = { vm.setType(ItemTypes.VARIANT) }, label = { Text("Variants") }) }
                item { FilterChip(selected = availableOnly, onClick = { vm.setAvailableOnly(!availableOnly) }, label = { Text("In stock") }) }
            }
            if (conditions.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = conditionId == null, onClick = { vm.setCondition(null) }, label = { Text("Any condition") }) }
                    items(conditions.size, key = { conditions[it].id }) { i ->
                        val c = conditions[i]
                        FilterChip(selected = conditionId == c.id, onClick = { vm.setCondition(if (conditionId == c.id) null else c.id) }, label = { Text(c.name.ifBlank { "Condition #${c.id}" }) })
                    }
                }
            }
            if (state.total > 0) {
                Text(
                    "${"%,d".format(state.total)} items", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }
            PagedList(
                state = state, onRefresh = vm::refresh, onLoadMore = vm::loadMore,
                emptyTitle = if (query.isBlank()) "No items yet" else "No items match “$query”",
                emptySubtitle = if (query.isBlank()) "Tap New item or scan a barcode to add one." else null,
            ) { rows ->
                items(rows.size, key = { rows[it].id }) { i ->
                    val item = rows[i]
                    ItemRow(item) { nav.navigate(Routes.item(item.id, item.itemType)) }
                }
            }
        }
    }
}

@Composable
fun ItemRow(item: ItemSummary, onClick: () -> Unit) {
    val typeLabel = ItemTypes.label(item.itemType).takeIf { ItemTypes.normalize(item.itemType) != ItemTypes.INVENTORY && it != "—" }
    EntityRow(
        imageUrl = item.primaryImage,
        title = item.name,
        subtitle = listOfNotNull(typeLabel, item.marketplaceTitle, item.upcCode?.let { "UPC $it" }).joinToString(" · "),
        onClick = onClick,
        trailing = {
            Row { Text("${item.onHand ?: 0}", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.width(2.dp)); Text("on hand", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 6.dp)) }
            MoneyText(item.averageCost, style = MaterialTheme.typography.labelMedium)
        },
    )
}
