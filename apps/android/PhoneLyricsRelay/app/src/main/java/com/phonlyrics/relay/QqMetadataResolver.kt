package com.phonlyrics.relay

/**
 * QQ 音乐元数据矫正(2026-09-19 真机排查产物)。
 *
 * ## 症状
 *
 * 手机端链路统计显示 **100% 成功**,Mac 端却一个歌词都没有 —— 连接完全正常,
 * 坏的是**数据本身**。
 *
 * ## 根因
 *
 * QQ 音乐开着「通知栏显示歌词」时,它塞进 MediaSession 的字段布局跟标准不一样:
 *
 * | 字段 | 实际内容 | 例 |
 * | --- | --- | --- |
 * | TITLE  | **当前歌词行**(或制作人员行) | 看我犯错 / 编曲:钱雷 |
 * | ARTIST | **歌名-歌手**拼在一起 | 大小孩-张韶涵 |
 * | ALBUM  | 正常 | 与世之争 |
 *
 * 后果是叠加的两层:
 *  1. 拿「编曲:钱雷」这样的假歌名去搜歌词,永远搜不到;
 *  2. TITLE 每几秒换一行 → 曲目标识跟着变 → Mac 认为"换歌了" → 刚拿到的歌词被清空重搜。
 *     真机上一条都没解析成功(enrich 缓存 29 条记录、0 条有歌词)。
 *
 * ## 判据
 *
 * 不去猜"QQ 音乐是不是开着歌词",而是**找证据**:同一首歌期间(ALBUM 与时长都没变)
 * TITLE 竟然变了 —— 一首歌的歌名不会变,所以那个字段一定不是歌名。
 * 一旦确认,**本会话内一直按这个模式处理**,换首歌也不必重新等一次证据。
 *
 * 另有两条静态判据给"证据出现之前"的第一条事件兜底:TITLE 里带冒号(制作人员行),
 * 且 ARTIST 里带连字符(疑似"歌名-歌手")。两条**同时**成立才按这个模式解析,
 * 避免误伤真名里带连字符的歌手(如 A-Lin)。
 *
 * ⚠️ 全部状态都是"上一轮采样"级别的,不落盘。进程重启后第一首歌要重新攒一次证据
 * (约一秒),之后的歌都直接走对的路。
 */
class QqMetadataResolver {

    private var lastAlbum = ""
    private var lastDurationSec = -1L
    private var lastTitle = ""

    /** 本会话是否已确认处于"通知栏歌词"模式。一旦为真就不再回退。 */
    private var lyricModeConfirmed = false

    /**
     * @param title      给界面与歌词搜索用的歌名。
     * @param artist     同上,歌手。
     * @param identity   曲目身份,用来算 trackId —— **只由不会变的东西构成**。
     *   这是修复的另一半:原来的标识里含 TITLE,而它在歌词模式下每几秒变一次。
     * @param lyricLayout 这一轮是不是按"通知栏歌词"布局解析的(诊断用)。
     */
    data class Resolved(
        val title: String,
        val artist: String,
        val identity: String,
        val lyricLayout: Boolean,
    )

    fun resolve(rawTitle: String, rawArtist: String, rawAlbum: String, durationMs: Long): Resolved {
        val durationSec = Math.round(durationMs / 1000.0)
        val sameSong = rawAlbum.isNotEmpty() &&
            rawAlbum == lastAlbum &&
            lastDurationSec > 0 &&
            durationSec == lastDurationSec

        // ── 证据:同一首歌期间歌名变了 → 那个字段不是歌名 ──
        if (sameSong && rawTitle.isNotEmpty() && lastTitle.isNotEmpty() && rawTitle != lastTitle) {
            lyricModeConfirmed = true
        }
        lastAlbum = rawAlbum
        lastDurationSec = durationSec
        lastTitle = rawTitle

        // ── 静态兜底:证据还没出现时,靠字段形态认 ──
        val artistHasSeparator = rawArtist.any { it in SEPARATORS }
        val titleLooksLikeCredit = rawTitle.contains(FULL_WIDTH_COLON) || rawTitle.contains(HALF_WIDTH_COLON)
        val lyricLayout = lyricModeConfirmed || (titleLooksLikeCredit && artistHasSeparator)

        var title = rawTitle
        var artist = rawArtist
        if (lyricLayout) {
            splitSongAndArtist(rawArtist)?.let { (song, name) ->
                title = song
                artist = name
            }
        }

        // ── 身份:优先用"专辑 + 时长 + 原始歌手字段",这三样在歌词模式下都不变 ──
        // 时长取整秒,避免毫秒抖动把同一首歌判成两首。
        //
        // ⚠️ **身份里绝不包含 TITLE**。这是本次故障的另一半:歌词模式下 TITLE 每几秒变一次,
        // 含它的标识会让 Mac 以为一直在换歌,刚搜到的歌词立刻被清空。
        // 用 rawArtist(原始字段)而不是解析后的 artist:前者在两种模式下都稳定,
        // 后者在歌词模式下是拆出来的歌手名、也稳定,但用原始值更少一层假设。
        // 时长取整秒,避免毫秒抖动把同一首歌判成两首。
        val identity = buildString {
            if (rawAlbum.isNotEmpty()) append(rawAlbum).append('|')
            if (durationSec > 0) append(durationSec).append('|')
            append(rawArtist)
        }

        return Resolved(title, artist, identity, lyricLayout)
    }

    /**
     * 把「歌名-歌手」拆开。**按第一个**分隔符拆:
     * 歌手名自己可能带连字符(A-Lin),按第一个拆才能得到 `歌名` + `A-Lin`。
     * 拆不出两个非空段(比如 `张韶涵` 这种正常歌手名)就返回 null,由调用方原样使用。
     */
    private fun splitSongAndArtist(raw: String): Pair<String, String>? {
        for (index in raw.indices) {
            if (raw[index] !in SEPARATORS) continue
            val song = raw.substring(0, index).trim()
            val name = raw.substring(index + 1).trim()
            if (song.isNotEmpty() && name.isNotEmpty()) return song to name
        }
        return null
    }

    private companion object {
        /** 见过的分隔符。QQ 音乐用半角连字符,其余是防御性的同类字符。 */
        val SEPARATORS = charArrayOf(
            '-',
            '\u2013', // – en dash
            '\u2014', // — em dash
            '\uFF0D', // - 全角连字符
            '\uFE63', // ﹣
            '\uFE58', // ﹘
        )
        const val FULL_WIDTH_COLON = '\uFF1A'
        const val HALF_WIDTH_COLON = ':'
    }
}
