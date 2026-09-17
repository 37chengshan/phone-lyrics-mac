import Foundation
import LyrimuseCore

@MainActor
func runPhoneProtocolTests() {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
    let fixtures = root.appendingPathComponent("packages/protocol/fixtures")

    func data(_ name: String) -> Data {
        (try? Data(contentsOf: fixtures.appendingPathComponent(name))) ?? Data()
    }
    func decodes(_ name: String) -> Bool {
        (try? PhonePlaybackEnvelope.decodeAndValidate(data(name))) != nil
    }

    expectEqual(data("valid-playing.json").isEmpty, false, "手机协议: 找得到共享 fixture")
    expectEqual(decodes("valid-playing.json"), true, "手机协议: playing fixture 合法")
    expectEqual(decodes("valid-paused.json"), true, "手机协议: paused fixture 合法")
    expectEqual(decodes("valid-track-change.json"), true, "手机协议: trackChanged fixture 合法")
    expectEqual(decodes("invalid-sequence.json"), false, "手机协议: sequence 必须为正数")
    expectEqual(decodes("invalid-state.json"), false, "手机协议: 拒绝未知播放状态")

    let valid = try? PhonePlaybackEnvelope.decodeAndValidate(data("valid-playing.json"))
    expectEqual(valid?.track.title, "晴天", "手机协议: 中文曲名无损解码")
    expectEqual(valid?.playback.positionMs, 35_214, "手机协议: 毫秒位置解码")

    func mutation(_ edit: (inout [String: Any]) -> Void) -> Bool {
        guard var object = try? JSONSerialization.jsonObject(with: data("valid-playing.json")) as? [String: Any] else { return false }
        edit(&object)
        guard let encoded = try? JSONSerialization.data(withJSONObject: object) else { return false }
        return (try? PhonePlaybackEnvelope.decodeAndValidate(encoded)) != nil
    }
    expectEqual(mutation { $0["protocolVersion"] = 2 }, false, "手机协议: 拒绝未知版本")
    expectEqual(mutation { $0["sessionId"] = "" }, false, "手机协议: sessionId 不得为空")
    expectEqual(mutation { object in
        var source = object["source"] as! [String: Any]
        source["packageName"] = "com.spotify.music"
        object["source"] = source
    }, false, "手机协议: 只接受 QQ 音乐包名")
    expectEqual(mutation { object in
        var playback = object["playback"] as! [String: Any]
        playback["positionMs"] = -1
        object["playback"] = playback
    }, false, "手机协议: 位置不得为负")
    expectEqual(mutation { object in
        var playback = object["playback"] as! [String: Any]
        playback["speed"] = 4.1
        object["playback"] = playback
    }, false, "手机协议: speed 不得超过 4")
}
