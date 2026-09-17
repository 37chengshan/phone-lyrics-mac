import Foundation
import LyrimuseCore

@MainActor
func runPhonePairingTests() {
    var bytes = Array(UInt8(0) ... UInt8(63))
    func random(_ count: Int) -> Data {
        let result = Data(bytes.prefix(count))
        bytes = Array(bytes.dropFirst(count)) + Array(bytes.prefix(count))
        return result
    }
    let persistence = InMemoryPhoneCredentialPersistence()
    var store = PhonePairingStore(persistence: persistence, randomBytes: random)
    let code = store.createCode(now: Date(timeIntervalSince1970: 1_000))
    expectEqual(code.value.count, 6, "手机配对: 配对码固定六位")
    expectEqual(code.value.allSatisfy(\.isNumber), true, "手机配对: 配对码只含数字")
    expectEqual(code.expiresAt, Date(timeIntervalSince1970: 1_300), "手机配对: 五分钟过期")

    let device = PhonePairingDevice(deviceId: "phone-a", name: "Xiaomi")
    let credential = try? store.pair(code: code.value, device: device, peer: "peer-a", now: Date(timeIntervalSince1970: 1_001))
    expectEqual(credential?.token.count, 64, "手机配对: 令牌是 32 byte 的十六进制")
    expectEqual(store.authorize(bearer: credential?.token ?? "")?.deviceId, "phone-a", "手机配对: 正确 token 可授权")
    expectEqual(store.authorize(bearer: "wrong"), nil, "手机配对: 错误 token 被拒绝")
    expectEqual(persistence.savedRecords.values.first?.tokenDigest.contains(credential?.token ?? ""), false,
                "手机配对: 持久层只存摘要不存明文 token")
    expectEqual((try? store.pair(code: code.value, device: device, peer: "peer-a", now: Date(timeIntervalSince1970: 1_002))) == nil,
                true, "手机配对: 配对码只能消费一次")
    store.revoke(deviceId: "phone-a")
    expectEqual(store.authorize(bearer: credential?.token ?? ""), nil, "手机配对: 撤销后 token 立即失效")

    var expiring = PhonePairingStore(persistence: InMemoryPhoneCredentialPersistence(), randomBytes: random)
    let expired = expiring.createCode(now: Date(timeIntervalSince1970: 2_000))
    expectEqual((try? expiring.pair(code: expired.value, device: device, peer: "peer-b", now: Date(timeIntervalSince1970: 2_301))) == nil,
                true, "手机配对: 过期码被拒绝")

    var limited = PhonePairingStore(persistence: InMemoryPhoneCredentialPersistence(), randomBytes: random)
    _ = limited.createCode(now: Date(timeIntervalSince1970: 3_000))
    var rateLimited = false
    for attempt in 0 ..< 6 {
        do {
            _ = try limited.pair(code: "999999", device: device, peer: "peer-c", now: Date(timeIntervalSince1970: 3_000 + Double(attempt)))
        } catch PhonePairingError.rateLimited {
            rateLimited = true
        } catch {}
    }
    expectEqual(rateLimited, true, "手机配对: 同 peer 每分钟最多五次失败")
}
