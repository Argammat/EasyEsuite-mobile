import SwiftUI
import PhotosUI
import EasyEsuiteKit

struct PhotoEntry: Identifiable, Hashable { let url: String; var selected = true; var fromLookup = false; var id: String { url } }

@MainActor
final class NewItemModel: ObservableObject {
    // Form
    @Published var name = "" { didSet { if name != oldValue && !settingName { nameTouched = true } } }
    @Published var upc = ""
    @Published var title = "" { didSet { if !nameTouched { settingName = true; name = NewItemModel.suggestSku(title); settingName = false } } }
    @Published var brand = ""
    @Published var platform = ""
    @Published var manufacturer = ""
    @Published var description = ""
    @Published var cost = ""
    @Published var weight = ""
    @Published var weightUnit = "Pounds"
    @Published var length = ""
    @Published var width = ""
    @Published var height = ""
    @Published var dimensionUnit = "Inches"
    @Published var reorderPoint = ""
    /// Id from `items/item_conditions/`; nil = let the backend default.
    @Published var conditionId: Int?
    /// Id from `items/tax_schedules/`; nil = let the backend default.
    @Published var taxScheduleId: Int?
    @Published var initialQty = ""
    @Published var warehouseId: Int?
    // State
    @Published var photos: [PhotoEntry] = []
    @Published var warehouses: [Warehouse] = []
    @Published var conditions: [ItemCondition] = []
    @Published var taxSchedules: [TaxSchedule] = []
    @Published var lookingUp = false
    @Published var lookupMessage: String?
    @Published var uploading = false
    @Published var submitting = false
    @Published var error: String?
    @Published var created: ItemDetail?
    @Published var stockWarning: String?

    private var nameTouched = false
    private var settingName = false
    let graph: AppContainer.Graph

    init(graph: AppContainer.Graph, initialUpc: String?) {
        self.graph = graph
        upc = initialUpc ?? ""
        Task {
            if let list = try? await graph.items.warehouses() {
                warehouses = list
                if warehouseId == nil { warehouseId = list.first(where: \.default)?.id ?? list.first?.id }
            }
            if !(initialUpc ?? "").isEmpty { await lookupUpc() }
        }
        // Form dropdowns (`items/item_conditions/`, `items/tax_schedules/`). Defaults: the "New" condition, the default schedule.
        Task {
            if let list = try? await graph.items.conditions() {
                conditions = list
                if conditionId == nil { conditionId = (list.first { $0.name?.caseInsensitiveCompare("new") == .orderedSame } ?? list.first)?.id }
            }
        }
        Task {
            if let list = try? await graph.items.taxSchedules() {
                taxSchedules = list
                if taxScheduleId == nil { taxScheduleId = (list.first { $0.isDefault == true } ?? list.first)?.id }
            }
        }
    }

    var nameError: String? {
        if name.trimmingCharacters(in: .whitespaces).isEmpty { return "Required" }
        if name.count > 64 { return "Keep it under 64 characters" }
        if name.contains(where: \.isWhitespace) { return "Use dashes instead of spaces (e.g. TCG-Destined-ETB)" }
        return nil
    }
    var costError: String? { cost.isEmpty ? nil : (Double(cost) == nil ? "Enter a number" : nil) }
    var qtyError: String? { initialQty.isEmpty ? nil : ((Int(initialQty) ?? -1) < 0 ? "Whole number" : nil) }
    var upcError: String? { upc.isEmpty ? nil : ((!upc.allSatisfy(\.isNumber) || !(8...14).contains(upc.count)) ? "8–14 digits" : nil) }
    var canSubmit: Bool {
        let valid = nameError == nil && costError == nil && qtyError == nil && upcError == nil
        return valid && ((Int(initialQty) ?? 0) == 0 || warehouseId != nil) && !submitting
    }

    func setUpc(_ s: String) { upc = String(s.filter(\.isNumber).prefix(14)); lookupMessage = nil }

