package com.phonlyrics.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 元数据矫正的回归测试。
 *
 * ⚠️ 里面的"歌词模式"样本是 **2026-09-19 真机抓到的原值**,不是编出来的:
 *
 *     title  = 编曲:钱雷        (歌词行 / 制作人员行)
 *     artist = 大小孩-张韶涵      (歌名-歌手)
 *     album  = 与世之争
 *
 * 真机症状是手机端显示 100% 成功、Mac 端一个歌词都没有 —— 连接完全正常,坏的是内容。
 * 这组测试守住的就是"内容"这一层:它坏了不会报错,只会静默地什么都搜不到。
 */
class QqMetadataResolverTest {

    private fun resolve(
        resolver: QqMetadataResolver,
        title: String,
        artist: String,
        album: String = "与世之争",
        durationMs: Long = 293_821L,
    ) = resolver.resolve(title, artist, album, durationMs)

    /** 真机样本:制作人员行 + 「歌名-歌手」,应当被拆成正确的歌名与歌手。 */
    @Test
    fun splitsSongAndArtistFromRealDeviceSample() {
        val resolver = QqMetadataResolver()
        val fixed = resolve(resolver, "编曲:钱雷", "大小孩-张韶涵")
        assertEquals("歌名要从「歌名-歌手」里拆出来", "大小孩", fixed.title)
        assertEquals("歌手同理", "张韶涵", fixed.artist)
        assertTrue("应当判定为歌词模式布局", fixed.lyricLayout)
    }

    /**
     * 这条是本次故障的**核心**:同一首歌期间 TITLE 一直在变(每句歌词换一次),
     * 曲目身份必须**纹丝不动**,否则 Mac 会认为一直在换歌、刚搜到的歌词被反复清空。
     */
    @Test
    fun identityStaysStableWhileLyricLinesChange() {
        val resolver = QqMetadataResolver()
        val first = resolve(resolver, "编曲:钱雷", "大小孩-张韶涵")
        val second = resolve(resolver, "不用问漫天的大雪", "大小孩-张韶涵")
        val third = resolve(resolver, "也知来路 我失去过盛夏", "大小孩-张韶涵")

        assertEquals("歌词换行不能让曲目身份变", first.identity, second.identity)
        assertEquals("再多换几行也不能变", second.identity, third.identity)
        assertEquals("身份里不该出现歌词行", false, first.identity.contains("编曲"))
        assertEquals("身份里也不该出现别的歌词行", false, second.identity.contains("漫天"))
    }

    /** 正常布局(没开通知栏歌词)必须原样穿过,不能被误拆。 */
    @Test
    fun leavesNormalMetadataAlone() {
        val resolver = QqMetadataResolver()
        val fixed = resolve(resolver, "晴天", "周杰伦", album = "叶惠美", durationMs = 269_000L)
        assertEquals("歌名原样", "晴天", fixed.title)
        assertEquals("歌手原样", "周杰伦", fixed.artist)
        assertEquals("不该误判成歌词模式", false, fixed.lyricLayout)
    }

    /**
     * 歌手名自带连字符(如 A-Lin)时,正常布局下**不能**被当成「歌名-歌手」拆开。
     * 静态兜底要求 TITLE 带冒号**且** ARTIST 带分隔符,两条同时成立才动手,
     * 这条测的就是后半条不成立时的行为。
     */
    @Test
    fun doesNotSplitHyphenatedArtistNameInNormalLayout() {
        val resolver = QqMetadataResolver()
        val fixed = resolve(resolver, "给我一个理由忘记", "A-Lin", album = "罪恶感", durationMs = 280_000L)
        assertEquals("歌名原样", "给我一个理由忘记", fixed.title)
        assertEquals("A-Lin 不能被拆成 A 和 Lin", "A-Lin", fixed.artist)
        assertEquals(false, fixed.lyricLayout)
    }

    /**
     * 一旦靠证据确认了歌词模式,**本会话内一直按它处理** —— 换首歌不必重新等一次证据。
     * 证据是:同一首歌(专辑与时长都没变)期间 TITLE 变了 —— 歌名不会变,所以那个字段不是歌名。
     */
    @Test
    fun staysInLyricModeForLaterTracks() {
        val resolver = QqMetadataResolver()
        // 第一首:攒证据(TITLE 在两拍之间变了,而专辑/时长没变)
        resolve(resolver, "编曲:钱雷", "大小孩-张韶涵", album = "与世之争", durationMs = 293_821L)
        resolve(resolver, "不用问漫天的大雪", "大小孩-张韶涵", album = "与世之争", durationMs = 293_821L)
        // 第二首:即使这一拍的 TITLE 恰好不含冒号(纯歌词行),也该继续按歌词模式解析
        val next = resolve(resolver, "只是歌词", "漩涡-孙燕姿", album = "逆光", durationMs = 291_373L)
        assertEquals("换歌后仍按歌词模式解析", "漩涡", next.title)
        assertEquals("孙燕姿", next.artist)
        assertTrue(next.lyricLayout)
    }

    /** 专辑缺失时的兜底身份也必须**不含 TITLE**,否则身份又会被歌词行带跑。 */
    @Test
    fun identityOmitsTitleWhenAlbumMissing() {
        val resolver = QqMetadataResolver()
        val a = resolve(resolver, "第一句歌词", "歌名-歌手", album = "", durationMs = 200_000L)
        val b = resolve(resolver, "第二句歌词", "歌名-歌手", album = "", durationMs = 200_000L)
        assertEquals("没有专辑时身份仍要稳定", a.identity, b.identity)
        assertEquals("身份里不含歌词行", false, a.identity.contains("歌词"))
    }

/**
     * 真名带感叹号之类的符号时不能被误判(2026-09-19 用户确认 CANCELLED! 就是正确歌名)。
     *
     * 静态兜底要求 TITLE 带冒号**且** ARTIST 带分隔符 —— 这条守住前半条不成立时的行为。
     * 之前真机抓到过"标题被当成歌词行"的样本,但那是**另外**一首歌;像下面这种正常元数据
     * 必须原样穿过,否则真歌名会被拿去做"歌名-歌手"拆分,拆出来的东西谁也搜不到。
     */
    @Test
    fun keepsRealTitleContainingPunctuation() {
        val resolver = QqMetadataResolver()
        val fixed = resolve(resolver, "CANCELLED!", "Taylor Swift",
            album = "The Life of a Showgirl", durationMs = 187_000L)
        assertEquals("真歌名原样,不能因为带符号就当成歌词行", "CANCELLED!", fixed.title)
        assertEquals("歌手原样", "Taylor Swift", fixed.artist)
        assertEquals("不该判成歌词模式", false, fixed.lyricLayout)
        assertEquals("非歌词模式下不给歌词行", null, fixed.lyricLine)
    }
}
