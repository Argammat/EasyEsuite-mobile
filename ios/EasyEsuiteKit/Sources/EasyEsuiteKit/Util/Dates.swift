import Foundation

/// Inclusive ISO-8601 UTC bounds the API expects, computed from calendar days in the user's zone.
public struct DateRange: Hashable, Sendable, Identifiable {
    public var start: Date   // local midnight of the first day
    public var end: Date     // local midnight of the last day
    public var label: String
    public var id: String { label }

    public init(start: Date, end: Date, label: String) { self.start = start; self.end = end; self.label = label }

    private static let iso: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'"
        return f
    }()

    public func query(calendar: Calendar = .current) -> [String: Any?] {
        let dayStart = calendar.startOfDay(for: start)
        let nextDay = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: end))!
        return [
            "date_after": DateRange.iso.string(from: dayStart),
            "date_before": DateRange.iso.string(from: nextDay.addingTimeInterval(-1)),
        ]
    }

    public static func today(_ cal: Calendar = .current, now: Date = Date()) -> DateRange {
        let d = cal.startOfDay(for: now); return DateRange(start: d, end: d, label: "Today")
    }
    public static func yesterday(_ cal: Calendar = .current, now: Date = Date()) -> DateRange {
        let d = cal.date(byAdding: .day, value: -1, to: cal.startOfDay(for: now))!; return DateRange(start: d, end: d, label: "Yesterday")
    }
    public static func last7Days(_ cal: Calendar = .current, now: Date = Date()) -> DateRange {
        let e = cal.startOfDay(for: now); return DateRange(start: cal.date(byAdding: .day, value: -6, to: e)!, end: e, label: "Last 7 days")
    }
    public static func last30Days(_ cal: Calendar = .current, now: Date = Date()) -> DateRange {
        let e = cal.startOfDay(for: now); return DateRange(start: cal.date(byAdding: .day, value: -29, to: e)!, end: e, label: "Last 30 days")
    }
    public static func thisMonth(_ cal: Calendar = .current, now: Date = Date()) -> DateRange {
        let e = cal.startOfDay(for: now)
        let s = cal.date(from: cal.dateComponents([.year, .month], from: e))!
        return DateRange(start: s, end: e, label: "This month")
    }
    public static func lastMonth(_ cal: Calendar = .current, now: Date = Date()) -> DateRange {
        let thisStart = cal.date(from: cal.dateComponents([.year, .month], from: now))!
        let s = cal.date(byAdding: .month, value: -1, to: thisStart)!
        let e = cal.date(byAdding: .day, value: -1, to: thisStart)!
        return DateRange(start: s, end: e, label: "Last month")
    }
    public static func yearToDate(_ cal: Calendar = .current, now: Date = Date()) -> DateRange {
        let e = cal.startOfDay(for: now)
        let s = cal.date(from: cal.dateComponents([.year], from: e))!
        return DateRange(start: s, end: e, label: "Year to date")
    }
    public static var presets: [DateRange] { [today(), yesterday(), last7Days(), last30Days(), thisMonth(), lastMonth(), yearToDate()] }
}

public enum DateText {
    private static let isoFractional: ISO8601DateFormatter = { let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]; return f }()
    private static let isoPlain: ISO8601DateFormatter = { let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime]; return f }()
    private static let dayOnly: DateFormatter = { let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = TimeZone(identifier: "UTC"); f.dateFormat = "yyyy-MM-dd"; return f }()

    /// Parses `2026-10-03T19:54:02.861763Z`, `2026-09-03 16:14:32.325946+00:00` and `2026-10-03`.
    public static func parse(_ raw: String?) -> Date? {
        guard var s = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty else { return nil }
        s = s.replacingOccurrences(of: " ", with: "T")
        // ISO8601DateFormatter only accepts up to 3 fractional digits reliably; trim microseconds.
        if let dot = s.firstIndex(of: "."), let tzStart = s[dot...].firstIndex(where: { $0 == "Z" || $0 == "+" || $0 == "-" }) {
            let frac = s[s.index(after: dot)..<tzStart]
            s = String(s[..<dot]) + "." + String(frac.prefix(3)) + String(s[tzStart...])
        }
        return isoFractional.date(from: s) ?? isoPlain.date(from: s) ?? dayOnly.date(from: String(s.prefix(10)))
    }

    public static func short(_ raw: String?) -> String {
        guard let d = parse(raw) else { return "—" }
        return d.formatted(date: .abbreviated, time: .omitted)
    }

    public static func long(_ raw: String?) -> String {
        guard let d = parse(raw) else { return "—" }
        return d.formatted(date: .abbreviated, time: .shortened)
    }

    public static func relative(_ raw: String?, now: Date = Date()) -> String {
        guard let d = parse(raw) else { return "—" }
        let minutes = Int(now.timeIntervalSince(d) / 60)
        switch minutes {
        case ..<1: return "just now"
        case ..<60: return "\(minutes)m ago"
        case ..<(60 * 24): return "\(minutes / 60)h ago"
        case ..<(60 * 24 * 30): return "\(minutes / (60 * 24))d ago"
        default: return short(raw)
        }
    }

    /// `2026-10-07T18:00:00Z` — what the session stores for token expirations.
    public static func iso8601(_ date: Date) -> String { isoPlain.string(from: date) }

    /// `yyyy-MM-dd` for dates in request bodies.
    public static func apiDate(_ date: Date = Date()) -> String {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM-dd"; return f.string(from: date)
    }
}