    func lookupUpc() async {
        guard upc.count >= 8 else { lookupMessage = "Enter at least 8 digits"; return }
        lookingUp = true; lookupMessage = nil
        defer { lookingUp = false }
        if let existing = try? await graph.items.findByBarcode(upc), let first = existing.first {
            lookupMessage = "Already in your catalog as \(first.name)"
            return
        }
        do {
            guard let p = try await graph.items.lookupUpc(upc) else {
                lookupMessage = "No product found for this UPC — fill in the details manually."; return
            }
            if let t = p.title, !t.isEmpty { title = t }
            if let d = p.description, !d.isEmpty { description = d }
            if let b = p.brand, !b.isEmpty { brand = b }
            if let w = p.weight?.filter({ $0.isNumber || $0 == "." }), !w.isEmpty { weight = w }
            let lookupPhotos = Array(Set(p.images ?? [])).sorted().map { PhotoEntry(url: $0, selected: true, fromLookup: true) }
            photos = photos.filter { !$0.fromLookup } + lookupPhotos
            lookupMessage = "Prefilled from product database — review before saving."
        } catch {
            lookupMessage = error.userMessage
        }
    }

    func togglePhoto(_ url: String) { photos = photos.map { $0.url == url ? PhotoEntry(url: $0.url, selected: !$0.selected, fromLookup: $0.fromLookup) : $0 } }

    func addPhoto(data: Data) async {
        uploading = true; error = nil
        defer { uploading = false }
        do {
            if let url = try await graph.items.uploadImage(data) { photos.append(PhotoEntry(url: url)) }
            else { error = "Upload succeeded but no image URL came back." }
        } catch { self.error = "Photo upload failed: \(error.userMessage)" }
    }

    func submit() async {
        guard canSubmit else { return }
        submitting = true; error = nil
        defer { submitting = false }
        var req = CreateItemRequest(name: name.trimmingCharacters(in: .whitespaces))
        req.upcCode = upc.nilIfEmpty
        req.marketplaceTitle = title.nilIfBlank
        req.description = description.nilIfBlank
        req.marketplaceBrand = brand.nilIfBlank
        req.marketplacePlatform = platform.nilIfBlank
        req.manufacturer = manufacturer.nilIfBlank
        req.purchasePrice = Double(cost).map { String(format: "%.2f", $0) }
        req.weight = Double(weight).map { String(format: "%.2f", $0) }
        req.weightUnit = weightUnit
        req.length = length.nilIfBlank; req.width = width.nilIfBlank; req.height = height.nilIfBlank
        req.dimensionUnit = [length, width, height].contains { !$0.isEmpty } ? dimensionUnit : nil
        req.reorderPoint = Int(reorderPoint)
        req.itemCondition = conditionId
        req.taxSchedule = taxScheduleId
        req.salesDescription = description.nilIfBlank
        req.itemImages = photos.filter(\.selected).map { ItemImage(imageUrl: $0.url) }
        do {
            let item = try await graph.items.createItem(req)
            if let qty = Int(initialQty), qty > 0, let wh = warehouseId {
                do { _ = try await graph.items.createOpeningStock(itemId: item.id, warehouseId: wh, quantity: qty) }
                catch { stockWarning = "Item saved, but opening stock could not be posted: \(error.userMessage)" }
            }
            created = item
            NotificationCenter.default.post(name: .itemCreated, object: nil)
        } catch { self.error = error.userMessage }
    }

    /// "Pokémon Destined Rivals Elite Trainer Box" → "Pokemon-Destined-Rivals-Elite-Trainer".
    static func suggestSku(_ title: String) -> String {
        let folded = title.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: .current)
        let cleaned = folded.replacingOccurrences(of: "[^A-Za-z0-9 ]", with: " ", options: .regularExpression)
        let stop: Set<String> = ["the", "a", "an", "and", "of", "for", "with", "official", "new"]
        let words = cleaned.split(separator: " ").map(String.init).filter { !stop.contains($0.lowercased()) }.prefix(5)
        let sku = words.map { $0.prefix(1).uppercased() + $0.dropFirst() }.joined(separator: "-")
        return String(sku.prefix(40)).trimmingCharacters(in: CharacterSet(charactersIn: "-"))
    }
}

