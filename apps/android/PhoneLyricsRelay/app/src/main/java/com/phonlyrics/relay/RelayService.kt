package com.phonlyrics.relay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import java.util.UUID

class RelayService : Service() {
    companion object {
        const val EXTRA_IP = "ip"
        const val EXTRA_PORT = "port"
        private const val HEARTBEAT_MS = 1_000L
        private const val NOTIF_ID = 42
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var capture: QqPlaybackCapture
    private lateinit var api: RelayApiClient
    private lateinit var queue: RelayEventQueue
    private lateinit var factory: PlaybackEnvelopeFactory
    private lateinit var discovery: MacDiscoveryManager
    private var previous: QqPlaybackSnapshot? = null
    private var wakeLock: PowerManager.WakeLock? = null
    /// 最近一次发送失败属于哪一类(401 = 令牌失效 / unreachable = 连不上 / other = 其它)。
    /// 给"重试耗尽"那一刻的失败计数用 —— 见下面 sender 里的注释。
    @Volatile
    private var lastFailureKind = "other"

    private val heartbeat = object : Runnable {
        override fun run() {
            // 心跳有两个用途:给 Activity 判断"服务到底还在不在"(见 RelayLog.isServiceAlive),
            // 以及让采集在没歌时也定期重扫一次会话。
            RelayLog.heartbeat(this@RelayService)
            capture.currentSnapshot()?.let(::publish) ?: capture.refresh()
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 服务可能先于 Activity 被系统拉起(sticky 重启),那时通知渠道还不存在 ——
        // startForeground 会因为渠道缺失直接抛异常。渠道创建放在这里才是安全的。
        createChannelIfNeeded()
        RelayLog.install(this)
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        val deviceId = prefs.getString("deviceId", null) ?: Settings.Secure.getString(
            contentResolver, Settings.Secure.ANDROID_ID).orEmpty().ifBlank { UUID.randomUUID().toString() }
        prefs.edit().putString("deviceId", deviceId).apply()
        factory = PlaybackEnvelopeFactory(UUID.randomUUID().toString(), deviceId)
        api = RelayApiClient(prefs.getString("ip", "").orEmpty(), prefs.getInt("port", 8765),
            SecureTokenStore(this).load().orEmpty())
        // sender 走带计时的那个,顺手把统计记下来(给状态页的仪表盘用,见 RelayLog.noteSendResult)。
        // ⚠️ RelayEventQueue 是**单线程串行**的(它的设计就是这样,保证事件不乱序),所以这里
        // 对 SharedPreferences 的"读-改-写"天然不会并发,不需要额外加锁。
        queue = RelayEventQueue(sender = { envelope ->
            val outcome = api.sendDetailed(envelope)
            // 歌词随响应回来(2026-09-19):Mac 那边有十个源的解析引擎与缓存,歌词由它给。
            // 手机这边原先想从 QQ 音乐的通知栏元数据里捞,那条路不可靠 —— 它取决于
            // QQ 音乐自己的通知行为,实测拿不到。现在改成"解析在 Mac、显示在手机"。
            outcome.lyric?.let { lyric ->
                RelayLog.publishLyric(applicationContext, lyric)
            }
            // ⚠️ 成功才在这里记。失败**不能**记在这儿:RelayEventQueue 对瞬时失败会重试
            // (最多 4 次,指数退避),每试一次都会调 sender —— 原来把失败也记在这里,于是网络抖
            // 一下界面上就出现"失败 3",而那条事件其实最终送出去了。失败的计数交给下面的
            // onExhausted,由队列在重试真正用尽时回调一次。
            if (outcome.ok) RelayLog.noteSendSuccess(applicationContext, outcome.latencyMs)
            else lastFailureKind = when {
                outcome.authRejected -> "auth"
                outcome.unreachable -> "unreachable"
                else -> "other"
            }
            outcome.ok
        }, onExhausted = { envelope ->
            // 重试用尽 = 这一条确实没送达。这里才记失败,并且把**最近一次的故障类型**留下来
            // (401 还是连不上),让界面能给出对症的提示。
            RelayLog.noteSendFailure(applicationContext, lastFailureKind)
        })
        capture = QqPlaybackCapture(this, handler, ::publish)
        discovery = MacDiscoveryManager(this, { mac ->
            val expected = prefs.getString("macStableId", null)
            if (expected == null || expected == mac.stableId) {
                api.host = mac.host
                api.port = mac.port
                prefs.edit().putString("ip", mac.host).putInt("port", mac.port).apply()
            }
        }, {})
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneLyrics:relay").apply {
            setReferenceCounted(false)
            // ⚠️ 这里**必须**容错。2026-09-17 真机实测:manifest 少一条 WAKE_LOCK 时,
            // acquire() 抛 SecurityException,而它发生在 onCreate 里、没有任何人接 —— 整个
            // 进程当场死掉,表现是"点开始同步 App 就没了"。manifest 已经补上那个权限,但
            // 这道兜底仍然要留:唤醒锁只是**优化**(让 CPU 在锁屏时别睡太死),拿不到就不拿,
            // 服务照常跑 —— 总比为了一个可选优化把整个同步功能带走强。
            runCatching { acquire(12 * 60 * 60 * 1000L) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        intent?.getStringExtra(EXTRA_IP)?.takeIf { it.isNotBlank() }?.let { api.host = it }
        intent?.getIntExtra(EXTRA_PORT, -1)?.takeIf { it in 1..65535 }?.let { api.port = it }
        api.token = SecureTokenStore(this).load().orEmpty()
        if (api.host.isBlank() || api.token.isBlank()) {
            RelayLog.note("service start aborted: host=${api.host.ifBlank { "(空)" }} " +
                "token=${if (api.token.isBlank()) "(空)" else "有"}")
            stopSelf()
            return START_NOT_STICKY
        }
        // startForeground 本身也可能抛(通知权限被撤销、渠道被用户关掉),接住它 ——
        // 否则又是一个"点了就闪退"。
        try {
            startForeground(NOTIF_ID, notification("等待 QQ 音乐…"))
        } catch (error: Exception) {
            RelayLog.note("startForeground FAILED: ${error.javaClass.simpleName}: ${error.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            capture.start()
            RelayLog.note("capture started")
        } catch (error: SecurityException) {
            RelayLog.note("capture needs notification access")
            updateNotification("请先开启通知使用权")
        }
        discovery.start()
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
        prefs.edit().putString("ip", api.host).putInt("port", api.port).putBoolean("running", true).apply()
        // 新一次同步从零开始计数,理由见 RelayLog.resetSendStats 的注释。
        RelayLog.resetSendStats(this)
        RelayLog.note("relay running -> ${api.host}:${api.port}")
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        runCatching { capture.stop() }
        discovery.stop()
        queue.close()
        wakeLock?.let { if (it.isHeld) it.release() }
        // 曲目状态也要清(2026-09-17 审查发现)。原来只有 MainActivity.stopRelay() 那条
        // **用户主动停止**的路径会清,而服务被系统回收(低内存、厂商 ROM 清理、用户在上游把
        // 它划掉)走的是这条 onDestroy —— 那条路不清的话,下次打开界面会看到上一次播的那首歌
        // 停在那里,读起来像"还在同步",而实际上服务早就没了。
        //
        // 判据看心跳而不是 running 布尔:running 在那个场景下仍是 true(没人改过它),
        // 而心跳会随进程一起停 —— 这正是两个信号的区别所在。
        RelayLog.clearNowPlaying(this)
        // 歌词一样(2026-09-19):它是 Mac 回传的,进程一停就不会再有新的,留着会定在最后一句。
        RelayLog.clearLyric(this)
        getSharedPreferences("relay", MODE_PRIVATE).edit().putBoolean("running", false).apply()
        super.onDestroy()
    }

    private fun publish(snapshot: QqPlaybackSnapshot) {
        val event = PlaybackEventDeriver.derive(previous, snapshot, snapshot.capturedAtMonotonicMs)
        // ⚠️ 这道闸只用来**省网络流量**(700ms 内的重复心跳不必再发一条),
        // 绝不能让它在闸门处 `return` 整个函数 ——
        //
        // 2026-09-19 真机故障就出在这儿:下面那两行 UI 发布原本写在 return 之后,
        // 于是事件被判"太快"时,**曲目与歌词也一起不发了**。表现是 Mac 上正常
        // (它有 1 秒心跳兜底)、手机界面却经常收不到更新 —— 用户报的"手机不显示歌曲名"
        // 正是这个。所以闸门只决定"要不要入队发网络",不影响本地界面。
        val tooSoonForNetwork = event == PlaybackEvent.HEARTBEAT && previous != null &&
            snapshot.capturedAtMonotonicMs - previous!!.capturedAtMonotonicMs < 700

        // ⚠️ 歌词换行**必须**带动事件:QQ 音乐每换一句歌词,TITLE 就变一次,而播放状态没变 ——
        // derive() 只看状态与位置,于是仍判成 HEARTBEAT,再被上面那道闸拦掉。
        // 不加这一条的话,开了通知栏歌词的歌在手机上一句都不会动。
        val lyricChanged = previous?.lyricLine != snapshot.lyricLine
        // 换歌时把本机那几行歌词清掉(2026-09-19)。
        //
        // ⚠️ 这一条是必须的,不然后半段会出错:歌词是 Mac 回传的,而它换歌时要先搜一遍,
        // 那段时间它给的是 nil(见 PhoneHTTPRouter.lyricProvider 的注释),手机这边的约定
        // 是"nil 就保留上一帧"。单看那句约定没问题,但**换歌**场景下它等于让上一首的最后
        // 一句一直挂在屏幕上 —— 用户看到的是"新歌在放、歌词是旧歌的"。
        //
        // 手机自己就知道换没换歌(trackId 是它算的),所以这个判断不该去猜 Mac 的时序。
        // 清掉之后界面会显示"Mac 正在为这首歌找歌词…",那才是此刻的真实状态。
        val trackChanged = previous?.trackId != null && previous!!.trackId != snapshot.trackId
        previous = snapshot
        if (trackChanged) RelayLog.clearLyric(applicationContext)
        if (!tooSoonForNetwork || lyricChanged) {
            queue.enqueue(factory.next(event, snapshot))
        }
        updateNotification("同步中 · ${snapshot.title} · ${snapshot.artist}")
        // 把当前曲目发布给界面(2026-09-17)。用户实测反馈"状态显示不完整":原来界面上只有
        // 一个笼统的"运行中",看不到**正在同步哪首歌** —— 而这恰恰是判断"到底通没通"最直接的
        // 证据。走 SharedPreferences 而不是 LocalBroadcast:Activity 可能在后台上被系统重建,
        // 重建后读一次就能拿到最新值,不需要收发配对的时序假设。
        RelayLog.publishNowPlaying(applicationContext, snapshot.title, snapshot.artist,
            snapshot.state == WirePlaybackState.PLAYING)
        // ⚠️ 歌词**不在这里**发布(2026-09-19 改了架构)。
        //
        // 原先从 snapshot.lyricLine 取 —— 那条路赌的是"QQ 音乐会把它正在唱的那句写进通知栏
        // 元数据",而实测拿到的只有真歌名。现在改成:歌词由 Mac 解析(它有十个源、缓存、逐字、
        // 译文),搭在每次发送的**响应**里回来,见下面 sender 里对 outcome.lyric 的处理。
        // 顺手记一条收听(见 ListeningLog 头注)。只在真在播时记,暂停/停止不计时。
        if (snapshot.state == WirePlaybackState.PLAYING) {
            ListeningLog.note(applicationContext, snapshot)
        }
    }

    private fun notification(text: String): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "relay")
            .setContentTitle("手机歌词同步").setContentText(text)
            // 通知图标同步换成自己的(2026-09-17):原来用的是系统播放三角,跟应用图标对不上。
            // ⚠️ 通知的 smallIcon 必须是**单色**图形,系统只取它的 alpha 通道着色 —— 直接用
            // 那个彩色的应用图标会渲染成一坨实心方块,所以这里单独给一份白色剪影版。
            .setSmallIcon(R.drawable.ic_notification).setContentIntent(pending)
            .setOngoing(true).setOnlyAlertOnce(true).build()
    }

    private fun updateNotification(text: String) {
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }

    private fun createChannelIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(
                android.app.NotificationChannel("relay", "手机歌词同步",
                    android.app.NotificationManager.IMPORTANCE_LOW))
        }
    }
}
