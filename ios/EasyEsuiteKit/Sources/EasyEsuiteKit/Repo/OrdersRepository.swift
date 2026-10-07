import Foundation

public final class OrdersRepository: @unchecked Sendable {
    private let client: APIClient
    public init(client: APIClient) { self.client = client }

    public func orders(status: String? = nil, marketplaceId: Int? = nil, search: String? = nil, range: DateRange? = nil, page: PageQuery = PageQuery()) async throws -> Page<SalesOrderSummary> {
        var q = page.query.merging(["status": status, "marketplace": marketplaceId, "search": search?.nilIfBlank, "ordering": "-date"]) { $1 }
        if let range { q.merge(range.query()) { $1 } }
        return try await client.get(Endpoints.salesOrders, query: q)
    }

    /// Cheap count: list with limit=1 and read `count`.
    public func count(status: String? = nil, marketplaceId: Int? = nil, range: DateRange? = nil) async throws -> Int {
        var q: [String: Any?] = ["limit": 1, "status": status, "marketplace": marketplaceId]
        if let range { q.merge(range.query()) { $1 } }
        let p: Page<SalesOrderSummary> = try await client.get(Endpoints.salesOrders, query: q)
        return p.count
    }

    public func order(_ id: Int64) async throws -> SalesOrderDetail { try await client.get("\(Endpoints.salesOrders)\(id)/") }

    // MARK: Sales finance (VERIFIED): invoices and customer payments

    /// `sales_orders/invoices/` — filters: status (Open|Paid|Partial Paid|Voided), marketplace, sales_order, search (IN-…), date_after/before.
    public func invoices(status: String? = nil, search: String? = nil, salesOrderId: Int64? = nil, range: DateRange? = nil, page: PageQuery = PageQuery()) async throws -> Page<Invoice> {
        var q: [String: Any?] = page.query.merging(["status": status, "search": search?.nilIfBlank, "sales_order": salesOrderId, "ordering": "-date"]) { $1 }
        if let range { q.merge(range.query()) { $1 } }
        return try await client.get(Endpoints.invoices, query: q)
    }

    public func invoice(_ id: Int64) async throws -> Invoice { try await client.get("\(Endpoints.invoices)\(id)/") }

    /// `sales_orders/payments/` — customer payments, newest first. `search` is ASSUMED (payment number / ref).
    public func payments(search: String? = nil, range: DateRange? = nil, page: PageQuery = PageQuery()) async throws -> Page<Payment> {
        var q: [String: Any?] = page.query.merging(["search": search?.nilIfBlank, "ordering": "-date"]) { $1 }
        if let range { q.merge(range.query()) { $1 } }
        return try await client.get(Endpoints.payments, query: q)
    }

    public func payment(_ id: Int64) async throws -> Payment { try await client.get("\(Endpoints.payments)\(id)/") }

    public func findByNumber(_ number: String) async throws -> SalesOrderSummary? {
        let p: Page<SalesOrderSummary> = try await client.get(Endpoints.salesOrders, query: ["number": number.trimmingCharacters(in: .whitespaces), "limit": 1])
        return p.results.first
    }

    public func update(_ id: Int64, _ req: UpdateOrderRequest) async throws -> SalesOrderDetail { try await client.patch("\(Endpoints.salesOrders)\(id)/", body: req) }
    public func fulfill(_ req: FulfillRequest) async throws -> JSONValue { try await client.post(Endpoints.fulfillments, body: req) }
}

public final class ShippingRepository: @unchecked Sendable {
    private let client: APIClient
    public init(client: APIClient) { self.client = client }

    public func shipments(status: String? = nil, search: String? = nil, page: PageQuery = PageQuery()) async throws -> Page<Shipment> {
        try await client.get(Endpoints.shipments, query: page.query.merging(["status": status, "search": search?.nilIfBlank]) { $1 })
    }
    public func count(status: String) async throws -> Int {
        let p: Page<Shipment> = try await client.get(Endpoints.shipments, query: ["status": status, "limit": 1])
        return p.count
    }
    public func shipment(_ id: String) async throws -> Shipment { try await client.get("\(Endpoints.shipments)\(id)/") }
    public func rerate(_ id: String) async throws -> Shipment { try await client.postEmpty("\(Endpoints.shipments)\(id)/rerate/") }
    public func buyLabel(_ id: String, rateId: String?) async throws -> Shipment { try await client.post("\(Endpoints.shipments)\(id)/buy/", body: BuyLabelRequest(rateId: rateId)) }
    public func label(_ id: String) async throws -> JSONValue { try await client.get("\(Endpoints.shipments)\(id)/label/") }
    public func tracking(_ id: String) async throws -> TrackingDetails { try await client.get("\(Endpoints.shipments)\(id)/tracking_details/") }
    public func hold(_ id: String, reason: String?) async throws -> Shipment { try await client.post("\(Endpoints.shipments)\(id)/hold/", body: HoldRequest(reason: reason)) }
    public func unhold(_ id: String) async throws -> Shipment { try await client.postEmpty("\(Endpoints.shipments)\(id)/unhold/") }
    /// Scanning a label / packing-slip barcode in the warehouse resolves to the shipment.
    public func resolveBarcode(_ code: String) async throws -> Shipment { try await client.get(Endpoints.shipmentResolveBarcode, query: ["barcode": code.trimmingCharacters(in: .whitespaces)]) }
    public func verifyScan(_ id: String, upc: String) async throws -> JSONValue { try await client.post("\(Endpoints.shipments)\(id)/verify_scan/", body: VerifyScanRequest(upc: upc.trimmingCharacters(in: .whitespaces))) }
    public func packVerification(_ id: String) async throws -> JSONValue { try await client.get("\(Endpoints.shipments)\(id)/pack_verification/") }
    public func balance() async throws -> JSONValue { try await client.get(Endpoints.shippingBalance) }
}

