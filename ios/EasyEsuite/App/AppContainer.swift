import Foundation
import Combine
import EasyEsuiteKit

/// One object graph per signed-in workspace. The API client is rebuilt on login/logout because the
/// workspace's `schema_name` is part of every URL.
@MainActor
final class AppContainer: ObservableObject {
    @Published private(set) var session: Session?
    /// "Switch workspace" from Settings: the login screen is shown over the app while the current session stays valid.
    @Published private(set) var switchingWorkspace = false

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
        /// Firestore-backed data (Copilot conversations, connector status) — nil when the build has no Firebase config.
        let firestoreConnector: FirestoreClient?
        /// In-app notifications — nil when the build has no Firebase config.
        let firestoreNotifications: FirestoreClient?

        init(tenant: String, tokenStore: TokenStore, onExpired: @escaping @Sendable () -> Void) {
            self.tenant = tenant
            let firebase = AppConfig.firebase
            let apiClient = APIClient(config: ApiConfig(tenant: tenant, apiRoot: AppConfig.apiRoot, firebase: firebase), tokenStore: tokenStore)
            apiClient.onSessionExpired = onExpired
            let authService = AuthService(client: apiClient)
            client = apiClient
            auth = authService
            items = ItemsRepository(client: apiClient)
            inventory = InventoryRepository(client: apiClient)
            orders = OrdersRepository(client: apiClient)
            shipping = ShippingRepository(client: apiClient)
            reports = ReportsRepository(client: apiClient)
            assistant = AssistantRepository(client: apiClient)
            // Locals, not `self.`: a closure must not capture self before every stored property is set.
            firestoreConnector = firebase.map { FirestoreClient(config: $0, database: $0.connectorDatabase, auth: authService, urlSession: apiClient.urlSession) }
            firestoreNotifications = firebase.map { FirestoreClient(config: $0, database: $0.notificationsDatabase, auth: authService, urlSession: apiClient.urlSession) }
        }
    }

    init() {
        if let stored = tokenStore.load(), !stored.tenant.isEmpty { session = stored }
        if let s = session { graph = makeGraph(tenant: s.tenant) }
    }

    /// A graph for signing in: blank tenant = the global root (step 1, listing workspaces); a `schema_name` = that
    /// workspace (step 2, the actual login).
    func makeGraph(tenant: String = "") -> Graph {
        Graph(tenant: tenant, tokenStore: tokenStore) { [weak self] in
            Task { @MainActor in self?.signOut() }
        }
    }

    /// Graph for the active workspace; only valid while signed in.
    func require() -> Graph { graph! }

    func signedIn(_ session: Session) {
        tokenStore.lastTenant = session.tenant
        tokenStore.lastEmail = session.email
        graph = makeGraph(tenant: session.tenant)
        switchingWorkspace = false
        self.session = session
    }

    /// Keep the current workspace until the new login succeeds; the login screen asks for the password again
    /// (the backend's login is per workspace, and the app never stores the password).
    func switchWorkspace() { switchingWorkspace = true }
    func cancelWorkspaceSwitch() { switchingWorkspace = false }

    /// Delete both JWTs and the Firebase tokens; nothing is cached locally beyond that.
    func signOut() {
        tokenStore.clear()
        switchingWorkspace = false
        graph = nil
        session = nil
    }
}

/// Build-time configuration, read from Info.plist (filled from build settings in project.yml; the real
/// values for each environment live in an untracked `ios/Config/Environment.local.xcconfig` or in CI secrets —
/// never in git, the repository is public):
///  - `EasyEsuiteAPIRoot`                 — defaults to the production API host
///  - `EasyEsuiteFirebaseAPIKey`          — Firebase web API key of the same environment
///  - `EasyEsuiteFirebaseProjectId`       — Firebase project id
///  - `EasyEsuiteFirestoreConnectorDB`    — named Firestore database with Copilot / connector data
///  - `EasyEsuiteFirestoreNotificationsDB`— named Firestore database with in-app notifications
/// Without the Firebase keys the app works REST-only (Copilot history / notifications unavailable).
enum AppConfig {
    private static func string(_ key: String) -> String? {
        (Bundle.main.object(forInfoDictionaryKey: key) as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
    }
    static var apiRoot: URL { string("EasyEsuiteAPIRoot").flatMap(URL.init(string:)) ?? ApiConfig.defaultApiRoot }
    static var firebase: FirebaseConfig? {
        guard let key = string("EasyEsuiteFirebaseAPIKey"), let project = string("EasyEsuiteFirebaseProjectId") else { return nil }
        return FirebaseConfig(apiKey: key, projectId: project,
                              connectorDatabase: string("EasyEsuiteFirestoreConnectorDB") ?? "(default)",
                              notificationsDatabase: string("EasyEsuiteFirestoreNotificationsDB") ?? "(default)")
    }
    static var environmentName: String { ApiConfig(tenant: "", apiRoot: apiRoot).environmentName }
    static var hasFirebase: Bool { firebase != nil }
}
