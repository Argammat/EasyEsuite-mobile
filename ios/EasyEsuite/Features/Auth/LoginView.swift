import SwiftUI
import EasyEsuiteKit

/// Sign-in like the web app: email + password, then choose the workspace (company).
@MainActor
final class LoginModel: ObservableObject {
    enum Step { case credentials, secondFactor, workspace }

    @Published var step: Step = .credentials
    @Published var email = ""
    @Published var password = ""
    @Published var code = ""
    @Published var busy = false
    @Published var error: String?
    @Published var message: String?
    /// Tokens waiting for a workspace.
    @Published var pending: Session?
    @Published var workspaces: [TenantInfo] = []
    @Published var selectedTenant: String?
    /// Typed slug when the backend returned no workspace list.
    @Published var manualTenant = ""
    @Published var switching = false
    private var challengeToken: String?

    var canSubmit: Bool { !busy && email.contains("@") && password.count >= 4 }
    var chosenTenant: String? {
        let t = selectedTenant ?? manualTenant.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return t.isEmpty ? nil : t
    }

    func prepare(container: AppContainer) {
        if email.isEmpty { email = container.tokenStore.lastEmail ?? "" }
        // "Switch workspace" from Settings: tokens are still valid, go straight to the picker.
        if let pending = container.pendingSwitch, step == .credentials {
            openPicker(pending, workspaces: [], switching: true, container: container)
        }
    }

    func submit(container: AppContainer) {
        guard canSubmit else { return }
        busy = true; error = nil
        let graph = container.makeGraph()   // no tenant yet: global sign-in
        Task {
            do { handle(try await graph.auth.login(email: email, password: password), container: container) }
            catch { busy = false; self.error = friendly(error) }
        }
    }

    func submitCode(container: AppContainer) {
        guard code.count >= 4, !busy else { return }
        busy = true; error = nil
        let graph = container.makeGraph()
        Task {
            do { handle(try await graph.auth.completeSecondFactor(email: email, challengeToken: challengeToken, code: code), container: container) }
            catch { busy = false; self.error = friendly(error) }
        }
    }

    private func handle(_ result: LoginResult, container: AppContainer) {
        switch result {
        case .success(let session):
            container.signedIn(session)
        case .secondFactorRequired(let token, let msg):
            challengeToken = token; step = .secondFactor; message = msg ?? "Enter the code from your authenticator app."; busy = false
        case .workspaceRequired(let pending, let workspaces):
            openPicker(pending, workspaces: workspaces, switching: false, container: container)
        }
    }

    private func openPicker(_ pending: Session, workspaces: [TenantInfo], switching: Bool, container: AppContainer) {
        let last = container.tokenStore.lastTenant
        self.pending = pending
        self.workspaces = workspaces
        self.switching = switching
        selectedTenant = workspaces.first { $0.slug == last }?.slug ?? workspaces.first?.slug
        step = .workspace
        busy = workspaces.isEmpty && switching
        if workspaces.isEmpty && switching {
            // Still inside the previous workspace: its graph tries the global list and the tenant-scoped (verified) one.
            let graph = container.makeGraph(tenant: last ?? "")
            Task {
                let list = (try? await graph.auth.workspaces()) ?? []
                self.workspaces = list
                selectedTenant = list.first { $0.slug == last }?.slug ?? list.first?.slug
                busy = false
            }
        }
    }

    func continueToWorkspace(container: AppContainer) {
        guard let pending, let tenant = chosenTenant else { return }
        let session = container.makeGraph().auth.selectWorkspace(pending, tenant: tenant)
        container.signedIn(session)
    }

    func back(container: AppContainer) {
        if step == .workspace, switching { container.cancelWorkspaceSwitch(); return }
        container.makeGraph().auth.logout()
        step = .credentials; code = ""; challengeToken = nil; pending = nil; workspaces = []; selectedTenant = nil; manualTenant = ""; error = nil; busy = false
    }

    private func friendly(_ e: Error) -> String {
        let m = e.userMessage
        if m.localizedCaseInsensitiveContains("credentials") || m.localizedCaseInsensitiveContains("unable to log in") || m.localizedCaseInsensitiveContains("incorrect") { return "Email or password is incorrect." }
        return m
    }
}

