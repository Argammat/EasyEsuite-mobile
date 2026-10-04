import SwiftUI
import EasyEsuiteKit

@MainActor
final class ReceiveModel: PagedListModel<PurchaseOrder> {
    let graph: AppContainer.Graph
    @Published var sheet: PurchaseOrder?
    @Published var lines: [PurchaseOrderLine] = []
    @Published var quantities: [Int64: Int] = [:]
    @Published var memo = ""
    @Published var linesLoading = false
    @Published var submitting = false
    @Published var sheetError: String?
    @Published var toast: String?

    init(graph: AppContainer.Graph) { self.graph = graph; super.init(); refresh() }
    override func fetch(_ page: PageQuery) async throws -> Page<PurchaseOrder> { try await graph.inventory.purchaseOrders(page: page) }

    var anyQuantity: Bool { quantities.values.contains { $0 > 0 } }

    func open(_ po: PurchaseOrder) {
        sheet = po; lines = []; quantities = [:]; memo = ""; sheetError = nil; linesLoading = true
        Task {
            defer { linesLoading = false }
            do {
                lines = try await graph.inventory.purchaseOrderLines(po.id)
                quantities = Dictionary(uniqueKeysWithValues: lines.map { ($0.id, $0.remaining) })   // default: receive everything outstanding
            } catch { sheetError = error.userMessage }
        }
    }

    func receiveAll() { quantities = Dictionary(uniqueKeysWithValues: lines.map { ($0.id, $0.remaining) }) }

    func qtyBinding(_ id: Int64) -> Binding<String> {
        Binding(get: { (self.quantities[id] ?? 0) == 0 ? "" : String(self.quantities[id] ?? 0) },
                set: { self.quantities[id] = max(0, Int($0.filter(\.isNumber)) ?? 0) })
    }

    func submit() async {
        guard let po = sheet, anyQuantity, !submitting else { return }
        submitting = true; sheetError = nil
        defer { submitting = false }
        do {
            _ = try await graph.inventory.receive(ReceiveRequest(purchaseOrder: po.id, receivedDate: DateText.apiDate(), memo: memo.nilIfBlank,
                                                                   items: quantities.filter { $0.value > 0 }.map { ReceiptLine(purchaseOrderItem: $0.key, quantity: $0.value) }))
            toast = "Received against \(po.number)"
            sheet = nil
            refresh()
        } catch { sheetError = error.userMessage }
    }
}

struct ReceiveView: View {
    @StateObject private var model: ReceiveModel
    init(graph: AppContainer.Graph) { _model = StateObject(wrappedValue: ReceiveModel(graph: graph)) }

    var body: some View {
        PagedListView(model: model, emptyTitle: "Nothing to receive", emptySubtitle: "Open and partially received POs show up here.") { po in
            Button { model.open(po) } label: {
                EntityRow(imageUrl: nil, title: "\(po.number) · \(po.company ?? po.vendor ?? "")",
                          subtitle: "\(po.warehouse ?? "—") · \(po.totalQuantityOrdered) units · \(DateText.short(po.date))" + (po.memo?.nilIfBlank.map { "\n\($0)" } ?? "")) {
                    StatusChip(status: po.status)
                    Text(po.totalAmountNew?.formatted() ?? "").font(.caption)
                }
            }.buttonStyle(.plain)
        }
        .navigationTitle("Receive POs")
        .toast($model.toast)
        .sheet(item: $model.sheet) { po in receiveSheet(po) }
    }

    @ViewBuilder
    private func receiveSheet(_ po: PurchaseOrder) -> some View {
        NavigationStack {
            Form {
                Section {
                    Text("\(po.company ?? "") → \(po.warehouse ?? "")").font(.subheadline).foregroundStyle(.secondary)
                }
                Section {
                    if model.linesLoading { HStack { Spacer(); ProgressView(); Spacer() } }
                    else if model.lines.isEmpty { Text(model.sheetError ?? "This PO has no lines to receive.").foregroundStyle(.red) }
                    else {
                        Button("Receive all remaining") { model.receiveAll() }.font(.subheadline)
                        ForEach(model.lines) { line in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(line.displayName).font(.subheadline.weight(.medium))
                                    Text("Ordered \(line.ordered) · received \(line.received) · remaining \(line.remaining)").font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                TextField("Qty", text: model.qtyBinding(line.id)).keyboardType(.numberPad).multilineTextAlignment(.trailing).frame(width: 70).textFieldStyle(.roundedBorder)
                                    .foregroundStyle((model.quantities[line.id] ?? 0) > line.remaining ? Brand.red : .primary)
                            }
                        }
                    }
                } header: { Text("Lines") }
                Section("Memo") { TextField("Optional", text: $model.memo) }
                if let e = model.sheetError, !model.lines.isEmpty { Section { Text(e).foregroundStyle(.red) } }
                Section { Button { Task { await model.submit() } } label: { HStack { Spacer(); Text(model.submitting ? "Receiving…" : "Post receipt").bold(); Spacer() } }.disabled(!model.anyQuantity || model.submitting) }
            }
            .navigationTitle("Receive \(po.number)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { model.sheet = nil } } }
        }
    }
}
