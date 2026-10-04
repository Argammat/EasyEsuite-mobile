package com.easyesuite.app.ui.orders

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.easyesuite.app.ui.theme.Green
import com.easyesuite.core.model.Shipment
import com.easyesuite.core.model.ShipmentRate
import com.easyesuite.core.model.ShipmentStatus
import com.easyesuite.core.model.TrackingDetails
import com.easyesuite.core.util.DateText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class ShipmentUi(
    val shipment: Shipment,
    val tracking: TrackingDetails? = null,
    val rates: List<ShipmentRate> = emptyList(),
    val selectedRate: String? = null,
    val ratesOpen: Boolean = false,
    val holdOpen: Boolean = false,
    val holdReason: String = "",
    /** UPCs verified so far during scan-to-verify (local view; the server is the source of truth). */
    val verified: Map<String, Int> = emptyMap(),
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val allVerified: Boolean get() = shipment.items.isNotEmpty() && shipment.items.all { (verified[it.upc ?: ""] ?: 0) >= it.quantity }
}

class ShipmentDetailViewModel(private val graph: AppContainer.Graph, private val id: String) : ViewModel() {
    val state = MutableStateFlow<Load<ShipmentUi>>(Load.Loading)

    init { load() }

    fun load() {
        state.value = Load.Loading
        viewModelScope.launch {
            try {
                val s = graph.shipping.shipment(id)
                val tracking = if (!s.trackingCode.isNullOrBlank()) runCatching { graph.shipping.tracking(id) }.getOrNull() else null
                state.value = Load.Ready(ShipmentUi(s, tracking, rates = s.rates, selectedRate = s.selectedRate?.id ?: s.rates.firstOrNull()?.id))
            } catch (e: Exception) {
                state.value = Load.Failed(e.userMessage())
            }
        }
    }

    private fun edit(f: (ShipmentUi) -> ShipmentUi) = state.update { s -> if (s is Load.Ready) Load.Ready(f(s.value)) else s }
    private fun current(): ShipmentUi? = (state.value as? Load.Ready<ShipmentUi>)?.value

