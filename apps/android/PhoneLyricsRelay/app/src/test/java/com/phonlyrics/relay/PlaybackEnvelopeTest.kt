package com.phonlyrics.relay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackEnvelopeTest {
    private fun snapshot(position: Long = 1_000, state: WirePlaybackState = WirePlaybackState.PLAYING) =
        QqPlaybackSnapshot("id-1", "Song", "Singer", "Album", 180_000, position, state, 1f,
            "com.tencent.qqmusic", 10)

    @Test fun envelopeMatchesProtocolV1() {
        val json = PlaybackEnvelopeFactory("session", "device").next(PlaybackEvent.TRACK_CHANGED, snapshot()).toJson()
        val root = JSONObject(json)
        assertEquals(1, root.getInt("protocolVersion"))
        assertEquals(1, root.getLong("sequence"))
        assertEquals("trackChanged", root.getString("event"))
        assertEquals("id-1", root.getJSONObject("track").getString("trackId"))
        assertEquals("playing", root.getJSONObject("playback").getString("state"))
        assertEquals("com.tencent.qqmusic", root.getJSONObject("source").getString("packageName"))
    }

    @Test fun sequenceIsStrictlyIncreasing() {
        val factory = PlaybackEnvelopeFactory("session", "device")
        assertEquals(1, factory.next(PlaybackEvent.HEARTBEAT, snapshot()).sequence)
        assertEquals(2, factory.next(PlaybackEvent.PAUSE, snapshot(state = WirePlaybackState.PAUSED)).sequence)
    }

    @Test fun qqSourceFilterNeverFallsBackToOtherApps() {
        assertTrue(QqPlaybackCapture.isQqMusic("com.tencent.qqmusic"))
        assertTrue(QqPlaybackCapture.isQqMusic("com.tencent.qqmusicpad"))
        assertEquals(false, QqPlaybackCapture.isQqMusic("com.netease.cloudmusic"))
        assertEquals(false, QqPlaybackCapture.isQqMusic("com.spotify.music"))
    }

    @Test fun eventDerivationCoversTrackStateAndSeek() {
        val playing = snapshot()
        assertEquals(PlaybackEvent.TRACK_CHANGED, PlaybackEventDeriver.derive(null, playing, 10))
        assertEquals(PlaybackEvent.PAUSE,
            PlaybackEventDeriver.derive(playing, snapshot(position = 1_200, state = WirePlaybackState.PAUSED), 200))
        assertEquals(PlaybackEvent.SEEK,
            PlaybackEventDeriver.derive(playing, snapshot(position = 9_000), 1_000))
        assertEquals(PlaybackEvent.HEARTBEAT,
            PlaybackEventDeriver.derive(playing, snapshot(position = 2_000), 1_000))
    }

    @Test fun queuePreservesOrderAcrossRetry() {
        val delivered = mutableListOf<Long>()
        var failFirst = true
        val queue = RelayEventQueue(sender = {
            if (failFirst) { failFirst = false; false } else { delivered += it.sequence; true }
        }, sleeper = {}, maxAttempts = 3)
        val factory = PlaybackEnvelopeFactory("session", "device")
        queue.enqueue(factory.next(PlaybackEvent.HEARTBEAT, snapshot()))
        queue.enqueue(factory.next(PlaybackEvent.HEARTBEAT, snapshot(2_000)))
        assertTrue(queue.awaitIdle(2_000))
        assertEquals(listOf(1L, 2L), delivered)
        queue.close()
    }
}
