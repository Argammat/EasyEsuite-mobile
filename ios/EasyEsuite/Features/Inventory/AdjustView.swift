import SwiftUI
import EasyEsuiteKit

let adjustmentReasons = ["Cycle count", "Damaged", "Lost", "Found", "Return to stock", "Sample", "Other"]

@MainActor
final class AdjustModel: LinePickerModel {
    @Published var warehouseId: Int?
    @Published var memo = ""
    var canSubmit: Bool { warehouseId != nil && !lines.isEmpty && lines.allSatisfy { $0.quantity != 0 } && !submitting }

    init(graph: AppContainer.Graph, preselectedItem: Int64?, preselectedWarehouse: Int?) {
        super.init(graph: graph, preselectedItem: preselectedItem)
        warehouseId = preselectedWarehouse
    }
    override func warehousesLoaded() { if warehouseId == nil { warehouseId = warehouses.first(where: \.default)?.id ?? warehouses.first?.id } }
    override func add(_ item: ItemSummary, quantity: Int) {
        if !lines.contains(where: { $0.item.id == item.id }) { lines.append(PickedLine(item: item, quantity: 0, reason: adjustmentReasons[0])) }
        search = ""; searchResults = []
    }

    func submit() async {
        guard canSubmit, let wh = warehouseId else { return }
        submitting = true; error = nil
        defer { submitting = false }
        do {
            _ = try await graph.inventory.createAdjustment(CreateAdjustmentRequest(warehouse: wh, date: DateText.apiDate(), memo: memo.nilIfBlank, items: lines.map { AdjustmentLineRequest(item: $0.item.id, quantity: $0.quantity, reason: $0.reason.nilIfBlank) }))
            done = true
        } catch { self.error = error.userMessage }
    }
}

struct AdjustView: View {
    @StateObject private var model: AdjustModel
    @Environment(\.dismiss) private var dismiss
    @State private var scanning = false

    init(graph: AppContainer.Graph, preselectedItem: Int64?, preselectedWarehouse: Int?) {
        _model = StateObject(wrappedValue: AdjustModel(graph: graph, preselectedItem: preselectedItem, preselectedWarehouse: preselectedWarehouse))
    }

    var body: some View {
        Form {
            Section { Picker("Warehouse", selection: $model.warehouseId) { Text("—").tag(Int?.none); ForEach(model.warehouses) { Text($0.name).tag(Optional($0.id)) } } }
            ItemPickerSection(model: model, scanning: $scanning, allowNegative: true, hint: "Add items, then enter a positive number to add stock or a negative one to remove it.", reasons: adjustmentReasons)
            Section("Memo") { TextField("Memo", text: $model.memo, axis: .vertical).lineLimit(2...4) }
            if let e = model.error { Section { Text(e).foregroundStyle(.red) } }
            Section { Button { Task { await model.submit() } } label: { HStack { Spacer(); Text(model.submitting ? "Posting…" : "Post adjustment").bold(); Spacer() } }.disabled(!model.canSubmit) }
        }
        .navigationTitle("Adjust stock")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $scanning) { ScannerSheet { model.addByBarcode($0) } }
        .onChange(of: model.done) { if $0 { dismiss() } }
    }
}
