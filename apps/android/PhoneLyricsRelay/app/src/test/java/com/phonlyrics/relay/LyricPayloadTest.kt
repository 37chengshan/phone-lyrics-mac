package com.phonlyrics.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 歌词线格式的测试(2026-09-19)。
 *
 * ## 为什么单独立一个文件
 *
 * 这一层是**两端之间的契约**:Mac 写的键名(current / next / translation / romanization)
 * 与手机读的必须逐字对上。一边改了名、另一边读旧名,两端的测试各自都过,线上表现却永远是
 * "没有歌词" —— 而那种故障从两端任何一侧看都像是对方的问题。
 *
 * ⚠️ 里面的响应体样本是**照着 Mac 侧真实产出的形状写的**(见 PhoneHTTPRouter.route 与
 * PhonePlaybackService.refreshLyricSnapshot)。改任何一边的字段名时,这里应当跟着红。
 */
class LyricPayloadTest {

    private val client = RelayApiClient("127.0.0.1", 8765, "token")

    /** Mac 的正常响应:ok/sequence 是本来就有的,lyric 是这一版新加的。 */
    @Test
    fun parsesFullLyricPayload() {
        val body = """
            {"ok":true,"sequence":42,"lyric":{"current":"不用问漫天的大雪",
            "next":"也知来路 我失去过盛夏","translation":"Ask not the snow",
            "romanization":"bu yong wen","title":"大小孩","artist":"张韶涵"}}
        """.trimIndent()
        val lyric = client.parseLyric(body)
        assertEquals("当前行", "不用问漫天的大雪", lyric?.current)
        assertEquals("下一句", "也知来路 我失去过盛夏", lyric?.next)
        assertEquals("译文", "Ask not the snow", lyric?.translation)
        assertEquals("罗马音", "bu yong wen", lyric?.romanization)
        assertEquals("歌名", "大小孩", lyric?.title)
        assertEquals("歌手", "张韶涵", lyric?.artist)
    }

    /** 副行取"译文优先、没有译文才用罗马音" —— 界面只认这一条规则。 */
    @Test
    fun secondaryPrefersTranslation() {
        val withBoth = LyricPayload("行", null, "译文", "roman", null, null)
        assertEquals("两者都有时用译文", "译文", withBoth.secondary)
        val romanOnly = LyricPayload("行", null, null, "roman", null, null)
        assertEquals("只有罗马音时用它", "roman", romanOnly.secondary)
        val neither = LyricPayload("行", null, null, null, null, null)
        assertNull("都没有时是 null,不是空串", neither.secondary)
    }

    /**
     * 没有 lyric 键(没在播 / 这首歌没词 / Mac 还没搜到)时给 null。
     *
     * 这是**最常见的一种**,不是异常路径:Mac 换歌之后要先搜一遍,那段时间每次都走这里。
     * 给 null 而不是空对象,手机才会按"保留上一帧"处理而不是把界面清掉。
     */
    @Test
    fun missingLyricKeyYieldsNull() {
        assertNull("只有 ok/sequence", client.parseLyric("""{"ok":true,"sequence":1}"""))
        assertNull("空 body", client.parseLyric(""))
        assertNull("null body", client.parseLyric(null))
    }

    /** lyric 存在但 current 是空的:没有可显示的行,整份丢掉而不是给个空壳。 */
    @Test
    fun lyricWithoutCurrentIsDropped() {
        assertNull("current 空串", client.parseLyric("""{"ok":true,"lyric":{"current":""}}"""))
        assertNull("current 缺失", client.parseLyric("""{"ok":true,"lyric":{"next":"下一句"}}"""))
    }

    /** 坏 JSON 不能抛 —— 歌词是附加信息,拿不到不该影响同步本身。 */
    @Test
    fun malformedBodyIsSwallowed() {
        assertNull("截断的 JSON", client.parseLyric("""{"ok":true,"lyric":{"""))
        assertNull("根本不是 JSON", client.parseLyric("<html>401</html>"))
        assertNull("lyric 不是对象", client.parseLyric("""{"ok":true,"lyric":"x"}"""))
    }

    /**
     * Mac 省略空字段时(它把空串一律不放进去,见 refreshLyricSnapshot),
     * 缺的那几个要落到 null。
     */
    @Test
    fun absentOptionalFieldsBecomeNull() {
        val lyric = client.parseLyric("""{"ok":true,"lyric":{"current":"只有这一行"}}""")
        assertEquals("当前行拿到", "只有这一行", lyric?.current)
        assertNull("下一句缺失", lyric?.next)
        assertNull("译文缺失", lyric?.translation)
        assertNull("罗马音缺失", lyric?.romanization)
        assertNull("副行因此为空", lyric?.secondary)
    }
}
