import Foundation

public final class PhoneHTTPRouter: @unchecked Sendable {
    private let lock = NSLock()
    private var pairingStore: PhonePairingStore
    public private(set) var lastAcceptedEnvelope: PhonePlaybackEnvelope?
    public var onPlayback: ((PhonePlaybackEnvelope) -> Void)?
    public var onPairingChanged: (() -> Void)?

    /// 当前歌词的提供方(2026-09-19)。由 App 侧接上 PlaybackCoordinator —— 解析好的当前行、
    /// 下一句、译文与罗马音都在那里。
    ///
    /// 为什么搭在**响应**里而不是单开接口:手机每秒本来就在发心跳,顺路带回来是零额外请求、
    /// 零额外延迟。单开接口要么让手机再轮询一次(请求翻倍),要么走长连接(复杂度陡增)。
    ///
    /// ⚠️ nil = "此刻没有歌词可给"(没在播 / 这首歌没歌词),与"歌词是空的"是两回事 ——
    /// 前者让手机保留上一帧,别把界面清掉。
    public var lyricProvider: (() -> [String: Any]?)?

    public init(pairingStore: PhonePairingStore) {
        self.pairingStore = pairingStore
    }

    public func activatePairingCode(now: Date = Date()) -> PhonePairingCode {
        lock.withLock { pairingStore.createCode(now: now) }
    }

    public func route(_ request: PhoneHTTPRequest, now: Date = Date()) -> PhoneHTTPResponse {
        lock.withLock { routeLocked(request, now: now) }
    }

    public var pairedDevices: [PhonePairingDevice] {
        lock.withLock { pairingStore.pairedDevices }
    }

    public func revoke(deviceId: String) {
        lock.withLock { pairingStore.revoke(deviceId: deviceId) }
        onPairingChanged?()
    }

    private func routeLocked(_ request: PhoneHTTPRequest, now: Date) -> PhoneHTTPResponse {
        guard request.body.count <= 65_536 else { return .json(status: 413, object: ["error": "payload_too_large"]) }
        switch (request.method, request.path) {
        case ("GET", "/api/v1/health"):
            return .json(status: 200, object: ["ok": true])
        case ("GET", "/api/v1/info"):
            return .json(status: 200, object: ["protocolVersion": 1, "service": "phone-lyrics"])
        case ("POST", "/api/v1/pair"):
            return pair(request, now: now)
        case ("POST", "/api/v1/playback"):
            guard authorizedDevice(request) != nil else { return .json(status: 401, object: ["error": "unauthorized"]) }
            do {
                let envelope = try PhonePlaybackEnvelope.decodeAndValidate(request.body)
                lastAcceptedEnvelope = envelope
                onPlayback?(envelope)

                // 把当前歌词一并带回(2026-09-19)。
                //
                // ⚠️ 读取必须排在 onPlayback 之后:那一步是"把这次事件交给播放管线",
                // 换歌时的歌词解析由它触发。先读的话会拿到上一首的行。
                var payload: [String: Any] = ["ok": true, "sequence": envelope.sequence]
                if let lyric = lyricProvider?() { payload["lyric"] = lyric }
                return .json(status: 200, object: payload)
            } catch {
                return .json(status: 400, object: ["error": "invalid_playback"])
            }
        default:
            return .json(status: 404, object: ["error": "not_found"])
        }
    }

    private func pair(_ request: PhoneHTTPRequest, now: Date) -> PhoneHTTPResponse {
        guard let object = try? JSONSerialization.jsonObject(with: request.body) as? [String: Any],
              Set(object.keys) == Set(["code", "deviceId", "deviceName"]),
              let code = object["code"] as? String,
              let deviceId = object["deviceId"] as? String,
              let deviceName = object["deviceName"] as? String
        else { return .json(status: 400, object: ["error": "invalid_pair_request"]) }
        do {
            let credential = try pairingStore.pair(
                code: code,
                device: PhonePairingDevice(deviceId: deviceId, name: deviceName),
                peer: request.peer,
                now: now)
            onPairingChanged?()
            return .json(status: 200, object: [
                "deviceId": credential.device.deviceId,
                "protocolVersion": 1,
                "token": credential.token,
            ])
        } catch PhonePairingError.rateLimited {
            return .json(status: 429, object: ["error": "rate_limited"])
        } catch {
            return .json(status: 401, object: ["error": "pairing_rejected"])
        }
    }

    private func authorizedDevice(_ request: PhoneHTTPRequest) -> PhonePairingDevice? {
        guard let value = request.headers["authorization"], value.hasPrefix("Bearer ") else { return nil }
        return pairingStore.authorize(bearer: String(value.dropFirst("Bearer ".count)))
    }
}

private extension NSLock {
    func withLock<T>(_ body: () throws -> T) rethrows -> T {
        lock()
        defer { unlock() }
        return try body()
    }
}
