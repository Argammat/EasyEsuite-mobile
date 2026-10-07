import SwiftUI
import EasyEsuiteKit

@MainActor
final class TransfersModel: PagedListModel<Transfer> {
    let graph: AppContainer.Graph
    @Published var status: String? { didSet { refresh() } }
    init(graph: AppContainer.Graph) { self.graph = graph; super.init(); refresh() }
    override func fetch(_ page: PageQuery) async throws -> Page<Transfer> { try await graph.inventory.transfers(status: status, page: page) }
}

struct TransfersView: View {
    @StateObject private var model: TransfersModel
    init(graph: AppContainer.Graph) { _model = StateObject(wrappedValue: TransfersModel(graph: graph)) }

    var body: some View {
        let statusOptions: [(String?, String)] = [(nil, "All")] + TransferStatus.all.map { (Optional($0), $0) }
        return PagedListView(model: model, emptyTitle: "No transfers", header: {
            ChipRow(options: statusOptions, selected: model.status) { model.status = $0 }
                .listRowInsets(EdgeInsets()).listRowSeparator(.hidden)
        }) { t in
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(t.number).font(.body.weight(.medium))
                    Text("\(t.fromWarehouse ?? "?") → \(t.toWarehouse ?? "?")").font(.caption)
                    Text("\(t.totalQuantity) units · \(DateText.short(t.date))" + (t.remarks?.nilIfBlank.map { " · \($0)" } ?? "")).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                StatusChip(status: t.status)
            }
        }
        .navigationTitle("Transfers")
        .toolbar { ToolbarItem(placement: .navigationBarTrailing) { NavigationLink(value: Route.newTransfer(itemId: nil)) { Image(systemName: "plus") } } }
        .onReceive(NotificationCenter.default.publisher(for: .transferCreated)) { _ in model.refresh() }
    }
}

// MARK: - New transfer

struct PickedLine: Identifiable { let item: ItemSummary; var quantity: Int; var reason = ""; var id: Int64 { item.id } }

/// Shared "pick items + quantities" model used by transfers and adjustments.
@MainActor
class LinePickerModel: ObservableObject {
    @Published var warehouses: [Warehouse] = []
    @Published var lines: [PickedLine] = []
    @Published var search = "" { didSet { searchChanged() } }
    @Published var searchResults: [ItemSummary] = []
    @Published var submitting = false
    @Published var error: String?
    @Published var done = false
    let graph: AppContainer.Graph
    private var searchTask: Task<Void, Never>?

    init(graph: AppContainer.Graph, preselectedItem: Int64?) {
        self.graph = graph
        Task {
            warehouses = (try? await graph.items.warehouses()) ?? []
            warehousesLoaded()
            if let id = preselectedItem, let d = try? await graph.items.item(id) { add(d.summary, quantity: 1) }
        }
    }

    func warehousesLoaded() {}

    private func searchChanged() {
        searchTask?.cancel()
        guard search.count >= 2 else { searchResults = []; return }
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 300_000_000)
            if Task.isCancelled { return }
            searchResults = (try? await graph.items.catalog(search: search, page: PageQuery(limit: 8)))?.results ?? []
        }
    }

    func addByBarcode(_ code: String) {
        Task {
            let found = (try? await graph.items.findByBarcode(code)) ?? []
            if let f = found.first { add(f, quantity: 1) } else { error = "No item found for “\(code)”" }
        }
    }

    func add(_ item: ItemSummary, quantity: Int) {
        if let i = lines.firstIndex(where: { $0.item.id == item.id }) { lines[i].quantity += quantity } else { lines.append(PickedLine(item: item, quantity: quantity)) }
        search = ""; searchResults = []
    }
    func remove(_ id: Int64) { lines.removeAll { $0.item.id == id } }
    func qtyBinding(_ id: Int64) -> Binding<String> {
        Binding(get: { self.lines.first { $0.item.id == id }.map { $0.quantity == 0 ? "" : String($0.quantity) } ?? "" },
                set: { v in if let i = self.lines.firstIndex(where: { $0.item.id == id }) { self.lines[i].quantity = Int(v.filter { $0.isNumber || $0 == "-" }) ?? 0 } })
    }
}

@MainActor
final class NewTransferModel: LinePickerModel {
    @Published var from: Int?
    @Published var to: Int?
    @Published var remarks = ""
    var canSubmit: Bool { from != nil && to != nil && from != to && !lines.isEmpty && lines.allSatisfy { $0.quantity > 0 } && !submitting }
    override func warehousesLoaded() { if from == nil { from = warehouses.first(where: \.default)?.id ?? warehouses.first?.id } }

