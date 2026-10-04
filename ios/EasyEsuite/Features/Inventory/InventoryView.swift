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

@MainActor
final class InventoryModel: PagedListModel<StockRowModel> {
    let graph: AppContainer.Graph
    var search = SearchDebouncer()
    @Published var availableOnly = true { didSet { refresh() } }
    @Published var lowStockOnly = false { didSet { refresh() } }
    @Published var warehouses: [Warehouse] = []
    @Published var warehouseId: Int? { didSet { refresh() } }
    private var bag = Set<AnyCancellable>()

    init(graph: AppContainer.Graph) {
        self.graph = graph
        super.init()
        search.$query.dropFirst().sink { [weak self] _ in self?.refresh() }.store(in: &bag)
        search.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &bag)
        refresh()
        Task { warehouses = (try? await graph.items.warehouses()) ?? [] }
    }

    override func fetch(_ page: PageQuery) async throws -> Page<StockRowModel> {
        if let wh = warehouseId {
            let p = try await graph.items.stockInWarehouse(wh, search: search.query, page: page)
            let rows = p.results
                .filter { !availableOnly || $0.onHand > 0 }
                .filter { !lowStockOnly || $0.belowReorderPoint }
                .map(StockRowModel.perWarehouse)
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
            }
            .listRowSeparator(.hidden)
        }) { row in
            switch row {
            case .company(let item):
                NavigationLink(value: Route.item(item.id)) {
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
                .background(on ? Brand.blue : Color(.secondarySystemBackground)).foregroundStyle(on ? .white : .primary).clipShape(Capsule())
        }.buttonStyle(.plain)
    }
}
