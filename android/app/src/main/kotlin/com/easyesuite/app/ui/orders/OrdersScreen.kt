package com.easyesuite.app.ui.orders

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.easyesuite.app.di.AppContainer
import com.easyesuite.app.ui.Routes
import com.easyesuite.app.ui.common.ChipRow
import com.easyesuite.app.ui.common.EntityRow
import com.easyesuite.app.ui.common.PagedList
import com.easyesuite.app.ui.common.PagedListViewModel
import com.easyesuite.app.ui.common.SearchField
import com.easyesuite.app.ui.common.StatusChip
import com.easyesuite.core.model.OrderStatus
import com.easyesuite.core.model.Page
import com.easyesuite.core.model.PageQuery
import com.easyesuite.core.model.SalesOrderSummary
import com.easyesuite.core.model.Shipment
import com.easyesuite.core.model.ShipmentStatus
import com.easyesuite.core.util.DateText
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class OrdersViewModel(private val graph: AppContainer.Graph) : PagedListViewModel<SalesOrderSummary>() {
    val status = MutableStateFlow<String?>(OrderStatus.PENDING_FULFILLMENT)
    val query = MutableStateFlow("")
    init {
        refresh()
        viewModelScope.launch { query.drop(1).debounce(350).distinctUntilChanged().collect { refresh() } }
    }
    fun setStatus(s: String?) { status.value = s; refresh() }
    override suspend fun fetch(page: PageQuery): Page<SalesOrderSummary> = graph.orders.orders(status = status.value, search = query.value, page = page)
}

@OptIn(FlowPreview::class)
class ShipmentsViewModel(private val graph: AppContainer.Graph) : PagedListViewModel<Shipment>() {
    val status = MutableStateFlow<String?>(ShipmentStatus.TO_SHIP)
    val query = MutableStateFlow("")
    init {
        refresh()
        viewModelScope.launch { query.drop(1).debounce(350).distinctUntilChanged().collect { refresh() } }
    }
    fun setStatus(s: String?) { status.value = s; refresh() }
    override suspend fun fetch(page: PageQuery): Page<Shipment> = graph.shipping.shipments(status = status.value, search = query.value, page = page)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(graph: AppContainer.Graph, nav: NavHostController) {
    var segment by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (segment == 0) "Orders" else "Shipments") },
                actions = { IconButton(onClick = { nav.navigate(Routes.scanner()) }) { Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan label or order") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                SegmentedButton(selected = segment == 0, onClick = { segment = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Orders") }
                SegmentedButton(selected = segment == 1, onClick = { segment = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Shipments") }
            }
            Spacer(Modifier.height(4.dp))
            if (segment == 0) OrdersList(graph, nav) else ShipmentsList(graph, nav)
        }
    }
}

@Composable
private fun OrdersList(graph: AppContainer.Graph, nav: NavHostController) {
    val vm: OrdersViewModel = viewModel { OrdersViewModel(graph) }
    val state by vm.state.collectAsState()
    val status by vm.status.collectAsState()
    val query by vm.query.collectAsState()

    SearchField(
        value = query, onValueChange = { vm.query.value = it }, placeholder = "Order #, PO #, customer",
        trailing = if (query.isNotEmpty()) ({ IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") } }) else null,
    )
    ChipRow(
        options = listOf<Pair<String?, String>>(null to "All") + listOf(
            OrderStatus.PENDING_FULFILLMENT to "Pending fulfillment", OrderStatus.OPEN to "Open", OrderStatus.PARTIAL_FULFILLED to "Partial",
            OrderStatus.FULFILLED_PENDING_INVOICE to "Fulfilled", OrderStatus.INVOICED to "Invoiced", OrderStatus.VOIDED to "Voided",
        ),
        selected = status, onSelect = vm::setStatus,
    )
    if (state.total > 0) Text("${"%,d".format(state.total)} orders", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    PagedList(state = state, onRefresh = vm::refresh, onLoadMore = vm::loadMore, emptyTitle = "No orders here") { rows ->
        items(rows.size, key = { rows[it].id }) { i -> OrderRow(rows[i]) { nav.navigate(Routes.order(rows[i].id)) } }
    }
}

@Composable
fun OrderRow(o: SalesOrderSummary, onClick: () -> Unit) {
    EntityRow(
        imageUrl = null,
        title = "${o.number}  ·  ${o.marketplace ?: ""}",
        subtitle = listOfNotNull(
            o.company?.takeIf { it.isNotBlank() && it != o.marketplace },
            "${o.totalQuantity ?: 0} unit${if ((o.totalQuantity ?: 0) == 1) "" else "s"} · ${o.warehouse ?: "—"}",
            o.shipDate?.let { "ship by ${DateText.short(it)}" } ?: DateText.relative(o.date),
        ).joinToString(" · "),
        onClick = onClick,
        trailing = {
            Text(o.amount.formatted(), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            StatusChip(o.status)
        },
    )
}

@Composable
private fun ShipmentsList(graph: AppContainer.Graph, nav: NavHostController) {
    val vm: ShipmentsViewModel = viewModel { ShipmentsViewModel(graph) }
    val state by vm.state.collectAsState()
    val status by vm.status.collectAsState()
    val query by vm.query.collectAsState()

    SearchField(
        value = query, onValueChange = { vm.query.value = it }, placeholder = "Order #, tracking #, recipient",
        trailing = if (query.isNotEmpty()) ({ IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") } }) else null,
    )
    ChipRow(options = ShipmentStatus.tabs.map { it to ShipmentStatus.label(it) }, selected = status, onSelect = { vm.setStatus(it ?: ShipmentStatus.TO_SHIP) })
    if (state.total > 0) Text("${"%,d".format(state.total)} shipments", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    PagedList(state = state, onRefresh = vm::refresh, onLoadMore = vm::loadMore, emptyTitle = "No shipments in this view") { rows ->
        items(rows.size, key = { rows[it].id }) { i -> ShipmentRow(rows[i]) { nav.navigate(Routes.shipment(rows[i].id)) } }
    }
}

@Composable
fun ShipmentRow(s: Shipment, onClick: () -> Unit) {
    EntityRow(
        imageUrl = s.items.firstOrNull()?.imageUrl,
        title = "${s.orderNumber ?: "Shipment ${s.id}"}  ·  ${s.marketplaceName ?: ""}",
        subtitle = listOfNotNull(
            s.items.firstOrNull()?.title?.let { t -> if ((s.itemCount ?: 1) > 1) "$t +${(s.itemCount ?: 1) - 1} more" else t },
            s.destination.takeIf { it.isNotBlank() },
            s.shipByDate?.let { "ship by ${DateText.short(it)}" },
            s.trackingCode,
        ).joinToString(" · "),
        onClick = onClick,
        trailing = {
            Row { Text(s.orderShippingCarrier ?: s.labelProvider ?: "", style = MaterialTheme.typography.labelMedium) }
            Spacer(Modifier.height(4.dp))
            StatusChip(s.status, ShipmentStatus.label(s.status))
        },
    )
}
