import Foundation

/// Row of `sales_orders/invoice_items/invoice_analytics/` — recognised sales per item.
public struct SalesByItemRow: Decodable, Identifiable, Hashable, Sendable {
    public var itemId: Int64
    public var itemName: String
    public var itemUpc: String?
    public var totalAmount: Money?
    public var totalQuantity: Double?
    public var totalPerQty: Double?
    public var totalProfit: Money?
    public var marketplace: Int?
    public var itemImgUrl1: String?
    public var itemImages: [ItemImage]?

    /// Stable row id even when the same item appears once per marketplace.
    public var id: String { "\(itemId)-\(marketplace.map(String.init) ?? "all")" }
    public var revenue: Money { totalAmount ?? .zero }
    public var units: Int { Int(totalQuantity ?? 0) }
    public var profit: Money? { totalProfit }
    public var averagePrice: Double? { totalPerQty }
    public var primaryImage: String? { itemImages?.first?.imageUrl ?? itemImgUrl1 }
    public var marginPercent: Double? {
        guard let p = totalProfit, !revenue.isZero else { return nil }
        return p.doubleValue / revenue.doubleValue * 100
    }
}

public enum SalesOrdering {
    public static let revenue = "-total_amount", units = "-total_quantity", profit = "-total_profit"
}

/// A loosely-typed JSON value for endpoints whose shape we do not control (dashboard cards, copilot).
public enum JSONValue: Codable, Hashable, Sendable {
    case string(String), number(Double), bool(Bool), null
    case array([JSONValue]), object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else if let o = try? c.decode([String: JSONValue].self) { self = .object(o) }
        else { throw DecodingError.dataCorruptedError(in: c, debugDescription: "Unsupported JSON") }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let s): try c.encode(s)
        case .number(let n): try c.encode(n)
        case .bool(let b): try c.encode(b)
        case .null: try c.encodeNil()
        case .array(let a): try c.encode(a)
        case .object(let o): try c.encode(o)
        }
    }

    public subscript(key: String) -> JSONValue? { if case .object(let o) = self { return o[key] }; return nil }
    public var stringValue: String? {
        switch self { case .string(let s): return s; case .number(let n): return n == n.rounded() && abs(n) < 1e15 ? String(Int64(n)) : String(n); case .bool(let b): return String(b); default: return nil }
    }
    public var doubleValue: Double? {
        switch self { case .number(let n): return n; case .string(let s): return Money.parse(s)?.doubleValue; default: return nil }
    }
    public var arrayValue: [JSONValue]? { if case .array(let a) = self { return a }; return nil }
    public var objectValue: [String: JSONValue]? { if case .object(let o) = self { return o }; return nil }
}

/// The dashboard endpoints return provider-shaped JSON. We flatten whatever comes back into cards.
public struct DashboardCard: Identifiable, Hashable, Sendable {
    public var key: String
    public var label: String
    public var value: String
    public var numeric: Double?
    public var id: String { key }

    public static func cards(from json: JSONValue?) -> [DashboardCard] {
        var obj: [String: JSONValue]? = json?.objectValue
        if obj == nil, let first = json?.arrayValue?.first { obj = first.objectValue }
        guard let o = obj else { return [] }
        var out: [DashboardCard] = []
        for (key, value) in o.sorted(by: { $0.key < $1.key }) {
            switch value {
            case .string, .number, .bool:
                out.append(DashboardCard(key: key, label: humanize(key), value: display(value), numeric: value.doubleValue))
            case .object(let inner):
                if let v = inner["value"] ?? inner["total"] ?? inner["amount"] ?? inner["count"], v.stringValue != nil {
                    out.append(DashboardCard(key: key, label: inner["label"]?.stringValue ?? humanize(key), value: display(v), numeric: v.doubleValue))
                } else {
                    for (k2, v2) in inner.sorted(by: { $0.key < $1.key }) where v2.stringValue != nil {
                        out.append(DashboardCard(key: "\(key).\(k2)", label: humanize(key) + " · " + humanize(k2), value: display(v2), numeric: v2.doubleValue))
                    }
                }
            default: break
            }
        }
        return out
    }

    static func display(_ v: JSONValue) -> String {
        if let d = v.doubleValue {
            let f = NumberFormatter(); f.numberStyle = .decimal; f.locale = Locale(identifier: "en_US")
            f.maximumFractionDigits = d == d.rounded() ? 0 : 2
            return f.string(from: NSNumber(value: d)) ?? v.stringValue ?? "—"
        }
        return v.stringValue ?? "—"
    }

    public static func humanize(_ key: String) -> String {
        let spaced = key.replacingOccurrences(of: "_", with: " ")
            .replacingOccurrences(of: "([a-z])([A-Z])", with: "$1 $2", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
        return spaced.prefix(1).uppercased() + spaced.dropFirst()
    }
}

/// One (x, y) point for the small dashboard charts.
public struct SeriesPoint: Identifiable, Hashable, Sendable {
    public var label: String
    public var value: Double
    public var series: String?
    public var id: String { "\(series ?? "")|\(label)" }
    public init(label: String, value: Double, series: String? = nil) { self.label = label; self.value = value; self.series = series }

    /// Accepts the common shapes (array of points, {labels, data}, {labels, datasets}, {series: [points]}).
    public static func parse(_ json: JSONValue?) -> [SeriesPoint] {
        guard let json else { return [] }
        switch json {
        case .array(let arr): return arr.compactMap(point)
        case .object(let o):
            if let labels = o["labels"]?.arrayValue?.map({ $0.stringValue ?? "" }) {
                if let datasets = o["datasets"]?.arrayValue {
                    return datasets.flatMap { ds -> [SeriesPoint] in
                        let name = ds["label"]?.stringValue
                        return (ds["data"]?.arrayValue ?? []).enumerated().compactMap { i, v in v.doubleValue.map { SeriesPoint(label: i < labels.count ? labels[i] : "\(i)", value: $0, series: name) } }
                    }
                }
                if let data = o["data"]?.arrayValue {
                    return data.enumerated().compactMap { i, v in v.doubleValue.map { SeriesPoint(label: i < labels.count ? labels[i] : "\(i)", value: $0) } }
                }
            }
            return o.sorted(by: { $0.key < $1.key }).flatMap { series, v -> [SeriesPoint] in
                (v.arrayValue ?? []).compactMap(point).map { SeriesPoint(label: $0.label, value: $0.value, series: series) }
            }
        default: return []
        }
    }

    private static func point(_ v: JSONValue) -> SeriesPoint? {
        guard let o = v.objectValue else { return nil }
        guard let label = ["date", "label", "name", "interval", "day", "marketplace", "x"].lazy.compactMap({ o[$0]?.stringValue }).first else { return nil }
        guard let value = ["total", "total_amount", "value", "amount", "quantity", "count", "y"].lazy.compactMap({ o[$0]?.doubleValue }).first else { return nil }
        let series = o["marketplace_name"]?.stringValue ?? o["series"]?.stringValue
        return SeriesPoint(label: label, value: value, series: series)
    }
}
