import Foundation
import LyrimuseCore

@MainActor
func runPhonePlaybackStateMachineTests() {
    func envelope(
        session: String = "s1", sequence: Int64, event: PhonePlaybackEvent = .heartbeat,
        trackId: String = "track-a", state: PhonePlaybackState = .playing,
        position: Int64 = 10_000, speed: Double = 1, duration: Int64 = 200_000
    ) -> PhonePlaybackEnvelope {
        PhonePlaybackEnvelope(
            sessionId: session, sequence: sequence, event: event,
            track: PhoneTrack(trackId: trackId, title: trackId, artist: "artist", album: "album", durationMs: duration),
            playback: PhonePlayback(state: state, positionMs: position, speed: speed, capturedAtMonotonicMs: 1),
            source: PhonePlaybackSourceIdentity(packageName: "com.tencent.qqmusic", deviceId: "phone-a"))
    }

    do {
        var machine = PhonePlaybackStateMachine()
        let first = machine.ingest(envelope(sequence: 1), receivedAtMs: 1_000)
        expectEqual(first.isAccepted, true, "手机状态机: 接受首个会话事件")
        expectEqual(machine.position(atMs: 2_000), 11_000, "手机状态机: playing 按单调时钟外推")
        expectEqual(machine.ingest(envelope(sequence: 1), receivedAtMs: 2_100), .ignoreOutOfOrder,
                    "手机状态机: 丢弃重复 sequence")
        expectEqual(machine.ingest(envelope(sequence: 0), receivedAtMs: 2_200), .ignoreOutOfOrder,
                    "手机状态机: 丢弃倒退 sequence")
        expectEqual(machine.ingest(envelope(session: "s2", sequence: 1), receivedAtMs: 2_300).isAccepted, true,
                    "手机状态机: 新 session 重置排序")
    }

    do {
        var machine = PhonePlaybackStateMachine()
        _ = machine.ingest(envelope(sequence: 1), receivedAtMs: 1_000)
        _ = machine.ingest(envelope(sequence: 2, event: .pause, state: .paused, position: 12_345, speed: 0), receivedAtMs: 2_000)
        expectEqual(machine.position(atMs: 9_000), 12_345, "手机状态机: pause 后位置冻结")
        _ = machine.ingest(envelope(sequence: 3, event: .play, state: .playing, position: 13_000), receivedAtMs: 10_000)
        expectEqual(machine.position(atMs: 11_500), 14_500, "手机状态机: resume 重建锚点")
        _ = machine.ingest(envelope(sequence: 4, event: .seek, state: .playing, position: 80_000), receivedAtMs: 12_000)
        expectEqual(machine.position(atMs: 12_000), 80_000, "手机状态机: seek 立即硬跳")
    }

    do {
        var machine = PhonePlaybackStateMachine()
        _ = machine.ingest(envelope(sequence: 1), receivedAtMs: 1_000)
        let changed = machine.ingest(envelope(sequence: 2, event: .trackChanged, trackId: "track-b", position: 0), receivedAtMs: 2_000)
        expectEqual(changed.clearsPreviousTrack, true, "手机状态机: 切歌先清旧歌词")
        expectEqual(machine.currentTrackId, "track-b", "手机状态机: 切歌后采用新 trackId")
        expectEqual(machine.ingest(envelope(sequence: 3, event: .stop, state: .stopped, position: 5_000, speed: 0), receivedAtMs: 3_000),
                    .clear(.stopped), "手机状态机: stop 清空播放状态")
    }

    do {
        var machine = PhonePlaybackStateMachine()
        _ = machine.ingest(envelope(sequence: 1), receivedAtMs: 1_000)
        expectEqual(machine.connectionStatus(atMs: 3_999), .connected, "手机状态机: 三秒内在线")
        expectEqual(machine.connectionStatus(atMs: 4_000), .unstable, "手机状态机: 三秒进入弱连接")
        expectEqual(machine.connectionStatus(atMs: 9_000), .offline, "手机状态机: 八秒进入离线")
        expectEqual(machine.checkTimeout(atMs: 9_000), .clear(.offline), "手机状态机: 离线首次清理")
        expectEqual(machine.checkTimeout(atMs: 10_000), .none, "手机状态机: 离线清理只发一次")
    }

    do {
        var machine = PhonePlaybackStateMachine()
        _ = machine.ingest(envelope(sequence: 1, position: 10_000, duration: 10_500), receivedAtMs: 1_000)
        expectEqual(machine.position(atMs: 5_000), 10_500, "手机状态机: 外推位置钳制到曲长")
    }

    do {
        var machine = PhonePlaybackStateMachine()
        _ = machine.ingest(envelope(sequence: 1, position: 10_000), receivedAtMs: 1_000)
        _ = machine.ingest(envelope(sequence: 2, position: 11_100), receivedAtMs: 2_000)
        expectEqual(machine.position(atMs: 2_000), 11_000, "手机状态机: 150ms 内误差不扰动锚点")
        _ = machine.ingest(envelope(sequence: 3, position: 12_400), receivedAtMs: 3_000)
        expectEqual(machine.position(atMs: 3_000), 12_000, "手机状态机: 中偏差从原锚点开始平滑")
        expectEqual(machine.position(atMs: 4_000), 13_400, "手机状态机: 中偏差一秒完成收敛")
        _ = machine.ingest(envelope(sequence: 4, position: 20_000), receivedAtMs: 5_000)
        expectEqual(machine.position(atMs: 5_000), 20_000, "手机状态机: 大偏差立即重锚")
    }
}

private extension PhonePlaybackDecision {
    var isAccepted: Bool {
        if case .accept = self { return true }
        return false
    }

    var clearsPreviousTrack: Bool {
        if case let .accept(_, clearPreviousTrack) = self { return clearPreviousTrack }
        return false
    }
}