struct LoginView: View {
    @EnvironmentObject private var container: AppContainer
    @StateObject private var model = LoginModel()
    @State private var showPassword = false

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                brandBand
                VStack(alignment: .leading, spacing: 14) {
                    switch model.step {
                    case .credentials: credentials
                    case .secondFactor: secondFactor
                    case .workspace: workspacePicker
                    }
                    if let err = model.error { Text(err).foregroundStyle(.red).font(.subheadline) }
                }
                .padding(24)
            }
        }
        .background(Brand.page)
        .scrollDismissesKeyboard(.interactively)
        .onAppear { model.prepare(container: container) }
    }

    /// The web login's navy panel: logo + tagline.
    private var brandBand: some View {
        VStack(alignment: .leading, spacing: 8) {
            Image("Logo").resizable().scaledToFit().frame(height: 54)
            Text("EASYESUITE ERP").font(.caption2.weight(.bold)).tracking(1.6).foregroundStyle(Color(red: 0x1F / 255, green: 0xC4 / 255, blue: 0x87 / 255)).padding(.top, 10)
            Text("One system for everything you sell.").font(.title2.weight(.heavy)).foregroundStyle(.white)
            Text("Marketplaces, inventory, fulfillment, and accounting — synchronized in one operation.").font(.subheadline).foregroundStyle(Color(red: 0x9C / 255, green: 0xB2 / 255, blue: 0xC9 / 255))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 24).padding(.top, 64).padding(.bottom, 28)
        .background(Brand.navy)
    }

    @ViewBuilder private var credentials: some View {
        Text("Log in").font(.title2.weight(.medium)).foregroundStyle(Brand.ink)
        Text("Welcome back — sign in to continue.").font(.subheadline).foregroundStyle(.secondary)
        field("Email", text: $model.email).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled().textContentType(.username)
        HStack {
            Group {
                if showPassword { TextField("Password", text: $model.password) } else { SecureField("Password", text: $model.password) }
            }.textContentType(.password)
            Button { showPassword.toggle() } label: { Image(systemName: showPassword ? "eye.slash" : "eye") }.buttonStyle(.plain).foregroundStyle(.secondary)
        }
        .padding(12).background(Brand.field).clipShape(RoundedRectangle(cornerRadius: 14))

        Button { model.submit(container: container) } label: {
            if model.busy { ProgressView().tint(.white).frame(maxWidth: .infinity) } else { Text("Log in").frame(maxWidth: .infinity) }
        }
        .buttonStyle(.borderedProminent).controlSize(.large).disabled(!model.canSubmit)
        Text("After signing in you choose which company to work in — the same workspaces you see on erp.easyesuite.com.")
            .font(.caption).foregroundStyle(.secondary)
    }

    @ViewBuilder private var secondFactor: some View {
        Text("Verification").font(.title2.weight(.medium)).foregroundStyle(Brand.ink)
        Text(model.message ?? "Enter your verification code").font(.subheadline).foregroundStyle(.secondary)
        field("Verification code", text: $model.code).keyboardType(.numberPad).textContentType(.oneTimeCode)
        Button { model.submitCode(container: container) } label: { Text(model.busy ? "Verifying…" : "Verify").frame(maxWidth: .infinity) }
            .buttonStyle(.borderedProminent).controlSize(.large).disabled(model.busy || model.code.count < 4)
        Button("Back") { model.back(container: container) }
    }

    @ViewBuilder private var workspacePicker: some View {
        Text("Choose your workspace").font(.title2.weight(.medium)).foregroundStyle(Brand.ink)
        Text("Signed in as \(model.pending?.email ?? model.email). You can switch workspaces any time from Settings.").font(.subheadline).foregroundStyle(.secondary)
        if model.busy && model.workspaces.isEmpty {
            HStack { ProgressView(); Text("Loading your workspaces…").font(.subheadline) }
        } else if model.workspaces.isEmpty {
            Text("We couldn't list your workspaces — enter the company name you use on the web.").font(.caption).foregroundStyle(.secondary)
            field("Company (workspace)", text: $model.manualTenant).textInputAutocapitalization(.never).autocorrectionDisabled()
        } else {
            ForEach(model.workspaces) { w in
                WorkspaceRow(workspace: w, selected: w.slug == model.selectedTenant) { model.selectedTenant = w.slug }
            }
        }
        Button { model.continueToWorkspace(container: container) } label: {
            Text(model.chosenTenant.map { "Continue to \($0)" } ?? "Continue").frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent).controlSize(.large).disabled(model.chosenTenant == nil || model.busy)
        Button(model.switching ? "Cancel" : "Use a different account") { model.back(container: container) }.frame(maxWidth: .infinity)
    }

    private func field(_ title: String, text: Binding<String>) -> some View {
        TextField(title, text: text).padding(12).background(Brand.field).clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

private struct WorkspaceRow: View {
    let workspace: TenantInfo
    let selected: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Text(workspace.slug.prefix(2).uppercased()).font(.caption.weight(.bold)).foregroundStyle(Brand.greenText)
                    .frame(width: 40, height: 40).background(Brand.greenTint).clipShape(RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 2) {
                    Text(workspace.slug).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                    if !workspace.name.isEmpty, workspace.name.caseInsensitiveCompare(workspace.slug) != .orderedSame {
                        Text(workspace.name).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if selected { Image(systemName: "checkmark").foregroundStyle(Brand.greenText).fontWeight(.bold) }
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .background(selected ? Color(red: 0xF3 / 255, green: 0xFB / 255, blue: 0xF7 / 255) : Color.white)
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(selected ? Brand.green : Color(red: 0xE4 / 255, green: 0xEC / 255, blue: 0xF4 / 255), lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
    }
}
