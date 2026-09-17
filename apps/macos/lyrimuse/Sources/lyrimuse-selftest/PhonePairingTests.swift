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

    // ---- 手动地址过滤(2026-09-17 真机排查) ----
    //
    // 设置页「手动连接备用地址」原来把所有非回环 IPv4 都列出来,于是代理软件插进来的
    // 隧道网卡(实测 utun5 / 198.18.0.1)也跟着显示。用户挑到那个地址,表现成「配对能成功
    // 但同步永远不通」——而屏幕上两条地址并排,看不出哪条是错的。
    //
    // 规则本体是 LyrimuseCore 的纯函数(见 PhoneAddressFilter 头注),这里直接喂样本:
    // 断言的是规则,不是「这台机器现在的网卡长什么样」,换机器跑结果一样。
    do {
        // 真机那台机器的实际形态:en0 是默认路由,utun5 是代理插进来的隧道。
        expectEqual(
            PhoneAddressFilter.visible([
                (interface: "en0", address: "192.168.1.100:8765"),
                (interface: "utun5", address: "198.18.0.1:8765"),
            ], primaryInterface: "en0"),
            ["192.168.1.100:8765"],
            "手动地址: 只列真实网卡,隧道地址不出现(列两条会让人挑错,真机踩过)")

        // 隧道一律不出现,哪怕它是唯一带 IPv4 的那块。
        expectEqual(
            PhoneAddressFilter.visible(
                [(interface: "utun5", address: "198.18.0.1:8765")], primaryInterface: nil),
            [],
            "手动地址: 隧道地址永远不进列表")

        // 认不出默认路由那块时(全走 VPN 的极端情况),用真实网卡的其它地址兜底 ——
        // 宁可给个可疑的,也不要让用户一个能填的都没有。
        expectEqual(
            PhoneAddressFilter.visible(
                [(interface: "en1", address: "192.168.1.5:8765")], primaryInterface: nil),
            ["192.168.1.5:8765"],
            "手动地址: 认不出主网卡时用真实网卡地址兜底")

        expectEqual(PhoneAddressFilter.isTunnelInterface("utun5"), true, "手动地址: utun5 算隧道")
        expectEqual(PhoneAddressFilter.isTunnelInterface("en0"), false, "手动地址: en0 不算隧道")
    }
}
