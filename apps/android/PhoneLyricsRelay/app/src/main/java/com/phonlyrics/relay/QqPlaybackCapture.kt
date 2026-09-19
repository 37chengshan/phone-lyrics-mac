package com.phonlyrics.relay

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock

class QqPlaybackCapture(context: Context, private val callbackHandler: Handler,
                        private val onChanged: (QqPlaybackSnapshot) -> Unit) {
    private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent = ComponentName(context, NotificationListener::class.java)
    private var controller: MediaController? = null
    /// 元数据矫正器(2026-09-19)。QQ 音乐开「通知栏显示歌词」时会把歌词行塞进 TITLE、
    /// 把「歌名-歌手」塞进 ARTIST,直接发出去会让 Mac 永远搜不到歌词。判定与解析的完整
    /// 来龙去脉见 QqMetadataResolver 的头注。
    private val resolver = QqMetadataResolver()
    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { currentSnapshot()?.let(onChanged) }
        override fun onMetadataChanged(metadata: MediaMetadata?) { currentSnapshot()?.let(onChanged) }
        override fun onSessionDestroyed() { selectController(activeSessions()) }
    }
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { selectController(it) }

    fun start() {
        selectController(activeSessions())
        manager.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent, callbackHandler)
    }
    fun stop() {
        controller?.unregisterCallback(controllerCallback)
        manager.removeOnActiveSessionsChangedListener(sessionsChanged)
        controller = null
    }
    fun refresh() = selectController(activeSessions())

    fun currentSnapshot(nowMs: Long = SystemClock.elapsedRealtime()): QqPlaybackSnapshot? {
        val c = controller ?: return null
        if (!isQqMusic(c.packageName)) return null
        val metadata = c.metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        if (title.isEmpty()) return null
        val artist = (metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty().trim()
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty().trim()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0)
        // 先让矫正器看一眼:它在"通知栏歌词"模式下会把歌名/歌手拆正,并给出一个
        // **不会被歌词行带跑**的曲目身份(见 QqMetadataResolver.resolve 的注释)。
        val fixed = resolver.resolve(title, artist, album, duration)
        val state = c.playbackState
        val wireState = when (state?.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> WirePlaybackState.PLAYING
            PlaybackState.STATE_PAUSED -> WirePlaybackState.PAUSED
            else -> WirePlaybackState.STOPPED
        }
        val speed = if (wireState == WirePlaybackState.PLAYING) (state?.playbackSpeed ?: 1f).coerceIn(0f, 4f) else 0f
        val age = (nowMs - (state?.lastPositionUpdateTime ?: nowMs)).coerceAtLeast(0)
        val rawPosition = (state?.position ?: 0).coerceAtLeast(0)
        val position = if (wireState == WirePlaybackState.PLAYING) rawPosition + (age * speed).toLong() else rawPosition
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.trim().orEmpty()
        // ⚠️ 身份优先用 mediaId(QQ 音乐会给,且跟显示无关);没有才用矫正器算的那个。
        // **绝不能**退回含 TITLE 的旧公式 —— 歌词模式下 TITLE 每几秒变一次,那种标识会让
        // Mac 以为一直在换歌,刚搜到的歌词立刻被清空(这正是本次故障的另一半)。
        val trackId = mediaId.ifEmpty { fixed.identity }
        return QqPlaybackSnapshot(trackId, fixed.title, fixed.artist, album, duration,
            position.coerceAtMost(if (duration > 0) duration else Long.MAX_VALUE), wireState, speed,
            c.packageName, nowMs)
    }

    private fun activeSessions(): List<MediaController> = manager.getActiveSessions(listenerComponent).orEmpty()
    private fun selectController(sessions: List<MediaController>?) {
        val qq = sessions.orEmpty().filter { isQqMusic(it.packageName) }
        val next = qq.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: qq.firstOrNull()
        if (next?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(controllerCallback)
        controller = next
        next?.registerCallback(controllerCallback, callbackHandler)
        currentSnapshot()?.let(onChanged)
    }

    companion object {
        fun isQqMusic(packageName: String?): Boolean = packageName?.startsWith("com.tencent.qqmusic") == true
    }
}
