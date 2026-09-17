import Foundation

/// Thread-safe boundary between authenticated phone events and Lyrimuse's existing
/// `MediaControlSnapshot` pipeline. The same normalized snapshot is atomically
/// persisted for the Go collector, so UI lyrics and enrichment observe one source.
public final class PhonePlaybackBridge: @unchecked Sendable {
    public static let shared = PhonePlaybackBridge()

    private let lock = NSLock()
    private var machine = PhonePlaybackStateMachine()
    private let snapshotFile: URL
    private var enabled = false

    public init(snapshotFile: URL = LyrimusePaths.configFile("phone-now-playing.json")) {
        self.snapshotFile = snapshotFile
    }

    public func setEnabled(_ enabled: Bool) {
        lock.lock()
        self.enabled = enabled
        if !enabled { removePersistedSnapshot() }
        lock.unlock()
    }

    public var isEnabled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return enabled
    }

    @discardableResult
    public func ingest(_ envelope: PhonePlaybackEnvelope, receivedAtMs: Int64 = PhonePlaybackBridge.monotonicNowMs()) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        switch machine.ingest(envelope, receivedAtMs: receivedAtMs) {
        case let .accept(snapshot, _):
            persist(snapshot)
            return true
        case .clear:
            removePersistedSnapshot()
            return true
        case .ignoreOutOfOrder, .none:
            return false
        }
    }

    public func mediaSnapshot(atMs nowMs: Int64 = PhonePlaybackBridge.monotonicNowMs()) -> MediaControlSnapshot? {
        lock.lock()
        defer { lock.unlock() }
        guard enabled else { return nil }
        if case .clear = machine.checkTimeout(atMs: nowMs) {
            removePersistedSnapshot()
            return nil
        }
        guard let current = machine.currentSnapshot,
              let positionMs = machine.position(atMs: nowMs)
        else { return nil }
        return makeMediaSnapshot(current.envelope, positionMs: positionMs)
    }

    public func connectionStatus(atMs nowMs: Int64 = PhonePlaybackBridge.monotonicNowMs()) -> PhoneConnectionStatus {
        lock.lock()
        defer { lock.unlock() }
        return machine.connectionStatus(atMs: nowMs)
    }

    public static func monotonicNowMs() -> Int64 {
        Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
    }

    private func makeMediaSnapshot(_ envelope: PhonePlaybackEnvelope, positionMs: Int64) -> MediaControlSnapshot {
        MediaControlSnapshot(
            title: envelope.track.title,
            artist: envelope.track.artist,
            album: envelope.track.album,
            duration: Double(envelope.track.durationMs) / 1_000,
            elapsedTime: Double(positionMs) / 1_000,
            playing: envelope.playback.state == .playing,
            playbackRate: envelope.playback.state == .playing ? envelope.playback.speed : 0,
            isMusicApp: true,
            bundleIdentifier: envelope.source.packageName,
            anchorElapsedTime: Double(positionMs) / 1_000,
            isRadio: false)
    }

    private func persist(_ snapshot: PhonePlaybackSnapshot) {
        let envelope = snapshot.envelope
        let object: [String: Any] = [
            "title": envelope.track.title,
            "artist": envelope.track.artist,
            "album": envelope.track.album,
            "duration": Double(envelope.track.durationMs) / 1_000,
            "elapsedTime": Double(snapshot.anchor.position(atMs: snapshot.receivedAtMs)) / 1_000,
            "anchorElapsedTime": Double(snapshot.anchor.position(atMs: snapshot.receivedAtMs)) / 1_000,
            "playing": envelope.playback.state == .playing,
            "playbackRate": envelope.playback.state == .playing ? envelope.playback.speed : 0,
            "isMusicApp": true,
            "bundleIdentifier": envelope.source.packageName,
            "timestamp": ISO8601DateFormatter().string(from: Date()),
            "sessionId": envelope.sessionId,
            "sequence": envelope.sequence,
            "deviceId": envelope.source.deviceId,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]) else { return }
        do {
            try FileManager.default.createDirectory(
                at: snapshotFile.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: snapshotFile, options: .atomic)
        } catch {
            // UI remains live in memory even if the optional collector handoff cannot be written.
        }
    }

    private func removePersistedSnapshot() {
        try? FileManager.default.removeItem(at: snapshotFile)
    }
}
