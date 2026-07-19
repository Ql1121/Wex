package wex.ui.core.feature

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import wex.ui.core.WexStore
import de.robv.android.xposed.XposedBridge
import java.net.HttpURLConnection
import java.net.URL

/**
 * 媒体通知控制。
 * 通知栏/锁屏显示歌曲信息 + 上一曲/暂停/下一曲控制（下拉通知栏即可操作，无需回微信）。
 *
 * 方案A（零侵入核心）+ 补全按钮（方案②）+ 懒初始化（关键点3）：
 *  - 自起每秒 Handler 轮询 MusicCardFeature 状态刷新通知（最多 1 秒延迟）。
 *  - 通知栏三个按钮用自定义 action 广播 + 动态注册 BroadcastReceiver（方案X），使其真正可点。
 *  - MediaSession 回调保留（锁屏/耳机键/系统媒体面板也能控制）。
 *
 * ⚠️ 唯一侵入核心处：MusicCardFeature.loadAndPlay 播放开始时调用本类 onPlaybackStarted() 启动轮询。
 */
object MediaSessionFeature {

    private val M = MusicCardFeature
    private val mh = Handler(Looper.getMainLooper())

    private const val CHANNEL_ID = "wex_music_playback"
    private const val NOTI_ID = 888
    private const val ACTION_PREV = "wex.ui.core.media.PREV"
    private const val ACTION_TOGGLE = "wex.ui.core.media.TOGGLE"
    private const val ACTION_NEXT = "wex.ui.core.media.NEXT"

    private var mediaSession: MediaSession? = null
    private var notificationManager: NotificationManager? = null
    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null

    private var initialized = false
    private var loopRunnable: Runnable? = null

    // 封面缓存（按歌缓存，播放过的歌封面永久保留，切歌不重新下载）
    private val coverCache = HashMap<String, Bitmap>()

    // ==================== 入口：播放开始时由 MusicCardFeature 调用 ====================
    fun onPlaybackStarted() {
        startLoop()
    }

    // ==================== 每秒轮询（方案A） ====================
    private fun startLoop() {
        if (loopRunnable != null) return
        loopRunnable = object : Runnable {
            override fun run() {
                try {
                    if (!initialized) initMediaSession()   // 懒初始化（关键点3）
                    updateMediaSession()
                } catch (_: Exception) {}
                mh.postDelayed(this, 1000)
            }
        }
        mh.post(loopRunnable!!)
    }

    private fun stopLoop() {
        loopRunnable?.let { mh.removeCallbacks(it) }
        loopRunnable = null
    }

    // ==================== 懒初始化 ====================
    private fun initMediaSession() {
        try {
            val ctx = M._act()?.applicationContext ?: return
            appContext = ctx
            notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID, "音乐播放", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "后台音乐播放控制"
                    setShowBadge(false)
                }
                notificationManager?.createNotificationChannel(channel)
            }

            // 动态注册 Receiver（方案X）：接收通知栏按钮广播
            if (receiver == null) {
                receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        when (intent?.action) {
                            ACTION_PREV -> M.playPrev()
                            ACTION_TOGGLE -> M.toggle()
                            ACTION_NEXT -> M.playNext()
                        }
                    }
                }
                val filter = IntentFilter().apply {
                    addAction(ACTION_PREV); addAction(ACTION_TOGGLE); addAction(ACTION_NEXT)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    ctx.registerReceiver(receiver, filter)
                }
            }

            // MediaSession（锁屏/耳机键/系统媒体面板控制）
            mediaSession = MediaSession(ctx, "WexMusic").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() { M.handlePlayButtonClick() }
                    override fun onPause() { M.toggle() }
                    override fun onSkipToNext() { M.playNext() }
                    override fun onSkipToPrevious() { M.playPrev() }
                    override fun onStop() { M.toggle() }
                    override fun onSeekTo(pos: Long) { M.seekTo(pos.toInt()) }
                })
                isActive = true
            }
            initialized = true
            log("MediaSession 初始化成功")
        } catch (e: Exception) {
            log("MediaSession 初始化失败: $e")
        }
    }

    // ==================== 刷新通知 ====================
    private fun updateMediaSession() {
        val session = mediaSession ?: return
        val ctx = appContext ?: return
        val song = M.getCurrentSong() ?: return
        try {
            val duration = M.getDuration()
            val pos = M.getCurrentPos()
            val playing = M.isPlaying()

            // 元数据（含封面，优先复用 MusicCardFeature 共享缓存→零延迟；没有再异步下载）
            val coverKey = song.title + "|" + song.coverUrl
            var cover = coverCache[coverKey]
            if (cover == null && song.coverUrl.isNotEmpty()) {
                cover = M.coverSharedCache[song.coverUrl]
                if (cover != null) coverCache[coverKey] = cover // 同步到本地缓存
            }
            if (cover == null && song.coverUrl.isNotEmpty()) {
                downloadCover(coverKey, song.coverUrl)
            }
            val meta = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration.toLong())
            cover?.let { meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
            session.setMetadata(meta.build())

            // 播放态
            val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setState(
                        if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        pos.toLong(), 1.0f
                    )
                    .setActions(actions)
                    .build()
            )

            // 点击通知回微信
            val contentPi = try {
                val intent = Intent().apply {
                    setClassName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            } catch (_: Exception) { null }

            val builder = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(song.title)
                .setContentText(song.artist + " · WEX 音乐")
                .setSubText(formatTime(pos) + " / " + formatTime(duration))
                .setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(session.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(playing)
            if (contentPi != null) builder.setContentIntent(contentPi)

            // 三个可点按钮（方案②补全：自定义 action 广播 PendingIntent）
            builder.addAction(android.R.drawable.ic_media_previous, "上一首", buildActionPi(ctx, ACTION_PREV, 1))
            builder.addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "暂停" else "播放", buildActionPi(ctx, ACTION_TOGGLE, 2)
            )
            builder.addAction(android.R.drawable.ic_media_next, "下一首", buildActionPi(ctx, ACTION_NEXT, 3))

            notificationManager?.notify(NOTI_ID, builder.build())
        } catch (e: Exception) {
            log("updateMediaSession 失败: $e")
        }
    }

    private fun buildActionPi(ctx: Context, action: String, reqCode: Int): PendingIntent {
        val intent = Intent(action).apply { setPackage("com.tencent.mm") }
        return PendingIntent.getBroadcast(ctx, reqCode, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    // 异步下载封面（首次播放时触发，按歌存入 coverCache，同一首歌绝不重复下载）
    private fun downloadCover(key: String, coverUrl: String) {
        Thread {
            try {
                val conn = URL(coverUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.connect()
                if (conn.responseCode == 200) {
                    val bmp = BitmapFactory.decodeStream(conn.inputStream)
                    if (bmp != null) { coverCache[key] = bmp; log("封面下载成功") }
                }
                conn.disconnect()
            } catch (e: Exception) { log("封面下载失败: ${e.message}") }
        }.start()
    }

    // ==================== 释放 ====================
    fun release() {
        stopLoop()
        try { notificationManager?.cancel(NOTI_ID) } catch (_: Exception) {}
        try { mediaSession?.release() } catch (_: Exception) {}
        mediaSession = null
        try { receiver?.let { appContext?.unregisterReceiver(it) } } catch (_: Exception) {}
        receiver = null
        initialized = false
    }

    private fun formatTime(millis: Int): String {
        val ts = millis / 1000
        return "%02d:%02d".format(ts / 60, ts % 60)
    }

    private fun log(msg: String) {
        XposedBridge.log("WEX: $msg")
        try { WexStore.appendLog(msg) } catch (_: Exception) {}
    }
}