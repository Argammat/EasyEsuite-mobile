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

    /// True when this build signs in against Firebase / Identity Platform (see `AppConfig`).
    var firebaseSignIn: Bool { AppConfig.firebaseApiKey != nil }

    /// Set by `switchWorkspace()`: the login screen opens straight on the workspace picker with these tokens.
    private(set) var pendingSwitch: Session?

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
        // A session whose workspace is still to be chosen never counts as signed in (and is dropped on launch).
        if let stored = tokenStore.load() {
            if stored.isPending { tokenStore.clear() } else { session = stored }
        }
        if let s = session { graph = makeGraph(tenant: s.tenant, firebaseTenantId: s.firebaseTenantId) }
    }

    /// A graph for signing in. With a blank tenant the auth calls go to the global root (email + password first,
    /// workspace afterwards); with a tenant they are scoped to that workspace.
    func makeGraph(tenant: String = "", firebaseTenantId: String? = nil) -> Graph {
        Graph(tenant: tenant, firebaseTenantId: firebaseTenantId, tokenStore: tokenStore) { [weak self] in
            Task { @MainActor in self?.signOut() }
        }
    }

    /// Graph for the active tenant; only valid while signed in.
    func require() -> Graph { graph! }

    func signedIn(_ session: Session) {
        tokenStore.lastTenant = session.tenant
        tokenStore.lastEmail = session.email
        pendingSwitch = nil
        graph = makeGraph(tenant: session.tenant, firebaseTenantId: session.firebaseTenantId)
        self.session = session
    }

    /// Keep the tokens, drop the workspace: the login screen shows the picker again.
    func switchWorkspace() {
        guard var current = session else { return }
        current.tenant = ""
        pendingSwitch = current
        graph = nil
        session = nil
    }

    /// Backing out of a workspace switch: restore the previous workspace without re-authenticating.
    func cancelWorkspaceSwitch() {
        guard var pending = pendingSwitch, let previous = tokenStore.lastTenant else { return }
        pending.tenant = previous
        signedIn(pending)
    }

    func signOut() {
        tokenStore.clear()
        pendingSwitch = nil
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
