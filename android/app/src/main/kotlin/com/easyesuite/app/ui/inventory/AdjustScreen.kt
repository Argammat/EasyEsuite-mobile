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
import androidx.compose.material3.FilterChip
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
import com.easyesuite.app.ui.common.SectionTitle
import com.easyesuite.app.ui.common.Thumb
import com.easyesuite.app.ui.common.userMessage
import com.easyesuite.app.ui.items.WarehousePicker
import com.easyesuite.core.model.AdjustmentLineRequest
import com.easyesuite.core.model.CreateAdjustmentRequest
import com.easyesuite.core.model.ItemSummary
import com.easyesuite.core.model.PageQuery
import com.easyesuite.core.model.Warehouse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class AdjustLine(val item: ItemSummary, val delta: Int, val reason: String = "")

data class AdjustUi(
    val warehouses: List<Warehouse> = emptyList(),
    val warehouseId: Int? = null,
    val lines: List<AdjustLine> = emptyList(),
    val memo: String = "",
    val search: String = "",
    val searchResults: List<ItemSummary> = emptyList(),
    val submitting: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
) {
    val canSubmit: Boolean get() = warehouseId != null && lines.isNotEmpty() && lines.all { it.delta != 0 } && !submitting
}

val adjustmentReasons = listOf("Cycle count", "Damaged", "Lost", "Found", "Return to stock", "Sample", "Other")

class AdjustViewModel(private val graph: AppContainer.Graph, preselectedItem: Long?, preselectedWarehouse: Int?) : ViewModel() {
    val ui = MutableStateFlow(AdjustUi(warehouseId = preselectedWarehouse))

    init {
        viewModelScope.launch {
            runCatching { graph.items.warehouses() }.onSuccess { list ->
                ui.update { it.copy(warehouses = list, warehouseId = it.warehouseId ?: list.firstOrNull { w -> w.isDefault }?.id ?: list.firstOrNull()?.id) }
            }
            if (preselectedItem != null) runCatching { graph.items.item(preselectedItem) }.onSuccess { d ->
                addItem(ItemSummary(id = d.id, name = d.name, upcCode = d.upcCode, marketplaceTitle = d.marketplaceTitle, onHand = d.onHand, image1 = d.image1, images = d.images))
            }
        }
    }

    fun set(transform: AdjustUi.() -> AdjustUi) = ui.update { it.transform().copy(error = null) }

    fun search(q: String) {
        ui.update { it.copy(search = q) }
        if (q.length < 2) { ui.update { it.copy(searchResults = emptyList()) }; return }
        viewModelScope.launch {
            val res = runCatching { graph.items.catalog(search = q, page = PageQuery(limit = 8)) }.getOrNull()
            ui.update { it.copy(searchResults = res?.results ?: emptyList()) }
        }
    }

    fun addByBarcode(code: String) {
        viewModelScope.launch {
            val found = runCatching { graph.items.findByBarcode(code) }.getOrDefault(emptyList())
            if (found.isEmpty()) ui.update { it.copy(error = "No item found for “$code”") } else addItem(found.first())
        }
    }

    fun addItem(item: ItemSummary) = ui.update { s ->
        if (s.lines.any { it.item.id == item.id }) s.copy(search = "", searchResults = emptyList())
        else s.copy(lines = s.lines + AdjustLine(item, 0, adjustmentReasons.first()), search = "", searchResults = emptyList())
    }

    fun setDelta(itemId: Long, delta: Int) = ui.update { s -> s.copy(lines = s.lines.map { if (it.item.id == itemId) it.copy(delta = delta) else it }) }
    fun setReason(itemId: Long, reason: String) = ui.update { s -> s.copy(lines = s.lines.map { if (it.item.id == itemId) it.copy(reason = reason) else it }) }
    fun remove(itemId: Long) = ui.update { s -> s.copy(lines = s.lines.filterNot { it.item.id == itemId }) }

    fun submit() {
        val s = ui.value
        if (!s.canSubmit) return
        ui.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                graph.inventory.createAdjustment(
                    CreateAdjustmentRequest(
                        warehouse = s.warehouseId!!, date = LocalDate.now().toString(), memo = s.memo.ifBlank { null },
                        items = s.lines.map { AdjustmentLineRequest(it.item.id, it.delta, it.reason.ifBlank { null }) },
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
fun AdjustScreen(graph: AppContainer.Graph, nav: NavHostController, preselectedItem: Long?, preselectedWarehouse: Int?) {
    val vm: AdjustViewModel = viewModel(key = "adjust") { AdjustViewModel(graph, preselectedItem, preselectedWarehouse) }
    val ui by vm.ui.collectAsState()

    val scanned = nav.currentBackStackEntry?.savedStateHandle?.get<String>("scanned_code")
    LaunchedEffect(scanned) { if (!scanned.isNullOrBlank()) { nav.currentBackStackEntry?.savedStateHandle?.remove<String>("scanned_code"); vm.addByBarcode(scanned) } }
    LaunchedEffect(ui.done) { if (ui.done) nav.popBackStack() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Adjust stock") }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            WarehousePicker(ui.warehouses.map { it.id to it.name }, ui.warehouseId, { id -> vm.set { copy(warehouseId = id) } }, Modifier.fillMaxWidth())

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
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Thumb(line.item.primaryImage, size = 40); Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) { Text(line.item.name, fontWeight = FontWeight.Medium); Text("${line.item.onHand ?: 0} on hand", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                        OutlinedTextField(
                            value = if (line.delta == 0) "" else line.delta.toString(),
                            onValueChange = { v -> vm.setDelta(line.item.id, v.filter { it.isDigit() || it == '-' }.toIntOrNull() ?: 0) },
                            label = { Text("+/−") }, singleLine = true, modifier = Modifier.width(96.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        IconButton(onClick = { vm.remove(line.item.id) }) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
                    }
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(adjustmentReasons.size) { i -> val r = adjustmentReasons[i]; FilterChip(selected = line.reason == r, onClick = { vm.setReason(line.item.id, r) }, label = { Text(r) }) }
                    }
                }
            }
            if (ui.lines.isEmpty()) Text("Add items, then enter a positive number to add stock or a negative one to remove it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

            SectionTitle("Memo")
            OutlinedTextField(value = ui.memo, onValueChange = { v -> vm.set { copy(memo = v) } }, modifier = Modifier.fillMaxWidth(), minLines = 2)

            if (ui.error != null) { Spacer(Modifier.height(8.dp)); Text(ui.error!!, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(16.dp))
            Button(onClick = vm::submit, enabled = ui.canSubmit, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text(if (ui.submitting) "Posting…" else "Post adjustment") }
            Spacer(Modifier.height(32.dp))
        }
    }
}
