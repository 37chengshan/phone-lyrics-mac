package com.phonlyrics.relay

import java.util.Calendar

/**
 * 收听统计的**纯聚合逻辑**(2026-09-19)。不碰文件、不碰 Android —— 只有数据和算术,
 * 所以能被单元测试直接钉住。
 *
 * 拆出来的理由:统计口径是那种"看一眼代码觉得对、跑起来才发现差一天"的东西(时区、
 * 边界时刻、跨天拆分),放在 Activity 或文件读写里就只能靠真机点,没人会去测。
 */
object ListeningStats {

    /// 一条原始记录:发生在 `atSecs` 这一刻,听了 `ms` 毫秒。
    data class Record(val atSecs: Long, val trackId: String, val title: String, val artist: String, val ms: Long)

    /// 一首歌的累计。
    data class TrackTotal(
        val trackId: String,
        val title: String,
        val artist: String,
        val totalMs: Long,
        val firstAtSecs: Long,
        val lastAtSecs: Long,
    )

    /// 一天的累计。
    data class DayTotal(val dayStartSecs: Long, val ms: Long)

    /// 某个歌手(或时段)的累计。
    data class Slice(val key: String, val ms: Long)

    data class Summary(
        val totalMs: Long,
        val tracks: List<TrackTotal>,
        val days: List<DayTotal>,
        val recordCount: Int,
        /// 24 小时分布。下标 = 本地小时,值 = 毫秒。
        val byHour: List<Long> = List(24) { 0L },
        /// 歌手排行(按时长降序)。
        val artists: List<Slice> = emptyList(),
        /// 上一个等长窗口的总时长,给"环比"用。0 = 没法比(比如看"全部")。
        val previousWindowMs: Long = 0L,
        /// 连续有记录的天数(含今天)。
        val streakDays: Int = 0,
    ) {
        val isEmpty: Boolean get() = recordCount == 0

        /// 平均每天听多久(按**有记录的天数**算,不是按窗口长度 —— 没用过的日子不该拉低均值)。
        val averagePerActiveDayMs: Long get() = if (days.isEmpty()) 0L else totalMs / days.size

        /// 环比变化。nil = 上一个窗口没数据(不是"涨了 100%",别把无说成有)。
        val changeRatio: Double?
            get() {
                if (previousWindowMs <= 0L) return null
                return (totalMs - previousWindowMs).toDouble() / previousWindowMs.toDouble()
            }

        /// 听得最多的那个时段(0-23)。全是 0 时返回 nil。
        val peakHour: Int?
            get() {
                val max = byHour.maxOrNull() ?: 0L
                return if (max <= 0L) null else byHour.indexOf(max)
            }
    }

    /**
     * 汇总最近 `days` 天(含今天)。`days <= 0` 表示全部时间。
     *
     * ⚠️ 按**本地时区**切天:用户说的"今天听了多久"是本地的一天,不是 UTC 的一天。
     * 用 UTC 切的话,东八区凌晨 0-8 点听的东西会被算到前一天 —— 那种错在测试里看不出来,
     * 只有用户半夜听歌时才会觉得"这个数字怎么不对"。
     */
    fun summarize(records: List<Record>, days: Int, nowSecs: Long, zone: Calendar = Calendar.getInstance()): Summary {
        if (records.isEmpty()) return Summary(0L, emptyList(), emptyList(), 0)

        val cutoff = if (days > 0) startOfDaySeconds(nowSecs, zone, daysFromToday = days - 1) else 0L
        val window = records.filter { it.atSecs >= cutoff }
        if (window.isEmpty()) return Summary(0L, emptyList(), emptyList(), 0)

        val byTrack = window.groupBy { it.trackId }.map { (id, rows) ->
            TrackTotal(
                trackId = id,
                title = rows.first().title,
                artist = rows.first().artist,
                totalMs = rows.sumOf { it.ms },
                firstAtSecs = rows.minOf { it.atSecs },
                lastAtSecs = rows.maxOf { it.atSecs },
            )
        }.sortedByDescending { it.totalMs }

        val byDay = window.groupBy { startOfDaySeconds(it.atSecs, zone, 0) }
            .map { (day, rows) -> DayTotal(day, rows.sumOf { it.ms }) }
            .sortedBy { it.dayStartSecs }

        // 时段分布:按本地小时归集。用户想知道"我什么时候听歌最多" ——
        // 这个维度比"哪天听得多"更常被用到(比如判断睡前听得多不多)。
        val hours = MutableList(24) { 0L }
        for (r in window) {
            val cal = Calendar.getInstance(zone.timeZone)
            cal.timeInMillis = r.atSecs * 1000
            hours[cal.get(Calendar.HOUR_OF_DAY)] += r.ms
        }

        // 歌手排行。空歌手归到"未知"而不是丢掉 —— 丢掉会让总时长对不上。
        val byArtist = window.groupBy { it.artist.ifBlank { "未知歌手" } }
            .map { (name, rows) -> Slice(name, rows.sumOf { it.ms }) }
            .sortedByDescending { it.ms }

        // 环比:取**等长**的上一段窗口。看"今天"就跟昨天比,看"7 天"就跟再往前 7 天比。
        // days <= 0(全部)时不比 —— 没有"上一段全部",硬编一个基准就是编数字。
        val previousMs = if (days > 0) {
            val windowStart = startOfDaySeconds(nowSecs, zone, days - 1)
            val previousStart = startOfDaySeconds(nowSecs, zone, days * 2 - 1)
            records.filter { it.atSecs >= previousStart && it.atSecs < windowStart }
                .sumOf { it.ms }
        } else 0L

        // 连续天数:从今天往回数,直到某天没有记录。
        val dayStarts = byDay.map { it.dayStartSecs }.toSet()
        var streak = 0
        var cursor = startOfDaySeconds(nowSecs, zone, 0)
        while (dayStarts.contains(cursor)) {
            streak++
            cursor = startOfDaySeconds(cursor - 86_400L, zone, 0)
        }

        return Summary(
            totalMs = window.sumOf { it.ms },
            tracks = byTrack,
            days = byDay,
            recordCount = window.size,
            byHour = hours,
            artists = byArtist,
            previousWindowMs = previousMs,
            streakDays = streak,
        )
    }

    /// 某一刻所在那一天的 0 点(本地时区),返回秒。`daysFromToday` 用于往回推几天。
    fun startOfDaySeconds(atSecs: Long, zone: Calendar, daysFromToday: Int): Long {
        val cal = Calendar.getInstance(zone.timeZone)
        cal.timeInMillis = atSecs * 1000
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (daysFromToday != 0) cal.add(Calendar.DAY_OF_YEAR, -daysFromToday)
        return cal.timeInMillis / 1000
    }
}

