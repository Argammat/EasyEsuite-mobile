import SwiftUI
import EasyEsuiteKit

@MainActor
final class OrderDetailModel: ObservableObject {
    @Published var state: Loadable<SalesOrderDetail> = .idle
    @Published var shipments: [Shipment] = []
    @Published var fulfillOpen = false
    @Published var tracking = ""
    @Published var busy = false
    @Published var message: String?
    @Published var actionError: String?
    @Published var memo = ""
    let graph: AppContainer.Graph
    let id: Int64
    init(graph: AppContainer.Graph, id: Int64) { self.graph = graph; self.id = id }

    func load() async {
        state = .loading
        do {
            let o = try await graph.orders.order(id)
            memo = o.memo ?? ""
            state = .ready(o)
            shipments = ((try? await graph.shipping.shipments(search: o.number, page: PageQuery(limit: 10)))?.results ?? []).filter { $0.orderNumber == o.number }
        } catch { state = .failed(error.userMessage) }
    }

    func fulfill() async {
        guard let o = state.value else { return }
        busy = true; actionError = nil
        defer { busy = false }
        do {
            _ = try await graph.orders.fulfill(FulfillRequest(salesOrder: o.id, warehouse: o.warehouse, trackingNumber: tracking.nilIfBlank, shippingCarrier: o.shippingCarrier, shippingMethod: o.shippingMethod,
                                                              items: o.lines.filter { $0.unfulfilled > 0 }.map { FulfillLine(salesOrderItem: $0.id, quantity: $0.unfulfilled) }))
            fulfillOpen = false
            message = "Order fulfilled"
            await load()
        } catch { actionError = error.userMessage }
    }

    func saveMemo() async {
        do {
            let updated = try await graph.orders.update(id, UpdateOrderRequest(memo: memo))
            state = .ready(updated); message = "Memo saved"
        } catch { actionError = error.userMessage }
    }
}

struct OrderDetailView: View {
    @StateObject private var model: OrderDetailModel
    init(graph: AppContainer.Graph, id: Int64) { _model = StateObject(wrappedValue: OrderDetailModel(graph: graph, id: id)) }

    var body: some View {
        Group {
            switch model.state {
            case .idle, .loading: ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed(let m): ErrorRow(message: m) { Task { await model.load() } }
            case .ready(let o): content(o)
            }
        }
        .navigationTitle(model.state.value?.number ?? "Order")
        .navigationBarTitleDisplayMode(.inline)
        .task { if case .idle = model.state { await model.load() } }
        .toast($model.message)
        .alert("Fulfill \(model.state.value?.number ?? "")", isPresented: $model.fulfillOpen) {
            TextField("Tracking number (optional)", text: $model.tracking)
            Button("Fulfill") { Task { await model.fulfill() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            let units = model.state.value?.lines.reduce(0) { $0 + $1.unfulfilled } ?? 0
            Text("Marks all \(units) unshipped unit(s) as fulfilled from \(model.state.value?.lines.first?.warehouseName ?? "the order warehouse").")
        }
    }

    @ViewBuilder
    private func content(_ o: SalesOrderDetail) -> some View {
        List {
            Section {
                HStack {
                    VStack(alignment: .leading) {
                        Text(o.marketplaceName ?? "").font(.headline)
                        Text("\(DateText.long(o.date)) · PO \(o.poNumber ?? "—")").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    StatusChip(status: o.status)
                }
                KeyValueRow(label: "Items", value: "\(o.totalQuantity ?? o.lines.reduce(0) { $0 + $1.qty }) units")
                KeyValueRow(label: "Subtotal", value: o.subTotal?.formatted())
                KeyValueRow(label: "Tax", value: o.totalTaxesAmount?.formatted())
                KeyValueRow(label: "Shipping", value: o.shippingCost?.formatted())
                KeyValueRow(label: "Total", value: o.amount.formatted())
                if let e = model.actionError { Text(e).foregroundStyle(.red).font(.subheadline) }
                HStack {
                    if o.canFulfill { Button { model.fulfillOpen = true } label: { Label("Fulfill", systemImage: "shippingbox") }.buttonStyle(.borderedProminent).disabled(model.busy) }
                    if let sh = model.shipments.first {
                        NavigationLink(value: Route.shipment(sh.id)) { Text(model.shipments.count > 1 ? "Shipments (\(model.shipments.count))" : "Shipment") }.buttonStyle(.bordered)
                    }
                }
            }
            Section("Lines") {
                ForEach(o.lines) { line in
                    HStack(alignment: .top, spacing: 10) {
                        Thumb(url: line.primaryImage, size: 48)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(line.name).font(.subheadline.weight(.medium))
                            if let d = line.description { Text(d).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                            Text(lineMeta(line)).font(.caption)
                        }
                        Spacer()
                        Text(line.lineTotal.formatted()).font(.subheadline.weight(.semibold))
                    }
                }
            }
            if let a = o.shipTo {
                Section("Ship to") {
                    ForEach(a.lines, id: \.self) { Text($0).font(.subheadline) }
                    if let p = a.phone { Text(p).font(.caption).foregroundStyle(.secondary) }
                }
            }
            Section("Shipping") {
                KeyValueRow(label: "Method", value: o.shippingMethodName)
                KeyValueRow(label: "Ship by", value: DateText.short(o.shipDate))
                KeyValueRow(label: "Deliver by", value: o.deliverByDate.map { DateText.short($0) })
                KeyValueRow(label: "Marketplace sync", value: o.connectorSyncStatus)
            }
            Section("Memo") {
                TextField("Memo", text: $model.memo, axis: .vertical).lineLimit(2...5)
                if model.memo != (o.memo ?? "") { Button("Save memo") { Task { await model.saveMemo() } } }
            }
        }
        .refreshable { await model.load() }
    }

    private func lineMeta(_ line: SalesOrderLine) -> String {
        var parts = ["\(line.qty) × \(line.price?.formatted() ?? "—")"]
        if line.fulfilled > 0 { parts.append("\(line.fulfilled) fulfilled") }
        if line.unfulfilled > 0 { parts.append("\(line.unfulfilled) to ship") }
        if let a = line.availableQuantity { parts.append("\(a) available") }
        if let w = line.warehouseName { parts.append(w) }
        return parts.joined(separator: " · ")
    }
}
