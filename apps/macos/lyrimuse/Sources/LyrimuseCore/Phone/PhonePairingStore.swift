import CryptoKit
import Foundation
import Security

public struct PhonePairingCode: Equatable, Sendable {
    public let value: String
    public let expiresAt: Date
}

public struct PhonePairingDevice: Codable, Equatable, Sendable {
    public let deviceId: String
    public let name: String

    public init(deviceId: String, name: String) {
        self.deviceId = deviceId
        self.name = name
    }
}

public struct PhoneDeviceCredential: Equatable, Sendable {
    public let device: PhonePairingDevice
    public let token: String
}

public struct PhoneCredentialRecord: Codable, Equatable, Sendable {
    public let device: PhonePairingDevice
    public let tokenDigest: String
    public let pairedAt: Date
}

public protocol PhoneCredentialPersistence: AnyObject {
    func loadRecords() -> [String: PhoneCredentialRecord]
    func save(_ record: PhoneCredentialRecord)
    func remove(deviceId: String)
}

public final class InMemoryPhoneCredentialPersistence: PhoneCredentialPersistence, @unchecked Sendable {
    public private(set) var savedRecords: [String: PhoneCredentialRecord] = [:]
    public init() {}
    public func loadRecords() -> [String: PhoneCredentialRecord] { savedRecords }
    public func save(_ record: PhoneCredentialRecord) { savedRecords[record.device.deviceId] = record }
    public func remove(deviceId: String) { savedRecords.removeValue(forKey: deviceId) }
}

public enum PhonePairingError: Error, Equatable, Sendable {
    case noActiveCode
    case expired
    case invalidCode
    case invalidDevice
    case rateLimited
    case randomFailure
}

public struct PhonePairingStore {
    private let persistence: PhoneCredentialPersistence
    private let randomBytes: (Int) -> Data
    private var records: [String: PhoneCredentialRecord]
    private var activeCode: PhonePairingCode?
    private var failedAttempts: [String: [Date]] = [:]

    public init(
        persistence: PhoneCredentialPersistence,
        randomBytes: @escaping (Int) -> Data = PhonePairingStore.secureRandomBytes
    ) {
        self.persistence = persistence
        self.randomBytes = randomBytes
        records = persistence.loadRecords()
    }

    public mutating func createCode(now: Date = Date()) -> PhonePairingCode {
        let raw = randomBytes(4)
        let number = raw.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) } % 1_000_000
        let code = PhonePairingCode(value: String(format: "%06u", number), expiresAt: now.addingTimeInterval(300))
        activeCode = code
        return code
    }

    public mutating func pair(
        code: String,
        device: PhonePairingDevice,
        peer: String,
        now: Date = Date()
    ) throws -> PhoneDeviceCredential {
        let recent = failedAttempts[peer, default: []].filter { now.timeIntervalSince($0) < 60 }
        failedAttempts[peer] = recent
        guard recent.count < 5 else { throw PhonePairingError.rateLimited }
        guard !device.deviceId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !device.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { throw PhonePairingError.invalidDevice }
        guard let activeCode else { return try reject(peer: peer, now: now, error: .noActiveCode) }
        guard now <= activeCode.expiresAt else {
            self.activeCode = nil
            return try reject(peer: peer, now: now, error: .expired)
        }
        guard constantTimeEqual(code, activeCode.value) else {
            return try reject(peer: peer, now: now, error: .invalidCode)
        }
        let tokenData = randomBytes(32)
        guard tokenData.count == 32 else { throw PhonePairingError.randomFailure }
        let token = tokenData.map { String(format: "%02x", $0) }.joined()
        let record = PhoneCredentialRecord(device: device, tokenDigest: Self.digest(token), pairedAt: now)
        records[device.deviceId] = record
        persistence.save(record)
        self.activeCode = nil
        failedAttempts[peer] = []
        return PhoneDeviceCredential(device: device, token: token)
    }

    public func authorize(bearer: String) -> PhonePairingDevice? {
        guard !bearer.isEmpty else { return nil }
        let candidate = Self.digest(bearer)
        return records.values.first { constantTimeEqual($0.tokenDigest, candidate) }?.device
    }

    public mutating func revoke(deviceId: String) {
        records.removeValue(forKey: deviceId)
        persistence.remove(deviceId: deviceId)
    }

    public var pairedDevices: [PhonePairingDevice] {
        records.values.map(\.device).sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    private mutating func reject<T>(peer: String, now: Date, error: PhonePairingError) throws -> T {
        failedAttempts[peer, default: []].append(now)
        throw error
    }

    private static func digest(_ token: String) -> String {
        SHA256.hash(data: Data(token.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    private func constantTimeEqual(_ lhs: String, _ rhs: String) -> Bool {
        let a = Array(lhs.utf8), b = Array(rhs.utf8)
        guard a.count == b.count else { return false }
        return zip(a, b).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }

    public static func secureRandomBytes(_ count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        guard SecRandomCopyBytes(kSecRandomDefault, count, &bytes) == errSecSuccess else { return Data() }
        return Data(bytes)
    }
}