struct NewItemView: View {
    @StateObject private var model: NewItemModel
    @Environment(\.dismiss) private var dismiss
    @State private var scanning = false
    @State private var pickerItem: PhotosPickerItem?
    @State private var cameraOpen = false
    @State private var navigateTo: Int64?

    init(graph: AppContainer.Graph, initialUpc: String?) { _model = StateObject(wrappedValue: NewItemModel(graph: graph, initialUpc: initialUpc)) }

    var body: some View {
        Form {
            Section("Barcode") {
                HStack {
                    TextField("UPC / EAN", text: Binding(get: { model.upc }, set: { model.setUpc($0) })).keyboardType(.numberPad)
                    Button { Task { await model.lookupUpc() } } label: { if model.lookingUp { ProgressView() } else { Image(systemName: "magnifyingglass") } }.disabled(model.lookingUp)
                    Button { scanning = true } label: { Image(systemName: "barcode.viewfinder") }
                }
                .buttonStyle(.borderless)
                if let e = model.upcError { Text(e).font(.caption).foregroundStyle(.red) }
                else if let m = model.lookupMessage { Text(m).font(.caption).foregroundStyle(.secondary) }
                else { Text("Scan or type, then look up to prefill from the product database.").font(.caption).foregroundStyle(.secondary) }
            }

            Section("Photos") {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(model.photos) { p in
                            Thumb(url: p.url, size: 84)
                                .overlay(RoundedRectangle(cornerRadius: 8).stroke(p.selected ? Brand.green : Color.clear, lineWidth: 2))
                                .overlay(alignment: .topTrailing) { if p.selected { Image(systemName: "checkmark.circle.fill").foregroundStyle(Brand.greenDeep).padding(4) } }
                                .onTapGesture { model.togglePhoto(p.url) }
                        }
                        VStack(spacing: 6) {
                            Button { cameraOpen = true } label: { Label("Camera", systemImage: "camera") }
                            PhotosPicker(selection: $pickerItem, matching: .images) { Label("Library", systemImage: "photo.on.rectangle") }
                        }
                        .buttonStyle(.bordered).controlSize(.small).disabled(model.uploading)
                    }
                }
                if model.uploading { HStack { ProgressView(); Text("Uploading photo…").font(.caption) } }
            }

            Section("Product information") {
                TextField("Marketplace title", text: $model.title)
                VStack(alignment: .leading, spacing: 2) {
                    TextField("Item / SKU name *", text: $model.name).autocorrectionDisabled().textInputAutocapitalization(.never)
                    Text(model.nameError.map { model.name.isEmpty ? "Short, dash-separated. This is the ERP's identifier." : $0 } ?? "Short, dash-separated. This is the ERP's identifier.")
                        .font(.caption).foregroundStyle(model.nameError != nil && !model.name.isEmpty ? .red : .secondary)
                }
                TextField("Brand", text: $model.brand)
                TextField("Platform", text: $model.platform)
                TextField("Manufacturer", text: $model.manufacturer)
                TextField("Description", text: $model.description, axis: .vertical).lineLimit(3...6)
                Picker("Condition", selection: $model.conditionId) {
                    Text("—").tag(Int?.none)
                    ForEach(model.conditions) { Text($0.displayName).tag(Optional($0.id)) }
                }
                Picker("Tax schedule", selection: $model.taxScheduleId) {
                    Text("—").tag(Int?.none)
                    ForEach(model.taxSchedules) { Text($0.displayName).tag(Optional($0.id)) }
                }
            }

