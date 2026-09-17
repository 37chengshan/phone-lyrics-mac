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
