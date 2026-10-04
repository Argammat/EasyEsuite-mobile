import SwiftUI
import Combine
import EasyEsuiteKit

@MainActor
final class OrdersModel: PagedListModel<SalesOrderSummary> {
    let graph: AppContainer.Graph
    var search = SearchDebouncer()
    @Published var status: String? = OrderStatus.pendingFulfillment { didSet { refresh() } }
    private var bag = Set<AnyCancellable>()
    init(graph: AppContainer.Graph) {
        self.graph = graph; super.init()
        search.$query.dropFirst().sink { [weak self] _ in self?.refresh() }.store(in: &bag)
        search.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &bag)
        refresh()
    }
    override func fetch(_ page: PageQuery) async throws -> Page<SalesOrderSummary> { try await graph.orders.orders(status: status, search: search.query, page: page) }
}

@MainActor
final class ShipmentsModel: PagedListModel<Shipment> {
    let graph: AppContainer.Graph
    var search = SearchDebouncer()
    @Published var status: String = ShipmentStatus.toShip { didSet { refresh() } }
    private var bag = Set<AnyCancellable>()
    init(graph: AppContainer.Graph) {
        self.graph = graph; super.init()
        search.$query.dropFirst().sink { [weak self] _ in self?.refresh() }.store(in: &bag)
        search.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &bag)
        refresh()
    }
    override func fetch(_ page: PageQuery) async throws -> Page<Shipment> { try await graph.shipping.shipments(status: status, search: search.query, page: page) }
}

struct OrdersView: View {
    let graph: AppContainer.Graph
    @StateObject private var orders: OrdersModel
    @StateObject private var shipments: ShipmentsModel
    @State private var segment = 0
    @State private var scanning = false
    @State private var scanned: String?

    init(graph: AppContainer.Graph) {
        self.graph = graph
        _orders = StateObject(wrappedValue: OrdersModel(graph: graph))
        _shipments = StateObject(wrappedValue: ShipmentsModel(graph: graph))
    }

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $segment) { Text("Orders").tag(0); Text("Shipments").tag(1) }.pickerStyle(.segmented).padding(.horizontal).padding(.bottom, 6)
            if segment == 0 { ordersList } else { shipmentsList }
        }
        .navigationTitle(segment == 0 ? "Orders" : "Shipments")
        .toolbar { ToolbarItem(placement: .navigationBarTrailing) { Button { scanning = true } label: { Image(systemName: "barcode.viewfinder") } } }
        .sheet(isPresented: $scanning) { ScannerSheet(title: "Scan label or order") { scanned = $0 } }
        .navigationDestination(isPresented: Binding(get: { scanned != nil }, set: { if !$0 { scanned = nil } })) {
            if let code = scanned { ScanResultView(graph: graph, code: code) }
        }
    }

    private var ordersList: some View {
        let statusOptions: [(String?, String)] = [
            (nil, "All"), (OrderStatus.pendingFulfillment, "Pending fulfillment"), (OrderStatus.open, "Open"), (OrderStatus.partialFulfilled, "Partial"),
            (OrderStatus.fulfilledPendingInvoice, "Fulfilled"), (OrderStatus.invoiced, "Invoiced"), (OrderStatus.voided, "Voided"),
        ]
        return PagedListView(model: orders, emptyTitle: "No orders here", header: {
            ChipRow(options: statusOptions, selected: orders.status) { orders.status = $0 }
                .listRowInsets(EdgeInsets()).listRowSeparator(.hidden)
            if orders.total > 0 { Text("\(fmtCount(orders.total)) orders").font(.caption).foregroundStyle(.secondary).listRowSeparator(.hidden) }
        }) { o in
            NavigationLink(value: Route.order(o.id)) { OrderRow(order: o) }
        }
        .searchable(text: $orders.search.text, prompt: "Order #, PO #, customer")
    }

    private var shipmentsList: some View {
        PagedListView(model: shipments, emptyTitle: "No shipments in this view", header: {
            ChipRow(options: ShipmentStatus.tabs.map { ($0, ShipmentStatus.label($0)) }, selected: shipments.status) { shipments.status = $0 }
                .listRowInsets(EdgeInsets()).listRowSeparator(.hidden)
            if shipments.total > 0 { Text("\(fmtCount(shipments.total)) shipments").font(.caption).foregroundStyle(.secondary).listRowSeparator(.hidden) }
        }) { s in
            NavigationLink(value: Route.shipment(s.id)) { ShipmentRow(shipment: s) }
        }
        .searchable(text: $shipments.search.text, prompt: "Order #, tracking #, recipient")
    }
}

struct OrderRow: View {
    let order: SalesOrderSummary
    var body: some View {
        let units = order.totalQuantity ?? 0
        EntityRow(imageUrl: nil, title: "\(order.number)  ·  \(order.marketplace ?? "")",
                  subtitle: [order.company.flatMap { $0 != order.marketplace ? $0 : nil },
                             "\(units) unit\(units == 1 ? "" : "s") · \(order.warehouse ?? "—")",
                             order.shipDate.map { "ship by \(DateText.short($0))" } ?? DateText.relative(order.date)].compactMap { $0 }.joined(separator: " · ")) {
            Text(order.amount.formatted()).font(.subheadline.weight(.semibold))
            StatusChip(status: order.status)
        }
    }
}

struct ShipmentRow: View {
    let shipment: Shipment
    var body: some View {
        let first = shipment.lines.first
        let extra = (shipment.itemCount ?? 1) - 1
        EntityRow(imageUrl: first?.itemImageUrl, title: "\(shipment.orderNumber ?? "Shipment \(shipment.id)")  ·  \(shipment.marketplaceName ?? "")",
                  subtitle: [first?.title.map { extra > 0 ? "\($0) +\(extra) more" : $0 }, shipment.destination.nilIfEmpty,
                             shipment.shipByDate.map { "ship by \(DateText.short($0))" }, shipment.trackingCode].compactMap { $0 }.joined(separator: " · ")) {
            Text(shipment.orderShippingCarrier ?? shipment.labelProvider ?? "").font(.caption)
            StatusChip(status: shipment.status, label: ShipmentStatus.label(shipment.status))
        }
    }
}