public final class ReportsRepository: @unchecked Sendable {
    private let client: APIClient
    public init(client: APIClient) { self.client = client }

    /// Recognised sales per item (from invoices — never from sales orders, which over-count).
    public func salesByItem(range: DateRange, ordering: String = SalesOrdering.revenue, limit: Int = 25, offset: Int = 0, search: String? = nil, splitByMarketplace: Bool = false, itemId: Int64? = nil) async throws -> Page<SalesByItemRow> {
        var q = range.query()
        q.merge(["split_by_marketplace": splitByMarketplace, "ordering": ordering, "limit": limit, "offset": offset, "search": search?.nilIfBlank, "item": itemId]) { $1 }
        return try await client.get(Endpoints.invoiceAnalytics, query: q)
    }

    public func overviewCards(range: DateRange) async throws -> [DashboardCard] {
        let raw: JSONValue = try await client.get(Endpoints.overviewCards, query: range.query())
        return DashboardCard.cards(from: raw)
    }
    public func bestSellers(range: DateRange, limit: Int = 5) async throws -> JSONValue {
        var q = range.query(); q["limit"] = limit
        return try await client.get(Endpoints.bestSellers, query: q)
    }
    public func ordersByMarketplaceOverTime(range: DateRange) async throws -> [SeriesPoint] {
        let raw: JSONValue = try await client.get(Endpoints.marketplaceOrderIntervals, query: range.query())
        return SeriesPoint.parse(raw)
    }
    public func unitsSoldOverTime(range: DateRange) async throws -> [SeriesPoint] {
        let raw: JSONValue = try await client.get(Endpoints.unitsSoldIntervals, query: range.query())
        return SeriesPoint.parse(raw)
    }
    public func carrierSpend(range: DateRange) async throws -> [SeriesPoint] {
        let raw: JSONValue = try await client.get(Endpoints.carrierSpend, query: range.query())
        return SeriesPoint.parse(raw)
    }
}

/// The ERP Copilot (`ai/ai_copilot_agent_v2/`). Only the path is verified — payload decoded leniently.
public struct CopilotRequest: Encodable, Sendable {
    public var message: String
    public var threadId: String?
    public var client: String = "mobile"
}

public struct AssistantReply: Sendable {
    public var text: String
    public var threadId: String?
    public var suggestions: [String]
    public var navigation: String?
    public var raw: JSONValue
}

public final class AssistantRepository: @unchecked Sendable {
    private let client: APIClient
    public init(client: APIClient) { self.client = client }

    public func ask(_ message: String, threadId: String?) async throws -> AssistantReply {
        let raw: JSONValue = try await client.post(Endpoints.copilot, body: CopilotRequest(message: message.trimmingCharacters(in: .whitespacesAndNewlines), threadId: threadId))
        return AssistantRepository.parse(raw, previousThread: threadId)
    }
    public func featureSettings() async throws -> JSONValue { try await client.get(Endpoints.aiFeatureSettings) }
    public func credits() async throws -> JSONValue { try await client.get(Endpoints.aiCredits) }

    static let textKeys = ["answer", "response", "message", "content", "output", "text", "reply"]

    public static func parse(_ raw: JSONValue, previousThread: String?) -> AssistantReply {
        guard let obj = raw.objectValue else {
            return AssistantReply(text: raw.stringValue ?? "\(raw)", threadId: previousThread, suggestions: [], navigation: nil, raw: raw)
        }
        func text(in o: [String: JSONValue]) -> String? {
            for k in textKeys {
                if let s = o[k]?.stringValue, o[k]?.objectValue == nil { return s }
                if let inner = o[k]?.objectValue, let s = inner["text"]?.stringValue ?? inner["content"]?.stringValue { return s }
            }
            return nil
        }
        let t = text(in: obj) ?? obj["data"]?.objectValue.flatMap(text(in:)) ?? "(no answer)"
        let thread = ["thread_id", "threadId", "session_id", "conversation_id"].lazy.compactMap { obj[$0]?.stringValue }.first ?? previousThread
        let suggestions = (obj["suggestions"]?.arrayValue ?? []).compactMap { $0.stringValue ?? $0["text"]?.stringValue }
        let nav = obj["navigate_to"]?.stringValue ?? obj["route"]?.stringValue
        return AssistantReply(text: t, threadId: thread, suggestions: suggestions, navigation: nav, raw: raw)
    }
}
