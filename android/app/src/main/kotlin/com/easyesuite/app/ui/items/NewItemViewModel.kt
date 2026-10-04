package com.easyesuite.app.ui.items

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.easyesuite.app.di.AppContainer
import com.easyesuite.app.ui.common.userMessage
import com.easyesuite.core.model.CreateItemRequest
import com.easyesuite.core.model.ItemDetail
import com.easyesuite.core.model.ItemImage
import com.easyesuite.core.model.Warehouse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class PhotoEntry(val url: String, val selected: Boolean = true, val fromLookup: Boolean = false)

data class NewItemForm(
    val name: String = "",
    val nameTouched: Boolean = false,
    val upc: String = "",
    val title: String = "",
    val brand: String = "",
    val platform: String = "",
    val manufacturer: String = "",
    val description: String = "",
    val cost: String = "",
    val weight: String = "",
    val weightUnit: String = "Pounds",
    val length: String = "",
    val width: String = "",
    val height: String = "",
    val dimensionUnit: String = "Inches",
    val reorderPoint: String = "",
    val conditionNew: Boolean = true,
    val initialQty: String = "",
    val warehouseId: Int? = null,
) {
    val nameError: String? get() = when {
        name.isBlank() -> "Required"
        name.length > 64 -> "Keep it under 64 characters"
        name.any { it.isWhitespace() } -> "Use dashes instead of spaces (e.g. TCG-Destined-ETB)"
        else -> null
    }
    val costError: String? get() = cost.takeIf { it.isNotBlank() }?.let { if (it.toDoubleOrNull() == null) "Enter a number" else null }
    val qtyError: String? get() = initialQty.takeIf { it.isNotBlank() }?.let { if (it.toIntOrNull() == null || it.toInt() < 0) "Whole number" else null }
    val upcError: String? get() = upc.takeIf { it.isNotBlank() }?.let { u -> if (!u.all { it.isDigit() } || u.length !in 8..14) "8–14 digits" else null }
    val canSubmit: Boolean
        get() {
            val valid = nameError == null && costError == null && qtyError == null && upcError == null
            val qty = initialQty.toIntOrNull() ?: 0
            return valid && (qty == 0 || warehouseId != null)
        }
}

data class NewItemUi(
    val form: NewItemForm = NewItemForm(),
    val photos: List<PhotoEntry> = emptyList(),
    val warehouses: List<Warehouse> = emptyList(),
    val lookingUp: Boolean = false,
    val lookupMessage: String? = null,
    val uploading: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val created: ItemDetail? = null,
    val stockWarning: String? = null,
)

class NewItemViewModel(private val graph: AppContainer.Graph, initialUpc: String?) : ViewModel() {
    val ui = MutableStateFlow(NewItemUi(form = NewItemForm(upc = initialUpc ?: "")))

    init {
        viewModelScope.launch {
            runCatching { graph.items.warehouses() }.onSuccess { list ->
                ui.update { it.copy(warehouses = list, form = it.form.copy(warehouseId = it.form.warehouseId ?: list.firstOrNull { w -> w.isDefault }?.id ?: list.firstOrNull()?.id)) }
            }
        }
        if (!initialUpc.isNullOrBlank()) lookupUpc()
    }

    fun edit(transform: NewItemForm.() -> NewItemForm) = ui.update { it.copy(form = it.form.transform(), error = null) }

    /** Name auto-follows the title until the user types a name themselves. */
    fun setTitle(title: String) = ui.update {
        val f = it.form.copy(title = title)
        it.copy(form = if (f.nameTouched) f else f.copy(name = suggestSku(title)))
    }

    fun setName(name: String) = ui.update { it.copy(form = it.form.copy(name = name, nameTouched = true)) }

    fun setUpc(upc: String) = ui.update { it.copy(form = it.form.copy(upc = upc.filter { c -> c.isDigit() }.take(14)), lookupMessage = null) }

