import Foundation
import Security

public final class PhoneKeychainCredentialPersistence: PhoneCredentialPersistence, @unchecked Sendable {
    private let service: String

    public init(service: String = "me.yudaotor.lyrimuse.phone-pairing") {
        self.service = service
    }

    public func loadRecords() -> [String: PhoneCredentialRecord] {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecMatchLimit as String: kSecMatchLimitAll,
            kSecReturnAttributes as String: true,
            kSecReturnData as String: true,
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let items = result as? [[String: Any]]
        else { return [:] }
        var records: [String: PhoneCredentialRecord] = [:]
        for item in items {
            guard let data = item[kSecValueData as String] as? Data,
                  let record = try? JSONDecoder().decode(PhoneCredentialRecord.self, from: data)
            else { continue }
            records[record.device.deviceId] = record
        }
        return records
    }

    public func save(_ record: PhoneCredentialRecord) {
        guard let data = try? JSONEncoder().encode(record) else { return }
        remove(deviceId: record.device.deviceId)
        let item: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: record.device.deviceId,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
            kSecValueData as String: data,
        ]
        SecItemAdd(item as CFDictionary, nil)
    }

    public func remove(deviceId: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: deviceId,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
