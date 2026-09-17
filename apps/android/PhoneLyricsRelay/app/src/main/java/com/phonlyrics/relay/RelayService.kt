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
        queue = RelayEventQueue(sender = api::send)
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
        RelayLog.note("relay running -> ${api.host}:${api.port}")
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        runCatching { capture.stop() }
        discovery.stop()
        queue.close()
        wakeLock?.let { if (it.isHeld) it.release() }
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
