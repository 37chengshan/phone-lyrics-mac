import Foundation
import LyrimuseCore
import Network

@MainActor
final class PhonePlaybackService: ObservableObject {
    static let shared = PhonePlaybackService()

    @Published private(set) var pairingCode: PhonePairingCode?
    @Published private(set) var serverStatus = "starting"

    private let router: PhoneHTTPRouter
    private let server: PhoneHTTPServer
    private let stableMacId: String
    private var started = false

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
        restartServer(pairingEnabled: false)
    }

    @discardableResult
    func beginPairing() -> PhonePairingCode {
        let code = router.activatePairingCode()
        pairingCode = code
        restartServer(pairingEnabled: true)
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
}
