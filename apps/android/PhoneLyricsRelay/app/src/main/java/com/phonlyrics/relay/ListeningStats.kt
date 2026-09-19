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

    data class Summary(
        val totalMs: Long,
        val tracks: List<TrackTotal>,
        val days: List<DayTotal>,
        val recordCount: Int,
    ) {
        val isEmpty: Boolean get() = recordCount == 0
        /// 平均每天听多久(按**有记录的天数**算,不是按窗口长度 —— 没用过的日子不该拉低均值)。
        val averagePerActiveDayMs: Long get() = if (days.isEmpty()) 0L else totalMs / days.size
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

        return Summary(
            totalMs = window.sumOf { it.ms },
            tracks = byTrack,
            days = byDay,
            recordCount = window.size,
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

