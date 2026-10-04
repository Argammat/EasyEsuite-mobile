import Foundation

public enum APIError: Error, LocalizedError, Sendable {
    /// Non-2xx response. `detail` is the human-readable message extracted from the DRF body.
    case http(status: Int, detail: String, body: String?)
    /// The session could not be refreshed; the user has to log in again.
    case unauthorized
    case network(String)
    case decoding(String, body: String?)

    public var errorDescription: String? {
        switch self {
        case .http(_, let detail, _): return detail
        case .unauthorized: return "Your session expired. Please sign in again."
        case .network(let m): return m.isEmpty ? "Can't reach EasyEsuite. Check your connection." : m
        case .decoding(let m, _): return "Unexpected response from the server (\(m))."
        }
    }

    public var status: Int? { if case .http(let s, _, _) = self { return s }; return nil }
    public var isNotFound: Bool { status == 404 }
    public var isValidation: Bool { status == 400 }

    /// Pulls a readable message out of DRF error bodies: {"detail": ".."} or {"field": ["msg"]}.
    public static func describe(status: Int, body: Data?) -> String {
        guard let body, !body.isEmpty else { return defaultMessage(status) }
        guard let json = try? JSONSerialization.jsonObject(with: body) else {
            return String(data: body, encoding: .utf8).map { String($0.prefix(200)) } ?? defaultMessage(status)
        }
        if let dict = json as? [String: Any] {
            for key in ["detail", "error", "message"] { if let s = dict[key] as? String { return s } }
            let parts: [String] = dict.keys.sorted().compactMap { key in
                let value = dict[key]
                let msg: String
                if let s = value as? String { msg = s }
                else if let arr = value as? [Any] { msg = arr.map { "\($0)" }.joined(separator: ", ") }
                else if let v = value { msg = "\(v)" } else { return nil }
                return key == "non_field_errors" ? msg : "\(key): \(msg)"
            }
            return parts.isEmpty ? defaultMessage(status) : parts.joined(separator: "; ")
        }
        if let arr = json as? [Any] { return arr.map { "\($0)" }.joined(separator: ", ") }
        return defaultMessage(status)
    }

    public static func defaultMessage(_ status: Int) -> String {
        switch status {
        case 400: return "The server rejected the request."
        case 401: return "Not signed in."
        case 403: return "You don't have permission to do that."
        case 404: return "Not found."
        case 429: return "Too many requests, slow down."
        case 500...599: return "Server error (\(status))."
        default: return "Request failed (\(status))."
        }
    }
}
