import SwiftUI
import EasyEsuiteKit

@MainActor
final class LoginModel: ObservableObject {
    @Published var tenant = ""
    @Published var email = ""
    @Published var password = ""
    @Published var code = ""
    @Published var busy = false
    @Published var error: String?
    @Published var secondFactor = false
    @Published var message: String?
    private var challengeToken: String?

    var canSubmit: Bool { !busy && !tenant.trimmingCharacters(in: .whitespaces).isEmpty && email.contains("@") && password.count >= 4 }

    func prefill(from store: KeychainTokenStore) {
        tenant = store.lastTenant ?? ""
        email = store.lastEmail ?? ""
    }

    func submit(container: AppContainer) {
        guard canSubmit else { return }
        busy = true; error = nil
        Task {
            // Firebase sign-in needs the workspace's Identity Platform tenant id; the global directory maps the
            // company name to it (falls back to the build-time default, then to project-level users).
            var identityTenant: String?
            if container.firebaseSignIn { identityTenant = await container.tenantDirectory.lookup(tenant)?.firebaseTenantId }
            let graph = container.makeGraph(tenant: tenant, firebaseTenantId: identityTenant)
            do {
                switch try await graph.auth.login(email: email, password: password) {
                case .success(let session): container.signedIn(session, using: graph)
                case .secondFactorRequired(let token, let msg):
                    challengeToken = token; secondFactor = true; message = msg ?? "Enter the code from your authenticator app."; busy = false
                }
            } catch {
                busy = false; self.error = friendly(error)
            }
        }
    }

    func submitCode(container: AppContainer) {
        guard code.count >= 4, !busy else { return }
        busy = true; error = nil
        let graph = container.makeGraph(tenant: tenant)
        Task {
            do {
                switch try await graph.auth.completeSecondFactor(email: email, challengeToken: challengeToken, code: code) {
                case .success(let session): container.signedIn(session, using: graph)
                case .secondFactorRequired: busy = false; error = "That code was not accepted."
                }
            } catch { busy = false; self.error = friendly(error) }
        }
    }

    func cancelSecondFactor() { secondFactor = false; code = ""; challengeToken = nil; error = nil }

    private func friendly(_ e: Error) -> String {
        let m = e.userMessage
        if (e as? APIError)?.isNotFound == true { return "We couldn't find a workspace called “\(tenant)”. Check the company name." }
        if m.localizedCaseInsensitiveContains("credentials") || m.localizedCaseInsensitiveContains("unable to log in") { return "Email or password is incorrect." }
        return m
    }
}

struct LoginView: View {
    @EnvironmentObject private var container: AppContainer
    @StateObject private var model = LoginModel()
    @State private var showPassword = false

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                Spacer(minLength: 60)
                Text("EasyEsuite").font(.largeTitle.bold()).foregroundStyle(Brand.blue)
                Text("Sign in to your workspace").foregroundStyle(.secondary)
                Spacer(minLength: 24)

                if !model.secondFactor {
                    field("Company (workspace)", text: $model.tenant, help: "The same company name you use on erp.easyesuite.com")
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    field("Email", text: $model.email).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled().textContentType(.username)
                    HStack {
                        Group {
                            if showPassword { TextField("Password", text: $model.password) } else { SecureField("Password", text: $model.password) }
                        }.textContentType(.password)
                        Button { showPassword.toggle() } label: { Image(systemName: showPassword ? "eye.slash" : "eye") }.buttonStyle(.plain).foregroundStyle(.secondary)
                    }
                    .padding(12).background(Color(.secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 10))

                    Button { model.submit(container: container) } label: {
                        if model.busy { ProgressView().tint(.white).frame(maxWidth: .infinity) } else { Text("Sign in").frame(maxWidth: .infinity) }
                    }
                    .buttonStyle(.borderedProminent).controlSize(.large).disabled(!model.canSubmit)
                } else {
                    Text(model.message ?? "Enter your verification code").font(.subheadline)
                    field("Verification code", text: $model.code).keyboardType(.numberPad).textContentType(.oneTimeCode)
                    Button { model.submitCode(container: container) } label: { Text(model.busy ? "Verifying…" : "Verify").frame(maxWidth: .infinity) }
                        .buttonStyle(.borderedProminent).controlSize(.large).disabled(model.busy || model.code.count < 4)
                    Button("Back") { model.cancelSecondFactor() }
                }

                if let err = model.error { Text(err).foregroundStyle(.red).font(.subheadline).multilineTextAlignment(.center) }
            }
            .padding(24)
        }
        .scrollDismissesKeyboard(.interactively)
        .onAppear { model.prefill(from: container.tokenStore) }
    }

    private func field(_ title: String, text: Binding<String>, help: String? = nil) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            TextField(title, text: text).padding(12).background(Color(.secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 10))
            if let help { Text(help).font(.caption).foregroundStyle(.secondary) }
        }
    }
}
