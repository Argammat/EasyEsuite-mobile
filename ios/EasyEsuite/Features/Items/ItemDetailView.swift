import SwiftUI
import EasyEsuiteKit

struct ItemDetailData { var item: ItemDetail; var stock: [WarehouseStock]; var stockError: String? }

@MainActor
final class ItemDetailModel: ObservableObject {
    @Published var state: Loadable<ItemDetailData> = .idle
    let graph: AppContainer.Graph
    let id: Int64
    init(graph: AppContainer.Graph, id: Int64) { self.graph = graph; self.id = id }

    func load() async {
        state = .loading
        do {
            async let item = graph.items.item(id)
            var stock: [WarehouseStock] = []
            var stockError: String?
            do { stock = try await graph.items.stockByWarehouse(itemId: id) } catch { stockError = error.userMessage }
            let detail = try await item
            state = .ready(ItemDetailData(item: detail, stock: stock, stockError: stockError))
        } catch {
            state = .failed(error.userMessage)
        }
    }
}

struct ItemDetailView: View {
    @StateObject private var model: ItemDetailModel

    init(graph: AppContainer.Graph, id: Int64) { _model = StateObject(wrappedValue: ItemDetailModel(graph: graph, id: id)) }

    var body: some View {
        Group {
            switch model.state {
            case .idle, .loading: ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed(let m): ErrorRow(message: m) { Task { await model.load() } }
            case .ready(let data): content(for: data)
            }
        }
        .navigationTitle(model.state.value?.item.name ?? "Item")
        .navigationBarTitleDisplayMode(.inline)
        .task { if case .idle = model.state { await model.load() } }
    }

    @ViewBuilder
    private func content(for data: ItemDetailData) -> some View {
        let item = data.item
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if !item.allImages.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) { ForEach(item.allImages, id: \.self) { Thumb(url: $0, size: 120) } }
                    }
                }
                Text(item.displayTitle).font(.headline)
                Text([item.upcCode.map { "UPC \($0)" }, item.marketplaceBrand, item.itemConditionName].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary)

                HStack(spacing: 8) {
                    StatTile(label: "On hand", value: "\(item.onHand)")
                    StatTile(label: "Avg cost", value: item.averageCost?.formatted() ?? "—")
                    StatTile(label: "Last sold", value: item.lastSellingPrice?.formatted() ?? "—")
                    StatTile(label: "Value", value: item.totalValue?.formatted() ?? "—")
                }

                HStack {
                    NavigationLink(value: Route.newTransfer(itemId: item.id)) { Label("Transfer", systemImage: "arrow.left.arrow.right") }.buttonStyle(.bordered)
                    NavigationLink(value: Route.adjust(itemId: item.id, warehouseId: nil)) { Label("Adjust stock", systemImage: "slider.horizontal.3") }.buttonStyle(.bordered)
                }

                SectionHeader(text: "Stock by warehouse")
                if let e = data.stockError { Text(e).foregroundStyle(.red).font(.caption) }
                if data.stock.isEmpty && data.stockError == nil { Text("No warehouse records yet.").font(.caption).foregroundStyle(.secondary) }
                ForEach(data.stock) { StockRow(stock: $0) }

                if let pricing = item.marketplacePricing, !pricing.isEmpty {
                    SectionHeader(text: "Marketplace prices")
                    ForEach(Array(pricing.enumerated()), id: \.offset) { _, p in KeyValueRow(label: Marketplaces.name(p.marketplace), value: p.price?.formatted()) }
                }
                if !item.marketplaceSkus.isEmpty {
                    SectionHeader(text: "Marketplace SKUs")
                    ForEach(Array(item.marketplaceSkus.enumerated()), id: \.offset) { _, s in KeyValueRow(label: s.0, value: s.1) }
                }

                SectionHeader(text: "Details")
                KeyValueRow(label: "Brand", value: item.marketplaceBrand)
                KeyValueRow(label: "Platform", value: item.marketplacePlatform)
                KeyValueRow(label: "Manufacturer", value: item.manufacturer)
                KeyValueRow(label: "Weight", value: item.weight.map { "\($0) \(item.weightUnit ?? "")" })
                KeyValueRow(label: "Dimensions", value: item.dimensionsText)
                KeyValueRow(label: "Cost (purchase price)", value: item.purchasePrice?.formatted())
                KeyValueRow(label: "Last purchase price", value: item.lastPurchasePrice?.formatted())
                KeyValueRow(label: "Taxable", value: item.taxable.map { $0 ? "Yes" : "No" })
                KeyValueRow(label: "Created", value: DateText.short(item.createdDate))
                KeyValueRow(label: "Updated", value: DateText.short(item.modifiedDate))
                if let d = item.description, !d.isEmpty {
                    SectionHeader(text: "Description")
                    Text(d).font(.subheadline)
                }
            }
            .padding()
        }
        .refreshable { await model.load() }
    }
}

struct StockRow: View {
    let stock: WarehouseStock
    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(stock.warehouse).font(.subheadline.weight(.medium))
                Text(detail).font(.caption).foregroundStyle(stock.belowReorderPoint ? Brand.amber : .secondary)
            }
            Spacer()
            VStack(alignment: .trailing) {
                Text("\(stock.onHand)").font(.headline).foregroundStyle(stock.onHand > 0 ? Brand.green : .secondary)
                Text("on hand").font(.caption2).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
    private var detail: String {
        var parts = ["Available \(stock.available)"]
        if stock.committed > 0 { parts.append("committed \(stock.committed)") }
        if stock.onOrder > 0 { parts.append("on order \(stock.onOrder)") }
        if stock.inTransit > 0 { parts.append("in transit \(stock.inTransit)") }
        if let r = stock.reorderPoint, r > 0 { parts.append("reorder at \(r)") }
        return parts.joined(separator: " · ")
    }
}