            Section("Cost & stock") {
                HStack { Text("Cost (USD)"); Spacer(); TextField("0.00", text: $model.cost).keyboardType(.decimalPad).multilineTextAlignment(.trailing) }
                if let e = model.costError { Text(e).font(.caption).foregroundStyle(.red) }
                HStack { Text("Reorder point"); Spacer(); TextField("0", text: $model.reorderPoint).keyboardType(.numberPad).multilineTextAlignment(.trailing) }
                HStack { Text("Opening qty"); Spacer(); TextField("0", text: $model.initialQty).keyboardType(.numberPad).multilineTextAlignment(.trailing) }
                if let e = model.qtyError { Text(e).font(.caption).foregroundStyle(.red) }
                Picker("Warehouse", selection: $model.warehouseId) {
                    Text("—").tag(Int?.none)
                    ForEach(model.warehouses) { Text($0.name).tag(Optional($0.id)) }
                }
            }

            Section("Weight & dimensions") {
                HStack { TextField("Weight", text: $model.weight).keyboardType(.decimalPad); Picker("", selection: $model.weightUnit) { ForEach(["Pounds", "Ounces", "Kilograms", "Grams"], id: \.self) { Text($0) } }.labelsHidden() }
                HStack {
                    TextField("L", text: $model.length).keyboardType(.decimalPad)
                    TextField("W", text: $model.width).keyboardType(.decimalPad)
                    TextField("H", text: $model.height).keyboardType(.decimalPad)
                    Picker("", selection: $model.dimensionUnit) { ForEach(["Inches", "Centimeters"], id: \.self) { Text($0) } }.labelsHidden()
                }
            }

            if let e = model.error { Section { Text(e).foregroundStyle(.red) } }

            Section {
                Button { Task { await model.submit() } } label: { HStack { Spacer(); Text(model.submitting ? "Saving…" : "Create item").bold(); Spacer() } }
                    .disabled(!model.canSubmit)
            }
        }
        .navigationTitle("New item")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Save") { Task { await model.submit() } }.disabled(!model.canSubmit) } }
        .sheet(isPresented: $scanning) { ScannerSheet(title: "Scan barcode") { code in model.setUpc(code); Task { await model.lookupUpc() } } }
        .sheet(isPresented: $cameraOpen) { CameraPicker { data in Task { await model.addPhoto(data: data) } } }
        .onChange(of: pickerItem) { item in
            guard let item else { return }
            Task { if let data = try? await item.loadTransferable(type: Data.self) { await model.addPhoto(data: Self.jpeg(data)) }; pickerItem = nil }
        }
        .onChange(of: model.created) { created in
            guard let created else { return }
            if model.stockWarning == nil { navigateTo = created.id }
        }
        .alert("Item created", isPresented: Binding(get: { model.created != nil && model.stockWarning != nil }, set: { _ in })) {
            Button("OK") { let id = model.created?.id; model.stockWarning = nil; navigateTo = id }
        } message: { Text(model.stockWarning ?? "") }
        .navigationDestination(isPresented: Binding(get: { navigateTo != nil }, set: { if !$0 { navigateTo = nil } })) {
            if let id = navigateTo { ItemDetailView(graph: model.graph, id: id, itemType: model.created?.itemType) }
        }
    }

    /// Re-encode whatever the library hands us (HEIC etc.) as a reasonably sized JPEG.
    static func jpeg(_ data: Data) -> Data {
        guard let img = UIImage(data: data) else { return data }
        let maxSide: CGFloat = 1600
        let scale = min(1, maxSide / max(img.size.width, img.size.height))
        let size = CGSize(width: img.size.width * scale, height: img.size.height * scale)
        let r = UIGraphicsImageRenderer(size: size)
        let resized = r.image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
        return resized.jpegData(compressionQuality: 0.85) ?? data
    }
}

/// UIImagePickerController camera wrapper returning JPEG data.
struct CameraPicker: UIViewControllerRepresentable {
    let onImage: (Data) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let p = UIImagePickerController()
        p.sourceType = UIImagePickerController.isSourceTypeAvailable(.camera) ? .camera : .photoLibrary
        p.delegate = context.coordinator
        return p
    }
    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let img = info[.originalImage] as? UIImage, let data = img.jpegData(compressionQuality: 0.85) { parent.onImage(NewItemView.jpeg(data)) }
            parent.dismiss()
        }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}
