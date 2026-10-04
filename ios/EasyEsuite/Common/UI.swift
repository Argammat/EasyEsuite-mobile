import SwiftUI
import Combine
import EasyEsuiteKit

// MARK: - Loading state

enum Loadable<T> {
    case idle, loading, ready(T), failed(String)
    var value: T? { if case .ready(let v) = self { return v }; return nil }
    var isLoading: Bool { if case .loading = self { return true }; return false }
}

extension Error {
    var userMessage: String {
        if let api = self as? APIError { return api.errorDescription ?? "Something went wrong." }
        if self is CancellationError { return "Cancelled" }
        return localizedDescription
    }
}

// MARK: - Paged list view model

/// Infinite-scroll list model: subclasses override `fetch`, call `refresh()` when filters change.
@MainActor
class PagedListModel<T: Identifiable>: ObservableObject {
    @Published var items: [T] = []
    @Published var total = 0
    @Published var loading = false
    @Published var loadingMore = false
    @Published var error: String?
    @Published var hasMore = false

    private var offset = 0
    private var task: Task<Void, Never>?
    let pageSize: Int

    init(pageSize: Int = 25) { self.pageSize = pageSize }

    func fetch(_ page: PageQuery) async throws -> Page<T> { fatalError("override") }

    func refresh() { load(reset: true) }

    func loadMoreIfNeeded(current: T) {
        guard hasMore, !loading, !loadingMore else { return }
        if let idx = items.firstIndex(where: { $0.id == current.id }), idx >= items.count - 4 { load(reset: false) }
    }

    var isEmpty: Bool { items.isEmpty && !loading && error == nil }

    private func load(reset: Bool) {
        task?.cancel()
        if reset { offset = 0; loading = true } else { loadingMore = true }
        error = nil
        task = Task { [weak self] in
            guard let self else { return }
            do {
                let page = try await self.fetch(PageQuery(limit: self.pageSize, offset: self.offset))
                if Task.isCancelled { return }
                self.offset += page.results.count
                self.items = reset ? page.results : self.items + page.results
                self.total = page.count
                self.hasMore = page.hasMore && !page.results.isEmpty
            } catch is CancellationError {
                return
            } catch {
                if Task.isCancelled { return }
                self.error = error.userMessage
            }
            self.loading = false
            self.loadingMore = false
        }
    }

    func replace(where predicate: (T) -> Bool, with transform: (T) -> T) {
        items = items.map { predicate($0) ? transform($0) : $0 }
    }
}

/// Renders a PagedListModel inside a List with pull-to-refresh, empty/error states and load-more.
struct PagedListView<T: Identifiable, Row: View, Header: View>: View {
    @ObservedObject var model: PagedListModel<T>
    var emptyTitle: String
    var emptySubtitle: String? = nil
    @ViewBuilder var header: () -> Header
    @ViewBuilder var row: (T) -> Row

    init(model: PagedListModel<T>, emptyTitle: String, emptySubtitle: String? = nil, @ViewBuilder header: @escaping () -> Header = { EmptyView() }, @ViewBuilder row: @escaping (T) -> Row) {
        self.model = model; self.emptyTitle = emptyTitle; self.emptySubtitle = emptySubtitle; self.header = header; self.row = row
    }

    var body: some View {
        List {
            header()
            if model.loading && model.items.isEmpty {
                HStack { Spacer(); ProgressView(); Spacer() }.listRowSeparator(.hidden)
            } else if let err = model.error, model.items.isEmpty {
                ErrorRow(message: err) { model.refresh() }
            } else if model.isEmpty {
                EmptyRow(title: emptyTitle, subtitle: emptySubtitle)
            } else {
                ForEach(model.items) { item in
                    row(item).onAppear { model.loadMoreIfNeeded(current: item) }
                }
                if model.loadingMore { HStack { Spacer(); ProgressView(); Spacer() }.listRowSeparator(.hidden) }
                if let err = model.error { ErrorRow(message: err) { model.refresh() } }
            }
        }
        .listStyle(.plain)
        .refreshable { await model.refresh() }
    }
}

// MARK: - Small components

struct ErrorRow: View {
    let message: String
    var retry: (() -> Void)? = nil
    var body: some View {
        VStack(spacing: 8) {
            Text(message).foregroundStyle(.red).font(.subheadline).multilineTextAlignment(.center)
            if let retry { Button("Try again", action: retry).font(.subheadline) }
        }
        .frame(maxWidth: .infinity).padding().listRowSeparator(.hidden)
    }
}

