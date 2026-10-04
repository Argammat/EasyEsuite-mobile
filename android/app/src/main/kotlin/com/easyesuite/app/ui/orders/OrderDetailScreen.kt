package com.easyesuite.app.ui.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
import com.easyesuite.app.ui.common.StatusChip
import com.easyesuite.app.ui.common.Thumb
import com.easyesuite.app.ui.common.userMessage
import com.easyesuite.core.model.FulfillLine
import com.easyesuite.core.model.FulfillRequest
import com.easyesuite.core.model.SalesOrderDetail
import com.easyesuite.core.model.Shipment
import com.easyesuite.core.model.UpdateOrderRequest
import com.easyesuite.core.util.DateText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OrderUi(
    val order: SalesOrderDetail,
    val shipments: List<Shipment> = emptyList(),
    val fulfillOpen: Boolean = false,
    val tracking: String = "",
    val busy: Boolean = false,
    val actionError: String? = null,
    val actionMessage: String? = null,
)

class OrderDetailViewModel(private val graph: AppContainer.Graph, private val id: Long) : ViewModel() {
    val state = MutableStateFlow<Load<OrderUi>>(Load.Loading)

    init { load() }

    fun load() {
        state.value = Load.Loading
        viewModelScope.launch {
            try {
                val order = graph.orders.order(id)
                val shipments = runCatching { graph.shipping.shipments(search = order.number, page = com.easyesuite.core.model.PageQuery(limit = 10)).results.filter { it.orderNumber == order.number } }.getOrDefault(emptyList())
                state.value = Load.Ready(OrderUi(order, shipments))
            } catch (e: Exception) {
                state.value = Load.Failed(e.userMessage())
            }
        }
    }

    private fun edit(f: (OrderUi) -> OrderUi) = state.update { s -> if (s is Load.Ready) Load.Ready(f(s.value)) else s }

    fun openFulfill(open: Boolean) = edit { it.copy(fulfillOpen = open, actionError = null) }
    fun setTracking(t: String) = edit { it.copy(tracking = t) }

    fun fulfill() {
        val ui = (state.value as? Load.Ready<OrderUi>)?.value ?: return
        edit { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            try {
                graph.orders.fulfill(
                    FulfillRequest(
                        salesOrder = ui.order.id,
                        warehouse = ui.order.warehouse,
                        trackingNumber = ui.tracking.ifBlank { null },
                        shippingCarrier = ui.order.shippingCarrier,
                        shippingMethod = ui.order.shippingMethod,
                        items = ui.order.items.filter { it.unfulfilledQuantity > 0 }.map { FulfillLine(it.id, it.unfulfilledQuantity) },
                    ),
                )
                edit { it.copy(busy = false, fulfillOpen = false, actionMessage = "Order fulfilled") }
                load()
            } catch (e: Exception) {
                edit { it.copy(busy = false, actionError = e.userMessage()) }
            }
        }
    }

    fun saveMemo(memo: String) {
        viewModelScope.launch {
            try {
                val updated = graph.orders.update(id, UpdateOrderRequest(memo = memo))
                edit { it.copy(order = updated, actionMessage = "Memo saved") }
            } catch (e: Exception) { edit { it.copy(actionError = e.userMessage()) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailScreen(graph: AppContainer.Graph, nav: NavHostController, id: Long) {
    val vm: OrderDetailViewModel = viewModel(key = "order-$id") { OrderDetailViewModel(graph, id) }
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((state as? Load.Ready<OrderUi>)?.value?.order?.number ?: "Order") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        when (val s = state) {
            is Load.Loading, Load.Idle -> LoadingBox(Modifier.padding(padding))
            is Load.Failed -> ErrorBox(s.message, onRetry = vm::load, modifier = Modifier.padding(padding))
            is Load.Ready -> OrderBody(s.value, vm, nav, Modifier.padding(padding))
        }
    }
}

@Composable
private fun OrderBody(ui: OrderUi, vm: OrderDetailViewModel, nav: NavHostController, modifier: Modifier) {
    val o = ui.order
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(o.marketplaceName ?: "", style = MaterialTheme.typography.titleMedium)
                Text("${DateText.long(o.date)} · PO ${o.poNumber ?: "—"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            StatusChip(o.status)
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                KeyValueRow("Items", "${o.totalQuantity ?: o.items.sumOf { it.quantity }} units")
                KeyValueRow("Subtotal", o.subTotal?.formatted())
                KeyValueRow("Tax", o.totalTaxes?.formatted())
                KeyValueRow("Shipping", o.shippingCost?.formatted())
                KeyValueRow("Total", o.amount.formatted())
            }
        }

        if (ui.actionMessage != null) Text(ui.actionMessage, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 8.dp))
        if (ui.actionError != null) Text(ui.actionError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (o.canFulfill) Button(onClick = { vm.openFulfill(true) }) { Icon(Icons.Default.LocalShipping, null, Modifier.width(18.dp)); Spacer(Modifier.width(6.dp)); Text("Fulfill") }
            ui.shipments.firstOrNull()?.let { sh -> OutlinedButton(onClick = { nav.navigate(Routes.shipment(sh.id)) }) { Text(if (ui.shipments.size > 1) "Shipments (${ui.shipments.size})" else "Shipment") } }
        }

        SectionTitle("Lines")
        o.items.forEach { line ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(line.primaryImage, size = 48); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(line.name, fontWeight = FontWeight.Medium)
                    Text(line.description ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 2)
                    Text(
                        buildString {
                            append("${line.quantity} × ${line.price?.formatted() ?: "—"}")
                            if (line.fulfilledQuantity > 0) append(" · ${line.fulfilledQuantity} fulfilled")
                            if (line.unfulfilledQuantity > 0) append(" · ${line.unfulfilledQuantity} to ship")
                            line.availableQuantity?.let { append(" · $it available") }
                            line.warehouseName?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(line.lineTotal.formatted(), fontWeight = FontWeight.SemiBold)
            }
        }

        o.shipTo?.let { a ->
            SectionTitle("Ship to")
            a.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            listOfNotNull(a.cellPhone, a.workPhone).firstOrNull { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        }

        SectionTitle("Shipping")
        KeyValueRow("Method", o.shippingMethodName)
        KeyValueRow("Ship by", DateText.short(o.shipDate))
        KeyValueRow("Deliver by", o.deliverByDate?.let { DateText.short(it) })
        KeyValueRow("Marketplace sync", o.connectorSyncStatus)

        SectionTitle("Memo")
        var memo by androidx.compose.runtime.remember(o.memo) { androidx.compose.runtime.mutableStateOf(o.memo ?: "") }
        OutlinedTextField(value = memo, onValueChange = { memo = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        if (memo != (o.memo ?: "")) TextButton(onClick = { vm.saveMemo(memo) }) { Text("Save memo") }
        Spacer(Modifier.height(32.dp))
    }

    if (ui.fulfillOpen) {
        AlertDialog(
            onDismissRequest = { vm.openFulfill(false) },
            title = { Text("Fulfill ${o.number}") },
            text = {
                Column {
                    Text("Marks all ${o.items.sumOf { it.unfulfilledQuantity }} unshipped unit(s) as fulfilled from ${o.items.firstOrNull()?.warehouseName ?: "the order warehouse"}.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = ui.tracking, onValueChange = vm::setTracking, label = { Text("Tracking number (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (ui.actionError != null) Text(ui.actionError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { Button(onClick = vm::fulfill, enabled = !ui.busy) { Text(if (ui.busy) "Working…" else "Fulfill") } },
            dismissButton = { TextButton(onClick = { vm.openFulfill(false) }) { Text("Cancel") } },
        )
    }
}
