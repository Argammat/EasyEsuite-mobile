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

    /// Company name → workspace + Identity Platform tenant id (used only when Firebase sign-in is configured).
    let tenantDirectory = TenantDirectory(apiRoot: AppConfig.apiRoot)

    /// True when this build signs in against Firebase / Identity Platform (see `AppConfig`).
    var firebaseSignIn: Bool { AppConfig.firebaseApiKey != nil }

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

        init(tenant: String, firebaseTenantId: String?, tokenStore: TokenStore, onExpired: @escaping @Sendable () -> Void) {
            self.tenant = tenant
            let firebase = AppConfig.firebaseApiKey.map { FirebaseConfig(apiKey: $0, tenantId: firebaseTenantId ?? AppConfig.firebaseTenantId) }
            client = APIClient(config: ApiConfig(tenant: tenant, apiRoot: AppConfig.apiRoot, firebase: firebase), tokenStore: tokenStore)
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
        if let s = session { graph = makeGraph(tenant: s.tenant, firebaseTenantId: s.firebaseTenantId) }
    }

    func makeGraph(tenant: String, firebaseTenantId: String? = nil) -> Graph {
        Graph(tenant: tenant, firebaseTenantId: firebaseTenantId, tokenStore: tokenStore) { [weak self] in
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

/// Build-time configuration, read from Info.plist (set the keys in project.yml / xcconfig; never commit real values):
///  - `EasyEsuiteAPIRoot`            — defaults to the production API host
///  - `EasyEsuiteFirebaseAPIKey`     — Firebase web API key; empty = backend-proxied `auth/login/` flow
///  - `EasyEsuiteFirebaseTenantId`   — Identity Platform tenant id for single-tenant builds (optional)
enum AppConfig {
    private static func string(_ key: String) -> String? {
        (Bundle.main.object(forInfoDictionaryKey: key) as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
    }
    static var apiRoot: URL { string("EasyEsuiteAPIRoot").flatMap(URL.init(string:)) ?? ApiConfig.defaultApiRoot }
    static var firebaseApiKey: String? { string("EasyEsuiteFirebaseAPIKey") }
    static var firebaseTenantId: String? { string("EasyEsuiteFirebaseTenantId") }
}
