import Foundation
import LyrimuseCore
import Network
import Darwin

@MainActor
final class PhonePlaybackService: ObservableObject {
    static let shared = PhonePlaybackService()

    @Published private(set) var pairingCode: PhonePairingCode?
    @Published private(set) var serverStatus = "starting"
    @Published private(set) var pairedDevices: [PhonePairingDevice] = []

    private let router: PhoneHTTPRouter
    private let server: PhoneHTTPServer
    private let stableMacId: String
    private var started = false
    private var pairingExpirationTask: Task<Void, Never>?

    private init() {
        let defaults = UserDefaults.standard
        if let saved = defaults.string(forKey: "phoneLyrics.stableMacId"), !saved.isEmpty {
            stableMacId = saved
        } else {
            let fresh = UUID().uuidString.lowercased()
            defaults.set(fresh, forKey: "phoneLyrics.stableMacId")
            stableMacId = fresh
        }
        router = PhoneHTTPRouter(pairingStore: PhonePairingStore(
            persistence: PhoneKeychainCredentialPersistence()))
        server = PhoneHTTPServer(router: router)
        router.onPlayback = { envelope in
            Task { @MainActor in
                guard PhonePlaybackBridge.shared.ingest(envelope) else { return }
                LocalPlaybackSource.shared.refreshNow()
            }
        }
        router.onPairingChanged = { [weak self] in
            Task { @MainActor in
                self?.pairingCode = nil
                self?.pairingExpirationTask?.cancel()
                self?.reloadDevices()
                self?.updateDiscovery(pairingEnabled: false)
            }
        }
        server.onStateChange = { [weak self] state in
            Task { @MainActor in
                switch state {
                case .ready: self?.serverStatus = "ready"
                case .failed: self?.serverStatus = "failed"
                case .waiting: self?.serverStatus = "waiting"
                case .cancelled: self?.serverStatus = "stopped"
                default: break
                }
            }
        }
    }

    func start() {
        guard !started else { return }
        started = true
        PhonePlaybackBridge.shared.setEnabled(true)
        reloadDevices()
        restartServer(pairingEnabled: false)
    }

    func revoke(deviceId: String) {
        router.revoke(deviceId: deviceId)
        reloadDevices()
    }

    var connectionStatus: PhoneConnectionStatus {
        PhonePlaybackBridge.shared.connectionStatus()
    }

    var manualAddresses: [String] {
        var pointer: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&pointer) == 0, let first = pointer else { return ["\(ProcessInfo.processInfo.hostName):8765"] }
        defer { freeifaddrs(pointer) }
        var result: [String] = []
        // ⚠️ 2026-09-17 真机排查发现的误导源,两个都要治:
        //
        //  ① **虚拟网卡**。代理软件(Clash/utun 那类)会往系统里插一块网卡并占一个网段
        //     (实测本机是 utun5 的 198.18.0.1),它跟局域网毫无关系、手机永远连不上。原来
        //     的写法把所有非回环 IPv4 一律列出来,于是设置页并排显示两个地址 —— 用户挑了
        //     那个连不上的,表现成"配对能成功但同步永远不通",而屏幕上完全看不出哪条是错的。
        //     判据用 **默认路由所在那块网卡**:手机要连的必然是出网的那条路。
        //     ⚠️ 不能按"是不是 RFC1918 私网"判 —— 198.18.0.0/15 也是保留段,单看地址分不出来。
        //
        //  ② **顺序**。真实地址排前面,让用户第一眼看到的就是能用的那个。
        //
        //  ⚠️ 2026-09-17 第二轮修正:第一版写成"真实地址 + 其余全部",结果隧道地址照样
        //     出现在列表里(真机装完一看还是两个),等于没修。兜底必须**只在真实地址一个都
        //     找不到时**才生效 —— 有 en0 那条路的时候,列出来的就该只有它。
        let primaryInterface = Self.defaultRouteInterfaceName()
        var candidates: [(interface: String, address: String)] = []
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let item = cursor {
            defer { cursor = item.pointee.ifa_next }
            guard let address = item.pointee.ifa_addr,
                  address.pointee.sa_family == UInt8(AF_INET),
                  (item.pointee.ifa_flags & UInt32(IFF_LOOPBACK)) == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let length = socklen_t(address.pointee.sa_len)
            guard getnameinfo(address, length, &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            candidates.append((interface: String(cString: item.pointee.ifa_name),
                               address: "\(String(cString: host)):8765"))
        }
        // 筛选规则本体在 LyrimuseCore.PhoneAddressFilter(纯函数,自检直接覆盖)—— 这里只负责
        // 把 ifaddrs 读成候选,别把规则抄第二份。
        let visible = PhoneAddressFilter.visible(candidates, primaryInterface: primaryInterface)
        return visible.isEmpty ? ["\(ProcessInfo.processInfo.hostName):8765"] : visible
    }

    /// 默认路由走的是哪块网卡(如 `en0`)。拿不到返回 nil。
    ///
    /// ifaddrs 不含路由表,所以这里用"非点对点、非回环、有 IPv4、且不是隧道"来筛第一块可用
    /// 网卡 —— 硬编码 `en0` 在换了网卡顺序的机器上会错。判错也不致命:调用方会把它列出的
    /// 地址当主地址、其余当兜底,用户仍然看得到真实那个。
    private static func defaultRouteInterfaceName() -> String? {
        var pointer: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&pointer) == 0, let first = pointer else { return nil }
        defer { freeifaddrs(pointer) }
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let item = cursor {
            defer { cursor = item.pointee.ifa_next }
            let flags = Int32(bitPattern: item.pointee.ifa_flags)
            let name = String(cString: item.pointee.ifa_name)
            guard flags & IFF_UP != 0,
                  flags & IFF_LOOPBACK == 0,
                  flags & IFF_POINTOPOINT == 0,
                  !name.hasPrefix("utun"),
                  !name.hasPrefix("gif"),
                  !name.hasPrefix("stf"),
                  let address = item.pointee.ifa_addr,
                  address.pointee.sa_family == UInt8(AF_INET) else { continue }
            return name
        }
        return nil
    }

    private func reloadDevices() {
        pairedDevices = router.pairedDevices
    }

    @discardableResult
    func beginPairing() -> PhonePairingCode {
        let code = router.activatePairingCode()
        pairingCode = code
        updateDiscovery(pairingEnabled: true)
        pairingExpirationTask?.cancel()
        pairingExpirationTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(300))
            guard !Task.isCancelled, self?.pairingCode?.value == code.value else { return }
            self?.pairingCode = nil
            self?.updateDiscovery(pairingEnabled: false)
        }
        return code
    }

    private func restartServer(pairingEnabled: Bool) {
        let name = ProcessInfo.processInfo.hostName.split(separator: ".").first.map(String.init) ?? "Mac"
        do {
            try server.start(port: 8765, discovery: PhoneDiscoveryDescriptor(
                serviceName: "\(name) · Lyrimuse",
                stableMacId: stableMacId,
                pairingEnabled: pairingEnabled))
        } catch {
            serverStatus = "failed"
        }
    }

    private func updateDiscovery(pairingEnabled: Bool) {
        let name = ProcessInfo.processInfo.hostName.split(separator: ".").first.map(String.init) ?? "Mac"
        server.updateDiscovery(PhoneDiscoveryDescriptor(
            serviceName: "\(name) · Lyrimuse", stableMacId: stableMacId,
            pairingEnabled: pairingEnabled))
    }
}
