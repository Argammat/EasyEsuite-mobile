package com.easyesuite.app.ui.items

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.easyesuite.app.di.AppContainer
import com.easyesuite.app.ui.Routes
import com.easyesuite.app.ui.common.ErrorBox
import com.easyesuite.app.ui.common.KeyValueRow
import com.easyesuite.app.ui.common.Load
import com.easyesuite.app.ui.common.LoadingBox
import com.easyesuite.app.ui.common.SectionTitle
import com.easyesuite.app.ui.common.Thumb
import com.easyesuite.app.ui.common.userMessage
import com.easyesuite.app.ui.theme.Amber
import com.easyesuite.app.ui.theme.Green
import com.easyesuite.core.Marketplaces
import com.easyesuite.core.model.ItemDetail
import com.easyesuite.core.model.WarehouseStock
import com.easyesuite.core.util.DateText
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class ItemDetailUi(val item: ItemDetail, val stock: List<WarehouseStock>, val stockError: String? = null)

class ItemDetailViewModel(private val graph: AppContainer.Graph, private val id: Long) : ViewModel() {
    val state = MutableStateFlow<Load<ItemDetailUi>>(Load.Loading)

    init { load() }

    fun load() {
        state.value = Load.Loading
        viewModelScope.launch {
            try {
                val itemDeferred = async { graph.items.item(id) }
                val stock = runCatching { graph.items.stockByWarehouse(id) }
                val item = itemDeferred.await()
                state.value = Load.Ready(ItemDetailUi(item, stock.getOrDefault(emptyList()), stock.exceptionOrNull()?.userMessage()))
            } catch (e: Exception) {
                state.value = Load.Failed(e.userMessage())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(graph: AppContainer.Graph, nav: NavHostController, id: Long) {
    val vm: ItemDetailViewModel = viewModel(key = "item-$id") { ItemDetailViewModel(graph, id) }
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = (state as? Load.Ready<ItemDetailUi>)?.value?.item?.name ?: "Item"
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        when (val s = state) {
            is Load.Loading, Load.Idle -> LoadingBox(Modifier.padding(padding))
            is Load.Failed -> ErrorBox(s.message, onRetry = vm::load, modifier = Modifier.padding(padding))
            is Load.Ready -> ItemDetailBody(s.value, Modifier.padding(padding), nav)
        }
    }
}

@Composable
private fun ItemDetailBody(ui: ItemDetailUi, modifier: Modifier, nav: NavHostController) {
    val item = ui.item
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        if (item.allImages.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.allImages.forEach { Thumb(it, size = 120) }
            }
            Spacer(Modifier.height(12.dp))
        }
        Text(item.displayTitle, style = MaterialTheme.typography.titleMedium)
        Text(
            listOfNotNull(item.upcCode?.let { "UPC $it" }, item.brand, item.conditionName).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("On hand", "${item.onHand ?: 0}", Modifier.weight(1f))
            Stat("Avg cost", item.averageCost?.formatted() ?: "—", Modifier.weight(1f))
            Stat("Last sold", item.lastSellingPrice?.formatted() ?: "—", Modifier.weight(1f))
            Stat("Value", item.totalValue?.formatted() ?: "—", Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { nav.navigate(Routes.newTransfer(item.id)) }) { Icon(Icons.Default.SwapHoriz, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Transfer") }
            OutlinedButton(onClick = { nav.navigate(Routes.adjust(item.id)) }) { Icon(Icons.Default.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Adjust stock") }
        }

        SectionTitle("Stock by warehouse")
        if (ui.stockError != null) Text(ui.stockError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (ui.stock.isEmpty() && ui.stockError == null) Text("No warehouse records yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        ui.stock.forEach { s -> StockRow(s) }

        if (item.marketplacePricing.isNotEmpty()) {
            SectionTitle("Marketplace prices")
            item.marketplacePricing.forEach { KeyValueRow(Marketplaces.name(it.marketplace), it.price?.formatted()) }
        }
        if (item.marketplaceSkus.isNotEmpty()) {
            SectionTitle("Marketplace SKUs")
            item.marketplaceSkus.forEach { (mp, sku) -> KeyValueRow(mp, sku) }
        }

        SectionTitle("Details")
        KeyValueRow("Brand", item.brand)
        KeyValueRow("Platform", item.platform)
        KeyValueRow("Manufacturer", item.manufacturer)
        KeyValueRow("Weight", item.weight?.let { "$it ${item.weightUnit ?: ""}".trim() })
        KeyValueRow("Dimensions", item.dimensionsText)
        KeyValueRow("Cost (purchase price)", item.purchasePrice?.formatted())
        KeyValueRow("Last purchase price", item.lastPurchasePrice?.formatted())
        KeyValueRow("Taxable", item.taxable?.let { if (it) "Yes" else "No" })
        KeyValueRow("Created", DateText.short(item.createdDate))
        KeyValueRow("Updated", DateText.short(item.modifiedDate))
        if (!item.description.isNullOrBlank()) {
            SectionTitle("Description")
            Text(item.description!!, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun StockRow(s: WarehouseStock) {
    val low = s.reorderPoint != null && s.reorderPoint!! > 0 && s.available < s.reorderPoint!!
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s.warehouse, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                buildString {
                    append("Available ${s.available}")
                    if (s.committed > 0) append(" · committed ${s.committed}")
                    if (s.onOrder > 0) append(" · on order ${s.onOrder}")
                    if (s.inTransit > 0) append(" · in transit ${s.inTransit}")
                    if (s.reorderPoint != null && s.reorderPoint!! > 0) append(" · reorder at ${s.reorderPoint}")
                },
                style = MaterialTheme.typography.bodySmall, color = if (low) Amber else MaterialTheme.colorScheme.outline,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${s.onHand}", style = MaterialTheme.typography.titleMedium, color = if (s.onHand > 0) Green else MaterialTheme.colorScheme.outline)
            Text("on hand", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
