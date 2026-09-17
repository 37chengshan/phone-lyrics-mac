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

    /// 手机歌词镜像模式下,本机播放控制整排都不该显示。
    ///
    /// 产品规则(P0):手机是唯一播放源,Mac 只同步显示、绝不控制播放,所以播放/暂停/
    /// 上一首/下一首/拖进度这几件事在 Mac 上没有任何可达效果。留着它们是**死按钮** ——
    /// 点了什么都不发生比压根没有这个按钮更糟,用户会以为 App 卡了或者以为能遥控手机。
    ///
    /// 单独立一个名字而不是各处直接读 `isEnabled`:这个判据服务的是 UI 可见性,语义上跟
    /// "桥接有没有开"是两件事,以后要按连接状态细分(比如只在真的配对过之后才藏)时,
    /// 改这一处就够,四个 UI 面不用各改一遍。
    public static var hidesLocalTransportControls: Bool { shared.isEnabled }

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
