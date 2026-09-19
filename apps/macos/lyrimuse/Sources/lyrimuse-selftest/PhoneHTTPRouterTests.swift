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



    // ── 歌词随响应回传(2026-09-19) ──
    //
    // 这一段守的是"手机不解析歌词"那条架构决定:解析在 Mac(它有十个源、缓存、逐字、译文),
    // 手机只管显示。所以 playback 的响应里要带上当前歌词,而 provider 没接上时不能带上半个空壳。
    let responseWithoutProvider = router.route(
        request("POST", "/api/v1/playback", body: valid, token: token))
    expectEqual(responseWithoutProvider.status, 200, "歌词: 没接 provider 时照常 200")
    let noProviderJSON = try? JSONSerialization.jsonObject(with: responseWithoutProvider.body) as? [String: Any]
    expectEqual(noProviderJSON?["lyric"] == nil, true, "歌词: provider 缺席时不带 lyric 键")

    router.lyricProvider = { ["current": "第一句", "next": "第二句", "title": "晴天"] }
    let withLyric = router.route(request("POST", "/api/v1/playback", body: valid, token: token))
    let lyricJSON = (try? JSONSerialization.jsonObject(with: withLyric.body) as? [String: Any])
    let lyric = lyricJSON?["lyric"] as? [String: Any]
    expectEqual(lyric?["current"] as? String, "第一句", "歌词: 响应带回当前行")
    expectEqual(lyric?["next"] as? String, "第二句", "歌词: 响应带回下一句")
    expectEqual(lyricJSON?["ok"] as? Bool, true, "歌词: 原有字段不受影响")

    // ⚠️ nil 的语义是"此刻没有可给的",手机据此**保留上一帧**。
    // 所以这条要钉死:provider 返回 nil 时,响应体里**不能**出现一个空的 lyric 对象 ——
    // 那会被手机读成"歌词就是空的",把界面清掉。
    router.lyricProvider = { nil }
    let nilLyric = router.route(request("POST", "/api/v1/playback", body: valid, token: token))
    let nilJSON = try? JSONSerialization.jsonObject(with: nilLyric.body) as? [String: Any]
    expectEqual(nilJSON?["lyric"] == nil, true, "歌词: provider 给 nil 时不带 lyric 键")

    // provider 缺失、给 nil、或给数据,三种情况都必须是 200 —— 它是**附加信息**,
    // 拿不到不该影响同步本身(那条链路才是本职)。
    expectEqual(nilLyric.status, 200, "歌词: provider 给 nil 时仍是 200")

    // 线程安全的暂存:这是"主 actor 写、HTTP 队列读"那条单向数据流的载体,
    // 也是替掉 MainActor.assumeIsolated(在 HTTP 队列上会崩)的关键。这里从非主线程读写,
    // 只要不崩、值能拿到,就说明那条路是通的。
    let snapshot = PhoneLyricSnapshot()
    snapshot.update(["current": "从主线程写的"])
    let readBack = DispatchQueue.global().sync { () -> [String: Any]? in snapshot.current }
    expectEqual(readBack?["current"] as? String, "从主线程写的", "歌词: 快照可跨线程读回")
    snapshot.update(nil)
    expectEqual(snapshot.current == nil, true, "歌词: 快照可被清空")



    // ── 歌词载荷构造(2026-09-19) ──
    //
    // 这一段直接决定手机屏幕上有没有字:`PhoneLyricPayload.make` 的返回就是响应里 lyric 的值。
    // 规则看着琐碎(空值放不放、什么时候给 nil),但每一条都对应一次真实故障。
    let full = PhoneLyricPayload.make(
        hasLyricsContent: true,
        currentLine: "不用问漫天的大雪",
        translation: "Ask not the snow",
        romanization: "bu yong wen",
        nextLine: "也知来路 我失去过盛夏",
        title: "大小孩",
        artist: "张韶涵")
    expectEqual(full?["current"] as? String, "不用问漫天的大雪", "歌词载荷: 带当前行")
    expectEqual(full?["translation"] as? String, "Ask not the snow", "歌词载荷: 带译文")
    expectEqual(full?["romanization"] as? String, "bu yong wen", "歌词载荷: 带罗马音")
    expectEqual(full?["next"] as? String, "也知来路 我失去过盛夏", "歌词载荷: 带下一句")
    expectEqual(full?["title"] as? String, "大小孩", "歌词载荷: 带歌名")
    expectEqual(full?["artist"] as? String, "张韶涵", "歌词载荷: 带歌手")

    // ⚠️ 没歌词内容时给 nil,不是空字典。手机据此**保留上一帧**:换歌时 Mac 要重搜,
    // 那段时间如果给空字典,手机那边会读成"歌词就是空的"、把界面清掉 —— 用户看到的是
    // 歌词闪一下没了。
    expectEqual(PhoneLyricPayload.make(
        hasLyricsContent: false, currentLine: "上一首的最后一句", translation: nil,
        romanization: nil, nextLine: nil, title: "上一首", artist: "歌手") == nil,
        true, "歌词载荷: 没有歌词内容时给 nil,让手机保留上一帧")

    // 空串一律不放进载荷(而不是放空串)。这是**有线格式的一部分**:手机按"键在不在"判读。
    let sparse = PhoneLyricPayload.make(
        hasLyricsContent: true, currentLine: "只有这一行", translation: "",
        romanization: "", nextLine: "", title: "", artist: "")
    expectEqual(sparse?["current"] as? String, "只有这一行", "歌词载荷: 当前行照常给")
    expectEqual(sparse?["next"] == nil, true, "歌词载荷: 空的下一句不放进去")
    expectEqual(sparse?["translation"] == nil, true, "歌词载荷: 空的译文不放进去")
    expectEqual(sparse?["romanization"] == nil, true, "歌词载荷: 空的罗马音不放进去")
    expectEqual(sparse?["title"] == nil, true, "歌词载荷: 空的歌名不放进去")
    expectEqual(sparse?["artist"] == nil, true, "歌词载荷: 空的歌手不放进去")

    // ⚠️ 有内容但**还没有当前行**时也要 nil。这一态在换歌后、第一句歌词到位前真实存在;
    // 手机把 current 当作"能不能显示"的判据(见 RelayApiClient.parseLyric 的同名守卫),
    // 给一份没有 current 的载荷它会整份丢掉 —— 那还不如这里就不发,省一趟无用的体积。
    expectEqual(PhoneLyricPayload.make(
        hasLyricsContent: true, currentLine: nil, translation: nil,
        romanization: nil, nextLine: "下一句来了", title: "大小孩", artist: "张韶涵") == nil,
        true, "歌词载荷: 还没有当前行时给 nil")
    expectEqual(PhoneLyricPayload.make(
        hasLyricsContent: true, currentLine: "", translation: nil,
        romanization: nil, nextLine: nil, title: "", artist: "") == nil,
        true, "歌词载荷: 当前行是空串时同样给 nil")

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
