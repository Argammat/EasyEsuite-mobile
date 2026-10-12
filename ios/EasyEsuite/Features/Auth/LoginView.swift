import SwiftUI
import EasyEsuiteKit

/// Sign-in as the backend specifies it: email + password list the user's workspaces (no token yet), the chosen
/// workspace is then logged into with the same credentials. The password lives only in this model, in memory,
/// until the login succeeds.
@MainActor
final class LoginModel: ObservableObject {
    enum Step { case credentials, workspace, secondFactor, passwordReset }

    @Published var step: Step = .credentials
    @Published var email = ""
    @Published var password = ""
    @Published var code = ""
    @Published var busy = false
    @Published var error: String?
    @Published var message: String?
    @Published var workspaces: [Workspace] = []
    @Published var selectedTenant: String?
    @Published var switching = false
    private var challengeToken: String?
    private var pendingSession: Session?

    var canSubmit: Bool { !busy && email.contains("@") && password.count >= 4 }
    var selectedWorkspace: Workspace? { workspaces.first { $0.schemaName == selectedTenant } }

    func prepare(container: AppContainer) {
        if email.isEmpty { email = container.tokenStore.lastEmail ?? "" }
        switching = container.switchingWorkspace
        if switching { message = "Enter your password to choose another workspace." }
    }

    /// Step 1: list the workspaces. One workspace → straight to step 2.
    func submit(container: AppContainer) {
        guard canSubmit else { return }
        busy = true; error = nil
        Task {
            do {
                let list = try await container.makeGraph().auth.workspaces(email: email, password: password)
                workspaces = list
                let current = container.session?.tenant
                let last = container.tokenStore.lastTenant
                let preferred: Workspace?
                if switching {
                    preferred = list.first(where: { $0.schemaName != current })
                } else {
                    preferred = list.first(where: { $0.schemaName == last }) ?? list.first
                }
                selectedTenant = preferred?.schemaName ?? list.first?.schemaName
                if list.count == 1, !switching, let only = list.first {
                    await login(tenant: only.schemaName, container: container)
                } else if list.isEmpty {
                    busy = false; error = "Your account has no workspace yet. Ask an administrator to add you to a company."
                } else {
                    busy = false; step = .workspace
                }
            } catch { busy = false; self.error = friendly(error) }
        }
    }

    /// Step 2: log in to the chosen workspace.
    func continueToWorkspace(container: AppContainer) {
        guard let tenant = selectedTenant, !busy else { return }
        busy = true; error = nil
        Task { await login(tenant: tenant, container: container) }
    }

    private func login(tenant: String, container: AppContainer) async {
        let graph = container.makeGraph(tenant: tenant)
        do { handle(try await graph.auth.login(email: email, password: password), container: container) }
        catch { busy = false; self.error = friendly(error) }
    }

    func submitCode(container: AppContainer) {
        guard code.count >= 4, !busy, let tenant = selectedTenant else { return }
        busy = true; error = nil
        let graph = container.makeGraph(tenant: tenant)
        Task {
            do { handle(try await graph.auth.completeSecondFactor(email: email, challengeToken: challengeToken, pending: pendingSession, code: code), container: container) }
            catch { busy = false; self.error = friendly(error) }
        }
    }

    private func handle(_ result: LoginResult, container: AppContainer) {
        switch result {
        case .success(let session):
            password = ""; code = ""; pendingSession = nil; challengeToken = nil; busy = false
            container.signedIn(session)
        case .secondFactorRequired(let pending, let token, let msg):
            pendingSession = pending; challengeToken = token
            message = msg ?? "Enter the code from your authenticator app."
            step = .secondFactor; busy = false
        case .passwordResetRequired:
            password = ""; step = .passwordReset; busy = false
        }
    }

