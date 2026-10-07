import SwiftUI
import EasyEsuiteKit

struct MoreView: View {
    let graph: AppContainer.Graph
    var body: some View {
        List {
            Section("Warehouse") {
                NavigationLink(value: Route.receive) { Label("Receive purchase orders", systemImage: "tray.and.arrow.down") }
                NavigationLink(value: Route.transfers) { Label("Transfers", systemImage: "arrow.left.arrow.right") }
                NavigationLink(value: Route.adjust(itemId: nil, warehouseId: nil)) { Label("Stock adjustment", systemImage: "slider.horizontal.3") }
            }
            Section("Insights") {
                NavigationLink(value: Route.reports) { Label("Sales by item & carrier spend", systemImage: "chart.bar") }
                NavigationLink(value: Route.assistant) { Label("Copilot", systemImage: "sparkles") }
            }
            Section("Account") {
                NavigationLink(value: Route.settings) { Label("Settings & sign out", systemImage: "gearshape") }
            }
        }
        .navigationTitle("More")
    }
}

struct SettingsView: View {
    let graph: AppContainer.Graph
    @EnvironmentObject private var container: AppContainer
    @State private var profile: UserProfile?
    @State private var error: String?
    @State private var confirmSignOut = false

    var body: some View {
        List {
            Section("Signed in as") {
                KeyValueRow(label: "Name", value: profile?.displayName)
                KeyValueRow(label: "Email", value: profile?.email ?? container.session?.email)
                KeyValueRow(label: "Role", value: profile?.roleText)
                KeyValueRow(label: "Workspace", value: graph.tenant)
                KeyValueRow(label: "API", value: ApiConfig.defaultApiRoot.absoluteString)
                KeyValueRow(label: "App version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String)
                if let error { Text(error).foregroundStyle(.red).font(.caption) }
            }
            Section {
                Button { container.switchWorkspace() } label: { Label("Switch workspace", systemImage: "arrow.left.arrow.right") }
                Button(role: .destructive) { confirmSignOut = true } label: { Label("Sign out", systemImage: "rectangle.portrait.and.arrow.right") }
            }
        }
        .navigationTitle("Settings")
        .task { do { profile = try await graph.auth.me() } catch { self.error = error.userMessage } }
        .confirmationDialog("Sign out?", isPresented: $confirmSignOut, titleVisibility: .visible) {
            Button("Sign out", role: .destructive) { container.signOut() }
        } message: { Text("You'll need your company name, email and password to sign back in.") }
    }
}
