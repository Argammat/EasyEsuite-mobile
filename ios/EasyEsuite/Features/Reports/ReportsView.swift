import SwiftUI
import Charts
import Combine
import EasyEsuiteKit

@MainActor
final class ReportsModel: PagedListModel<SalesByItemRow> {
    let graph: AppContainer.Graph
    var search = SearchDebouncer()
    @Published var range = DateRange.thisMonth() { didSet { refresh(); Task { await loadCarrierSpend() } } }
    @Published var ordering = SalesOrdering.revenue { didSet { refresh() } }
    @Published var byMarketplace = false { didSet { refresh() } }
    @Published var carrierSpend: [SeriesPoint] = []
    private var bag = Set<AnyCancellable>()

    init(graph: AppContainer.Graph) {
        self.graph = graph; super.init()
        search.$query.dropFirst().sink { [weak self] _ in self?.refresh() }.store(in: &bag)
        search.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &bag)
        refresh()
        Task { await loadCarrierSpend() }
    }

    func loadCarrierSpend() async { carrierSpend = (try? await graph.reports.carrierSpend(range: range)) ?? [] }

    override func fetch(_ page: PageQuery) async throws -> Page<SalesByItemRow> {
        try await graph.reports.salesByItem(range: range, ordering: ordering, limit: page.limit, offset: page.offset, search: search.query, splitByMarketplace: byMarketplace)
    }
}

struct ReportsView: View {
    @StateObject private var model: ReportsModel
    init(graph: AppContainer.Graph) { _model = StateObject(wrappedValue: ReportsModel(graph: graph)) }

    var body: some View {
        PagedListView(model: model, emptyTitle: "No invoiced sales in this period", header: {
            VStack(alignment: .leading, spacing: 8) {
                ChipRow(options: DateRange.presets.map { ($0.label, $0.label) }, selected: model.range.label) { l in
                    if let r = DateRange.presets.first(where: { $0.label == l }) { model.range = r }
                }.padding(.horizontal, -16)
                Picker("Rank by", selection: $model.ordering) {
                    Text("Revenue").tag(SalesOrdering.revenue); Text("Units").tag(SalesOrdering.units); Text("Profit").tag(SalesOrdering.profit)
                }.pickerStyle(.segmented)
                Toggle("Split per marketplace", isOn: $model.byMarketplace).font(.subheadline)
                Text("Recognised sales from invoices (sales orders over-count). \(fmtCount(model.total)) rows.").font(.caption).foregroundStyle(.secondary)
                if !model.carrierSpend.isEmpty {
                    SectionHeader(text: "Carrier spend")
                    Chart(model.carrierSpend) { p in
                        BarMark(x: .value("Carrier", p.series ?? p.label), y: .value("Spend", p.value))
                    }.frame(height: 120)
                }
            }
            .listRowSeparator(.hidden)
        }) { r in
            NavigationLink(value: Route.item(r.itemId)) {
                HStack(spacing: 10) {
                    Thumb(url: r.primaryImage, size: 44)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(r.itemName).font(.subheadline.weight(.medium)).lineLimit(1)
                        Text([model.byMarketplace ? Marketplaces.name(r.marketplace) : nil, "\(r.units) units",
                              r.averagePrice.map { "avg $\(String(format: "%.2f", $0))" }, r.marginPercent.map { "\(Int($0.rounded()))% margin" }].compactMap { $0 }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    VStack(alignment: .trailing) {
                        Text(r.revenue.formatted()).font(.subheadline.weight(.semibold))
                        if let p = r.profit { Text("profit \(p.formatted())").font(.caption2).foregroundStyle(p.amount < 0 ? Brand.red : Brand.green) }
                    }
                }
            }
        }
        .searchable(text: $model.search.text, prompt: "Filter by SKU or UPC")
        .navigationTitle("Sales by item")
    }
}