    func back(container: AppContainer) {
        if switching, step == .credentials { container.cancelWorkspaceSwitch(); return }
        step = .credentials; code = ""; challengeToken = nil; pendingSession = nil; error = nil; busy = false
        if !switching { message = nil }
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
                    case .workspace: workspacePicker
                    case .secondFactor: secondFactor
                    case .passwordReset: passwordReset
                    }
                    if let err = model.error { Text(err).foregroundStyle(.red).font(.subheadline) }
                }
                .padding(24)
                environmentFooter
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
        Text(model.switching ? "Switch workspace" : "Log in").font(.title2.weight(.medium)).foregroundStyle(Brand.ink)
        Text(model.message ?? "Welcome back — sign in to continue.").font(.subheadline).foregroundStyle(.secondary)
        field("Email", text: $model.email).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled().textContentType(.username)
        HStack {
            Group {
                if showPassword { TextField("Password", text: $model.password) } else { SecureField("Password", text: $model.password) }
            }.textContentType(.password)
            Button { showPassword.toggle() } label: { Image(systemName: showPassword ? "eye.slash" : "eye") }.buttonStyle(.plain).foregroundStyle(.secondary)
        }
        .padding(12).background(Brand.field).clipShape(RoundedRectangle(cornerRadius: 14))

        Button { model.submit(container: container) } label: {
            if model.busy { ProgressView().tint(.white).frame(maxWidth: .infinity) } else { Text(model.switching ? "Continue" : "Log in").frame(maxWidth: .infinity) }
        }
        .buttonStyle(.borderedProminent).controlSize(.large).disabled(!model.canSubmit)
        if model.switching {
            Button("Cancel") { model.back(container: container) }.frame(maxWidth: .infinity)
        } else {
            Text("After signing in you choose which company to work in — the same workspaces you see on erp.easyesuite.com.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder private var workspacePicker: some View {
        Text("Choose your workspace").font(.title2.weight(.medium)).foregroundStyle(Brand.ink)
        Text("Workspaces for \(model.email). You can switch any time from Settings.").font(.subheadline).foregroundStyle(.secondary)
        ForEach(model.workspaces) { w in
            WorkspaceRow(workspace: w, selected: w.schemaName == model.selectedTenant, current: w.schemaName == container.session?.tenant) { model.selectedTenant = w.schemaName }
        }
        Button { model.continueToWorkspace(container: container) } label: {
            if model.busy { ProgressView().tint(.white).frame(maxWidth: .infinity) } else { Text(model.selectedWorkspace.map { "Continue to \($0.name)" } ?? "Continue").frame(maxWidth: .infinity) }
        }
        .buttonStyle(.borderedProminent).controlSize(.large).disabled(model.selectedTenant == nil || model.busy)
        Button(model.switching ? "Cancel" : "Use a different account") { model.back(container: container) }.frame(maxWidth: .infinity)
    }

    @ViewBuilder private var secondFactor: some View {
        Text("Verification").font(.title2.weight(.medium)).foregroundStyle(Brand.ink)
        Text(model.message ?? "Enter your verification code").font(.subheadline).foregroundStyle(.secondary)
        field("Verification code", text: $model.code).keyboardType(.numberPad).textContentType(.oneTimeCode)
        Button { model.submitCode(container: container) } label: { Text(model.busy ? "Verifying…" : "Verify").frame(maxWidth: .infinity) }
            .buttonStyle(.borderedProminent).controlSize(.large).disabled(model.busy || model.code.count < 4)
        Button("Back") { model.back(container: container) }
    }

    @ViewBuilder private var passwordReset: some View {
        Text("Password reset required").font(.title2.weight(.medium)).foregroundStyle(Brand.ink)
        Text("Your administrator requires a new password before you can continue. Reset it on the web, then sign in here again.")
            .font(.subheadline).foregroundStyle(.secondary)
        Link(destination: URL(string: "https://erp.easyesuite.com/login")!) { Text("Reset on erp.easyesuite.com").frame(maxWidth: .infinity) }
            .buttonStyle(.borderedProminent).controlSize(.large)
        Button("Back to sign in") { model.back(container: container) }.frame(maxWidth: .infinity)
    }

    /// Non-production builds say which backend they talk to (the team's rule: those builds stay internal).
    @ViewBuilder private var environmentFooter: some View {
        if AppConfig.environmentName != "Production" {
            Text("\(AppConfig.environmentName) backend · \(AppConfig.apiRoot.host ?? "")").font(.caption2).foregroundStyle(.secondary).padding(.bottom, 16)
        }
    }

    private func field(_ title: String, text: Binding<String>) -> some View {
        TextField(title, text: text).padding(12).background(Brand.field).clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

private struct WorkspaceRow: View {
    let workspace: Workspace
    let selected: Bool
    var current = false
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                avatar
                VStack(alignment: .leading, spacing: 2) {
                    Text(workspace.name).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                    Text(current ? "\(workspace.schemaName) · current" : workspace.schemaName).font(.caption).foregroundStyle(.secondary)
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

    @ViewBuilder private var avatar: some View {
        if let url = workspace.logoUrl.flatMap(URL.init(string:)) {
            AsyncImage(url: url) { phase in
                if let img = phase.image { img.resizable().scaledToFit().padding(6) } else { monogram }
            }
            .frame(width: 40, height: 40).background(Brand.greenTint).clipShape(RoundedRectangle(cornerRadius: 12))
        } else {
            monogram.frame(width: 40, height: 40).background(Brand.greenTint).clipShape(RoundedRectangle(cornerRadius: 12))
        }
    }
    private var monogram: some View { Text(workspace.monogram).font(.caption.weight(.bold)).foregroundStyle(Brand.greenText) }
}
