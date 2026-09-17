import Foundation
import LyrimuseCore

@MainActor
func runPhonePlaybackBridgeTests() {
    let file = FileManager.default.temporaryDirectory
        .appendingPathComponent("lyrimuse-phone-\(UUID().uuidString).json")
    defer { try? FileManager.default.removeItem(at: file) }
    let bridge = PhonePlaybackBridge(snapshotFile: file)
    bridge.setEnabled(true)

    func envelope(sequence: Int64, event: PhonePlaybackEvent, state: PhonePlaybackState,
                  position: Int64, trackId: String = "qq-1") -> PhonePlaybackEnvelope {
        PhonePlaybackEnvelope(
            sessionId: "session-a", sequence: sequence, event: event,
            track: PhoneTrack(trackId: trackId, title: "Song", artist: "Singer", album: "Album", durationMs: 180_000),
            playback: PhonePlayback(state: state, positionMs: position,
                                    speed: state == .playing ? 1 : 0, capturedAtMonotonicMs: 7),
            source: PhonePlaybackSourceIdentity(packageName: "com.tencent.qqmusic", deviceId: "phone-a"))
    }

    expectEqual(bridge.ingest(envelope(sequence: 1, event: .trackChanged, state: .playing, position: 12_000),
                              receivedAtMs: 1_000), true, "手机桥: 接受新事件")
    let playing = bridge.mediaSnapshot(atMs: 1_500)
    expectEqual(playing?.title, "Song", "手机桥: 元数据映射到原生歌词管线")
    expectEqual(playing?.elapsedTime, 12.5, "手机桥: 播放位置按手机锚点外推")
    expectEqual(playing?.playing, true, "手机桥: 播放态映射")
    expectEqual(playing?.bundleIdentifier, "com.tencent.qqmusic", "手机桥: 保留手机来源包名")
    expectEqual(FileManager.default.fileExists(atPath: file.path), true, "手机桥: 原子落盘供 Go collector 读取")

    let persisted = (try? Data(contentsOf: file))
        .flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
    expectEqual(persisted?["title"] as? String, "Song", "手机桥: 落盘标题")
    expectEqual(persisted?["elapsedTime"] as? Double, 12.0, "手机桥: 落盘使用接收时锚点")

    expectEqual(bridge.ingest(envelope(sequence: 2, event: .pause, state: .paused, position: 13_250),
                              receivedAtMs: 2_000), true, "手机桥: 接受暂停")
    expectEqual(bridge.mediaSnapshot(atMs: 7_000)?.elapsedTime, 13.25, "手机桥: 暂停位置冻结")
    expectEqual(bridge.mediaSnapshot(atMs: 7_000)?.playing, false, "手机桥: 暂停即时映射")

    expectEqual(bridge.mediaSnapshot(atMs: 10_000) == nil, true, "手机桥: 八秒无心跳后离线清空")
    expectEqual(FileManager.default.fileExists(atPath: file.path), false, "手机桥: 离线删除 collector 快照")
}
