package com.easyesuite.app.ui.scan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.easyesuite.app.di.AppContainer
import com.easyesuite.app.ui.Routes
import com.easyesuite.app.ui.common.EntityRow
import com.easyesuite.app.ui.common.LoadingBox
import com.easyesuite.app.ui.common.SectionTitle
import com.easyesuite.app.ui.common.StatusChip
import com.easyesuite.app.ui.common.userMessage
import com.easyesuite.app.ui.items.ItemRow
import com.easyesuite.core.model.ItemSummary
import com.easyesuite.core.model.SalesOrderSummary
import com.easyesuite.core.model.Shipment
import com.easyesuite.core.model.ShipmentStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class ScanResolution(
    val loading: Boolean = true,
    val items: List<ItemSummary> = emptyList(),
    val shipment: Shipment? = null,
    val order: SalesOrderSummary? = null,
    val errors: List<String> = emptyList(),
) {
    val nothing: Boolean get() = !loading && items.isEmpty() && shipment == null && order == null
}

/** Figures out what a scanned code is: a product UPC, a shipping label / tracking barcode, or an order number. */
class ScanResultViewModel(private val graph: AppContainer.Graph, private val code: String) : ViewModel() {
    val state = MutableStateFlow(ScanResolution())

    init { resolve() }

    fun resolve() {
        state.value = ScanResolution()
        viewModelScope.launch {
            val looksLikeUpc = code.all { it.isDigit() } && code.length in 8..14
            val looksLikeOrder = code.startsWith("SO-", ignoreCase = true)
            val errors = mutableListOf<String>()

            val items = async { runCatching { graph.items.findByBarcode(code) }.onFailure { errors += "Items: ${it.userMessage()}" }.getOrDefault(emptyList()) }
            val shipment = async {
                if (looksLikeUpc) null else runCatching { graph.shipping.resolveBarcode(code) }
                    .onFailure { if (it !is com.easyesuite.core.net.ApiException.Http || !it.isNotFound) errors += "Shipments: ${it.userMessage()}" }
                    .getOrNull()
            }
            val order = async { if (looksLikeOrder) runCatching { graph.orders.findByNumber(code) }.getOrNull() else null }

            state.value = ScanResolution(loading = false, items = items.await(), shipment = shipment.await(), order = order.await(), errors = errors)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanResultScreen(graph: AppContainer.Graph, nav: NavHostController, code: String) {
    val vm: ScanResultViewModel = viewModel(key = "scan-$code") { ScanResultViewModel(graph, code) }
    val s by vm.state.collectAsState()

    // Exactly one match → jump straight there.
    LaunchedEffect(s) {
        if (!s.loading) {
            val single = listOfNotNull(
                s.items.singleOrNull()?.let { Routes.item(it.id, it.itemType) },
                s.shipment?.let { Routes.shipment(it.id) },
                s.order?.let { Routes.order(it.id) },
            )
            if (single.size == 1 && s.items.size <= 1) nav.navigate(single.first()) { popUpTo(Routes.SCAN_RESULT) { inclusive = true } }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(code) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (s.loading) { LoadingBox(Modifier.padding(padding)); return@Scaffold }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if (s.items.isNotEmpty()) {
                SectionTitle("Items", Modifier.padding(horizontal = 16.dp))
                s.items.forEach { item -> ItemRow(item) { nav.navigate(Routes.item(item.id, item.itemType)) } }
            }
            s.shipment?.let { sh ->
                SectionTitle("Shipment", Modifier.padding(horizontal = 16.dp))
                EntityRow(
                    imageUrl = sh.items.firstOrNull()?.imageUrl, title = sh.orderNumber ?: "Shipment ${sh.id}",
                    subtitle = listOfNotNull(sh.marketplaceName, sh.destination.takeIf { it.isNotBlank() }).joinToString(" · "),
                    onClick = { nav.navigate(Routes.shipment(sh.id)) },
                    trailing = { StatusChip(sh.status, ShipmentStatus.label(sh.status)) },
                )
            }
            s.order?.let { o ->
                SectionTitle("Order", Modifier.padding(horizontal = 16.dp))
                EntityRow(imageUrl = null, title = o.number, subtitle = listOfNotNull(o.marketplace, o.company).joinToString(" · "), onClick = { nav.navigate(Routes.order(o.id)) }, trailing = { StatusChip(o.status) })
            }
            if (s.nothing) {
                Column(Modifier.padding(24.dp)) {
                    Text("Nothing in EasyEsuite matches “$code”.", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("If this is a product barcode you can create the item now — the product database lookup will prefill the details.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(16.dp))
                    if (code.all { it.isDigit() }) {
                        Button(onClick = { nav.navigate(Routes.newItem(code)) { popUpTo(Routes.SCAN_RESULT) { inclusive = true } } }, modifier = Modifier.fillMaxWidth()) { Text("Create item with UPC $code") }
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedButton(onClick = { nav.navigate(Routes.scanner()) { popUpTo(Routes.SCAN_RESULT) { inclusive = true } } }, modifier = Modifier.fillMaxWidth()) { Text("Scan again") }
                }
            }
            s.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        }
    }
}
