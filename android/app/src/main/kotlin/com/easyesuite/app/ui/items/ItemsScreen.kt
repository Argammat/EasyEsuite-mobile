package com.easyesuite.app.ui.items

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import com.easyesuite.core.model.ItemSummary
import com.easyesuite.core.model.Page
import com.easyesuite.core.model.PageQuery
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class ItemsViewModel(private val graph: AppContainer.Graph) : PagedListViewModel<ItemSummary>() {
    val query = MutableStateFlow("")

    init {
        refresh()
        viewModelScope.launch { query.drop(1).debounce(350).distinctUntilChanged().collect { refresh() } }
    }

    override suspend fun fetch(page: PageQuery): Page<ItemSummary> = graph.items.catalog(search = query.value, page = page)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsScreen(graph: AppContainer.Graph, nav: NavHostController) {
    val vm: ItemsViewModel = viewModel { ItemsViewModel(graph) }
    val state by vm.state.collectAsState()
    val query by vm.query.collectAsState()

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
                    ItemRow(item) { nav.navigate(Routes.item(item.id)) }
                }
            }
        }
    }
}

@Composable
fun ItemRow(item: ItemSummary, onClick: () -> Unit) {
    EntityRow(
        imageUrl = item.primaryImage,
        title = item.name,
        subtitle = listOfNotNull(item.marketplaceTitle, item.upcCode?.let { "UPC $it" }).joinToString(" · "),
        onClick = onClick,
        trailing = {
            Row { Text("${item.onHand ?: 0}", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.width(2.dp)); Text("on hand", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 6.dp)) }
            MoneyText(item.averageCost, style = MaterialTheme.typography.labelMedium)
        },
    )
}
