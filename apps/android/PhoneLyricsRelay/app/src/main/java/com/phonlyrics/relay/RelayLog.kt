package com.phonlyrics.relay

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/// 崩溃与事件的落盘记录 —— 存在的唯一理由是**让闪退可见**。
///
/// 2026-09-17 真机实测踩到的那次:manifest 少一条 WAKE_LOCK,服务 onCreate 抛
/// SecurityException,整个进程当场死。用户看到的是"点一下 App 就没了",而屏幕上、系统
/// 的「应用信息」里都没有任何可读的原因 —— 只能连数据线抓 logcat,那条路对使用者等于
/// 不存在。所以这里把未捕获异常写成一个文件,应用下次打开时能读到。
///
/// 另外兼一个很小的职责:`isServiceAlive()`。服务状态原来只看 SharedPreferences 里的
/// running 布尔,而那个值在进程被杀之后仍然是 true,界面会一直骗用户说"运行中"。
/// 这里用服务自己维护的心跳时间戳判断,超过阈值就当它已经不在跑了。
object RelayLog {
    private const val FILE_NAME = "relay-log.txt"
    private const val HEARTBEAT_KEY = "serviceHeartbeatMs"
    private const val NOW_TITLE_KEY = "nowTitle"
    private const val NOW_ARTIST_KEY = "nowArtist"
    private const val NOW_PLAYING_KEY = "nowPlaying"
    private const val NOW_LYRIC_KEY = "nowLyric"
    private const val SENT_COUNT_KEY = "sentCount"
    private const val FAILED_COUNT_KEY = "failedCount"
    private const val LAST_LATENCY_KEY = "lastLatencyMs"
    private const val LAST_SENT_AT_KEY = "lastSentAtMs"
    private const val LAST_FAILURE_KEY = "lastFailureKind"
    private const val PREFS = "relay"
    private const val ALIVE_WINDOW_MS = 8_000L
    private const val MAX_LINES = 200

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Volatile
    private var appContext: Context? = null

