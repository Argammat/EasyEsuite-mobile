import SwiftUI
import EasyEsuiteKit

struct ChatMessage: Identifiable { let id = UUID(); let role: String; let text: String; var suggestions: [String] = []; var isError = false }

@MainActor
final class AssistantModel: ObservableObject {
    @Published var messages: [ChatMessage] = [AssistantModel.welcome]
    @Published var input = ""
    @Published var busy = false
    private var threadId: String?
    let graph: AppContainer.Graph
    init(graph: AppContainer.Graph) { self.graph = graph }

    static let welcome = ChatMessage(role: "assistant",
        text: "Hi — I'm the EasyEsuite Copilot. Ask me about orders, stock, sales or shipping, e.g. “How many orders came in today?” or “What's low on stock in Sunvalley?”",
        suggestions: ["Orders today by marketplace", "Items below reorder point", "Top sellers this week", "Shipments on hold"])

    func reset() { messages = [AssistantModel.welcome]; threadId = nil; input = "" }

    func send(_ text: String? = nil) async {
        let msg = (text ?? input).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !msg.isEmpty, !busy else { return }
        messages.append(ChatMessage(role: "user", text: msg)); input = ""; busy = true
        defer { busy = false }
        do {
            let reply = try await graph.assistant.ask(msg, threadId: threadId)
            threadId = reply.threadId
            messages.append(ChatMessage(role: "assistant", text: reply.text, suggestions: reply.suggestions))
        } catch {
            messages.append(ChatMessage(role: "assistant", text: error.userMessage, isError: true))
        }
    }
}

struct AssistantView: View {
    @StateObject private var model: AssistantModel
    init(graph: AppContainer.Graph) { _model = StateObject(wrappedValue: AssistantModel(graph: graph)) }

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        ForEach(model.messages) { m in
                            let mine = m.role == "user"
                            VStack(alignment: mine ? .trailing : .leading, spacing: 6) {
                                Text(m.text)
                                    .padding(12)
                                    .background(m.isError ? Color.red.opacity(0.12) : (mine ? Brand.blue : Color(.secondarySystemBackground)))
                                    .foregroundStyle(mine ? .white : .primary)
                                    .clipShape(RoundedRectangle(cornerRadius: 14))
                                    .frame(maxWidth: 320, alignment: mine ? .trailing : .leading)
                                if !m.suggestions.isEmpty, m.id == model.messages.last?.id {
                                    ScrollView(.horizontal, showsIndicators: false) {
                                        HStack { ForEach(m.suggestions, id: \.self) { s in Button(s) { Task { await model.send(s) } }.buttonStyle(.bordered).controlSize(.small) } }
                                    }
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: mine ? .trailing : .leading)
                            .id(m.id)
                        }
                        if model.busy { HStack { ProgressView(); Text("Thinking…").font(.caption).foregroundStyle(.secondary) } }
                    }
                    .padding()
                }
                .onChange(of: model.messages.count) { _ in if let last = model.messages.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } } }
            }
            Divider()
            HStack(alignment: .bottom) {
                TextField("Ask about your business…", text: $model.input, axis: .vertical).lineLimit(1...4).textFieldStyle(.roundedBorder)
                Button { Task { await model.send() } } label: { Image(systemName: "arrow.up.circle.fill").font(.title2) }
                    .disabled(model.input.trimmingCharacters(in: .whitespaces).isEmpty || model.busy)
            }
            .padding()
            Text("Answers come from your ERP data via ai/ai_copilot_agent_v2.").font(.caption2).foregroundStyle(.secondary).padding(.bottom, 6)
        }
        .navigationTitle("Copilot")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .navigationBarTrailing) { Button { model.reset() } label: { Image(systemName: "arrow.counterclockwise") } } }
    }
}
