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
import androidx.compose.ui.unit.sp
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
import com.easyesuite.core.model.Invoice
import com.easyesuite.core.model.InvoiceStatus
import com.easyesuite.core.model.OrderStatus
import com.easyesuite.core.model.Payment
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

@OptIn(FlowPreview::class)
class InvoicesViewModel(private val graph: AppContainer.Graph) : PagedListViewModel<Invoice>() {
    val status = MutableStateFlow<String?>(InvoiceStatus.OPEN)
    val query = MutableStateFlow("")
    init {
        refresh()
        viewModelScope.launch { query.drop(1).debounce(350).distinctUntilChanged().collect { refresh() } }
    }
    fun setStatus(s: String?) { status.value = s; refresh() }
    override suspend fun fetch(page: PageQuery): Page<Invoice> = graph.orders.invoices(status = status.value, search = query.value, page = page)
}

@OptIn(FlowPreview::class)
class PaymentsViewModel(private val graph: AppContainer.Graph) : PagedListViewModel<Payment>() {
    val query = MutableStateFlow("")
    /** Client-side: the API has no "has unapplied" filter. */
    val unappliedOnly = MutableStateFlow(false)
    init {
        refresh()
        viewModelScope.launch { query.drop(1).debounce(350).distinctUntilChanged().collect { refresh() } }
    }
    fun setUnappliedOnly(v: Boolean) { unappliedOnly.value = v; refresh() }
    override suspend fun fetch(page: PageQuery): Page<Payment> {
        val p = graph.orders.payments(search = query.value, page = page)
        return if (unappliedOnly.value) p.copy(results = p.results.filter { it.hasUnapplied }) else p
    }
}

/** The web app's Ecommerce + Sales finance areas in one tab: Orders · Shipments · Invoices · Payments. */
private val ecommerceSegments = listOf("Orders", "Shipments", "Invoices", "Payments")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(graph: AppContainer.Graph, nav: NavHostController) {
    var segment by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ECOMMERCE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, letterSpacing = 1.4.sp)
                        Text(ecommerceSegments[segment])
                    }
                },
                actions = { IconButton(onClick = { nav.navigate(Routes.scanner()) }) { Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan label or order") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                ecommerceSegments.forEachIndexed { i, label ->
                    SegmentedButton(selected = segment == i, onClick = { segment = i }, shape = SegmentedButtonDefaults.itemShape(i, ecommerceSegments.size), icon = {}) {
                        Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            when (segment) {
                0 -> OrdersList(graph, nav)
                1 -> ShipmentsList(graph, nav)
                2 -> InvoicesList(graph, nav)
                else -> PaymentsList(graph, nav)
            }
        }
    }
}

@Composable
private fun InvoicesList(graph: AppContainer.Graph, nav: NavHostController) {
    val vm: InvoicesViewModel = viewModel { InvoicesViewModel(graph) }
    val state by vm.state.collectAsState()
    val status by vm.status.collectAsState()
    val query by vm.query.collectAsState()

    SearchField(
        value = query, onValueChange = { vm.query.value = it }, placeholder = "Invoice # (IN-…)",
        trailing = if (query.isNotEmpty()) ({ IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") } }) else null,
    )
    ChipRow(
        options = listOf<Pair<String?, String>>(null to "All") + InvoiceStatus.all.map { it to it },
        selected = status, onSelect = vm::setStatus,
    )
    if (state.total > 0) Text("${"%,d".format(state.total)} invoices", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    PagedList(state = state, onRefresh = vm::refresh, onLoadMore = vm::loadMore, emptyTitle = "No invoices here") { rows ->
        items(rows.size, key = { rows[it].id }) { i ->
            val inv = rows[i]
            InvoiceRow(inv) { inv.salesOrderId?.let { nav.navigate(Routes.order(it)) } }
        }
    }
}

@Composable
fun InvoiceRow(inv: Invoice, onClick: () -> Unit) {
    EntityRow(
        imageUrl = null,
        title = "${inv.number}  ·  ${inv.displayCustomer}",
        subtitle = listOfNotNull(
            inv.invoiceType,
            inv.salesOrderNumber,
            inv.poNumber?.let { "PO $it" },
            inv.warehouseName,
            DateText.short(inv.date),
        ).joinToString(" · "),
        onClick = onClick,
        trailing = {
            Text(inv.totalAmount?.formatted() ?: "—", fontWeight = FontWeight.SemiBold)
            if (inv.isOpen && inv.openAmount != null && inv.openAmount != inv.totalAmount) {
                Text("open ${inv.openAmount!!.formatted()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(4.dp))
            StatusChip(inv.returnStatus?.takeIf { it.isNotBlank() } ?: inv.status)
        },
    )
}

@Composable
private fun PaymentsList(graph: AppContainer.Graph, nav: NavHostController) {
    val vm: PaymentsViewModel = viewModel { PaymentsViewModel(graph) }
    val state by vm.state.collectAsState()
    val query by vm.query.collectAsState()
    val unappliedOnly by vm.unappliedOnly.collectAsState()

    SearchField(
        value = query, onValueChange = { vm.query.value = it }, placeholder = "Payment #, customer, reference",
        trailing = if (query.isNotEmpty()) ({ IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") } }) else null,
    )
    ChipRow(
        options = listOf<Pair<String?, String>>(null to "All", "unapplied" to "Has unapplied amount"),
        selected = if (unappliedOnly) "unapplied" else null,
        onSelect = { vm.setUnappliedOnly(it == "unapplied") },
    )
    if (state.total > 0) Text("${"%,d".format(state.total)} payments", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    PagedList(state = state, onRefresh = vm::refresh, onLoadMore = vm::loadMore, emptyTitle = "No payments here") { rows ->
        items(rows.size, key = { rows[it].id }) { i -> PaymentRow(rows[i]) }
    }
}

@Composable
fun PaymentRow(p: Payment) {
    EntityRow(
        imageUrl = null,
        title = "${p.number}  ·  ${p.customerName ?: "—"}",
        subtitle = listOfNotNull(
            p.paymentMethodName,
            p.bankName,
            p.refNumber?.let { "ref $it" } ?: p.checkNumber?.let { "check $it" },
            DateText.short(p.date),
        ).joinToString(" · "),
        onClick = {},
        trailing = {
            Text(p.amount?.formatted() ?: "—", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            if (p.hasUnapplied) StatusChip("On Hold", "Unapplied ${p.unappliedAmount!!.formatted()}")
            else StatusChip("Completed", "Applied" + (p.appliedAmount?.let { " ${it.formatted()}" } ?: ""))
        },
    )
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
