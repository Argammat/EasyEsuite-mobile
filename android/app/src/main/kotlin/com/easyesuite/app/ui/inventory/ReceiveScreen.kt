package com.easyesuite.app.ui.inventory

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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.easyesuite.app.di.AppContainer
import com.easyesuite.app.ui.common.EntityRow
import com.easyesuite.app.ui.common.LoadingBox
import com.easyesuite.app.ui.common.PagedList
import com.easyesuite.app.ui.common.PagedListViewModel
import com.easyesuite.app.ui.common.StatusChip
import com.easyesuite.app.ui.common.userMessage
import com.easyesuite.core.model.Page
import com.easyesuite.core.model.PageQuery
import com.easyesuite.core.model.PurchaseOrder
import com.easyesuite.core.model.PurchaseOrderLine
import com.easyesuite.core.model.ReceiptLine
import com.easyesuite.core.model.ReceiveRequest
import com.easyesuite.core.util.DateText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class ReceiveSheet(
    val po: PurchaseOrder,
    val lines: List<PurchaseOrderLine> = emptyList(),
    val quantities: Map<Long, Int> = emptyMap(),
    val memo: String = "",
    val loading: Boolean = true,
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val anyQuantity: Boolean get() = quantities.values.any { it > 0 }
}

class ReceiveViewModel(private val graph: AppContainer.Graph) : PagedListViewModel<PurchaseOrder>() {
    val sheet = MutableStateFlow<ReceiveSheet?>(null)
    val toast = MutableStateFlow<String?>(null)

    init { refresh() }

    override suspend fun fetch(page: PageQuery): Page<PurchaseOrder> = graph.inventory.purchaseOrders(page = page)

    fun open(po: PurchaseOrder) {
        sheet.value = ReceiveSheet(po)
        viewModelScope.launch {
            try {
                val lines = graph.inventory.purchaseOrderLines(po.id)
                // Default to receiving everything outstanding; the user trims from there.
                sheet.update { it?.copy(lines = lines, quantities = lines.associate { l -> l.id to l.remaining }, loading = false) }
            } catch (e: Exception) {
                sheet.update { it?.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    fun close() { sheet.value = null }
    fun setQty(lineId: Long, qty: Int) = sheet.update { it?.copy(quantities = it.quantities + (lineId to qty.coerceAtLeast(0))) }
    fun setMemo(m: String) = sheet.update { it?.copy(memo = m) }
    fun receiveAll() = sheet.update { s -> s?.copy(quantities = s.lines.associate { it.id to it.remaining }) }

    fun submit() {
        val s = sheet.value ?: return
        if (!s.anyQuantity || s.submitting) return
        sheet.update { it?.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                graph.inventory.receive(
                    ReceiveRequest(
                        purchaseOrder = s.po.id,
                        receivedDate = LocalDate.now().toString(),
                        memo = s.memo.ifBlank { null },
                        items = s.quantities.filter { it.value > 0 }.map { (lineId, qty) -> ReceiptLine(lineId, qty) },
                    ),
                )
                toast.value = "Received against ${s.po.number}"
                sheet.value = null
                refresh()
            } catch (e: Exception) {
                sheet.update { it?.copy(submitting = false, error = e.userMessage()) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(graph: AppContainer.Graph, nav: NavHostController) {
    val vm: ReceiveViewModel = viewModel { ReceiveViewModel(graph) }
    val state by vm.state.collectAsState()
    val sheet by vm.sheet.collectAsState()
    val toast by vm.toast.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Receive purchase orders") }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (toast != null) Text(toast!!, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(16.dp))
            PagedList(state = state, onRefresh = vm::refresh, onLoadMore = vm::loadMore, emptyTitle = "Nothing to receive", emptySubtitle = "Open and partially received POs show up here.") { rows ->
                items(rows.size, key = { rows[it].id }) { i ->
                    val po = rows[i]
                    EntityRow(
                        imageUrl = null, title = "${po.number} · ${po.company ?: po.vendor ?: ""}",
                        subtitle = "${po.warehouse ?: "—"} · ${po.totalQuantityOrdered ?: 0} units · ${DateText.short(po.date)}" + (po.memo?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""),
                        onClick = { vm.open(po) },
                        trailing = {
                            StatusChip(po.status)
                            Spacer(Modifier.height(4.dp))
                            Text(po.totalAmount?.formatted() ?: "", style = MaterialTheme.typography.labelMedium)
                        },
                    )
                }
            }
        }
    }

    sheet?.let { s ->
        ModalBottomSheet(onDismissRequest = vm::close) {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Text("Receive ${s.po.number}", style = MaterialTheme.typography.titleLarge)
                Text("${s.po.company ?: ""} → ${s.po.warehouse ?: ""}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                when {
                    s.loading -> LoadingBox(Modifier.height(120.dp))
                    s.lines.isEmpty() -> Text(s.error ?: "This PO has no lines to receive.", color = MaterialTheme.colorScheme.error)
                    else -> {
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) { TextButton(onClick = vm::receiveAll) { Text("Receive all remaining") } }
                        s.lines.forEach { line ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(line.displayName, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                                    Text("Ordered ${line.ordered} · received ${line.received} · remaining ${line.remaining}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                }
                                Spacer(Modifier.width(8.dp))
                                OutlinedTextField(
                                    value = (s.quantities[line.id] ?: 0).let { if (it == 0) "" else it.toString() },
                                    onValueChange = { v -> vm.setQty(line.id, v.filter { it.isDigit() }.toIntOrNull() ?: 0) },
                                    label = { Text("Qty") }, singleLine = true, modifier = Modifier.width(88.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    isError = (s.quantities[line.id] ?: 0) > line.remaining,
                                )
                            }
                        }
                        OutlinedTextField(value = s.memo, onValueChange = vm::setMemo, label = { Text("Memo (optional)") }, modifier = Modifier.fillMaxWidth())
                        if (s.error != null) Text(s.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = vm::submit, enabled = s.anyQuantity && !s.submitting, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                            Text(if (s.submitting) "Receiving…" else "Post receipt")
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
