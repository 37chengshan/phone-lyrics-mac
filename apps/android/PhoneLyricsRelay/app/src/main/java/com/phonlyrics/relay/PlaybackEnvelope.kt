package com.phonlyrics.relay

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

enum class WirePlaybackState(val wire: String) { PLAYING("playing"), PAUSED("paused"), STOPPED("stopped") }
enum class PlaybackEvent(val wire: String) {
    TRACK_CHANGED("trackChanged"), PLAY("play"), PAUSE("pause"), SEEK("seek"), STOP("stop"), HEARTBEAT("heartbeat")
}

data class QqPlaybackSnapshot(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val state: WirePlaybackState,
    val speed: Float,
    val packageName: String,
    val capturedAtMonotonicMs: Long,
    /// 当前歌词行(2026-09-19)。**只在本机显示用,不进协议**。
    ///
    /// 来源是 QQ 音乐通知栏那一行(见 QqMetadataResolver.lyricLine)。它本来就在这台手机上,
    /// 也不该发给 Mac:Mac 有自己的一整套歌词解析,收到这个反而会跟它的结果打架。
    /// 默认 null —— 非歌词模式下没有这个信息,各测试构造点也不必逐个改。
    val lyricLine: String? = null,
)

data class PlaybackEnvelope(
    val sessionId: String,
    val sequence: Long,
    val event: PlaybackEvent,
    val snapshot: QqPlaybackSnapshot,
    val deviceId: String,
) {
    fun toJson(): String = JSONObject().apply {
        put("protocolVersion", 1)
        put("sessionId", sessionId)
        put("sequence", sequence)
        put("event", event.wire)
        put("track", JSONObject().apply {
            put("trackId", snapshot.trackId)
            put("title", snapshot.title)
            put("artist", snapshot.artist)
            put("album", snapshot.album)
            put("durationMs", snapshot.durationMs.coerceAtLeast(0))
        })
        put("playback", JSONObject().apply {
            put("state", snapshot.state.wire)
            put("positionMs", snapshot.positionMs.coerceAtLeast(0))
            put("speed", snapshot.speed.coerceIn(0f, 4f).toDouble())
            put("capturedAtMonotonicMs", snapshot.capturedAtMonotonicMs)
        })
        put("source", JSONObject().apply {
            put("packageName", snapshot.packageName)
            put("deviceId", deviceId)
        })
    }.toString()
}

class PlaybackEnvelopeFactory(
    private val sessionId: String,
    private val deviceId: String,
) {
    private val sequence = AtomicLong(0)
    fun next(event: PlaybackEvent, snapshot: QqPlaybackSnapshot): PlaybackEnvelope =
        PlaybackEnvelope(sessionId, sequence.incrementAndGet(), event, snapshot, deviceId)
}

object PlaybackEventDeriver {
    fun derive(previous: QqPlaybackSnapshot?, current: QqPlaybackSnapshot, nowMs: Long): PlaybackEvent {
        if (previous == null || previous.trackId != current.trackId) return PlaybackEvent.TRACK_CHANGED
        if (current.state == WirePlaybackState.STOPPED && previous.state != WirePlaybackState.STOPPED) return PlaybackEvent.STOP
        if (current.state == WirePlaybackState.PAUSED && previous.state == WirePlaybackState.PLAYING) return PlaybackEvent.PAUSE
        if (current.state == WirePlaybackState.PLAYING && previous.state != WirePlaybackState.PLAYING) return PlaybackEvent.PLAY
        val elapsed = (nowMs - previous.capturedAtMonotonicMs).coerceAtLeast(0)
        val predicted = if (previous.state == WirePlaybackState.PLAYING)
            previous.positionMs + (elapsed * previous.speed).toLong() else previous.positionMs
        return if (kotlin.math.abs(current.positionMs - predicted) > 600) PlaybackEvent.SEEK else PlaybackEvent.HEARTBEAT
    }
}
