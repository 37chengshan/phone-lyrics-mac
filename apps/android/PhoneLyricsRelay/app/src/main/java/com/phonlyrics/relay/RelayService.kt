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
            capture.currentSnapshot()?.let(::publish) ?: capture.refresh()
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        intent?.getStringExtra(EXTRA_IP)?.takeIf { it.isNotBlank() }?.let { api.host = it }
        intent?.getIntExtra(EXTRA_PORT, -1)?.takeIf { it in 1..65535 }?.let { api.port = it }
        api.token = SecureTokenStore(this).load().orEmpty()
        if (api.host.isBlank() || api.token.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, notification("等待 QQ 音乐…"))
        try { capture.start() } catch (_: SecurityException) { updateNotification("请先开启通知使用权") }
        discovery.start()
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
        prefs.edit().putString("ip", api.host).putInt("port", api.port).putBoolean("running", true).apply()
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
    }

    private fun notification(text: String): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "relay")
            .setContentTitle("手机歌词同步").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play).setContentIntent(pending)
            .setOngoing(true).setOnlyAlertOnce(true).build()
    }

    private fun updateNotification(text: String) {
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }
}
