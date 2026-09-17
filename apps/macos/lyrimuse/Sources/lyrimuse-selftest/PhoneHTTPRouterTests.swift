import Foundation
import LyrimuseCore

@MainActor
func runPhoneHTTPRouterTests() {
    var randomCounter: UInt8 = 1
    let persistence = InMemoryPhoneCredentialPersistence()
    let router = PhoneHTTPRouter(
        pairingStore: PhonePairingStore(persistence: persistence) { count in
            defer { randomCounter &+= 1 }
            return Data(repeating: randomCounter, count: count)
        })

    func request(_ method: String, _ path: String, body: Data = Data(), token: String? = nil) -> PhoneHTTPRequest {
        var headers = ["content-type": "application/json"]
        if let token { headers["authorization"] = "Bearer \(token)" }
        return PhoneHTTPRequest(method: method, path: path, headers: headers, body: body, peer: "local-test")
    }
    expectEqual(router.route(request("GET", "/api/v1/health")).status, 200, "手机 HTTP: health 无需授权")
    expectEqual(router.route(request("GET", "/api/v1/info")).status, 200, "手机 HTTP: info 无需授权")
    expectEqual(router.route(request("POST", "/api/v1/playback", body: Data("{}".utf8))).status, 401,
                "手机 HTTP: playback 必须授权")
    expectEqual(router.route(request("GET", "/missing")).status, 404, "手机 HTTP: 未知路径 404")
    expectEqual(router.route(request("POST", "/api/v1/playback", body: Data(repeating: 1, count: 65_537))).status, 413,
                "手机 HTTP: 请求体上限 64KiB")

    let pairing = router.activatePairingCode(now: Date(timeIntervalSince1970: 1_000))
    let pairBody = try! JSONSerialization.data(withJSONObject: [
        "code": pairing.value, "deviceId": "phone-a", "deviceName": "Xiaomi",
    ])
    let pairResponse = router.route(request("POST", "/api/v1/pair", body: pairBody), now: Date(timeIntervalSince1970: 1_001))
    expectEqual(pairResponse.status, 200, "手机 HTTP: 正确配对码换 token")
    let pairJSON = try? JSONSerialization.jsonObject(with: pairResponse.body) as? [String: Any]
    let token = pairJSON?["token"] as? String ?? ""
    expectEqual(token.isEmpty, false, "手机 HTTP: 配对响应含 token")

    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    let valid = (try? Data(contentsOf: root.appendingPathComponent("packages/protocol/fixtures/valid-playing.json"))) ?? Data()
    expectEqual(router.route(request("POST", "/api/v1/playback", body: valid, token: token)).status, 200,
                "手机 HTTP: 已配对 token 可推送合法播放事件")
    expectEqual(router.lastAcceptedEnvelope?.track.title, "晴天", "手机 HTTP: 路由保留最近合法事件")
    expectEqual(router.route(request("POST", "/api/v1/playback", body: Data("{}".utf8), token: token)).status, 400,
                "手机 HTTP: 合法 token 的坏 payload 返回 400")
    expectEqual(router.route(request("POST", "/api/v1/playback", body: valid, token: "bad")).status, 401,
                "手机 HTTP: 错误 token 返回 401")

    let rendered = PhoneHTTPResponse.json(status: 401, object: ["error": "unauthorized"]).serialized()
    expectEqual(String(decoding: rendered, as: UTF8.self).contains("Authorization"), false,
                "手机 HTTP: 响应不回显 Authorization")

    let raw = Data("POST /api/v1/playback HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}".utf8)
    let parsed = try? PhoneHTTPRequest.parse(raw, peer: "peer-z")
    expectEqual(parsed?.method, "POST", "手机 HTTP: 解析 method")
    expectEqual(parsed?.path, "/api/v1/playback", "手机 HTTP: 解析 path")
    expectEqual(parsed?.body, Data("{}".utf8), "手机 HTTP: 按 Content-Length 解析 body")
    let incomplete = Data("POST / HTTP/1.1\r\nContent-Length: 9\r\n\r\n{}".utf8)
    expectEqual((try? PhoneHTTPRequest.parse(incomplete, peer: "peer-z")) == nil, true,
                "手机 HTTP: 拒绝不完整 body")
}
