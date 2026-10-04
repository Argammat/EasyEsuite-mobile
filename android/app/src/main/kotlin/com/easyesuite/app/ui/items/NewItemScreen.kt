package com.easyesuite.app.ui.items

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.easyesuite.app.di.AppContainer
import com.easyesuite.app.ui.Routes
import com.easyesuite.app.ui.common.SectionTitle
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewItemScreen(graph: AppContainer.Graph, nav: NavHostController, initialUpc: String?) {
    val vm: NewItemViewModel = viewModel(key = "new-item") { NewItemViewModel(graph, initialUpc) }
    val ui by vm.ui.collectAsState()
    val f = ui.form
    val context = LocalContext.current

    // Barcode scanned from the scanner screen (mode=return) lands in our savedStateHandle.
    val scanned = nav.currentBackStackEntry?.savedStateHandle?.get<String>("scanned_code")
    LaunchedEffect(scanned) {
        if (!scanned.isNullOrBlank()) {
            nav.currentBackStackEntry?.savedStateHandle?.remove<String>("scanned_code")
            vm.setUpc(scanned); vm.lookupUpc()
        }
    }

    // Photo capture / pick → bytes → upload.
    var pendingCapture by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCapture
        if (ok && uri != null) readBytes(context, uri)?.let(vm::addPhoto)
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) readBytes(context, uri)?.let(vm::addPhoto)
    }

    // Done → go to the created item (after acknowledging an opening-stock warning, if any).
    val created = ui.created
    val goToCreated: () -> Unit = {
        if (created != null) {
            nav.previousBackStackEntry?.savedStateHandle?.set("item_created", true)
            nav.navigate(Routes.item(created.id)) { popUpTo(Routes.ITEM_NEW) { inclusive = true } }
        }
    }
    LaunchedEffect(created) { if (created != null && ui.stockWarning == null) goToCreated() }
    if (created != null && ui.stockWarning != null) {
        AlertDialog(
            onDismissRequest = goToCreated,
            confirmButton = { TextButton(onClick = goToCreated) { Text("OK") } },
            title = { Text("Item created") },
            text = { Text(ui.stockWarning!!) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New item") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    TextButton(onClick = vm::submit, enabled = f.canSubmit && !ui.submitting) {
                        if (ui.submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Save")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            // ---- Barcode / UPC ---------------------------------------------------------------
            SectionTitle("Barcode")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = f.upc, onValueChange = vm::setUpc, label = { Text("UPC / EAN") }, singleLine = true,
                    isError = f.upcError != null, supportingText = { Text(f.upcError ?: ui.lookupMessage ?: "Scan or type, then look up to prefill from the product database.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                    modifier = Modifier.weight(1f),
                    trailingIcon = {
                        Row {
                            IconButton(onClick = vm::lookupUpc, enabled = !ui.lookingUp) {
                                if (ui.lookingUp) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Search, contentDescription = "Look up")
                            }
                            IconButton(onClick = { nav.navigate(Routes.scanner("return")) }) { Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan") }
                        }
                    },
                )
            }

            // ---- Photos -------------------------------------------------------------------------
            SectionTitle("Photos")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.photos.size) { i ->
                    val p = ui.photos[i]
                    Box(
                        Modifier.size(88.dp).clip(RoundedCornerShape(10.dp))
                            .border(2.dp, if (p.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                            .clickable { vm.togglePhoto(p.url) },
                    ) {
                        AsyncImage(model = p.url, contentDescription = null, modifier = Modifier.fillMaxSize())
                        if (p.selected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            onClick = {
                                val uri = newCaptureUri(context); pendingCapture = uri; takePicture.launch(uri)
                            },
                            enabled = !ui.uploading, modifier = Modifier.height(40.dp),
                        ) { Icon(Icons.Default.AddAPhoto, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Camera") }
                        OutlinedButton(
                            onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            enabled = !ui.uploading, modifier = Modifier.height(40.dp),
                        ) { Icon(Icons.Default.PhotoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Library") }
                    }
                }
            }
            if (ui.uploading) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Uploading photo…", style = MaterialTheme.typography.bodySmall) }

            // ---- Product information ------------------------------------------------------------
            SectionTitle("Product information")
            Field(f.title, vm::setTitle, "Marketplace title")
            Field(f.name, vm::setName, "Item / SKU name *", error = if (f.nameTouched || f.name.isNotBlank()) f.nameError else null, help = "Short, dash-separated. This is the ERP's identifier.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(f.brand, { v -> vm.edit { copy(brand = v) } }, "Brand", Modifier.weight(1f))
                Field(f.platform, { v -> vm.edit { copy(platform = v) } }, "Platform", Modifier.weight(1f))
            }
            Field(f.manufacturer, { v -> vm.edit { copy(manufacturer = v) } }, "Manufacturer")
            Field(f.description, { v -> vm.edit { copy(description = v) } }, "Description", singleLine = false, minLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = f.conditionNew, onClick = { vm.edit { copy(conditionNew = true) } }, label = { Text("New") })
                FilterChip(selected = !f.conditionNew, onClick = { vm.edit { copy(conditionNew = false) } }, label = { Text("Other / set later") })
            }

            // ---- Commercial ---------------------------------------------------------------------
            SectionTitle("Cost & stock")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(f.cost, { v -> vm.edit { copy(cost = v) } }, "Cost (USD)", Modifier.weight(1f), keyboard = KeyboardType.Decimal, error = f.costError)
                Field(f.reorderPoint, { v -> vm.edit { copy(reorderPoint = v.filter { it.isDigit() }) } }, "Reorder point", Modifier.weight(1f), keyboard = KeyboardType.Number)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(f.initialQty, { v -> vm.edit { copy(initialQty = v.filter { it.isDigit() }) } }, "Opening qty", Modifier.weight(1f), keyboard = KeyboardType.Number, error = f.qtyError)
                WarehousePicker(
                    options = ui.warehouses.map { it.id to it.name }, selected = f.warehouseId,
                    onSelect = { id -> vm.edit { copy(warehouseId = id) } }, modifier = Modifier.weight(1.4f),
                )
            }

            // ---- Dimensions ---------------------------------------------------------------------
            SectionTitle("Weight & dimensions")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(f.weight, { v -> vm.edit { copy(weight = v) } }, "Weight", Modifier.weight(1f), keyboard = KeyboardType.Decimal)
                UnitPicker(listOf("Pounds", "Ounces", "Kilograms", "Grams"), f.weightUnit, { vm.edit { copy(weightUnit = it) } }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(f.length, { v -> vm.edit { copy(length = v) } }, "L", Modifier.weight(1f), keyboard = KeyboardType.Decimal)
                Field(f.width, { v -> vm.edit { copy(width = v) } }, "W", Modifier.weight(1f), keyboard = KeyboardType.Decimal)
                Field(f.height, { v -> vm.edit { copy(height = v) } }, "H", Modifier.weight(1f), keyboard = KeyboardType.Decimal)
                UnitPicker(listOf("Inches", "Centimeters"), f.dimensionUnit, { vm.edit { copy(dimensionUnit = it) } }, Modifier.weight(1.3f))
            }

            if (ui.error != null) {
                Spacer(Modifier.height(12.dp))
                Text(ui.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = vm::submit, enabled = f.canSubmit && !ui.submitting, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text(if (ui.submitting) "Saving…" else "Create item")
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    error: String? = null,
    help: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine, minLines = minLines,
        isError = error != null,
        supportingText = if (error != null || help != null) ({ Text(error ?: help!!) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarehousePicker(options: List<Pair<Int, String>>, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, label: String = "Warehouse") {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: "", onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor().fillMaxWidth().padding(vertical = 4.dp),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); open = false }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitPicker(options: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected, onValueChange = {}, readOnly = true, singleLine = true, label = { Text("Unit") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor().fillMaxWidth().padding(vertical = 4.dp),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { DropdownMenuItem(text = { Text(it) }, onClick = { onSelect(it); open = false }) }
        }
    }
}

private fun newCaptureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "item-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun readBytes(context: Context, uri: Uri): ByteArray? =
    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
