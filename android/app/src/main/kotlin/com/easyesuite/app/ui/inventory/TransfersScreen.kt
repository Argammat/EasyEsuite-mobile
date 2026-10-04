package com.easyesuite.app.ui.inventory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.easyesuite.app.di.AppContainer
import com.easyesuite.app.ui.Routes
import com.easyesuite.app.ui.common.ChipRow
import com.easyesuite.app.ui.common.PagedList
import com.easyesuite.app.ui.common.PagedListViewModel
import com.easyesuite.app.ui.common.RowDivider
import com.easyesuite.app.ui.common.SectionTitle
import com.easyesuite.app.ui.common.StatusChip
import com.easyesuite.app.ui.common.Thumb
import com.easyesuite.app.ui.common.userMessage
import com.easyesuite.app.ui.items.WarehousePicker
import com.easyesuite.core.model.CreateTransferRequest
import com.easyesuite.core.model.ItemSummary
import com.easyesuite.core.model.Page
import com.easyesuite.core.model.PageQuery
import com.easyesuite.core.model.Transfer
import com.easyesuite.core.model.TransferLineRequest
import com.easyesuite.core.model.TransferStatus
import com.easyesuite.core.model.Warehouse
import com.easyesuite.core.util.DateText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

class TransfersViewModel(private val graph: AppContainer.Graph) : PagedListViewModel<Transfer>() {
    val status = MutableStateFlow<String?>(null)
    init { refresh() }
    fun setStatus(s: String?) { status.value = s; refresh() }
    override suspend fun fetch(page: PageQuery): Page<Transfer> = graph.inventory.transfers(status.value, page)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(graph: AppContainer.Graph, nav: NavHostController) {
    val vm: TransfersViewModel = viewModel { TransfersViewModel(graph) }
    val state by vm.state.collectAsState()
    val status by vm.status.collectAsState()
    val created = nav.currentBackStackEntry?.savedStateHandle?.get<Boolean>("transfer_created")
    LaunchedEffect(created) { if (created == true) { vm.refresh(); nav.currentBackStackEntry?.savedStateHandle?.set("transfer_created", false) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Transfers") }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { nav.navigate(Routes.newTransfer()) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New transfer") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ChipRow(options = listOf(null to "All") + TransferStatus.all.map { it to it }, selected = status, onSelect = vm::setStatus)
            Spacer(Modifier.height(4.dp))
            PagedList(state = state, onRefresh = vm::refresh, onLoadMore = vm::loadMore, emptyTitle = "No transfers") { rows ->
                items(rows.size, key = { rows[it].id }) { i ->
                    val t = rows[i]
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.number, fontWeight = FontWeight.Medium)
                            Text("${t.fromWarehouse ?: "?"} → ${t.toWarehouse ?: "?"}", style = MaterialTheme.typography.bodySmall)
                            Text("${t.totalQuantity ?: 0} units · ${DateText.short(t.date)}" + (t.remarks?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                        }
                        StatusChip(t.status)
                    }
                    RowDivider()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// New transfer
// ---------------------------------------------------------------------------------------------

data class TransferLineUi(val item: ItemSummary, val quantity: Int)

data class NewTransferUi(
    val warehouses: List<Warehouse> = emptyList(),
    val from: Int? = null,
    val to: Int? = null,
    val remarks: String = "",
    val lines: List<TransferLineUi> = emptyList(),
    val search: String = "",
    val searchResults: List<ItemSummary> = emptyList(),
    val searching: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
) {
    val canSubmit: Boolean get() = from != null && to != null && from != to && lines.isNotEmpty() && lines.all { it.quantity > 0 } && !submitting
}

class NewTransferViewModel(private val graph: AppContainer.Graph, preselectedItem: Long?) : ViewModel() {
    val ui = MutableStateFlow(NewTransferUi())

    init {
        viewModelScope.launch {
            runCatching { graph.items.warehouses() }.onSuccess { list ->
                ui.update { it.copy(warehouses = list, from = list.firstOrNull { w -> w.isDefault }?.id ?: list.firstOrNull()?.id) }
            }
            if (preselectedItem != null) runCatching { graph.items.item(preselectedItem) }.onSuccess { d ->
                addItem(ItemSummary(id = d.id, name = d.name, upcCode = d.upcCode, marketplaceTitle = d.marketplaceTitle, onHand = d.onHand, image1 = d.image1, images = d.images))
            }
        }
    }

    fun set(transform: NewTransferUi.() -> NewTransferUi) = ui.update { it.transform().copy(error = null) }

    fun search(q: String) {
        ui.update { it.copy(search = q) }
        if (q.length < 2) { ui.update { it.copy(searchResults = emptyList()) }; return }
        viewModelScope.launch {
            ui.update { it.copy(searching = true) }
            val res = runCatching { graph.items.catalog(search = q, page = PageQuery(limit = 8)) }.getOrNull()
            ui.update { it.copy(searching = false, searchResults = res?.results ?: emptyList()) }
        }
    }

    fun addByBarcode(code: String) {
        viewModelScope.launch {
            val found = runCatching { graph.items.findByBarcode(code) }.getOrDefault(emptyList())
            if (found.isEmpty()) ui.update { it.copy(error = "No item found for “$code”") } else addItem(found.first())
        }
    }

    fun addItem(item: ItemSummary) = ui.update { s ->
        val existing = s.lines.firstOrNull { it.item.id == item.id }
        val lines = if (existing != null) s.lines.map { if (it.item.id == item.id) it.copy(quantity = it.quantity + 1) else it } else s.lines + TransferLineUi(item, 1)
        s.copy(lines = lines, search = "", searchResults = emptyList())
    }

    fun setQty(itemId: Long, qty: Int) = ui.update { s -> s.copy(lines = s.lines.map { if (it.item.id == itemId) it.copy(quantity = qty.coerceAtLeast(0)) else it }) }
    fun remove(itemId: Long) = ui.update { s -> s.copy(lines = s.lines.filterNot { it.item.id == itemId }) }

    fun submit() {
        val s = ui.value
        if (!s.canSubmit) return
        ui.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                graph.inventory.createTransfer(
                    CreateTransferRequest(
                        fromWarehouse = s.from!!, toWarehouse = s.to!!, date = LocalDate.now().toString(),
                        remarks = s.remarks.ifBlank { null }, items = s.lines.map { TransferLineRequest(it.item.id, it.quantity) },
                    ),
                )
                ui.update { it.copy(submitting = false, done = true) }
            } catch (e: Exception) {
                ui.update { it.copy(submitting = false, error = e.userMessage()) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTransferScreen(graph: AppContainer.Graph, nav: NavHostController, preselectedItem: Long?) {
    val vm: NewTransferViewModel = viewModel(key = "new-transfer") { NewTransferViewModel(graph, preselectedItem) }
    val ui by vm.ui.collectAsState()

    val scanned = nav.currentBackStackEntry?.savedStateHandle?.get<String>("scanned_code")
    LaunchedEffect(scanned) { if (!scanned.isNullOrBlank()) { nav.currentBackStackEntry?.savedStateHandle?.remove<String>("scanned_code"); vm.addByBarcode(scanned) } }
    LaunchedEffect(ui.done) { if (ui.done) { nav.previousBackStackEntry?.savedStateHandle?.set("transfer_created", true); nav.popBackStack() } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("New transfer") }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            val options = ui.warehouses.map { it.id to it.name }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WarehousePicker(options, ui.from, { id -> vm.set { copy(from = id) } }, Modifier.weight(1f), label = "From")
                WarehousePicker(options, ui.to, { id -> vm.set { copy(to = id) } }, Modifier.weight(1f), label = "To")
            }
            if (ui.from != null && ui.from == ui.to) Text("Pick two different warehouses.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

            SectionTitle("Items")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = ui.search, onValueChange = vm::search, label = { Text("Add item by SKU / UPC / title") }, singleLine = true, modifier = Modifier.weight(1f))
                IconButton(onClick = { nav.navigate(Routes.scanner("return")) }) { Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan") }
            }
            ui.searchResults.forEach { r ->
                Row(Modifier.fillMaxWidth().clickable { vm.addItem(r) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(r.primaryImage, size = 36); Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) { Text(r.name, style = MaterialTheme.typography.bodyMedium); Text("${r.onHand ?: 0} on hand", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                    Icon(Icons.Default.Add, contentDescription = "Add")
                }
            }
            ui.lines.forEach { line ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(line.item.primaryImage, size = 40); Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) { Text(line.item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium); Text("${line.item.onHand ?: 0} on hand company-wide", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                    OutlinedTextField(
                        value = if (line.quantity == 0) "" else line.quantity.toString(), onValueChange = { v -> vm.setQty(line.item.id, v.filter { it.isDigit() }.toIntOrNull() ?: 0) },
                        label = { Text("Qty") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(84.dp),
                    )
                    IconButton(onClick = { vm.remove(line.item.id) }) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
                }
            }
            if (ui.lines.isEmpty()) Text("No items yet — search above or scan a barcode.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

            SectionTitle("Remarks")
            OutlinedTextField(value = ui.remarks, onValueChange = { v -> vm.set { copy(remarks = v) } }, modifier = Modifier.fillMaxWidth(), minLines = 2, placeholder = { Text("Why is this moving?") })

            if (ui.error != null) { Spacer(Modifier.height(8.dp)); Text(ui.error!!, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(16.dp))
            Button(onClick = vm::submit, enabled = ui.canSubmit, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text(if (ui.submitting) "Creating…" else "Create transfer") }
            Spacer(Modifier.height(32.dp))
        }
    }
}
