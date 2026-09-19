package com.phonlyrics.relay

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 本机收听记录的**读写层**(2026-09-19)。聚合算术在 [ListeningStats] 里,这里只管落盘与读回。
 *
 * ## 为什么不从 Mac 要数据
 *
 * Mac 那边确实有一份收听日志,但它是**为 Last.fm 打卡**服务的:只记"够格计入一次收听"的歌
 * (播够一半时长或四分钟),口径是"上报"。而手机要的是使用时长 —— 连听十秒的也算。
 * 两套口径混用会得到一个既不像打卡、也不像时长的数字。所以在本机自己记。
 *
 * ## 时长怎么算
 *
 * 不按事件条数算,而是按**播放位置推进**算:每次采样带一个 positionMs,与上一次的差值
 * (正、且小于 30 秒)累加。这样暂停不计、后台待机不计、拖动也不会计入被跳过的部分。
 *
 * ⚠️ 30 秒那道闸是必要的:拖进度条时 position 会瞬间跳几十秒,不加限制会把跳过的一整段
 * 都记成"听过";进程被杀后重启同理(那条采样与上一条隔了很久)。
 */
object ListeningLog {
    private const val FILE_NAME = "listening.jsonl"
    private const val MAX_BYTES = 512L * 1024L
    private const val MAX_DELTA_MS = 30_000L

    /// 当前正在累计的那一首 + 上一次采样到的播放位置。进程内状态即可 —— 重启后从下一条
    /// 采样重新起算,最多丢掉一次采样的间隔(一秒),不值得为此落盘。
    private var currentKey: String? = null
    private var lastPositionMs: Long = -1L

    /**
     * 一次采样。**只在真在播时调用**(调用点负责判断),所以这里不必再判播放状态。
     * positionMs 是播放器报的位置,不是墙钟 —— 时长由它的推进算出来。
     */
    @Synchronized
    fun note(context: Context, snapshot: QqPlaybackSnapshot) {
        val key = snapshot.trackId
        val position = snapshot.positionMs

        val previousKey = currentKey
        val previousPosition = lastPositionMs
        currentKey = key
        lastPositionMs = position

        // 换歌了:这一条只用来重置基线,不计时 —— 否则上一首的尾巴会算到新歌头上。
        if (previousKey != key || previousPosition < 0L) return

        val delta = position - previousPosition
        // 倒退(拖回去了)、没动、或跳得太大(拖进度条 / 长时间没采样)都不计时。
        if (delta <= 0L || delta > MAX_DELTA_MS) return

        append(context, JSONObject().apply {
            put("t", System.currentTimeMillis() / 1000)
            put("id", key)
            put("ti", snapshot.title)
            put("ar", snapshot.artist)
            put("ms", delta)
        })
    }

    /// 清空(设置里给用户一个出口)。同时重置基线,避免清完立刻又记上一条。
    @Synchronized
    fun clear(context: Context) {
        currentKey = null
        lastPositionMs = -1L
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    /// 汇总最近 `days` 天(<= 0 表示全部)。
    @Synchronized
    fun summarize(context: Context, days: Int = 0): ListeningStats.Summary =
        ListeningStats.summarize(readAll(context), days, System.currentTimeMillis() / 1000)

    private fun readAll(context: Context): List<ListeningStats.Record> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val obj = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
                val ts = obj.optLong("t", 0L)
                val ms = obj.optLong("ms", 0L)
                if (ts <= 0L || ms <= 0L) return@mapNotNull null
                ListeningStats.Record(
                    atSecs = ts,
                    trackId = obj.optString("id"),
                    title = obj.optString("ti"),
                    artist = obj.optString("ar"),
                    ms = ms,
                )
            }
        }.getOrElse { emptyList() }
    }

    /// 追加一行。超过上限就把文件对折(留最近一半),避免无限增长。
    private fun append(context: Context, obj: JSONObject) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists() && file.length() > MAX_BYTES) {
                val lines = file.readLines()
                file.writeText(lines.takeLast((lines.size / 2).coerceAtLeast(1)).joinToString("\n") + "\n")
            }
            file.appendText(obj.toString() + "\n")
        }
        // 落盘失败不影响主流程 —— 统计是附加价值,同步才是本职。
    }
}