    /// 装崩溃处理器 + 记住 application context(后面 note/heartbeat 都要用)。
    /// 幂等:Activity 与 Service 都会调,重复调用只是换一次处理器。
    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val detail = buildString {
                    append("崩溃 ")
                    append(stamp.format(Date()))
                    append(" ")
                    append(error.javaClass.name)
                    append(": ")
                    append(error.message ?: "(无消息)")
                    append("\n")
                    error.stackTrace.take(12).forEach { append("    at $it\n") }
                }
                write(app, detail)
            } catch (_: Throwable) {
                // 记录崩溃时再崩一次毫无意义,吞掉。
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /// 记一条普通事件,用于"我点了什么、服务有没有被拉起"这条时间线 —— 排查闪退时
    /// 它比任何级别标签都有用:这次就是靠"start requested 之后再没有 start ok"看出来的。
    fun note(line: String) {
        val app = appContext ?: return
        write(app, "${stamp.format(Date())} $line\n")
    }

    /// 服务每次心跳(每秒)调一次。
    fun heartbeat(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(HEARTBEAT_KEY, System.currentTimeMillis()).apply()
    }

    /// 心跳还在阈值内 = 服务确实在跑。进程被杀之后心跳会停,这里返回 false。
    fun isServiceAlive(): Boolean {
        val app = appContext ?: return false
        val last = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(HEARTBEAT_KEY, 0L)
        return last > 0 && System.currentTimeMillis() - last < ALIVE_WINDOW_MS
    }

    /// 把"此刻在同步哪首歌"写给界面(2026-09-17)。
    ///
    /// 原先界面上只有一个笼统的"运行中",用户看不到当前曲目 —— 而那正是判断整条链路通没通
    /// 最直接的证据:标题出现在这里,就说明通知使用权生效、QQ 音乐被认出来了、采集在跑。
    /// 用 SharedPreferences 而不是广播:Activity 被系统重建后读一次就能拿到最新值,
    /// 不依赖"它在场时才收得到"这种时序假设。
    fun publishNowPlaying(context: Context, title: String, artist: String, playing: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(NOW_TITLE_KEY, title)
            .putString(NOW_ARTIST_KEY, artist)
            .putBoolean(NOW_PLAYING_KEY, playing)
            .apply()
    }

    /// 发布当前歌词行(2026-09-19)。
    ///
    /// 跟 publishNowPlaying 分开两个方法而不是加个参数:歌词行的更新频率比曲目信息高得多
    /// (每句一次),而曲目信息几秒才动一回。合在一起会让曲目那几个值每句歌词都被重写一遍,
    /// 界面按值比较的"变了才动"逻辑就白做了。
    fun publishLyric(context: Context, line: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(NOW_LYRIC_KEY, line)
            .apply()
    }

    /// 读当前歌词行。空串 = 此刻没有歌词可显示(歌没播、或那首歌没有通知栏歌词)。
    fun currentLyric(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(NOW_LYRIC_KEY, "").orEmpty()

    /// 清掉当前曲目(停止同步时)。不清的话下次打开界面会显示上一首歌,像是还在播。
    fun clearNowPlaying(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(NOW_TITLE_KEY).remove(NOW_ARTIST_KEY).remove(NOW_PLAYING_KEY)
            .apply()
    }

    /// 界面读当前曲目。三项一起返回,免得调用方分三次读、拼出一个半新半旧的组合。
    fun nowPlaying(context: Context): Triple<String, String, Boolean> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Triple(
            prefs.getString(NOW_TITLE_KEY, "").orEmpty(),
            prefs.getString(NOW_ARTIST_KEY, "").orEmpty(),
            prefs.getBoolean(NOW_PLAYING_KEY, false),
        )
    }

    // MARK: - 链路统计(2026-09-17,给状态页的仪表盘用)
    //
    // 用户要求"仪表盘"。原来这些数字**一个都没记** —— 发送成功没成功、推送过多少条、
    // 往返多久,全都没有,所以界面只能给"运行中"这种非黑即白的结论,用户看不出它是不是
    // 真在正常工作。这里只记最少的四个量,每个都对应一个用户真会问的问题:
    //
    //   sent / failed   它到底送出去了吗?
    //   lastLatencyMs   局域网通不通、卡不卡?
    //   lastSentAtMs    现在还在送吗?
    //
    // ⚠️ 刻意**不记**失败原因、URL 或响应体:那些可能带上 Authorization 头或设备标识。
    // 这里全是不敏感的计数与时长。
    /// 一条事件**成功送达**。
    ///
    /// ⚠️ 只有成功才在这里记。失败**不能**跟它混在一个入口:发送队列对瞬时失败会重试,每试一次
    /// 都会调一次发送 —— 失败要按"这条到底有没有送达"算,而那个判断只有队列知道(见
    /// RelayEventQueue 的 onExhausted)。混在一起的后果是网络抖一下,界面上就出现"失败 3"。
    fun noteSendSuccess(context: Context, latencyMs: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(SENT_COUNT_KEY, prefs.getLong(SENT_COUNT_KEY, 0L) + 1)
            .putLong(LAST_SENT_AT_KEY, System.currentTimeMillis())
            .putLong(LAST_LATENCY_KEY, latencyMs)
            // 一旦有成功,上一次的故障类型就作废了 —— 留着会让界面一边显示"链路正常"
            // 一边提示"令牌失效",而那是刚才的事、已经自愈了。
            .remove(LAST_FAILURE_KEY)
            .apply()
    }

    /// 一条事件**重试耗尽仍未送达**。`kind` 见 RelayFailureKind。
    ///
    /// 不清 lastLatency:那一次的耗时不代表链路质量(多半是超时打满 1500ms),留着反而误导;
    /// 界面会另外用 lastSentAt 判断"多久没成功了"。
    fun noteSendFailure(context: Context, kind: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(FAILED_COUNT_KEY, prefs.getLong(FAILED_COUNT_KEY, 0L) + 1)
            .putString(LAST_FAILURE_KEY, kind)
            .apply()
    }

    /// 最近一次失败的类别。给界面出对症的提示用 —— "连不上"和"令牌失效"用户要做的事
    /// 完全不同(查 Wi-Fi vs 回 Mac 重新配对),笼统说"失败"等于没说。
    fun lastFailureKind(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LAST_FAILURE_KEY, null)

    /// 读统计。跟 nowPlaying 一个路子:一次读齐,免得调用方分几次读拼出个半新半旧的组合。
    fun sendStats(context: Context): SendStats {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SendStats(
            sent = prefs.getLong(SENT_COUNT_KEY, 0L),
            failed = prefs.getLong(FAILED_COUNT_KEY, 0L),
            lastLatencyMs = prefs.getLong(LAST_LATENCY_KEY, -1L),
            lastSentAtMs = prefs.getLong(LAST_SENT_AT_KEY, 0L),
        )
    }

    /// 开新一次同步时清零:否则"这次送出去多少"会被上一次的累计值盖住,用户重启服务后
    /// 看到一个大数字,没法判断这次到底通没通。
    fun resetSendStats(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(SENT_COUNT_KEY).remove(FAILED_COUNT_KEY)
            .remove(LAST_LATENCY_KEY).remove(LAST_SENT_AT_KEY).remove(LAST_FAILURE_KEY)
            .apply()
    }

    /// 链路统计快照。`lastLatencyMs` 为 -1 表示还没有过成功发送。
    data class SendStats(
        val sent: Long,
        val failed: Long,
        val lastLatencyMs: Long,
        val lastSentAtMs: Long,
    ) {
        /// 成功率。没有任何一次尝试时返回 nil —— 界面据此显示"—",而不是编一个 0%。
        val successRate: Double?
            get() {
                val total = sent + failed
                return if (total == 0L) null else sent.toDouble() / total.toDouble()
            }
    }

    /// 读出最近几条(诊断页展示用)。
    fun tail(context: Context, lines: Int = 12): String {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return "暂无记录"
        return try {
            file.readLines().takeLast(lines).joinToString("\n").ifBlank { "暂无记录" }
        } catch (_: Exception) {
            "读不出记录"
        }
    }

    private fun write(context: Context, text: String) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            val kept = if (file.exists()) file.readLines().takeLast(MAX_LINES) else emptyList()
            file.writeText((kept + text).joinToString("\n"))
        } catch (_: Exception) {
            // 落盘失败不该影响主流程。
        }
    }
}
