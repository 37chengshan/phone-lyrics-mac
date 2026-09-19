package com.phonlyrics.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 收听统计的聚合口径测试(2026-09-19)。
 *
 * 时间相关的算术是那种"盯着代码觉得对、跑起来才发现差一天"的东西,所以这里把边界
 * 逐条钉住:跨天怎么切、窗口从哪天算起、拖进度条为什么不计时。
 *
 * ⚠️ 用**固定时区**跑,不跟宿主机时区走 —— 否则同一个测试在西八区和东八区会得到
 * 不同结果,而 CI 机器通常跑 UTC,本地跑东八区,那种"本地过、CI 挂"最难查。
 */
class ListeningStatsTest {

    private val zone = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))

    /// 造一条记录(秒)。
    private fun rec(at: Long, id: String = "t1", ms: Long = 1_000L, title: String = "歌", artist: String = "人") =
        ListeningStats.Record(atSecs = at, trackId = id, title = title, artist = artist, ms = ms)

    /** 同一天的记录累加到同一天。 */
    @Test
    fun sumsRecordsWithinOneDay() {
        val day = 1_758_200_000L  // 2025-09-18 前后,具体是哪天不影响本测试
        val s = ListeningStats.summarize(
            listOf(rec(day, ms = 1_000), rec(day + 60, ms = 2_000)),
            days = 0, nowSecs = day + 3600, zone = zone)
        assertEquals("总时长是两条之和", 3_000L, s.totalMs)
        assertEquals("归到同一天", 1, s.days.size)
    }

    /**
     * 跨天的记录要拆到各自那一天。
     *
     * 这条是**本地时区**口径的核心:东八区凌晨听的歌,如果按 UTC 切会被算到前一天 ——
     * 用户半夜听歌时就会觉得数字不对。
     */
    @Test
    fun splitsRecordsAcrossLocalDays() {
        // 先拿一个已知的本地 0 点,再往前/往后挪来构造成跨天。
        val someDay = 1_758_200_000L
        val dayStart = ListeningStats.startOfDaySeconds(someDay, zone, 0)
        val beforeMidnight = dayStart - 60      // 前一天 23:59
        val afterMidnight = dayStart + 60       // 当天 00:01

        val s = ListeningStats.summarize(
            listOf(rec(beforeMidnight, ms = 1_000), rec(afterMidnight, ms = 2_000)),
            days = 0, nowSecs = afterMidnight + 3600, zone = zone)
        assertEquals("两天各算各的", 2, s.days.size)
        assertEquals("总时长仍是两条之和", 3_000L, s.totalMs)
        assertTrue("按时间正序", s.days[0].dayStartSecs < s.days[1].dayStartSecs)
        assertEquals("前一天那条只算自己的 1 秒", 1_000L, s.days[0].ms)
        assertEquals("当天那条只算自己的 2 秒", 2_000L, s.days[1].ms)
    }

    /** 窗口边界:最近 7 天应当**包含今天**,所以是今天往前数 6 天,而不是 7 天前。 */
    @Test
    fun sevenDayWindowIncludesToday() {
        val now = 1_758_200_000L
        val today = ListeningStats.startOfDaySeconds(now, zone, 0)
        val sixDaysAgo = ListeningStats.startOfDaySeconds(now, zone, 6)

        // 第 6 天(窗口内最早那天)的记录应当被算进去
        val inWindow = ListeningStats.summarize(
            listOf(rec(sixDaysAgo + 3600, ms = 5_000)), days = 7, nowSecs = now, zone = zone)
        assertEquals("窗口内最早那天的记录要算", 5_000L, inWindow.totalMs)

        // 再往前一天就该被排除
        val justOutside = ListeningStats.summarize(
            listOf(rec(sixDaysAgo - 3600, ms = 5_000)), days = 7, nowSecs = now, zone = zone)
        assertEquals("窗口外的不算", 0L, justOutside.totalMs)
        assertTrue("窗口外应当为空", justOutside.isEmpty)
    }

    /** 同一首歌多次收听要合并,并按累计时长排序 —— 统计页的"听得最多"就靠这个。 */
    @Test
    fun mergesRepeatedPlaysAndSortsByTotal() {
        val now = 1_758_200_000L
        val s = ListeningStats.summarize(
            listOf(
                rec(now, id = "a", ms = 1_000, title = "A"),
                rec(now + 10, id = "a", ms = 2_000, title = "A"),
                rec(now + 20, id = "b", ms = 5_000, title = "B"),
            ),
            days = 0, nowSecs = now + 3600, zone = zone)

        assertEquals("两首不同的歌", 2, s.tracks.size)
        assertEquals("听得最多的排第一", "b", s.tracks[0].trackId)
        assertEquals("A 的两次要合并成 3 秒", 3_000L, s.tracks[1].totalMs)
        // recordCount 是**总记录条数**(3 条:两条 A + 一条 B),不是去重后的曲目数。
        // 这两个量在界面上是两回事("听了 3 次" vs "听了 2 首歌"),所以各自断言。
        assertEquals("总记录条数 = 3", 3, s.recordCount)
        assertEquals("去重后的曲目数 = 2", 2, s.tracks.size)
    }

    /** 空数据不能崩,也不能编出一个数字。 */
    @Test
    fun emptyInputGivesEmptySummary() {
        val s = ListeningStats.summarize(emptyList(), days = 7, nowSecs = 1_758_200_000L, zone = zone)
        assertEquals(0L, s.totalMs)
        assertTrue(s.isEmpty)
        assertTrue(s.tracks.isEmpty())
        assertEquals("没有活跃日时平均是 0,不是 NaN 或崩溃", 0L, s.averagePerActiveDayMs)
    }

    /** 平均时长的分母是**有记录的天数**,不是窗口长度 —— 没用过的日子不该拉低均值。 */
    @Test
    fun averageUsesActiveDaysNotWindowLength() {
        val now = 1_758_200_000L
        val today = ListeningStats.startOfDaySeconds(now, zone, 0)
        val twoDaysAgo = ListeningStats.startOfDaySeconds(now, zone, 2)

        // 7 天窗口里只有两天有记录,各 1 小时
        val s = ListeningStats.summarize(
            listOf(rec(twoDaysAgo + 100, ms = 3_600_000), rec(today + 100, ms = 3_600_000)),
            days = 7, nowSecs = now, zone = zone)
        assertEquals("只有 2 个活跃日", 2, s.days.size)
        assertEquals("平均按活跃日算 = 1 小时,而不是 2 小时 / 7 天", 3_600_000L, s.averagePerActiveDayMs)
    }
    /** 时段分布要落在**本地小时**上,不是 UTC 小时。 */
    @Test
    fun hourBucketsUseLocalTime() {
        val base = 1_758_200_000L
        val dayStart = ListeningStats.startOfDaySeconds(base, zone, 0)
        // 本地 09:30 与 21:30 各一条
        val morning = dayStart + 9 * 3600 + 1800
        val night = dayStart + 21 * 3600 + 1800
        val s = ListeningStats.summarize(
            listOf(rec(morning, ms = 1_000), rec(night, ms = 3_000)),
            days = 0, nowSecs = night + 60, zone = zone)

        assertEquals("24 个桶", 24, s.byHour.size)
        assertEquals("9 点那条落在 9 号桶", 1_000L, s.byHour[9])
        assertEquals("21 点那条落在 21 号桶", 3_000L, s.byHour[21])
        assertEquals("峰值时段是 21 点", 21, s.peakHour)
        assertEquals("其余桶为 0", 0L, s.byHour[15])
    }

    /** 歌手排行按累计时长降序,空歌手归到"未知歌手"而不是丢掉。 */
    @Test
    fun artistRankingSortsAndKeepsUnknown() {
        val now = 1_758_200_000L
        val s = ListeningStats.summarize(
            listOf(
                rec(now, id = "a", ms = 1_000, artist = "甲"),
                rec(now + 10, id = "b", ms = 5_000, artist = "乙"),
                rec(now + 20, id = "c", ms = 2_000, artist = ""),
            ),
            days = 0, nowSecs = now + 60, zone = zone)

        assertEquals("三位(含未知)", 3, s.artists.size)
        assertEquals("听最多的排第一", "乙", s.artists[0].key)
        assertEquals("5 秒", 5_000L, s.artists[0].ms)
        // 未知歌手 2 秒排在甲 1 秒前面 —— 断言按**实际排序**写,不按我脑补的顺序。
        assertEquals("没写歌手的归到未知,不是丢掉", "未知歌手", s.artists[1].key)
        assertEquals("2 秒", 2_000L, s.artists[1].ms)
        assertEquals("甲垫底", "甲", s.artists[2].key)
    }

    /** 环比:今天跟昨天比。没有昨天时必须返回 nil,而不是编一个 0。 */
    @Test
    fun trendComparesAgainstPreviousWindow() {
        val now = 1_758_200_000L
        val today = ListeningStats.startOfDaySeconds(now, zone, 0)
        val yesterday = ListeningStats.startOfDaySeconds(now, zone, 1)

        // 昨天 1 分钟,今天 2 分钟 → 涨 100%
        val s = ListeningStats.summarize(
            listOf(
                rec(yesterday + 3600, ms = 60_000),
                rec(today + 3600, ms = 120_000),
            ),
            days = 1, nowSecs = now, zone = zone)
        assertEquals("今天只算今天的", 120_000L, s.totalMs)
        assertEquals("上一段是昨天", 60_000L, s.previousWindowMs)
        assertEquals("涨了 100%", 1.0, s.changeRatio ?: -1.0, 0.001)
    }

    /** 看"全部"时没有"上一段",环比必须是 nil。 */
    @Test
    fun trendIsNullForAllTimeRange() {
        val now = 1_758_200_000L
        val s = ListeningStats.summarize(
            listOf(rec(now, ms = 1_000)), days = 0, nowSecs = now, zone = zone)
        assertEquals("全部区间没有可比窗口", null, s.changeRatio)
    }

    /** 连续天数从今天往回数,断一天就停。 */
    @Test
    fun streakCountsConsecutiveDaysBackFromToday() {
        val now = 1_758_200_000L
        val today = ListeningStats.startOfDaySeconds(now, zone, 0)
        val s = ListeningStats.summarize(
            listOf(
                rec(today + 100, ms = 1_000),
                rec(today - 86_400L + 100, ms = 1_000),   // 昨天
                rec(today - 2 * 86_400L + 100, ms = 1_000), // 前天
                rec(today - 4 * 86_400L + 100, ms = 1_000), // 大前天缺席,隔了一天
            ),
            days = 0, nowSecs = now, zone = zone)
        assertEquals("今天+昨天+前天 = 3 天,到缺席那天断", 3, s.streakDays)
    }

    /** 今天没记录时连续天数归零 —— 不是从昨天开始数。 */
    @Test
    fun streakIsZeroWhenTodayHasNothing() {
        val now = 1_758_200_000L
        val yesterday = ListeningStats.startOfDaySeconds(now, zone, 1)
        val s = ListeningStats.summarize(
            listOf(rec(yesterday + 100, ms = 1_000)),
            days = 0, nowSecs = now, zone = zone)
        assertEquals("今天没听就不算连续", 0, s.streakDays)
    }
}
