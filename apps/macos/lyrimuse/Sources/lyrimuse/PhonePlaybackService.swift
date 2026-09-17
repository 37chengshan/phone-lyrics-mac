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
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let item = cursor {
            defer { cursor = item.pointee.ifa_next }
            guard let address = item.pointee.ifa_addr,
                  address.pointee.sa_family == UInt8(AF_INET),
                  (item.pointee.ifa_flags & UInt32(IFF_LOOPBACK)) == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let length = socklen_t(address.pointee.sa_len)
            if getnameinfo(address, length, &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                result.append("\(String(cString: host)):8765")
            }
        }
        if result.isEmpty { result.append("\(ProcessInfo.processInfo.hostName):8765") }
        return Array(Set(result)).sorted()
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
