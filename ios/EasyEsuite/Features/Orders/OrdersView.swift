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

@MainActor
final class InvoicesModel: PagedListModel<Invoice> {
    let graph: AppContainer.Graph
    var search = SearchDebouncer()
    @Published var status: String? = InvoiceStatus.open { didSet { refresh() } }
    private var bag = Set<AnyCancellable>()
    init(graph: AppContainer.Graph) {
        self.graph = graph; super.init()
        search.$query.dropFirst().sink { [weak self] _ in self?.refresh() }.store(in: &bag)
        search.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &bag)
        refresh()
    }
    override func fetch(_ page: PageQuery) async throws -> Page<Invoice> { try await graph.orders.invoices(status: status, search: search.query, page: page) }
}

@MainActor
final class PaymentsModel: PagedListModel<Payment> {
    let graph: AppContainer.Graph
    var search = SearchDebouncer()
    /// Client-side: the API has no "has unapplied" filter.
    @Published var unappliedOnly = false { didSet { refresh() } }
    private var bag = Set<AnyCancellable>()
    init(graph: AppContainer.Graph) {
        self.graph = graph; super.init()
        search.$query.dropFirst().sink { [weak self] _ in self?.refresh() }.store(in: &bag)
        search.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &bag)
        refresh()
    }
    override func fetch(_ page: PageQuery) async throws -> Page<Payment> {
        let p = try await graph.orders.payments(search: search.query, page: page)
        return unappliedOnly ? Page(count: p.count, next: p.next, previous: p.previous, results: p.results.filter(\.hasUnapplied)) : p
    }
}

/// The web app's Ecommerce + Sales finance areas in one tab: Orders · Shipments · Invoices · Payments.
struct OrdersView: View {
    let graph: AppContainer.Graph
    @StateObject private var orders: OrdersModel
    @StateObject private var shipments: ShipmentsModel
    @StateObject private var invoices: InvoicesModel
    @StateObject private var payments: PaymentsModel
    @State private var segment = 0
    @State private var scanning = false
    @State private var scanned: String?
    private let segments = ["Orders", "Shipments", "Invoices", "Payments"]

    init(graph: AppContainer.Graph) {
        self.graph = graph
        _orders = StateObject(wrappedValue: OrdersModel(graph: graph))
        _shipments = StateObject(wrappedValue: ShipmentsModel(graph: graph))
        _invoices = StateObject(wrappedValue: InvoicesModel(graph: graph))
        _payments = StateObject(wrappedValue: PaymentsModel(graph: graph))
    }

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $segment) { ForEach(segments.indices, id: \.self) { Text(segments[$0]).tag($0) } }
                .pickerStyle(.segmented).padding(.horizontal).padding(.bottom, 6)
            switch segment {
            case 0: ordersList
            case 1: shipmentsList
            case 2: invoicesList
            default: paymentsList
            }
        }
        .navigationTitle(segments[segment])
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

    private var invoicesList: some View {
        let statusOptions: [(String?, String)] = [(nil, "All")] + InvoiceStatus.all.map { ($0, $0) }
        return PagedListView(model: invoices, emptyTitle: "No invoices here", header: {
            ChipRow(options: statusOptions, selected: invoices.status) { invoices.status = $0 }
                .listRowInsets(EdgeInsets()).listRowSeparator(.hidden)
            if invoices.total > 0 { Text("\(fmtCount(invoices.total)) invoices").font(.caption).foregroundStyle(.secondary).listRowSeparator(.hidden) }
        }) { inv in
            if let soId = inv.salesOrderId {
                NavigationLink(value: Route.order(soId)) { InvoiceRow(invoice: inv) }
            } else {
                InvoiceRow(invoice: inv)
            }
        }
        .searchable(text: $invoices.search.text, prompt: "Invoice # (IN-…)")
    }

    private var paymentsList: some View {
        PagedListView(model: payments, emptyTitle: "No payments here", header: {
            ChipRow(options: [(false, "All"), (true, "Has unapplied amount")], selected: payments.unappliedOnly) { payments.unappliedOnly = $0 }
                .listRowInsets(EdgeInsets()).listRowSeparator(.hidden)
            if payments.total > 0 { Text("\(fmtCount(payments.total)) payments").font(.caption).foregroundStyle(.secondary).listRowSeparator(.hidden) }
        }) { p in
            PaymentRow(payment: p)
        }
        .searchable(text: $payments.search.text, prompt: "Payment #, customer, reference")
    }
}

struct InvoiceRow: View {
    let invoice: Invoice
    var body: some View {
        EntityRow(imageUrl: nil, title: "\(invoice.number)  ·  \(invoice.displayCustomer)",
                  subtitle: [invoice.invoiceType, invoice.salesOrderNumber, invoice.poNumber.map { "PO \($0)" }, invoice.warehouseName, DateText.short(invoice.date)]
                    .compactMap { $0 }.joined(separator: " · ")) {
            Text(invoice.totalAmount?.formatted() ?? "—").font(.subheadline.weight(.semibold))
            if invoice.isOpen, let open = invoice.openAmount, open != invoice.totalAmount {
                Text("open \(open.formatted())").font(.caption2).foregroundStyle(.secondary)
            }
            StatusChip(status: invoice.returnStatus?.nilIfBlank ?? invoice.status)
        }
    }
}

struct PaymentRow: View {
    let payment: Payment
    var body: some View {
        EntityRow(imageUrl: nil, title: "\(payment.number)  ·  \(payment.customerName ?? "—")",
                  subtitle: [payment.paymentMethodName, payment.bankName, payment.refNumber.map { "ref \($0)" } ?? payment.checkNumber.map { "check \($0)" }, DateText.short(payment.date)]
                    .compactMap { $0 }.joined(separator: " · ")) {
            Text(payment.amount?.formatted() ?? "—").font(.subheadline.weight(.semibold))
            if payment.hasUnapplied, let un = payment.unAppliedAmountNew {
                StatusChip(status: "On Hold", label: "Unapplied \(un.formatted())")
            } else {
                StatusChip(status: "Completed", label: "Applied" + (payment.appliedAmountNew.map { " \($0.formatted())" } ?? ""))
            }
        }
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
