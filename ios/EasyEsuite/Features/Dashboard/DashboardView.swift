import SwiftUI
import Charts
import EasyEsuiteKit

@MainActor
final class DashboardModel: ObservableObject {
    @Published var range = DateRange.today() { didSet { Task { await load() } } }
    @Published var loading = false
    @Published var ordersInRange: Int?
    @Published var pendingFulfillment: Int?
    @Published var toShip: Int?
    @Published var onHold: Int?
    @Published var exceptions: Int?
    @Published var cards: [DashboardCard] = []
    @Published var revenue: [SeriesPoint] = []
    @Published var topItems: [SalesByItemRow] = []
    @Published var errors: [String] = []
    let graph: AppContainer.Graph
    init(graph: AppContainer.Graph) { self.graph = graph }

    func load() async {
        loading = true; errors = []
        defer { loading = false }
        let range = self.range
        // Fire everything concurrently, then collect each result with its own error so one failure never blanks the page.
        async let a = graph.orders.count(range: range)
        async let b = graph.orders.count(status: OrderStatus.pendingFulfillment)
        async let c = graph.shipping.count(status: ShipmentStatus.toShip)
        async let d = graph.shipping.count(status: ShipmentStatus.onHold)
        async let e = graph.shipping.count(status: ShipmentStatus.exceptions)
        async let f = graph.reports.overviewCards(range: range)
        async let g = graph.reports.ordersByMarketplaceOverTime(range: range)
        async let h = graph.reports.salesByItem(range: range, limit: 5)

        do { ordersInRange = try await a } catch { errors.append("Orders: \(error.userMessage)") }
        do { pendingFulfillment = try await b } catch { errors.append("Pending: \(error.userMessage)") }
        do { toShip = try await c } catch { errors.append("To ship: \(error.userMessage)") }
        do { onHold = try await d } catch { errors.append("On hold: \(error.userMessage)") }
        do { exceptions = try await e } catch { errors.append("Exceptions: \(error.userMessage)") }
        do { cards = try await f } catch { errors.append("Overview: \(error.userMessage)") }
        do { revenue = try await g } catch { errors.append("Revenue: \(error.userMessage)") }
        do { topItems = try await h.results } catch { errors.append("Top items: \(error.userMessage)") }
    }
}

struct DashboardView: View {
    let graph: AppContainer.Graph
    @StateObject private var model: DashboardModel
    @State private var scanning = false
    @State private var scanned: String?

    init(graph: AppContainer.Graph) {
        self.graph = graph
        _model = StateObject(wrappedValue: DashboardModel(graph: graph))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                ChipRow(options: DateRange.presets.map { ($0.label, $0.label) }, selected: model.range.label) { label in
                    if let r = DateRange.presets.first(where: { $0.label == label }) { model.range = r }
                }
                .padding(.horizontal, -16)

                HStack(spacing: 8) {
                    tile("Orders · \(model.range.label.lowercased())", model.ordersInRange)
                    tile("Pending fulfillment", model.pendingFulfillment)
                }
                HStack(spacing: 8) {
                    tile("To ship", model.toShip)
                    tile("On hold", model.onHold)
                    tile("Exceptions", model.exceptions, alert: (model.exceptions ?? 0) > 0)
                }

                if !model.cards.isEmpty {
                    SectionHeader(text: "Overview")
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                        ForEach(model.cards) { c in StatTile(label: c.label, value: c.value) }
                    }
                }

                if !model.revenue.isEmpty {
                    SectionHeader(text: "Order value by marketplace")
                    Chart(model.revenue) { p in
                        BarMark(x: .value("Day", p.label), y: .value("Amount", p.value))
                            .foregroundStyle(by: .value("Marketplace", p.series ?? "Total"))
                    }
                    .chartXAxis { AxisMarks(values: .automatic(desiredCount: 4)) }
                    .frame(height: 180)
                }

                SectionHeader(text: "Top sellers · \(model.range.label.lowercased())")
                if model.topItems.isEmpty && !model.loading { Text("No invoiced sales in this period.").font(.caption).foregroundStyle(.secondary) }
                ForEach(Array(model.topItems.enumerated()), id: \.offset) { i, r in
                    NavigationLink(value: Route.item(r.itemId)) {
                        HStack(spacing: 10) {
                            Text("\(i + 1)").font(.headline).foregroundStyle(.secondary).frame(width: 22)
                            Thumb(url: r.primaryImage, size: 44)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(r.itemName).font(.subheadline.weight(.medium)).lineLimit(1)
                                Text("\(r.units) sold" + (r.profit.map { " · profit \($0.formatted())" } ?? "")).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(r.revenue.formatted()).font(.subheadline.weight(.semibold))
                        }
                    }.buttonStyle(.plain)
                }

                ForEach(model.errors, id: \.self) { Text($0).font(.caption).foregroundStyle(.red) }
            }
            .padding()
        }
        .refreshable { await model.load() }
        .task { if model.ordersInRange == nil && !model.loading { await model.load() } }
        .navigationTitle(graph.tenant.prefix(1).uppercased() + graph.tenant.dropFirst())
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                NavigationLink(value: Route.assistant) { Image(systemName: "sparkles") }
                Button { scanning = true } label: { Image(systemName: "barcode.viewfinder") }
            }
        }
        .sheet(isPresented: $scanning) { ScannerSheet { scanned = $0 } }
        .navigationDestination(isPresented: Binding(get: { scanned != nil }, set: { if !$0 { scanned = nil } })) {
            if let code = scanned { ScanResultView(graph: graph, code: code) }
        }
    }

    private func tile(_ label: String, _ value: Int?, alert: Bool = false) -> some View {
        StatTile(label: label, value: value.map(fmtCount) ?? "—", alert: alert)
    }
}
