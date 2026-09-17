import Foundation

public struct PhoneHTTPRequest: Equatable, Sendable {
    public let method: String
    public let path: String
    public let headers: [String: String]
    public let body: Data
    public let peer: String

    public init(method: String, path: String, headers: [String: String], body: Data, peer: String) {
        self.method = method.uppercased()
        self.path = path
        self.headers = Dictionary(uniqueKeysWithValues: headers.map { ($0.key.lowercased(), $0.value) })
        self.body = body
        self.peer = peer
    }

    public static func parse(_ data: Data, peer: String) throws -> PhoneHTTPRequest {
        guard data.count <= 81_920,
              let separator = data.range(of: Data("\r\n\r\n".utf8)),
              separator.lowerBound <= 16_384,
              let head = String(data: data[..<separator.lowerBound], encoding: .utf8)
        else { throw PhoneHTTPParseError.malformed }
        let lines = head.components(separatedBy: "\r\n")
        guard let first = lines.first else { throw PhoneHTTPParseError.malformed }
        let requestLine = first.split(separator: " ", omittingEmptySubsequences: true)
        guard requestLine.count == 3, requestLine[2].hasPrefix("HTTP/1.") else {
            throw PhoneHTTPParseError.malformed
        }
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { throw PhoneHTTPParseError.malformed }
            let key = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            guard !key.isEmpty, headers[key] == nil else { throw PhoneHTTPParseError.malformed }
            headers[key] = value
        }
        let length: Int
        if let raw = headers["content-length"] {
            guard let parsed = Int(raw), parsed >= 0, parsed <= 65_536 else { throw PhoneHTTPParseError.bodyTooLarge }
            length = parsed
        } else {
            length = 0
        }
        let bodyStart = separator.upperBound
        guard data.count == bodyStart + length else { throw PhoneHTTPParseError.incomplete }
        let path = String(requestLine[1]).split(separator: "?", maxSplits: 1).first.map(String.init) ?? "/"
        return PhoneHTTPRequest(
            method: String(requestLine[0]), path: path, headers: headers,
            body: data.subdata(in: bodyStart ..< data.count), peer: peer)
    }
}

public enum PhoneHTTPParseError: Error, Equatable, Sendable {
    case malformed
    case incomplete
    case bodyTooLarge
}

public struct PhoneHTTPResponse: Equatable, Sendable {
    public let status: Int
    public let headers: [String: String]
    public let body: Data

    public init(status: Int, headers: [String: String] = [:], body: Data = Data()) {
        self.status = status
        self.headers = headers
        self.body = body
    }

    public static func json(status: Int, object: [String: Any]) -> PhoneHTTPResponse {
        let data = (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? Data("{}".utf8)
        return PhoneHTTPResponse(status: status, headers: ["Content-Type": "application/json; charset=utf-8"], body: data)
    }

    public func serialized() -> Data {
        let reason: String
        switch status {
        case 200: reason = "OK"
        case 400: reason = "Bad Request"
        case 401: reason = "Unauthorized"
        case 404: reason = "Not Found"
        case 405: reason = "Method Not Allowed"
        case 413: reason = "Payload Too Large"
        case 429: reason = "Too Many Requests"
        default: reason = "Error"
        }
        var lines = ["HTTP/1.1 \(status) \(reason)"]
        for (key, value) in headers.sorted(by: { $0.key < $1.key }) { lines.append("\(key): \(value)") }
        lines.append("Content-Length: \(body.count)")
        lines.append("Connection: close")
        return Data((lines.joined(separator: "\r\n") + "\r\n\r\n").utf8) + body
    }
}
