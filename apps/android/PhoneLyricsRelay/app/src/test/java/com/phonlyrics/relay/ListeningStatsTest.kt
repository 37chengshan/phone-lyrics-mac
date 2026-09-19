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
}
