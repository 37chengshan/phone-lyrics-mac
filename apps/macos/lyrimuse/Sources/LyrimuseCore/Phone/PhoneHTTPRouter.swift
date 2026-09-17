import Foundation

public final class PhoneHTTPRouter: @unchecked Sendable {
    private let lock = NSLock()
    private var pairingStore: PhonePairingStore
    public private(set) var lastAcceptedEnvelope: PhonePlaybackEnvelope?
    public var onPlayback: ((PhonePlaybackEnvelope) -> Void)?
    public var onPairingChanged: (() -> Void)?

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
                return .json(status: 200, object: ["ok": true, "sequence": envelope.sequence])
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
