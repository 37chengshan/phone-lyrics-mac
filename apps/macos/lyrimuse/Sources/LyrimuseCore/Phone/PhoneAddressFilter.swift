import Foundation

/// 「手动连接备用地址」的筛选规则(纯函数,便于自检覆盖)。
///
/// 2026-09-17 真机排查产物。设置页原来把所有非回环 IPv4 都列出来,于是代理软件插进来的
/// 隧道网卡(实测 utun5 / 198.18.0.1)也跟着显示 —— 用户挑到那个,表现成"配对能成功但
/// 同步永远不通",而屏幕上两条地址并排、看不出哪条错。
///
/// 分成"候选"与"兜底"两档而不是简单过滤一遍,是因为两种失败模式要区别对待:
///
///   - 有真实局域网地址时,**只**列它(隧道那类一律不出现,免得挑错);
///   - 一个真实地址都没有时(极罕见,比如全走 VPN),退回列出非隧道地址 —— 宁可给个可疑的,
///     也不要让用户一个能填的地址都没有。
public enum PhoneAddressFilter {
    /// 隧道/虚拟网卡的名字前缀。代理软件(Clash / Surge / WireGuard 那类)插进来的网卡都长这样,
    /// 它们跟局域网无关,手机连上没有任何意义。
    static let tunnelPrefixes = ["utun", "gif", "stf", "ipsec", "ppp"]

    /// 一个网卡名是不是隧道。
    public static func isTunnelInterface(_ name: String) -> Bool {
        tunnelPrefixes.contains { name.hasPrefix($0) }
    }

    /// 从候选里挑出该给用户看的地址。
    ///
    /// - Parameters:
    ///   - entries: 全部候选,每项是 `(网卡名, "ip:port")`。
    ///   - primaryInterface: 默认路由那块网卡的名字(拿不到传 nil)。
    /// - Returns: 该展示的地址,真实地址在前。
    public static func visible(_ entries: [(interface: String, address: String)],
                              primaryInterface: String?) -> [String] {
        var real: [String] = []
        var fallback: [String] = []
        for entry in entries where !isTunnelInterface(entry.interface) {
            if entry.interface == primaryInterface {
                real.append(entry.address)
            } else {
                fallback.append(entry.address)
            }
        }
        let sortedReal = Array(Set(real)).sorted()
        if !sortedReal.isEmpty { return sortedReal }
        return Array(Set(fallback)).sorted()
    }
}

