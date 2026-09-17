package com.phonlyrics.relay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class RelayService : Service() {
    companion object {
        const val EXTRA_IP = "ip"
        const val EXTRA_PORT = "port"
        private const val INTERVAL_MS = 800L
        private const val NOTIF_ID = 42
    }

    private var ip = ""
    private var port = 8765
    private val handler = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastPushTitle = ""
    private var lastError = ""

    private val listener =
        MediaSessionManager.OnActiveSessionsChangedListener { sessions -> pickController(sessions) }

    private val tick = object : Runnable {
        override fun run() {
            pushNowPlaying()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneLyrics:relay").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_IP)?.takeIf { it.isNotBlank() }?.let { ip = it }
        intent?.getIntExtra(EXTRA_PORT, -1)?.takeIf { it in 1..65535 }?.let { port = it }
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        if (ip.isBlank()) ip = prefs.getString("ip", "") ?: ""
        if (ip.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification("启动中 $ip:$port"))

        manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        try {
            val cn = ComponentName(this, NotificationListener::class.java)
            val sessions = manager?.getActiveSessions(cn)
            pickController(sessions)
            manager?.addOnActiveSessionsChangedListener(listener, cn)
            lastError = if (sessions.isNullOrEmpty()) "无播放会话 · 请开通知使用权并播放 QQ 音乐" else ""
        } catch (e: SecurityException) {
            lastError = "未授予通知使用权"
            updateNotification(lastError)
        }

        handler.removeCallbacks(tick)
        handler.post(tick)
        prefs.edit().putString("ip", ip).putInt("port", port).putBoolean("running", true).apply()
        // LAN discovery beacon so phone/Mac can find each other
        startBeacon()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try {
            manager?.removeOnActiveSessionsChangedListener(listener)
        } catch (_: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        getSharedPreferences("relay", MODE_PRIVATE).edit().putBoolean("running", false).apply()
        super.onDestroy()
    }

    /** Prefer QQ Music exclusively when present — never mix with other apps. */
    private fun pickController(sessions: List<MediaController>?) {
        val list = sessions.orEmpty()
        val qq = list.filter {
            it.packageName?.contains("qqmusic", ignoreCase = true) == true ||
                it.packageName == "com.tencent.qqmusic" ||
                it.packageName == "com.tencent.qqmusicpad"
        }
        controller = qq.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: qq.firstOrNull()
            ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull()
        if (controller == null) lastError = "未检测到 QQ 音乐播放"
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, "relay")
            .setContentTitle("歌词中继")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun startBeacon() {
        Thread {
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val payload = "PHONLYRICS:$ip:$port".toByteArray()
                    val addr = InetAddress.getByName("255.255.255.255")
                    while (!Thread.currentThread().isInterrupted) {
                        socket.send(DatagramPacket(payload, payload.size, addr, 8766))
                        Thread.sleep(2000)
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun pushNowPlaying() {
        val c = controller
        if (c == null) {
            updateNotification(if (lastError.isEmpty()) "等待 QQ 音乐…" else lastError)
            // re-scan sessions periodically
            try {
                val cn = ComponentName(this, NotificationListener::class.java)
                pickController(manager?.getActiveSessions(cn))
            } catch (_: Exception) {}
            return
        }
        val meta = c.metadata
        if (meta == null) {
            updateNotification("会话无元数据")
            return
        }
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        if (title.isBlank()) return
        val artist =
            meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: ""
        val album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val state = c.playbackState
        val pos = state?.position ?: 0L
        val st = when (state?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            else -> "stopped"
        }

        val label = "$title · $artist"
        if (label != lastPushTitle) {
            lastPushTitle = label
            updateNotification("推送 $label")
        }

        val payload = JSONObject().apply {
            put("title", title)
            put("artist", artist)
            put("album", album)
            put("durationMs", duration)
            put("positionMs", pos)
            put("state", st)
            put("source", c.packageName ?: "unknown")
            put("ts", System.currentTimeMillis())
        }

        Thread {
            try {
                val url = URL("http://$ip:$port/api/now-playing")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 1200
                conn.readTimeout = 1200
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                val code = conn.responseCode
                conn.disconnect()
                lastError = if (code == 200) "" else "HTTP $code"
            } catch (e: Exception) {
                lastError = e.message ?: "网络失败"
            }
        }.start()
    }
}
