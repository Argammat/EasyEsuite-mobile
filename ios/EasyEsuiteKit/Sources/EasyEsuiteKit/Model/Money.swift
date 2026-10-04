import Foundation

/// The API returns money in three shapes, sometimes on the same record:
/// `"180.00"`, `"$82,765.20"`, `166464`. This type decodes all of them (and `"($12.00)"` negatives).
public struct Money: Codable, Hashable, Comparable, Sendable {
    public var amount: Decimal

    public init(_ amount: Decimal) { self.amount = amount }
    public init(_ amount: Double) { self.amount = Decimal(amount) }

    public static let zero = Money(Decimal(0))

    public var isZero: Bool { amount.isZero }
    public var doubleValue: Double { NSDecimalNumber(decimal: amount).doubleValue }

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .zero; return }
        // Go through the shortest string form so 745.92 does not become 745.9199999999…
        if let d = try? c.decode(Double.self) { self.amount = Decimal(string: "\(d)", locale: Locale(identifier: "en_US_POSIX")) ?? Decimal(d); return }
        if let s = try? c.decode(String.self), let m = Money.parse(s) { self = m; return }
        throw DecodingError.dataCorruptedError(in: c, debugDescription: "Unreadable money value")
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(apiString)
    }

    /// Plain decimal string the API accepts on write, e.g. "12.50".
    public var apiString: String {
        let f = NumberFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.minimumFractionDigits = 2
        f.maximumFractionDigits = 2
        f.usesGroupingSeparator = false
        return f.string(from: amount as NSDecimalNumber) ?? "0.00"
    }

    /// "$1,234.56" — USD is the only currency seen on the tenants so far.
    public func formatted(currencyCode: String = "USD") -> String {
        let f = NumberFormatter()
        f.numberStyle = .currency
        f.currencyCode = currencyCode
        f.locale = Locale(identifier: "en_US")
        return f.string(from: amount as NSDecimalNumber) ?? apiString
    }

    public static func parse(_ raw: String?) -> Money? {
        guard var s = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty else { return nil }
        let negativeByParens = s.hasPrefix("(") && s.hasSuffix(")")
        s = s.replacingOccurrences(of: "(", with: "").replacingOccurrences(of: ")", with: "")
        s = s.filter { $0.isNumber || $0 == "." || $0 == "-" }
        guard !s.isEmpty, s != "-", s != ".", let d = Decimal(string: s, locale: Locale(identifier: "en_US_POSIX")) else { return nil }
        return Money(negativeByParens ? -d : d)
    }

    public static func < (lhs: Money, rhs: Money) -> Bool { lhs.amount < rhs.amount }
    public static func + (lhs: Money, rhs: Money) -> Money { Money(lhs.amount + rhs.amount) }
    public static func * (lhs: Money, rhs: Int) -> Money { Money(lhs.amount * Decimal(rhs)) }
}

/// Some ids arrive as strings on one endpoint and numbers on another. Decodes either into a String.
@propertyWrapper
public struct FlexibleString: Codable, Hashable, Sendable {
    public var wrappedValue: String
    public init(wrappedValue: String) { self.wrappedValue = wrappedValue }
    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if let s = try? c.decode(String.self) { wrappedValue = s }
        else if let i = try? c.decode(Int64.self) { wrappedValue = String(i) }
        else if let d = try? c.decode(Double.self) { wrappedValue = String(d) }
        else { wrappedValue = "" }
    }
    public func encode(to encoder: Encoder) throws { var c = encoder.singleValueContainer(); try c.encode(wrappedValue) }
}

/// Same for optional strings that may come back as numbers (e.g. `sales_order_id`).
@propertyWrapper
public struct FlexibleOptionalString: Codable, Hashable, Sendable {
    public var wrappedValue: String?
    public init(wrappedValue: String?) { self.wrappedValue = wrappedValue }
    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { wrappedValue = nil }
        else if let s = try? c.decode(String.self) { wrappedValue = s }
        else if let i = try? c.decode(Int64.self) { wrappedValue = String(i) }
        else if let d = try? c.decode(Double.self) { wrappedValue = String(d) }
        else { wrappedValue = nil }
    }
    public func encode(to encoder: Encoder) throws { var c = encoder.singleValueContainer(); try c.encode(wrappedValue) }
}

extension KeyedDecodingContainer {
    /// Missing key → nil instead of throwing, for `@FlexibleOptionalString` properties.
    public func decode(_ type: FlexibleOptionalString.Type, forKey key: Key) throws -> FlexibleOptionalString {
        try decodeIfPresent(FlexibleOptionalString.self, forKey: key) ?? FlexibleOptionalString(wrappedValue: nil)
    }
}
