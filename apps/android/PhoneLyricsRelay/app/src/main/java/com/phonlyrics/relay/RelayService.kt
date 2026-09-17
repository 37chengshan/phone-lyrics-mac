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
        getSharedPreferences("relay", MODE_PRIVATE).edit().putBoolean("running", false).apply()
        super.onDestroy()
    }

    private fun publish(snapshot: QqPlaybackSnapshot) {
        val event = PlaybackEventDeriver.derive(previous, snapshot, snapshot.capturedAtMonotonicMs)
        if (event == PlaybackEvent.HEARTBEAT && previous != null &&
            snapshot.capturedAtMonotonicMs - previous!!.capturedAtMonotonicMs < 700) return
        previous = snapshot
        queue.enqueue(factory.next(event, snapshot))
        updateNotification("同步中 · ${snapshot.title} · ${snapshot.artist}")
        // 把当前曲目发布给界面(2026-09-17)。用户实测反馈"状态显示不完整":原来界面上只有
        // 一个笼统的"运行中",看不到**正在同步哪首歌** —— 而这恰恰是判断"到底通没通"最直接的
        // 证据。走 SharedPreferences 而不是 LocalBroadcast:Activity 可能在后台上被系统重建,
        // 重建后读一次就能拿到最新值,不需要收发配对的时序假设。
        RelayLog.publishNowPlaying(applicationContext, snapshot.title, snapshot.artist,
            snapshot.state == WirePlaybackState.PLAYING)
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
