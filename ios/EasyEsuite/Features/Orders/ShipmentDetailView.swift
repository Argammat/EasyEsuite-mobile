import SwiftUI
import EasyEsuiteKit

@MainActor
final class ShipmentDetailModel: ObservableObject {
    @Published var state: Loadable<Shipment> = .idle
    @Published var tracking: TrackingDetails?
    @Published var rates: [ShipmentRate] = []
    @Published var selectedRate: String?
    @Published var ratesOpen = false
    @Published var holdOpen = false
    @Published var holdReason = ""
    /// UPCs verified so far during scan-to-verify (local mirror; the server is the source of truth).
    @Published var verified: [String: Int] = [:]
    @Published var busy = false
    @Published var message: String?
    @Published var error: String?
    let graph: AppContainer.Graph
    let id: String
    init(graph: AppContainer.Graph, id: String) { self.graph = graph; self.id = id }

    var shipment: Shipment? { state.value }
    var allVerified: Bool { guard let s = shipment, !s.lines.isEmpty else { return false }; return s.lines.allSatisfy { (verified[$0.upc ?? ""] ?? 0) >= $0.qty } }

    func load() async {
        state = .loading
        do {
            let s = try await graph.shipping.shipment(id)
            state = .ready(s)
            rates = s.rates ?? []
            selectedRate = s.selectedRate?.id ?? rates.first?.id
            if let t = s.trackingCode, !t.isEmpty { tracking = try? await graph.shipping.tracking(id) }
        } catch { state = .failed(error.userMessage) }
    }

    private func perform(_ label: String, _ op: () async throws -> Shipment) async {
        busy = true; error = nil; message = nil
        defer { busy = false }
        do {
            let s = try await op()
            state = .ready(s); rates = s.rates ?? rates; message = label
        } catch { self.error = error.userMessage }
    }

    func fetchRates() async {
        busy = true; error = nil
        defer { busy = false }
        do {
            let s = try await graph.shipping.rerate(id)
            state = .ready(s); rates = s.rates ?? []; selectedRate = s.selectedRate?.id ?? rates.first?.id; ratesOpen = true
        } catch { self.error = error.userMessage }
    }
    func buyLabel() async { await perform("Label purchased") { try await graph.shipping.buyLabel(id, rateId: selectedRate) }; ratesOpen = false }
    func hold() async { await perform("Shipment put on hold") { try await graph.shipping.hold(id, reason: holdReason.nilIfBlank) }; holdOpen = false }
    func unhold() async { await perform("Hold released") { try await graph.shipping.unhold(id) } }

    func verify(_ code: String) async {
        guard let s = shipment else { return }
        guard let expected = s.lines.first(where: { $0.upc == code || $0.itemId == code }) else { error = "“\(code)” is not on this shipment."; return }
        busy = true; error = nil
        defer { busy = false }
        do {
            let res = try await graph.shipping.verifyScan(id, upc: expected.upc ?? code)
            let key = expected.upc ?? ""
            verified[key, default: 0] += 1
            if let status = res["pack_verification_status"]?.stringValue, var cur = shipment { cur.packVerificationStatus = status; state = .ready(cur) }
            message = "Verified \(expected.title ?? code)"
        } catch { self.error = error.userMessage }
    }
}

struct ShipmentDetailView: View {
    @StateObject private var model: ShipmentDetailModel
    @State private var scanning = false
    @Environment(\.openURL) private var openURL
    init(graph: AppContainer.Graph, id: String) { _model = StateObject(wrappedValue: ShipmentDetailModel(graph: graph, id: id)) }

    var body: some View {
        Group {
            switch model.state {
            case .idle, .loading: ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed(let m): ErrorRow(message: m) { Task { await model.load() } }
            case .ready(let s): content(s)
            }
        }
        .navigationTitle(model.shipment?.orderNumber ?? "Shipment")
        .navigationBarTitleDisplayMode(.inline)
        .task { if case .idle = model.state { await model.load() } }
        .toast($model.message)
        .sheet(isPresented: $scanning) { ScannerSheet(title: "Scan item to verify") { code in Task { await model.verify(code) } } }
        .sheet(isPresented: $model.ratesOpen) { ratesSheet }
        .alert("Put shipment on hold", isPresented: $model.holdOpen) {
            TextField("Reason", text: $model.holdReason)
            Button("Hold") { Task { await model.hold() } }
            Button("Cancel", role: .cancel) {}
        }
    }

