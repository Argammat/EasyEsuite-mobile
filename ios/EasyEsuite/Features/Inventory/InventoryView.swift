import SwiftUI
import Combine
import EasyEsuiteKit

/// Either a company-wide stock row or a per-warehouse row, so one list can show both views.
enum StockRowModel: Identifiable {
    case company(InventoryItem)
    case perWarehouse(WarehouseStock)
    var id: String {
        switch self { case .company(let i): return "c-\(i.id)"; case .perWarehouse(let s): return "w-\(s.id)" }
    }
}

/// Company view = `items/inventory_items/` (+ `get_inventory_totalization/`);
/// warehouse view = `items/warehouse_inventory_items/?warehouse_id=` (+ `totalization/`).
/// `search` and `is_available` are server-side; "below reorder point" is the only client-side filter
/// (the API has no parameter for it).
@MainActor
final class InventoryModel: PagedListModel<StockRowModel> {
    let graph: AppContainer.Graph
    var search = SearchDebouncer()
    @Published var availableOnly = true { didSet { refresh() } }
    @Published var lowStockOnly = false { didSet { refresh() } }
    @Published var warehouses: [Warehouse] = []
    @Published var warehouseId: Int? { didSet { refresh() } }
    /// Totals strip above the list; nil while loading, empty when the endpoint returned nothing usable.
    @Published var totals: [DashboardCard]?
    private var bag = Set<AnyCancellable>()
    private var totalsTask: Task<Void, Never>?

    init(graph: AppContainer.Graph) {
        self.graph = graph
        super.init()
        search.$query.dropFirst().sink { [weak self] _ in self?.refresh() }.store(in: &bag)
        search.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &bag)
        refresh()
        Task { warehouses = (try? await graph.items.warehouses()) ?? [] }
    }

    override func refresh() {
        super.refresh()
        loadTotals()
    }

    private func loadTotals() {
        totalsTask?.cancel()
        totals = nil
        let wh = warehouseId, query = search.query, available = availableOnly
        totalsTask = Task { [weak self] in
            guard let self else { return }
            let cards: [DashboardCard]
            if let wh {
                cards = (try? await self.graph.items.warehouseTotals(warehouseId: wh, availableOnly: available)) ?? []
            } else {
                cards = (try? await self.graph.items.inventoryTotals(search: query, availableOnly: available)) ?? []
            }
            if Task.isCancelled { return }
            self.totals = Array(cards.filter { $0.numeric != nil }.prefix(6))
        }
    }

    override func fetch(_ page: PageQuery) async throws -> Page<StockRowModel> {
        if let wh = warehouseId {
            let p = try await graph.items.stockInWarehouse(wh, search: search.query, availableOnly: availableOnly, page: page)
            let rows = p.results.filter { !lowStockOnly || $0.belowReorderPoint }.map(StockRowModel.perWarehouse)
            return Page(count: p.count, next: p.next, previous: p.previous, results: rows)
        }
        let p = try await graph.items.inventory(search: search.query, availableOnly: availableOnly, page: page)
        let rows = p.results.filter { !lowStockOnly || $0.belowReorderPoint }.map(StockRowModel.company)
        return Page(count: p.count, next: p.next, previous: p.previous, results: rows)
    }
}

struct InventoryView: View {
    let graph: AppContainer.Graph
    @StateObject private var model: InventoryModel
    @State private var scanning = false
    @State private var scanned: String?

    init(graph: AppContainer.Graph) {
        self.graph = graph
        _model = StateObject(wrappedValue: InventoryModel(graph: graph))
    }

    var body: some View {
        PagedListView(model: model, emptyTitle: "No stock to show", emptySubtitle: "Try clearing the filters or pick another warehouse.", header: {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 8) {
                    NavigationLink(value: Route.receive) { Label("Receive PO", systemImage: "tray.and.arrow.down") }
                    NavigationLink(value: Route.transfers) { Label("Transfer", systemImage: "arrow.left.arrow.right") }
                    NavigationLink(value: Route.adjust(itemId: nil, warehouseId: nil)) { Label("Adjust", systemImage: "slider.horizontal.3") }
                }
                .buttonStyle(.bordered).controlSize(.small).font(.caption)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        chip("All warehouses", on: model.warehouseId == nil) { model.warehouseId = nil }
                        ForEach(model.warehouses) { w in chip(w.name, on: model.warehouseId == w.id) { model.warehouseId = w.id } }
                    }
                }
                HStack(spacing: 8) {
                    chip("In stock", on: model.availableOnly) { model.availableOnly.toggle() }
                    chip("Below reorder point", on: model.lowStockOnly) { model.lowStockOnly.toggle() }
                }
                if let totals = model.totals, !totals.isEmpty {
                    // Company-wide or per-warehouse totals (`get_inventory_totalization/` / `totalization/`).
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(totals) { c in
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(c.label).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                                    Text(c.value).font(.subheadline.weight(.semibold)).lineLimit(1)
                                }
                                .padding(.horizontal, 12).padding(.vertical, 8)
                                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
                            }
                        }
                    }
                }
            }
            .listRowSeparator(.hidden)
        }) { row in
            switch row {
            case .company(let item):
                NavigationLink(value: Route.item(item.id, type: item.itemType)) {
                    EntityRow(imageUrl: item.primaryImage, title: item.name, subtitle: [item.marketplaceTitle, item.upcCode].compactMap { $0 }.joined(separator: " · "),
                              badge: item.belowReorderPoint ? "Below reorder point (\(item.reorderPoint ?? 0))" : nil) {
                        Text("\(item.available)").font(.headline).foregroundStyle(item.belowReorderPoint ? Brand.amber : Brand.green)
                        Text("available").font(.caption2).foregroundStyle(.secondary)
                        if item.onOrder > 0 { Text("+\(item.onOrder) on order").font(.caption2).foregroundStyle(.secondary) }
                    }
                }
            case .perWarehouse(let s):
                NavigationLink(value: Route.item(s.inventoryItemId ?? 0)) {
                    EntityRow(imageUrl: s.primaryImage, title: s.name, subtitle: [s.marketplaceTitle, s.upcCode].compactMap { $0 }.joined(separator: " · ")) {
                        Text("\(s.available)").font(.headline).foregroundStyle(s.available > 0 ? Brand.green : .secondary)
                        Text("avail · \(s.onHand) on hand").font(.caption2).foregroundStyle(.secondary)
                        if s.committed > 0 { Text("\(s.committed) committed").font(.caption2).foregroundStyle(.secondary) }
                    }
                }
                .disabled(s.inventoryItemId == nil)
            }
        }
        .searchable(text: $model.search.text, prompt: "Search SKU or UPC")
        .navigationTitle("Inventory")
        .toolbar { ToolbarItem(placement: .navigationBarTrailing) { Button { scanning = true } label: { Image(systemName: "barcode.viewfinder") } } }
        .sheet(isPresented: $scanning) { ScannerSheet { scanned = $0 } }
        .navigationDestination(isPresented: Binding(get: { scanned != nil }, set: { if !$0 { scanned = nil } })) {
            if let code = scanned { ScanResultView(graph: graph, code: code) }
        }
    }

    private func chip(_ label: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(.caption).padding(.horizontal, 10).padding(.vertical, 6)
                .background(on ? Brand.greenTint : Color(.secondarySystemBackground)).foregroundStyle(on ? Brand.greenText : .primary).fontWeight(on ? .semibold : .regular).clipShape(Capsule())
        }.buttonStyle(.plain)
    }
}