    fun lookupUpc() {
        val upc = ui.value.form.upc
        if (upc.length < 8) { ui.update { it.copy(lookupMessage = "Enter at least 8 digits") }; return }
        ui.update { it.copy(lookingUp = true, lookupMessage = null) }
        viewModelScope.launch {
            try {
                // Already in the catalog? Say so instead of creating a duplicate.
                val existing = runCatching { graph.items.findByBarcode(upc) }.getOrDefault(emptyList())
                if (existing.isNotEmpty()) {
                    ui.update { it.copy(lookingUp = false, lookupMessage = "Already in your catalog as ${existing.first().name}") }
                    return@launch
                }
                val p = graph.items.lookupUpc(upc)
                if (p == null) {
                    ui.update { it.copy(lookingUp = false, lookupMessage = "No product found for this UPC — fill in the details manually.") }
                } else {
                    ui.update { s ->
                        val f = s.form.copy(
                            title = p.title ?: s.form.title,
                            description = p.description ?: s.form.description,
                            brand = p.brand?.takeIf { it.isNotBlank() } ?: s.form.brand,
                            weight = p.weight?.filter { c -> c.isDigit() || c == '.' }?.takeIf { it.isNotBlank() } ?: s.form.weight,
                        )
                        val named = if (f.nameTouched || f.name.isNotBlank()) f else f.copy(name = suggestSku(f.title))
                        val lookupPhotos = p.images.distinct().map { PhotoEntry(it, selected = true, fromLookup = true) }
                        s.copy(
                            form = named,
                            photos = (s.photos.filterNot { it.fromLookup } + lookupPhotos),
                            lookingUp = false,
                            lookupMessage = "Prefilled from product database — review before saving.",
                        )
                    }
                }
            } catch (e: Exception) {
                ui.update { it.copy(lookingUp = false, lookupMessage = e.userMessage()) }
            }
        }
    }

    fun togglePhoto(url: String) = ui.update { s -> s.copy(photos = s.photos.map { if (it.url == url) it.copy(selected = !it.selected) else it }) }

    fun addPhoto(bytes: ByteArray) {
        ui.update { it.copy(uploading = true, error = null) }
        viewModelScope.launch {
            try {
                val url = graph.items.uploadImage(bytes)
                if (url == null) ui.update { it.copy(uploading = false, error = "Upload succeeded but no image URL came back.") }
                else ui.update { it.copy(uploading = false, photos = it.photos + PhotoEntry(url)) }
            } catch (e: Exception) {
                ui.update { it.copy(uploading = false, error = "Photo upload failed: ${e.userMessage()}") }
            }
        }
    }

    fun submit() {
        val s = ui.value
        val f = s.form
        if (!f.canSubmit || s.submitting) return
        ui.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                val req = CreateItemRequest(
                    name = f.name.trim(),
                    upcCode = f.upc.ifBlank { null },
                    marketplaceTitle = f.title.trim().ifBlank { null },
                    description = f.description.trim().ifBlank { null },
                    brand = f.brand.trim().ifBlank { null },
                    platform = f.platform.trim().ifBlank { null },
                    manufacturer = f.manufacturer.trim().ifBlank { null },
                    purchasePrice = f.cost.toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) },
                    weight = f.weight.toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) },
                    weightUnit = f.weightUnit,
                    length = f.length.ifBlank { null },
                    width = f.width.ifBlank { null },
                    height = f.height.ifBlank { null },
                    dimensionUnit = if (listOf(f.length, f.width, f.height).any { it.isNotBlank() }) f.dimensionUnit else null,
                    reorderPoint = f.reorderPoint.toIntOrNull(),
                    conditionId = if (f.conditionNew) 1 else null,
                    salesDescription = f.description.trim().ifBlank { null },
                    images = s.photos.filter { it.selected }.map { ItemImage(it.url) },
                )
                val created = graph.items.createItem(req)

                // Optional opening stock: an initial warehouse inventory record (what the web "initial stocking" does).
                var warning: String? = null
                val qty = f.initialQty.toIntOrNull() ?: 0
                if (qty > 0 && f.warehouseId != null) {
                    runCatching { graph.items.createOpeningStock(created.id, f.warehouseId, qty) }
                        .onFailure { warning = "Item saved, but opening stock could not be posted: ${it.userMessage()}" }
                }
                ui.update { it.copy(submitting = false, created = created, stockWarning = warning) }
            } catch (e: Exception) {
                ui.update { it.copy(submitting = false, error = e.userMessage()) }
            }
        }
    }

    companion object {
        /** "Pokémon Destined Rivals Elite Trainer Box" → "Pokemon-Destined-Rivals-Elite" (the ERP style: short, dash separated). */
        fun suggestSku(title: String): String {
            val normalized = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^A-Za-z0-9 ]"), " ")
            val words = normalized.split(' ').filter { it.isNotBlank() }
            val stop = setOf("the", "a", "an", "and", "of", "for", "with", "official", "new")
            val kept = words.filterNot { it.lowercase() in stop }.take(5)
            return kept.joinToString("-") { w -> w.replaceFirstChar { it.uppercase() } }.take(40).trimEnd('-')
        }
    }
}