    @ViewBuilder
    private func content(_ sh: Shipment) -> some View {
        List {
            Section {
                HStack {
                    VStack(alignment: .leading) {
                        Text("\(sh.marketplaceName ?? "") · \(sh.orderShippingMethod ?? "")").font(.headline)
                        Text([sh.destination.nilIfEmpty, sh.shipByDate.map { "ship by \(DateText.short($0))" }].compactMap { $0 }.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    StatusChip(status: sh.status, label: ShipmentStatus.label(sh.status))
                }
                if sh.onHold { Text("On hold: \(sh.holdReason ?? "")").foregroundStyle(Brand.amber).font(.subheadline) }
                if let ex = sh.exceptionDetail, !ex.isEmpty { Text("Exception: \(sh.exceptionCode ?? "") \(ex)").foregroundStyle(.red).font(.subheadline) }
                if let e = model.error { Text(e).foregroundStyle(.red).font(.subheadline) }
                HStack {
                    if sh.hasLabel, let u = sh.labelUrl.flatMap(URL.init(string:)) {
                        Button { openURL(u) } label: { Label("Open label", systemImage: "doc.text") }.buttonStyle(.borderedProminent)
                    } else if sh.canBuyLabel {
                        Button { Task { await model.fetchRates() } } label: { Text(model.busy ? "Working…" : "Get rates & buy label") }.buttonStyle(.borderedProminent).disabled(model.busy)
                    }
                    if sh.onHold { Button("Release hold") { Task { await model.unhold() } }.buttonStyle(.bordered).disabled(model.busy) }
                    else if sh.status == ShipmentStatus.toShip { Button("Hold") { model.holdOpen = true }.buttonStyle(.bordered).disabled(model.busy) }
                }
            }

            Section("Pack verification · \(sh.packVerificationStatus ?? "pending")") {
                ForEach(Array(sh.lines.enumerated()), id: \.offset) { _, it in
                    let done = (model.verified[it.upc ?? ""] ?? 0) >= it.qty
                    HStack(spacing: 8) {
                        Image(systemName: done ? "checkmark.circle.fill" : "circle").foregroundStyle(done ? Brand.green : .secondary)
                        Thumb(url: it.itemImageUrl, size: 40)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(it.title ?? it.itemId ?? "").font(.subheadline.weight(.medium)).lineLimit(2)
                            Text("\(it.itemId ?? "") · UPC \(it.upc ?? "—")").font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text("\(model.verified[it.upc ?? ""] ?? 0)/\(it.qty)").font(.subheadline.weight(.semibold))
                    }
                }
                if sh.status == ShipmentStatus.toShip {
                    Button { scanning = true } label: { Label(model.allVerified ? "All items verified" : "Scan item to verify", systemImage: "barcode.viewfinder") }
                }
            }

            Section("Details") {
                KeyValueRow(label: "Order", value: sh.orderNumber)
                KeyValueRow(label: "Carrier", value: [sh.orderShippingCarrier, sh.selectedRate?.service].compactMap { $0 }.joined(separator: " · ").nilIfEmpty)
                KeyValueRow(label: "Label cost", value: sh.shipmentCost?.formatted())
                KeyValueRow(label: "Order total", value: sh.orderTotal?.formatted())
                KeyValueRow(label: "Tracking", value: sh.trackingCode)
                KeyValueRow(label: "From", value: sh.fromAddressName)
                KeyValueRow(label: "Purchased", value: sh.purchasedAt.map { DateText.long($0) })
                KeyValueRow(label: "Delivered", value: sh.deliveredAt.map { DateText.long($0) })
                KeyValueRow(label: "Provider", value: sh.labelProvider)
            }

            if let t = model.tracking {
                Section("Tracking · \(t.status ?? "")") {
                    if let est = t.estDeliveryDate { KeyValueRow(label: "Estimated delivery", value: DateText.short(est)) }
                    ForEach(Array(t.timeline.enumerated()), id: \.offset) { _, ev in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(ev.text).font(.subheadline)
                            Text([ev.timestamp.map { DateText.long($0) }, ev.place].compactMap { $0 }.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .refreshable { await model.load() }
    }

    private var ratesSheet: some View {
        NavigationStack {
            List {
                if model.rates.isEmpty { Text("No rates came back. Check the ship-from address and package dimensions in the web app.").font(.subheadline) }
                ForEach(model.rates) { r in
                    Button { model.selectedRate = r.id } label: {
                        HStack {
                            Image(systemName: model.selectedRate == r.id ? "largecircle.fill.circle" : "circle").foregroundStyle(Brand.blue)
                            VStack(alignment: .leading) {
                                Text(r.label).font(.subheadline.weight(.medium))
                                if let d = r.days { Text("\(d) day\(d == 1 ? "" : "s")").font(.caption).foregroundStyle(.secondary) }
                            }
                            Spacer()
                            Text(r.rate?.formatted() ?? "").font(.subheadline.weight(.semibold))
                        }
                    }.buttonStyle(.plain)
                }
                if let e = model.error { Text(e).foregroundStyle(.red) }
            }
            .navigationTitle("Choose a rate")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { model.ratesOpen = false } }
                ToolbarItem(placement: .confirmationAction) { Button(model.busy ? "Buying…" : "Buy label") { Task { await model.buyLabel() } }.disabled(model.busy || (!model.rates.isEmpty && model.selectedRate == nil)) }
            }
        }
    }
}
