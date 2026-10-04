import SwiftUI
import EasyEsuiteKit

struct ScanResolution {
    var loading = true
    var items: [ItemSummary] = []
    var shipment: Shipment?
    var order: SalesOrderSummary?
    var errors: [String] = []
    var nothing: Bool { !loading && items.isEmpty && shipment == nil && order == nil }
}

/// Figures out what a scanned code is: a product UPC, a shipping label / tracking barcode, or an order number.
@MainActor
final class ScanResultModel: ObservableObject {
    @Published var state = ScanResolution()
    let graph: AppContainer.Graph
    let code: String
    init(graph: AppContainer.Graph, code: String) { self.graph = graph; self.code = code }

    func resolve() async {
        state = ScanResolution()
        let looksLikeUpc = code.allSatisfy(\.isNumber) && (8...14).contains(code.count)
        let looksLikeOrder = code.uppercased().hasPrefix("SO-")
        var r = ScanResolution(loading: false)
        do { r.items = try await graph.items.findByBarcode(code) } catch { r.errors.append("Items: \(error.userMessage)") }
        if !looksLikeUpc {
            do { r.shipment = try await graph.shipping.resolveBarcode(code) } catch {
                if (error as? APIError)?.isNotFound != true { r.errors.append("Shipments: \(error.userMessage)") }
            }
        }
        if looksLikeOrder { r.order = try? await graph.orders.findByNumber(code) }
        state = r
    }
}

struct ScanResultView: View {
    @StateObject private var model: ScanResultModel
    @State private var jumped = false
    @State private var autoRoute: Route?
    let graph: AppContainer.Graph

    init(graph: AppContainer.Graph, code: String) {
        self.graph = graph
        _model = StateObject(wrappedValue: ScanResultModel(graph: graph, code: code))
    }

    var body: some View {
        let s = model.state
        List {
            if s.loading {
                HStack { Spacer(); ProgressView(); Spacer() }
            } else {
                if !s.items.isEmpty {
                    Section("Items") { ForEach(s.items) { item in NavigationLink(value: Route.item(item.id)) { ItemRow(item: item) } } }
                }
                if let sh = s.shipment {
                    Section("Shipment") {
                        NavigationLink(value: Route.shipment(sh.id)) {
                            EntityRow(imageUrl: sh.lines.first?.itemImageUrl, title: sh.orderNumber ?? "Shipment \(sh.id)", subtitle: [sh.marketplaceName, sh.destination.nilIfEmpty].compactMap { $0 }.joined(separator: " · ")) {
                                StatusChip(status: sh.status, label: ShipmentStatus.label(sh.status))
                            }
                        }
                    }
                }
                if let o = s.order {
                    Section("Order") {
                        NavigationLink(value: Route.order(o.id)) {
                            EntityRow(imageUrl: nil, title: o.number, subtitle: [o.marketplace, o.company].compactMap { $0 }.joined(separator: " · ")) { StatusChip(status: o.status) }
                        }
                    }
                }
                if s.nothing {
                    Section {
                        Text("Nothing in EasyEsuite matches “\(model.code)”.").font(.headline)
                        Text("If this is a product barcode you can create the item now — the product database lookup will prefill the details.").font(.subheadline).foregroundStyle(.secondary)
                        if model.code.allSatisfy(\.isNumber) {
                            NavigationLink(value: Route.newItem(upc: model.code)) { Label("Create item with UPC \(model.code)", systemImage: "plus") }
                        }
                    }
                }
                ForEach(s.errors, id: \.self) { Text($0).font(.caption).foregroundStyle(.red) }
            }
        }
        .navigationTitle(model.code)
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.resolve(); jumpIfSingle() }
        .navigationDestination(isPresented: Binding(get: { autoRoute != nil }, set: { if !$0 { autoRoute = nil } })) {
            if let r = autoRoute { autoDestination(r) }
        }
    }

    /// Exactly one match → go straight there.
    private func jumpIfSingle() {
        guard !jumped else { return }
        let s = model.state
        var routes: [Route] = []
        if s.items.count == 1, let i = s.items.first { routes.append(.item(i.id)) }
        if let sh = s.shipment { routes.append(.shipment(sh.id)) }
        if let o = s.order { routes.append(.order(o.id)) }
        if routes.count == 1, s.items.count <= 1 { jumped = true; autoRoute = routes[0] }
    }

    @ViewBuilder private func autoDestination(_ r: Route) -> some View {
        switch r {
        case .item(let id): ItemDetailView(graph: graph, id: id)
        case .shipment(let id): ShipmentDetailView(graph: graph, id: id)
        case .order(let id): OrderDetailView(graph: graph, id: id)
        default: EmptyView()
        }
    }
}