    private fun run(label: String, block: suspend () -> Shipment?) {
        edit { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val updated = block()
                edit { it.copy(busy = false, message = label, shipment = updated ?: it.shipment, rates = updated?.rates ?: it.rates) }
                if (updated == null) load()
            } catch (e: Exception) {
                edit { it.copy(busy = false, error = e.userMessage()) }
            }
        }
    }

    fun fetchRates() {
        edit { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val s = graph.shipping.rerate(id)
                edit { it.copy(busy = false, shipment = s, rates = s.rates, selectedRate = s.selectedRate?.id ?: s.rates.firstOrNull()?.id, ratesOpen = true) }
            } catch (e: Exception) {
                edit { it.copy(busy = false, error = e.userMessage()) }
            }
        }
    }

    fun selectRate(rateId: String?) = edit { it.copy(selectedRate = rateId) }
    fun closeRates() = edit { it.copy(ratesOpen = false) }

    fun buyLabel() {
        val ui = current() ?: return
        run("Label purchased") { graph.shipping.buyLabel(id, ui.selectedRate).also { edit { u -> u.copy(ratesOpen = false) } } }
    }

    fun openHold(open: Boolean) = edit { it.copy(holdOpen = open) }
    fun setHoldReason(r: String) = edit { it.copy(holdReason = r) }
    fun hold() { val ui = current() ?: return; run("Shipment put on hold") { graph.shipping.hold(id, ui.holdReason.ifBlank { null }).also { edit { u -> u.copy(holdOpen = false) } } } }
    fun unhold() = run("Hold released") { graph.shipping.unhold(id) }

    /** Scan-to-verify: send the UPC to the server, mirror the count locally. */
    fun verify(upc: String) {
        val ui = current() ?: return
        val expected = ui.shipment.items.firstOrNull { it.upc == upc || it.sku == upc }
        if (expected == null) { edit { it.copy(error = "“$upc” is not on this shipment.") }; return }
        edit { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val res = graph.shipping.verifyScan(id, expected.upc ?: upc)
                val serverStatus = (res as? JsonObject)?.get("pack_verification_status")?.let { (it as? JsonPrimitive)?.contentOrNull }
                edit {
                    val count = (it.verified[expected.upc ?: ""] ?: 0) + 1
                    it.copy(
                        busy = false,
                        verified = it.verified + ((expected.upc ?: "") to count),
                        shipment = if (serverStatus != null) it.shipment.copy(packVerificationStatus = serverStatus) else it.shipment,
                        message = "Verified ${expected.title ?: upc}",
                    )
                }
            } catch (e: Exception) {
                edit { it.copy(busy = false, error = e.userMessage()) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShipmentDetailScreen(graph: AppContainer.Graph, nav: NavHostController, id: String) {
    val vm: ShipmentDetailViewModel = viewModel(key = "shipment-$id") { ShipmentDetailViewModel(graph, id) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val scanned = nav.currentBackStackEntry?.savedStateHandle?.get<String>("scanned_code")
    LaunchedEffect(scanned) { if (!scanned.isNullOrBlank()) { nav.currentBackStackEntry?.savedStateHandle?.remove<String>("scanned_code"); vm.verify(scanned) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((state as? Load.Ready<ShipmentUi>)?.value?.shipment?.orderNumber ?: "Shipment") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        when (val s = state) {
            is Load.Loading, Load.Idle -> LoadingBox(Modifier.padding(padding))
            is Load.Failed -> ErrorBox(s.message, onRetry = vm::load, modifier = Modifier.padding(padding))
            is Load.Ready -> {
                val ui = s.value
                val sh = ui.shipment
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${sh.marketplaceName ?: ""} · ${sh.orderShippingMethod ?: ""}", style = MaterialTheme.typography.titleMedium)
                            Text(listOfNotNull(sh.destination.takeIf { it.isNotBlank() }, sh.shipByDate?.let { "ship by ${DateText.short(it)}" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        StatusChip(sh.status, ShipmentStatus.label(sh.status))
                    }
                    if (sh.manualHold || !sh.holdReason.isNullOrBlank()) Text("On hold: ${sh.holdReason ?: ""}", color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 4.dp))
                    if (!sh.exceptionDetail.isNullOrBlank()) Text("Exception: ${sh.exceptionCode ?: ""} ${sh.exceptionDetail}", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                    if (ui.message != null) Text(ui.message, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 8.dp))
                    if (ui.error != null) Text(ui.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            sh.hasLabel -> Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sh.labelUrl))) }) { Text("Open label") }
                            sh.canBuyLabel -> Button(onClick = vm::fetchRates, enabled = !ui.busy) { Text(if (ui.busy) "Working…" else "Get rates & buy label") }
                        }
                        if (sh.status == ShipmentStatus.ON_HOLD || sh.manualHold) OutlinedButton(onClick = vm::unhold, enabled = !ui.busy) { Text("Release hold") }
                        else if (sh.status == ShipmentStatus.TO_SHIP) OutlinedButton(onClick = { vm.openHold(true) }, enabled = !ui.busy) { Text("Hold") }
                    }

                    SectionTitle("Pack verification · ${sh.packVerificationStatus ?: "pending"}")
                    sh.items.forEach { it ->
                        val done = (ui.verified[it.upc ?: ""] ?: 0) >= it.quantity
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, contentDescription = null, tint = if (done) Green else MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.width(8.dp))
                            Thumb(it.imageUrl, size = 40); Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(it.title ?: it.sku ?: "", fontWeight = FontWeight.Medium, maxLines = 2)
                                Text("${it.sku ?: ""} · UPC ${it.upc ?: "—"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Text("${ui.verified[it.upc ?: ""] ?: 0}/${it.quantity}", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (sh.status == ShipmentStatus.TO_SHIP) {
                        OutlinedButton(onClick = { nav.navigate(Routes.scanner("return")) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.QrCodeScanner, null, Modifier.width(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (ui.allVerified) "All items verified" else "Scan item to verify")
                        }
                    }

                    SectionTitle("Details")
                    KeyValueRow("Order", sh.orderNumber)
                    KeyValueRow("Carrier", listOfNotNull(sh.orderShippingCarrier, sh.selectedRate?.service).joinToString(" · ").ifBlank { null })
                    KeyValueRow("Label cost", sh.shipmentCost?.formatted())
                    KeyValueRow("Order total", sh.orderTotal?.formatted())
                    KeyValueRow("Tracking", sh.trackingCode)
                    KeyValueRow("From", sh.fromAddressName)
                    KeyValueRow("Purchased", sh.purchasedAt?.let { DateText.long(it) })
                    KeyValueRow("Delivered", sh.deliveredAt?.let { DateText.long(it) })
                    KeyValueRow("Provider", sh.labelProvider)

                    ui.tracking?.let { t ->
                        SectionTitle("Tracking · ${t.status ?: ""}")
                        t.estDeliveryDate?.let { KeyValueRow("Estimated delivery", DateText.short(it)) }
                        t.timeline.forEach { ev ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(ev.text, style = MaterialTheme.typography.bodyMedium)
                                Text(listOfNotNull(ev.timestamp?.let { DateText.long(it) }, ev.place).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }

                if (ui.ratesOpen) {
                    AlertDialog(
                        onDismissRequest = vm::closeRates,
                        title = { Text("Choose a rate") },
                        text = {
                            Column {
                                if (ui.rates.isEmpty()) Text("No rates came back. Check the ship-from address and package dimensions in the web app.")
                                ui.rates.forEach { r ->
                                    Row(Modifier.fillMaxWidth().clickable { vm.selectRate(r.id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = ui.selectedRate == r.id, onClick = { vm.selectRate(r.id) })
                                        Column(Modifier.weight(1f)) {
                                            Text(r.label, fontWeight = FontWeight.Medium)
                                            (r.deliveryDays ?: r.estDeliveryDays)?.let { Text("$it day${if (it == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                                        }
                                        Text(r.rate?.formatted() ?: "", fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                if (ui.error != null) Text(ui.error, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        confirmButton = { Button(onClick = vm::buyLabel, enabled = !ui.busy && (ui.rates.isEmpty() || ui.selectedRate != null)) { Text(if (ui.busy) "Buying…" else "Buy label") } },
                        dismissButton = { TextButton(onClick = vm::closeRates) { Text("Cancel") } },
                    )
                }
                if (ui.holdOpen) {
                    AlertDialog(
                        onDismissRequest = { vm.openHold(false) },
                        title = { Text("Put shipment on hold") },
                        text = { OutlinedTextField(value = ui.holdReason, onValueChange = vm::setHoldReason, label = { Text("Reason") }, modifier = Modifier.fillMaxWidth()) },
                        confirmButton = { Button(onClick = vm::hold, enabled = !ui.busy) { Text("Hold") } },
                        dismissButton = { TextButton(onClick = { vm.openHold(false) }) { Text("Cancel") } },
                    )
                }
            }
        }
    }
}