struct EmptyRow: View {
    let title: String
    var subtitle: String? = nil
    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: "shippingbox").font(.largeTitle).foregroundStyle(.secondary)
            Text(title).font(.headline)
            if let subtitle { Text(subtitle).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center) }
        }
        .frame(maxWidth: .infinity).padding(.vertical, 32).listRowSeparator(.hidden)
    }
}

struct Thumb: View {
    let url: String?
    var size: CGFloat = 56
    var body: some View {
        Group {
            if let url, let u = URL(string: url) {
                AsyncImage(url: u) { phase in
                    if let img = phase.image { img.resizable().scaledToFill() } else { placeholder }
                }
            } else { placeholder }
        }
        .frame(width: size, height: size)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
    private var placeholder: some View { Image(systemName: "shippingbox").foregroundStyle(.secondary) }
}

func statusColor(_ status: String?) -> Color {
    guard let s = status?.lowercased() else { return .gray }
    if s.contains("void") || s.contains("exception") { return Brand.red }
    if s.contains("pending") || s.contains("partial") || s.contains("hold") || s == "to_ship" || s == "open" { return Brand.amber }
    if s.contains("invoiced") || s.contains("fulfilled") || s.contains("completed") || s == "shipped" || s == "billed" || s.contains("received/pending") { return Brand.green }
    return Brand.blue
}

struct StatusChip: View {
    let status: String?
    var label: String? = nil
    var body: some View {
        let c = statusColor(status)
        Text(label ?? status ?? "—")
            .font(.caption.weight(.medium))
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(c.opacity(0.12)).foregroundStyle(c)
            .clipShape(RoundedRectangle(cornerRadius: 6))
    }
}

struct KeyValueRow: View {
    let label: String
    let value: String?
    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label).foregroundStyle(.secondary)
            Spacer(minLength: 16)
            Text((value?.isEmpty == false ? value : nil) ?? "—").multilineTextAlignment(.trailing)
        }
        .font(.subheadline)
    }
}

struct EntityRow<Trailing: View>: View {
    let imageUrl: String?
    let title: String
    let subtitle: String?
    var badge: String? = nil
    var badgeColor: Color = Brand.amber
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(spacing: 12) {
            Thumb(url: imageUrl)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.body.weight(.medium)).lineLimit(1)
                if let subtitle, !subtitle.isEmpty { Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                if let badge { Text(badge).font(.caption2).foregroundStyle(badgeColor) }
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) { trailing() }
        }
        .contentShape(Rectangle())
    }
}

struct ChipRow<Value: Hashable>: View {
    let options: [(Value, String)]
    let selected: Value
    let onSelect: (Value) -> Void
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Array(options.enumerated()), id: \.offset) { _, opt in
                    let on = opt.0 == selected
                    Button { onSelect(opt.0) } label: {
                        Text(opt.1).font(.subheadline)
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .background(on ? Brand.blue : Color(.secondarySystemBackground))
                            .foregroundStyle(on ? Color.white : Color.primary)
                            .clipShape(Capsule())
                    }.buttonStyle(.plain)
                }
            }.padding(.horizontal)
        }
    }
}

struct SectionHeader: View {
    let text: String
    var body: some View { Text(text).font(.subheadline.weight(.semibold)).foregroundStyle(Brand.blue).padding(.top, 8) }
}

struct StatTile: View {
    let label: String
    let value: String
    var alert = false
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            Text(value).font(.title3.weight(.semibold)).foregroundStyle(alert ? Brand.red : .primary).lineLimit(1).minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

/// Debounces a search field into a published query.
@MainActor
final class SearchDebouncer: ObservableObject {
    @Published var text = ""
    @Published private(set) var query = ""
    private var bag = Set<AnyCancellable>()
    init(delay: TimeInterval = 0.35) {
        $text.removeDuplicates().debounce(for: .seconds(delay), scheduler: RunLoop.main).sink { [weak self] in self?.query = $0 }.store(in: &bag)
    }
}

extension View {
    /// Shows a one-line message at the bottom for a few seconds.
    func toast(_ message: Binding<String?>) -> some View {
        overlay(alignment: .bottom) {
            if let m = message.wrappedValue {
                Text(m).font(.subheadline).padding(.horizontal, 16).padding(.vertical, 10)
                    .background(.ultraThinMaterial).clipShape(Capsule()).padding(.bottom, 24)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .task { try? await Task.sleep(nanoseconds: 2_500_000_000); withAnimation { message.wrappedValue = nil } }
            }
        }
    }
}

func fmtCount(_ n: Int) -> String { n.formatted(.number.grouping(.automatic)) }
