import SwiftUI
import EasyEsuiteKit

/// Every pushable screen in the app. One enum so any tab can deep-link anywhere.
enum Route: Hashable {
    /// `type` is the row's `item_type` (INV/KIT/VAR) so the detail screen can hit the type-specific endpoint.
    case item(Int64, type: String? = nil)
    case newItem(upc: String?)
    case order(Int64)
    case shipment(String)
    case scanResult(String)
    case transfers
    case newTransfer(itemId: Int64?)
    case receive
    case adjust(itemId: Int64?, warehouseId: Int?)
    case reports
    case assistant
    case settings
}

struct RootView: View {
    @EnvironmentObject private var container: AppContainer

    var body: some View {
        if let session = container.session {
            MainTabs(graph: container.require())
                .id(session.tenant)   // rebuild all view models when the tenant changes
        } else {
            LoginView()
        }
    }
}

struct MainTabs: View {
    let graph: AppContainer.Graph

    var body: some View {
        TabView {
            RoutedStack(graph: graph) { DashboardView(graph: graph) }
                .tabItem { Label("Dashboard", systemImage: "square.grid.2x2.fill") }
            RoutedStack(graph: graph) { ItemsView(graph: graph) }
                .tabItem { Label("Items", systemImage: "shippingbox.fill") }
            RoutedStack(graph: graph) { InventoryView(graph: graph) }
                .tabItem { Label("Inventory", systemImage: "building.2.fill") }
            RoutedStack(graph: graph) { OrdersView(graph: graph) }
                .tabItem { Label("Ecommerce", systemImage: "cart.fill") }
            RoutedStack(graph: graph) { MoreView(graph: graph) }
                .tabItem { Label("More", systemImage: "ellipsis.circle.fill") }
        }
    }
}

/// NavigationStack with the shared Route → View mapping.
struct RoutedStack<Root: View>: View {
    let graph: AppContainer.Graph
    @ViewBuilder let root: () -> Root

    var body: some View {
        NavigationStack {
            root()
                .navigationDestination(for: Route.self) { route in destination(route) }
        }
    }

    @ViewBuilder
    private func destination(_ route: Route) -> some View {
        switch route {
        case .item(let id, let type): ItemDetailView(graph: graph, id: id, itemType: type)
        case .newItem(let upc): NewItemView(graph: graph, initialUpc: upc)
        case .order(let id): OrderDetailView(graph: graph, id: id)
        case .shipment(let id): ShipmentDetailView(graph: graph, id: id)
        case .scanResult(let code): ScanResultView(graph: graph, code: code)
        case .transfers: TransfersView(graph: graph)
        case .newTransfer(let itemId): NewTransferView(graph: graph, preselectedItem: itemId)
        case .receive: ReceiveView(graph: graph)
        case .adjust(let itemId, let warehouseId): AdjustView(graph: graph, preselectedItem: itemId, preselectedWarehouse: warehouseId)
        case .reports: ReportsView(graph: graph)
        case .assistant: AssistantView(graph: graph)
        case .settings: SettingsView(graph: graph)
        }
    }
}
