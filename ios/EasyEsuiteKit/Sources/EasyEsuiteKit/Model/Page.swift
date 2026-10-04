import Foundation

/// Django REST Framework limit/offset page.
public struct Page<T> {
    public var count: Int
    public var next: String?
    public var previous: String?
    public var results: [T]

    public init(count: Int = 0, next: String? = nil, previous: String? = nil, results: [T] = []) {
        self.count = count; self.next = next; self.previous = previous; self.results = results
    }

    enum CodingKeys: String, CodingKey { case count, next, previous, results }

    public var hasMore: Bool { next != nil }
    public static var empty: Page<T> { Page() }
}

// Decodable only when the row type is; the app also builds pages of non-decodable view rows.
extension Page: Decodable where T: Decodable {
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        count = try c.decodeIfPresent(Int.self, forKey: .count) ?? 0
        next = try c.decodeIfPresent(String.self, forKey: .next)
        previous = try c.decodeIfPresent(String.self, forKey: .previous)
        results = try c.decodeIfPresent([T].self, forKey: .results) ?? []
    }
}

/// Query that every paged list accepts.
public struct PageQuery: Equatable, Sendable {
    public var limit: Int
    public var offset: Int
    public init(limit: Int = 25, offset: Int = 0) { self.limit = limit; self.offset = offset }
    public func next() -> PageQuery { PageQuery(limit: limit, offset: offset + limit) }
    public var query: [String: Any?] { ["limit": limit, "offset": offset] }
}
