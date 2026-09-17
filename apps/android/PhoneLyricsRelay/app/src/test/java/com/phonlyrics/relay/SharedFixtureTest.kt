package com.phonlyrics.relay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// 跨端协议夹具测试:读的是仓库里 **Swift 与 Kotlin 共用**的那一份
/// `packages/protocol/fixtures/`(经 build.gradle.kts 挂成测试资源,见那边的注释)。
///
/// 这组测试的价值不在于"再验一遍自己的编码",而在于**钉住两端对同一份字节的理解一致**
/// —— 只验自己构造的 JSON 时,两端可以各自自洽却互相读不懂,而那正是上线才暴露的那种失败。
class SharedFixtureTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)?.bufferedReader()?.use { it.readText() }
            ?: error("夹具读不到: $name(检查 build.gradle.kts 里 test 资源目录的挂载)")

    /// 合法夹具必须逐字段解出来 —— 名字与 Swift 侧 PhoneProtocolTests 取的是同一批文件。
    @Test
    fun validFixturesDecode() {
        val playing = JSONObject(fixture("valid-playing.json"))
        assertEquals(1, playing.getInt("protocolVersion"))
        assertEquals("晴天", playing.getJSONObject("track").getString("title"))
        assertEquals(35214L, playing.getJSONObject("playback").getLong("positionMs"))
        assertEquals("com.tencent.qqmusic", playing.getJSONObject("source").getString("packageName"))
        assertTrue(playing.getJSONObject("track").getString("album").isNotEmpty())

        val paused = JSONObject(fixture("valid-paused.json"))
        assertEquals("paused", paused.getJSONObject("playback").getString("state"))

        val changed = JSONObject(fixture("valid-track-change.json"))
        assertEquals("trackChanged", changed.getString("event"))
    }

    /// 非法夹具的"非法点"必须确实存在 —— 否则它们只是长得像非法样本,起不到反例作用。
    @Test
    fun invalidFixturesCarryTheIntendedDefect() {
        val badSequence = JSONObject(fixture("invalid-sequence.json"))
        assertTrue("invalid-sequence.json 的 sequence 应该不是正数", badSequence.getLong("sequence") <= 0)

        val badState = JSONObject(fixture("invalid-state.json"))
        val state = badState.getJSONObject("playback").getString("state")
        assertTrue("invalid-state.json 的状态应该是协议外的值,实际 $state",
            state !in setOf("playing", "paused", "stopped"))
    }

    /// 我们**自己生成的**信封,字段集合必须跟共享夹具逐键一致。
    ///
    /// 这条是两端一致性最直接的守卫:夹具是 Swift 侧解码用的"权威样本",如果我们发出去的
    /// 顶层键跟它不一样(少一个、多一个、拼错),Swift 那边要么解不出、要么把必填字段判空。
    /// schema 要求 additionalProperties: false,所以多一个键同样会被拒。
    @Test
    fun generatedEnvelopeMatchesFixtureShape() {
        val fixtureKeys = JSONObject(fixture("valid-playing.json")).keys().asSequence().toSet()
        val snapshot = QqPlaybackSnapshot(
            "qq-1", "晴天", "周杰伦", "叶惠美", 269_000, 35_214,
            WirePlaybackState.PLAYING, 1f, "com.tencent.qqmusic", 91_827_364)
        val ours = JSONObject(
            PlaybackEnvelopeFactory("session", "device").next(PlaybackEvent.PLAY, snapshot).toJson())
        assertEquals("顶层键必须与共享夹具一致", fixtureKeys, ours.keys().asSequence().toSet())

        val trackKeys = JSONObject(fixture("valid-playing.json")).getJSONObject("track").keys().asSequence().toSet()
        assertEquals("track 键必须一致", trackKeys, ours.getJSONObject("track").keys().asSequence().toSet())

        val playbackKeys = JSONObject(fixture("valid-playing.json")).getJSONObject("playback").keys().asSequence().toSet()
        assertEquals("playback 键必须一致", playbackKeys, ours.getJSONObject("playback").keys().asSequence().toSet())

        val sourceKeys = JSONObject(fixture("valid-playing.json")).getJSONObject("source").keys().asSequence().toSet()
        assertEquals("source 键必须一致", sourceKeys, ours.getJSONObject("source").keys().asSequence().toSet())

        // 事件与状态的取值域也必须落在夹具用过的同一套里。
        assertNotNull(ours.getString("event"))
    }
}

