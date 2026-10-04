import Foundation
import Combine
import EasyEsuiteKit

/// One object graph per signed-in tenant. The API client is rebuilt on login/logout because the
/// tenant is part of every URL.
@MainActor
final class AppContainer: ObservableObject {
    @Published private(set) var session: Session?

    let tokenStore = KeychainTokenStore()
    private(set) var graph: Graph?

    final class Graph {
        let tenant: String
        let client: APIClient
        let auth: AuthService
        let items: ItemsRepository
        let inventory: InventoryRepository
        let orders: OrdersRepository
        let shipping: ShippingRepository
        let reports: ReportsRepository
        let assistant: AssistantRepository

        init(tenant: String, tokenStore: TokenStore, onExpired: @escaping @Sendable () -> Void) {
            self.tenant = tenant
            client = APIClient(config: ApiConfig(tenant: tenant), tokenStore: tokenStore)
            client.onSessionExpired = onExpired
            auth = AuthService(client: client)
            items = ItemsRepository(client: client)
            inventory = InventoryRepository(client: client)
            orders = OrdersRepository(client: client)
            shipping = ShippingRepository(client: client)
            reports = ReportsRepository(client: client)
            assistant = AssistantRepository(client: client)
        }
    }

    init() {
        session = tokenStore.load()
        if let s = session { graph = makeGraph(tenant: s.tenant) }
    }

    func makeGraph(tenant: String) -> Graph {
        Graph(tenant: tenant, tokenStore: tokenStore) { [weak self] in
            Task { @MainActor in self?.signOut() }
        }
    }

    /// Graph for the active tenant; only valid while signed in.
    func require() -> Graph { graph! }

    func signedIn(_ session: Session, using graph: Graph) {
        tokenStore.lastTenant = session.tenant
        tokenStore.lastEmail = session.email
        self.graph = graph
        self.session = session
    }

    func signOut() {
        tokenStore.clear()
        graph = nil
        session = nil
    }
}
