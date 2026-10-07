import SwiftUI
import Combine
import EasyEsuiteKit

/// Catalog (`items/items/`): every item type in one list, filtered server-side by
/// `search`, `item_type`, `item_condition` and `is_available`.
@MainActor
final class ItemsModel: PagedListModel<ItemSummary> {
    let graph: AppContainer.Graph
    var search = SearchDebouncer()
    /// nil = all types; otherwise an `ItemTypes` code.
    @Published var itemType: String? { didSet { if itemType != oldValue { refresh() } } }
    @Published var conditionId: Int? { didSet { if conditionId != oldValue { refresh() } } }
    @Published var availableOnly = false { didSet { if availableOnly != oldValue { refresh() } } }
    @Published var conditions: [ItemCondition] = []
    private var bag = Set<AnyCancellable>()

    init(graph: AppContainer.Graph) {
        self.graph = graph
        super.init()
        search.$query.dropFirst().sink { [weak self] _ in self?.refresh() }.store(in: &bag)
        search.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &bag)
        refresh()
        Task { if let list = try? await graph.items.conditions() { conditions = list } }
    }

    override func fetch(_ page: PageQuery) async throws -> Page<ItemSummary> {
        try await graph.items.catalog(search: search.query, itemType: itemType, conditionId: conditionId, availableOnly: availableOnly, page: page)
    }
}

struct ItemsView: View {
    let graph: AppContainer.Graph
    @StateObject private var model: ItemsModel
    @State private var scanning = false
    @State private var scanned: String?

    init(graph: AppContainer.Graph) {
        self.graph = graph
        _model = StateObject(wrappedValue: ItemsModel(graph: graph))
    }

    var body: some View {
        PagedListView(model: model, emptyTitle: model.search.query.isEmpty ? "No items yet" : "No items match “\(model.search.query)”",
                      emptySubtitle: model.search.query.isEmpty ? "Tap + or scan a barcode to add one." : nil,
                      header: {
                          filters.listRowSeparator(.hidden).listRowInsets(EdgeInsets())
                          if model.total > 0 {
                              Text("\(fmtCount(model.total)) items").font(.caption).foregroundStyle(.secondary).listRowSeparator(.hidden)
                          }
                      }) { item in
            NavigationLink(value: Route.item(item.id, type: item.itemType)) { ItemRow(item: item) }
        }
        .searchable(text: $model.search.text, prompt: "Search SKU, title or UPC")
        .navigationTitle("Items")
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                Button { scanning = true } label: { Image(systemName: "barcode.viewfinder") }
                NavigationLink(value: Route.newItem(upc: nil)) { Image(systemName: "plus") }
            }
        }
        .sheet(isPresented: $scanning) { ScannerSheet(title: "Scan item or label") { code in scanned = code } }
        .navigationDestination(isPresented: Binding(get: { scanned != nil }, set: { if !$0 { scanned = nil } })) {
            if let code = scanned { ScanResultView(graph: graph, code: code) }
        }
        .onReceive(NotificationCenter.default.publisher(for: .itemCreated)) { _ in model.refresh() }
    }

    /// Type + availability (+ condition) chips — all server-side filters.
    @ViewBuilder private var filters: some View {
        VStack(alignment: .leading, spacing: 6) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    FilterChip(label: "All", selected: model.itemType == nil) { model.itemType = nil }
                    FilterChip(label: "Items", selected: model.itemType == ItemTypes.inventory) { model.itemType = ItemTypes.inventory }
                    FilterChip(label: "Kits", selected: model.itemType == ItemTypes.kit) { model.itemType = ItemTypes.kit }
                    FilterChip(label: "Variants", selected: model.itemType == ItemTypes.variant) { model.itemType = ItemTypes.variant }
                    FilterChip(label: "In stock", selected: model.availableOnly) { model.availableOnly.toggle() }
                }
                .padding(.horizontal, 16)
            }
            if !model.conditions.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        FilterChip(label: "Any condition", selected: model.conditionId == nil) { model.conditionId = nil }
                        ForEach(model.conditions) { c in
                            FilterChip(label: c.displayName, selected: model.conditionId == c.id) { model.conditionId = model.conditionId == c.id ? nil : c.id }
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
        }
        .padding(.vertical, 4)
    }
}

struct ItemRow: View {
    let item: ItemSummary
    var body: some View {
        EntityRow(imageUrl: item.primaryImage, title: item.name,
                  subtitle: [ItemTypes.badge(item.itemType), item.marketplaceTitle, item.upcCode.map { "UPC \($0)" }].compactMap { $0 }.joined(separator: " · ")) {
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text("\(item.onHand)").font(.headline)
                Text("on hand").font(.caption2).foregroundStyle(.secondary)
            }
            Text(item.averageCost?.formatted() ?? "—").font(.caption).foregroundStyle(.secondary)
        }
    }
}

extension Notification.Name {
    static let itemCreated = Notification.Name("easyesuite.itemCreated")
    static let transferCreated = Notification.Name("easyesuite.transferCreated")
}