    func submit() async {
        guard canSubmit, let from, let to else { return }
        submitting = true; error = nil
        defer { submitting = false }
        do {
            _ = try await graph.inventory.createTransfer(CreateTransferRequest(fromWarehouse: from, toWarehouse: to, date: DateText.apiDate(), remarks: remarks.nilIfBlank, items: lines.map { TransferLineRequest(item: $0.item.id, quantity: $0.quantity) }))
            NotificationCenter.default.post(name: .transferCreated, object: nil)
            done = true
        } catch { self.error = error.userMessage }
    }
}

struct NewTransferView: View {
    @StateObject private var model: NewTransferModel
    @Environment(\.dismiss) private var dismiss
    @State private var scanning = false

    init(graph: AppContainer.Graph, preselectedItem: Int64?) { _model = StateObject(wrappedValue: NewTransferModel(graph: graph, preselectedItem: preselectedItem)) }

    var body: some View {
        Form {
            Section {
                Picker("From", selection: $model.from) { Text("—").tag(Int?.none); ForEach(model.warehouses) { Text($0.name).tag(Optional($0.id)) } }
                Picker("To", selection: $model.to) { Text("—").tag(Int?.none); ForEach(model.warehouses) { Text($0.name).tag(Optional($0.id)) } }
                if model.from != nil && model.from == model.to { Text("Pick two different warehouses.").font(.caption).foregroundStyle(.red) }
            }
            ItemPickerSection(model: model, scanning: $scanning, allowNegative: false, hint: "No items yet — search above or scan a barcode.")
            Section("Remarks") { TextField("Why is this moving?", text: $model.remarks, axis: .vertical).lineLimit(2...4) }
            if let e = model.error { Section { Text(e).foregroundStyle(.red) } }
            Section { Button { Task { await model.submit() } } label: { HStack { Spacer(); Text(model.submitting ? "Creating…" : "Create transfer").bold(); Spacer() } }.disabled(!model.canSubmit) }
        }
        .navigationTitle("New transfer")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $scanning) { ScannerSheet { model.addByBarcode($0) } }
        .onChange(of: model.done) { if $0 { dismiss() } }
    }
}

/// Search + scan + lines, shared by transfer and adjustment forms.
struct ItemPickerSection<M: LinePickerModel>: View {
    @ObservedObject var model: M
    @Binding var scanning: Bool
    var allowNegative: Bool
    var hint: String
    var reasons: [String] = []

    var body: some View {
        Section("Items") {
            HStack {
                TextField("Add item by SKU / UPC / title", text: $model.search).autocorrectionDisabled()
                Button { scanning = true } label: { Image(systemName: "barcode.viewfinder") }.buttonStyle(.borderless)
            }
            ForEach(model.searchResults) { r in
                Button { model.add(r, quantity: allowNegative ? 0 : 1) } label: {
                    HStack { Thumb(url: r.primaryImage, size: 36); VStack(alignment: .leading) { Text(r.name).font(.subheadline); Text("\(r.onHand) on hand").font(.caption).foregroundStyle(.secondary) }; Spacer(); Image(systemName: "plus.circle") }
                }.buttonStyle(.plain)
            }
            ForEach(model.lines) { line in
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Thumb(url: line.item.primaryImage, size: 40)
                        VStack(alignment: .leading) { Text(line.item.name).font(.subheadline.weight(.medium)); Text("\(line.item.onHand) on hand").font(.caption).foregroundStyle(.secondary) }
                        Spacer()
                        TextField(allowNegative ? "+/−" : "Qty", text: model.qtyBinding(line.item.id)).keyboardType(allowNegative ? .numbersAndPunctuation : .numberPad).multilineTextAlignment(.trailing).frame(width: 70).textFieldStyle(.roundedBorder)
                        Button(role: .destructive) { model.remove(line.item.id) } label: { Image(systemName: "trash") }.buttonStyle(.borderless)
                    }
                    if !reasons.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 6) {
                                ForEach(reasons, id: \.self) { r in
                                    let on = line.reason == r
                                    Button { if let i = model.lines.firstIndex(where: { $0.id == line.id }) { model.lines[i].reason = r } } label: {
                                        Text(r).font(.caption).padding(.horizontal, 8).padding(.vertical, 4).background(on ? Brand.greenTint : Color(.secondarySystemBackground)).foregroundStyle(on ? Brand.greenText : .primary).fontWeight(on ? .semibold : .regular).clipShape(Capsule())
                                    }.buttonStyle(.plain)
                                }
                            }
                        }
                    }
                }
            }
            if model.lines.isEmpty { Text(hint).font(.caption).foregroundStyle(.secondary) }
        }
    }
}
