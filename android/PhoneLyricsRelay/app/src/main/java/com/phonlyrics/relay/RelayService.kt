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
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class RelayService : Service() {
    companion object {
        const val EXTRA_IP = "ip"
        const val EXTRA_PORT = "port"
        private const val INTERVAL_MS = 1000L
    }

    private lateinit var ip: String
    private var port = 8765
    private val handler = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var lastKey: String? = null

    private val listener =
        MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
            pickController(sessions)
        }

    private val tick = object : Runnable {
        override fun run() {
            pushNowPlaying()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ip = intent?.getStringExtra(EXTRA_IP) ?: ip
        port = intent?.getIntExtra(EXTRA_PORT, port) ?: port

        val notification = buildNotification("推送中 $ip:$port")
        startForeground(1, notification)

        manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        try {
            val componentName = ComponentName(this, NotificationListener::class.java)
            pickController(manager?.getActiveSessions(componentName))
            manager?.addOnActiveSessionsChangedListener(listener, componentName)
        } catch (e: SecurityException) {
            // User must enable Notification Access
        }

        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try {
            manager?.removeOnActiveSessionsChangedListener(listener)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun pickController(sessions: List<MediaController>?) {
        val list = sessions.orEmpty()
        controller = list.firstOrNull { c ->
            c.packageName.contains("qqmusic", ignoreCase = true)
        } ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull()
        lastKey = null
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, "relay")
            .setContentTitle("歌词中继")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun pushNowPlaying() {
        val c = controller ?: return
        val meta = c.metadata ?: return
        val state = c.playbackState
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val pos = state?.position ?: 0L
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val st = when {
            playing -> "playing"
            state?.state == PlaybackState.STATE_PAUSED -> "paused"
            else -> "stopped"
        }

        val key = "$title|$artist|$duration"
        if (key != lastKey) lastKey = key

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
            } catch (_: Exception) {
                // swallow; next tick retries
            }
        }.start()
    }
}
