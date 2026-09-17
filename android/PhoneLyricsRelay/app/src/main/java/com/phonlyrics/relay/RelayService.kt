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
import java.net.HttpURLConnection
import java.net.URL

class RelayService : Service() {
    companion object {
        const val EXTRA_IP = "ip"
        const val EXTRA_PORT = "port"
        private const val INTERVAL_MS = 1000L
        private const val NOTIF_ID = 42
    }

    private var ip = ""
    private var port = 8765
    private val handler = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastTitle = ""

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
            acquire(10 * 60 * 60 * 1000L) // 10h
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

        startForeground(NOTIF_ID, buildNotification("准备中…"))

        manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        try {
            val cn = ComponentName(this, NotificationListener::class.java)
            pickController(manager?.getActiveSessions(cn))
            manager?.addOnActiveSessionsChangedListener(listener, cn)
        } catch (_: SecurityException) {
            updateNotification("请开启通知使用权")
        }

        handler.removeCallbacks(tick)
        handler.post(tick)
        prefs.edit().putString("ip", ip).putInt("port", port).putBoolean("running", true).apply()
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

    private fun pickController(sessions: List<MediaController>?) {
        val list = sessions.orEmpty()
        controller =
            list.firstOrNull { it.packageName.contains("qqmusic", ignoreCase = true) }
                ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: list.firstOrNull()
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
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun pushNowPlaying() {
        val c = controller ?: run {
            updateNotification("未检测到播放会话 · $ip:$port")
            return
        }
        val meta = c.metadata ?: return
        val state = c.playbackState
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist =
            meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: ""
        val album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val pos = state?.position ?: 0L
        val st = when (state?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            else -> "stopped"
        }

        if (title != lastTitle) {
            lastTitle = title
            updateNotification("$title · $artist")
        }

        val payload = JSONObject().apply {
            put("title", title)
            put("artist", artist)
            put("album", album)
            put("durationMs", duration)
            put("positionMs", pos)
            put("state", st)
            put("source", c.packageName)
            put("ts", System.currentTimeMillis())
        }

        Thread {
            try {
                val url = URL("http://$ip:$port/api/now-playing")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }
}

// local import alias
private typealias NotificationManager = android.app.NotificationManager
