package wex.ui.core.feature

import android.app.Activity
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import wex.ui.core.WexStore
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 首页音乐卡片（完整：卡片 UI + MediaPlayer 播放 + 搜歌 + 历史 + 播放模式）。
 * 详情页/悬浮歌词/MediaSession 后续单独接入（本文件先占位）。
 */
object MusicCardFeature {

    // 歌曲数据
    class MusicItem(
        var title: String, var artist: String, var coverUrl: String,
        var playUrl: String, var lrctxt: String, var mid: String
    ) { var isFavorite = false }

    // 搜歌 API（请填入自己的 key 和接口地址）
    private const val API_KEY = ""
    private const val URL_WY = ""
    private const val URL_QQ = ""

    // 播放模式
    private const val MODE_SEQUENCE = 0
    private const val MODE_RANDOM = 1
    private const val MODE_LOOP = 2

    private var modulePath: String? = null
    private var cachedCard: View? = null
    private val iconCache = HashMap<String, Bitmap?>()

    // 封面共享缓存（通知栏复用，避免重复下载）
    val coverSharedCache = HashMap<String, Bitmap?>()

    private val playlist = ArrayList<MusicItem>()
    private var currentIndex = -1
    private var isPlaying = false
    private var isPreparing = false
    private var player: MediaPlayer? = null
    private var duration = 0
    private var currentPos = 0
    private var currentSong: MusicItem? = null
    private var miniCard: View? = null
    private var currentMode = MODE_SEQUENCE
    private val shuffledOrder = ArrayList<Int>()
    private var shuffleIndex = 0

    private val mh = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    fun setModulePath(modPath: String?) { modulePath = modPath }

    fun clearCache() {
        cachedCard = null
        iconCache.clear()
    }

    /** 供 HomeCardManager 调用：返回音乐卡 View */
    fun getCard(act: Activity): View? {
        _act = act
        return buildCard(act)
    }

    // ==================== 卡片 UI ====================
    private fun buildCard(ctx: Context): View? {
        if (playlist.isEmpty()) loadSavedPlaylist()
        cachedCard?.let {
            miniCard = it
            currentSong?.let { s -> updateMiniCardUI(s); refreshMiniFavIcon() }
            return it
        }
        try {
            initPlayMode()
            val d = ctx.resources.displayMetrics.density
            val sw = ctx.resources.displayMetrics.widthPixels
            val cw = (370 * d).toInt()
            val ch = (178 * d).toInt()
            val r = 24 * d
            val cr = 12 * d
            val pad = (10 * d).toInt()
            val cs = (54 * d).toInt()

            val wrapper = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, r) }
                }
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply { setColor(Color.parseColor("#D9FFFFFF")); cornerRadius = r }
            }

            // 第一行：封面 + 歌名/歌手
            val topRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val coverContainer = FrameLayout(ctx).apply {
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, cr) }
                }
            }
            val coverView = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#EEEEEE")); tag = "mini_cover"
            }
            loadCover(coverView, currentSong?.coverUrl ?: "")
            coverContainer.addView(coverView, FrameLayout.LayoutParams(-1, -1))
            topRow.addView(coverContainer, LinearLayout.LayoutParams(cs, cs))

            val textCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
            textCol.addView(TextView(ctx).apply {
                text = currentSong?.title ?: "暂无歌曲"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#0D0D0D")); isSingleLine = true; tag = "mini_title"
            }, LinearLayout.LayoutParams(-1, -2))
            textCol.addView(TextView(ctx).apply {
                text = currentSong?.artist ?: "搜索添加歌曲"; textSize = 11f
                setTextColor(Color.parseColor("#666666")); isSingleLine = true; tag = "mini_artist"
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (3 * d).toInt() })
            topRow.addView(textCol, LinearLayout.LayoutParams(0, cs, 1f).apply { leftMargin = (10 * d).toInt() })
            card.addView(topRow, LinearLayout.LayoutParams(-1, cs))

            // 歌词提示行
            card.addView(TextView(ctx).apply {
                text = if (currentSong != null) "点击卡片进入播放器" else "点击卡片搜索歌曲"
                textSize = 12f; setTextColor(Color.parseColor("#1677FF")); gravity = Gravity.CENTER
                isSingleLine = true; tag = "mini_lyric_hint"
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * d).toInt(); bottomMargin = (2 * d).toInt() })

            // 进度条行
            val seekRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
            }
            seekRow.addView(TextView(ctx).apply {
                text = "00:00"; textSize = 10f; setTextColor(Color.parseColor("#999999")); tag = "mini_start_time"
            }, LinearLayout.LayoutParams(-2, -2))
            seekRow.addView(SeekBar(ctx).apply {
                max = duration; progress = currentPos; setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0); tag = "mini_seekbar"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        if (fromUser && player != null) { try { player?.seekTo(p) } catch (_: Exception) {}; currentPos = p }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            seekRow.addView(TextView(ctx).apply {
                text = "00:00"; textSize = 10f; setTextColor(Color.parseColor("#999999")); tag = "mini_end_time"
            }, LinearLayout.LayoutParams(-2, -2))
            card.addView(seekRow, LinearLayout.LayoutParams(-1, (26 * d).toInt()))

            card.addView(View(ctx), LinearLayout.LayoutParams(-1, (6 * d).toInt()))

            // 控制按钮行
            val ctrlRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            val iconSize = (sw * 0.06).toInt()
            val playSize = (sw * 0.08).toInt()
            val favBtn = iconBtn(ctx, "未收藏.png", iconSize).apply { tag = "fav_btn" }
            favBtn.setOnClickListener {
                currentSong?.let { s ->
                    s.isFavorite = !s.isFavorite
                    loadIcon(if (s.isFavorite) "已收藏.png" else "未收藏.png")?.let { b -> (favBtn as ImageView).setImageBitmap(b) }
                    savePlaylist()
                }
            }
            ctrlRow.addView(favBtn, w1())
            ctrlRow.addView(iconBtn(ctx, "上一曲.png", iconSize).apply { setOnClickListener { playPrev() } }, w1())
            val playBtn = iconBtn(ctx, "点击播放.png", playSize).apply { tag = "mini_play_btn" }
            playBtn.setOnClickListener { handlePlayButtonClick() }
            ctrlRow.addView(playBtn, w1())
            ctrlRow.addView(iconBtn(ctx, "下一曲.png", iconSize).apply { setOnClickListener { playNext() } }, w1())
            ctrlRow.addView(iconBtn(ctx, "播放列表.png", iconSize).apply {
                setOnClickListener { wex.ui.core.feature.MusicPanels.showHistoryDialog(cardActivity()) }
            }, w1())
            card.addView(ctrlRow, LinearLayout.LayoutParams(-1, -2))

            // 卡片点击：无歌→搜索面板；有歌→全屏播放（详情页占位）
            card.setOnClickListener {
                if (currentSong == null) wex.ui.core.feature.MusicPanels.showSearch(cardActivity())
                else wex.ui.core.feature.MusicPanels.showDetails(cardActivity())
            }

            wrapper.addView(card, LinearLayout.LayoutParams(cw, ch))
            cachedCard = wrapper; miniCard = wrapper

            ensurePlayer()
            currentSong?.let { updateMiniCardUI(it) }
            return wrapper
        } catch (e: Exception) {
            log("音乐卡创建失败: ${e.message}")
            return null
        }
    }

    private var _act: Activity? = null
    private fun cardActivity(): Activity? = _act
    // buildCard 传入的 ctx 记录为 activity
    // （getCard 收到的是 Activity）

    private fun w1() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun iconBtn(ctx: Context, name: String, size: Int): ImageView =
        ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            loadIcon(name)?.let { setImageBitmap(it) }
            layoutParams = ViewGroup.LayoutParams(size, size)
        }

    private fun loadIcon(name: String): Bitmap? {
        iconCache[name]?.let { return it }
        val path = modulePath ?: return null
        return try {
            val am = AssetManager::class.java.newInstance()
            AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(am, path)
            val bmp = am.open("music_icon/$name").use { BitmapFactory.decodeStream(it) }
            iconCache[name] = bmp; bmp
        } catch (e: Exception) { log("音乐图标加载失败 $name: ${e.message}"); null }
    }

    // ==================== 播放控制 ====================
    private fun ensurePlayer() {
        if (player == null) {
            val p = MediaPlayer()
            p.setOnCompletionListener { isPlaying = false; updatePlayButtonUI(); stopProgressUpdate(); playNext() }
            player = p
        }
    }

    fun handlePlayButtonClick() {
        if (currentSong == null) { toast("请先搜索添加歌曲"); return }
        ensurePlayer()
        if (currentIndex < 0 || currentIndex >= playlist.size) currentIndex = 0
        if (!isPlaying) { if (currentPos == 0) loadAndPlay(currentIndex) else togglePlay() } else togglePlay()
    }

    fun loadAndPlay(index: Int) {
        if (isPreparing) return
        if (index < 0 || index >= playlist.size) return
        currentIndex = index
        currentSong = playlist[index]
        updateMiniCardUI(currentSong!!)
        try { addHistory("${currentSong!!.title}|${currentSong!!.artist}") } catch (_: Exception) {}
        val url = currentSong!!.playUrl
        if (url.isEmpty()) { toast("播放链接为空"); return }
        try {
            val p = player ?: MediaPlayer().also { player = it }
            try { p.reset() } catch (_: Exception) {
                try { p.release() } catch (_: Exception) {}; player = MediaPlayer()
            }
            player!!.setOnPreparedListener { mp ->
                isPreparing = false; duration = mp.duration; mp.start()
                isPlaying = true; updatePlayButtonUI(); startProgressUpdate()
                // ===== 媒体通知触发（MediaSessionFeature，唯一侵入点，勿删）=====
                try { wex.ui.core.feature.MediaSessionFeature.onPlaybackStarted() } catch (_: Exception) {}
            }
            player!!.setOnCompletionListener { isPlaying = false; updatePlayButtonUI(); stopProgressUpdate(); playNext() }
            player!!.setOnErrorListener { _, _, _ ->
                isPreparing = false; toast("播放出错"); isPlaying = false; updatePlayButtonUI(); stopProgressUpdate(); true
            }
            isPreparing = true
            player!!.setDataSource(url)
            player!!.prepareAsync()
        } catch (e: Exception) {
            isPreparing = false; toast("播放失败"); isPlaying = false; updatePlayButtonUI()
        }
    }

    private fun togglePlay() {
        val p = player ?: return; currentSong ?: return
        try {
            if (isPlaying) { p.pause(); isPlaying = false; stopProgressUpdate() }
            else { p.start(); isPlaying = true; startProgressUpdate() }
            updatePlayButtonUI()
        } catch (_: Exception) {}
    }

    fun playPrev() { if (playlist.isEmpty()) return; currentIndex = getPrevIndex(currentIndex, playlist.size); loadAndPlay(currentIndex) }
    fun playNext() { if (playlist.isEmpty()) return; currentIndex = getNextIndex(currentIndex, playlist.size); loadAndPlay(currentIndex) }

    private fun startProgressUpdate() {
        if (progressRunnable != null) return
        progressRunnable = object : Runnable {
            override fun run() {
                if (player != null && isPlaying) { try { currentPos = player!!.currentPosition; updateProgressUI() } catch (_: Exception) {} }
                updateMiniLyricHint()
                mh.postDelayed(this, 1000)
            }
        }
        mh.post(progressRunnable!!)
    }

    private fun stopProgressUpdate() { progressRunnable?.let { mh.removeCallbacks(it) }; progressRunnable = null }

    // ==================== UI 更新 ====================
    private fun updateMiniCardUI(item: MusicItem) {
        mh.post {
            val c = miniCard ?: return@post
            (c.findViewWithTag<TextView>("mini_title"))?.text = item.title
            (c.findViewWithTag<TextView>("mini_artist"))?.text = item.artist
            (c.findViewWithTag<ImageView>("mini_cover"))?.let { loadCover(it, item.coverUrl) }
        }
    }

    private fun updatePlayButtonUI() {
        mh.post {
            val c = miniCard ?: return@post
            (c.findViewWithTag<ImageView>("mini_play_btn"))?.let { btn ->
                loadIcon(if (isPlaying) "点击暂停.png" else "点击播放.png")?.let { btn.setImageBitmap(it) }
            }
        }
    }

    private fun updateProgressUI() {
        mh.post {
            val c = miniCard ?: return@post
            (c.findViewWithTag<SeekBar>("mini_seekbar"))?.let { it.max = duration; it.progress = currentPos }
            (c.findViewWithTag<TextView>("mini_start_time"))?.text = formatTime(currentPos)
            (c.findViewWithTag<TextView>("mini_end_time"))?.text = formatTime(duration)
        }
    }

    private fun updateMiniLyricHint() {
        val c = miniCard ?: return
        val hint = c.findViewWithTag<TextView>("mini_lyric_hint") ?: return
        val lyric = getCurrentLyricText(currentPos)
        // 无歌词（纯音乐/空歌词）时显示"暂无歌词"，避免残留上一首的歌词
        val show = if (lyric.isNullOrEmpty()) "暂无歌词" else lyric
        mh.post { hint.text = show }
    }

    private fun refreshMiniFavIcon() {
        mh.post {
            val c = miniCard ?: return@post
            val s = currentSong ?: return@post
            (c.findViewWithTag<ImageView>("fav_btn"))?.let { btn ->
                loadIcon(if (s.isFavorite) "已收藏.png" else "未收藏.png")?.let { btn.setImageBitmap(it) }
            }
        }
    }

    private fun loadCover(iv: ImageView, url: String) {
        if (url.isEmpty()) return
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000; conn.connect()
                if (conn.responseCode == 200) {
                    val bmp = BitmapFactory.decodeStream(conn.inputStream)
                    if (bmp != null) {
                        mh.post { iv.setImageBitmap(bmp) }
                        coverSharedCache[url] = bmp  // 共享缓存，通知栏可复用
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    private fun formatTime(millis: Int): String {
        val ts = millis / 1000; return "%02d:%02d".format(ts / 60, ts % 60)
    }

    // ==================== 歌词 ====================
    private fun parseLyric(lrc: String?): List<Pair<Int, String>> {
        val list = ArrayList<Pair<Int, String>>()
        if (lrc.isNullOrEmpty()) return list
        for (raw in lrc.split("\n")) {
            val line = raw.trim()
            if (line.length < 10) continue
            val end = line.indexOf("]"); if (end < 4) continue
            val timeStr = line.substring(1, end); val text = line.substring(end + 1).trim()
            if (text.isEmpty()) continue
            val parts = timeStr.split(":"); if (parts.size < 2) continue
            try {
                val min = parts[0].toInt()
                val secParts = parts[1].split(".")
                val sec = secParts[0].toInt()
                var ms = 0
                if (secParts.size > 1) {
                    var msStr = secParts[1]
                    if (msStr.length == 1) msStr += "00" else if (msStr.length == 2) msStr += "0"
                    ms = msStr.toInt()
                }
                list.add(((min * 60 + sec) * 1000 + ms) to text)
            } catch (_: Exception) {}
        }
        return list
    }

    private fun getCurrentLyricText(pos: Int): String? {
        val s = currentSong ?: return null
        val lrc = parseLyric(s.lrctxt); if (lrc.isEmpty()) return null
        var result: String? = null
        for ((t, text) in lrc) { if (t <= pos) result = text else break }
        return result
    }

    // ==================== 播放模式 ====================
    private fun initPlayMode() { setPlayMode(WexStore.getString("music_play_mode", "0").toIntOrNull() ?: MODE_SEQUENCE) }
    fun setPlayMode(mode: Int) {
        currentMode = mode
        if (mode == MODE_RANDOM) generateShuffledOrder()
        WexStore.putString("music_play_mode", mode.toString())
    }
    private fun getNextIndex(cur: Int, total: Int): Int {
        if (total <= 0) return 0
        return when (currentMode) {
            MODE_RANDOM -> {
                if (shuffledOrder.isEmpty()) generateShuffledOrder()
                shuffleIndex++; if (shuffleIndex >= shuffledOrder.size) { shuffleIndex = 0; generateShuffledOrder() }
                shuffledOrder[shuffleIndex]
            }
            else -> { var n = cur + 1; if (n >= total) n = 0; n }
        }
    }
    private fun getPrevIndex(cur: Int, total: Int): Int {
        if (total <= 0) return 0
        if (currentMode == MODE_RANDOM) {
            shuffleIndex--; if (shuffleIndex < 0) { generateShuffledOrder(); shuffleIndex = shuffledOrder.size - 1 }
            return shuffledOrder[shuffleIndex]
        }
        var p = cur - 1; if (p < 0) p = if (currentMode == MODE_LOOP) total - 1 else 0
        return p
    }
    private fun generateShuffledOrder() {
        shuffledOrder.clear()
        val total = playlist.size; if (total == 0) return
        for (i in 0 until total) shuffledOrder.add(i)
        for (i in total - 1 downTo 1) { val j = (Math.random() * (i + 1)).toInt(); val t = shuffledOrder[i]; shuffledOrder[i] = shuffledOrder[j]; shuffledOrder[j] = t }
        shuffleIndex = 0
    }

    // ==================== 播放列表 / 历史（存 WexStore JSON） ====================
    private fun savePlaylist() {
        try {
            val arr = JSONArray()
            for (m in playlist) {
                arr.put(JSONObject().apply {
                    put("title", m.title); put("artist", m.artist); put("coverUrl", m.coverUrl)
                    put("playUrl", m.playUrl); put("lrctxt", m.lrctxt); put("mid", m.mid); put("fav", m.isFavorite)
                })
            }
            WexStore.putString("music_playlist", arr.toString())
        } catch (_: Exception) {}
    }
    private fun loadSavedPlaylist() {
        try {
            val s = WexStore.getString("music_playlist", ""); if (s.isEmpty()) return
            val arr = JSONArray(s); playlist.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                playlist.add(MusicItem(
                    o.optString("title"), o.optString("artist"), o.optString("coverUrl"),
                    o.optString("playUrl"), o.optString("lrctxt"), o.optString("mid")
                ).apply { isFavorite = o.optBoolean("fav", false) })
            }
            if (playlist.isNotEmpty() && currentSong == null) { currentIndex = 0; currentSong = playlist[0] }
        } catch (_: Exception) {}
    }

    fun addToPlaylistAndPlay(item: MusicItem) {
        playlist.add(0, item)
        savePlaylist()
        loadAndPlay(0)
    }

    private fun addHistory(key: String) {
        if (key.isEmpty()) return
        try {
            val s = WexStore.getString("music_history", "[]")
            val arr = JSONArray(s)
            // 去重
            for (i in 0 until arr.length()) if (arr.getString(i) == key) return
            val newArr = JSONArray(); newArr.put(key)
            for (i in 0 until arr.length()) { if (newArr.length() >= 100) break; newArr.put(arr.getString(i)) }
            WexStore.putString("music_history", newArr.toString())
        } catch (_: Exception) {}
    }

    fun getPlaylist(): List<MusicItem> = playlist

    // ==================== 搜歌 API ====================
    /** 搜索歌曲列表，回调返回 JSONArray 字符串 */
    fun search(keyword: String, platform: String, cb: (String) -> Unit) {
        if (keyword.trim().isEmpty()) { cb("[]"); return }
        Thread {
            var resultJson = "[]"
            try {
                val base = if (platform == "qq") URL_QQ else URL_WY
                val encoded = URLEncoder.encode(keyword, "UTF-8")
                // QQ 接口不认 g 参数（传了会 400）；网易云需要 g=9
                val apiUrl = if (platform == "qq") "$base?key=$API_KEY&msg=$encoded"
                             else "$base?key=$API_KEY&msg=$encoded&g=9"
                val resp = httpGet(apiUrl)
                if (resp.isNotEmpty()) {
                    val root = JSONObject(resp)
                    if (root.optInt("code") == 200) {
                        val data = root.optJSONObject("data")
                        val songs = data?.optJSONArray("songs")
                        if (songs != null) {
                            val out = JSONArray()
                            for (i in 0 until songs.length()) {
                                val song = songs.getJSONObject(i)
                                val n = song.optInt("n")
                                if (n > 9) continue
                                out.put(JSONObject().apply {
                                    put("n", n); put("name", song.optString("name"))
                                    put("singer", song.optString("singer")); put("album", song.optString("album"))
                                    put("platform", platform)
                                })
                            }
                            resultJson = out.toString()
                        }
                    }
                }
            } catch (e: Exception) { log("搜索失败: ${e.message}") }
            val fj = resultJson
            mh.post { cb(fj) }
        }.start()
    }

    /** 拿歌曲详情（播放地址/歌词/封面），回调返回 MusicItem 或 null */
    fun getTrackDetail(keyword: String, n: Int, platform: String, cb: (MusicItem?) -> Unit) {
        Thread {
            var item: MusicItem? = null
            try {
                val base = if (platform == "qq") URL_QQ else URL_WY
                val encoded = URLEncoder.encode(keyword, "UTF-8")
                val apiUrl = "$base?key=$API_KEY&msg=$encoded&n=$n"
                val resp = httpGet(apiUrl)
                if (resp.isNotEmpty()) {
                    val root = JSONObject(resp)
                    if (root.optInt("code") == 200) {
                        val data = root.optJSONObject("data")
                        if (data != null) {
                            // 歌手：QQ=singer，网易云=songname
                            var singer = data.optString("singer")
                            if (singer.isEmpty()) singer = data.optString("songname")
                            // 播放地址：QQ=musicurl/music_url.url，网易云=url/vipmusic.url
                            var musicUrl = data.optString("musicurl")
                            if (musicUrl.isEmpty()) musicUrl = data.optString("url")
                            if (musicUrl.isEmpty()) data.optJSONObject("music_url")?.let { musicUrl = it.optString("url") }
                            if (musicUrl.isEmpty()) data.optJSONObject("vipmusic")?.let { musicUrl = it.optString("url") }
                            // 歌词：QQ=lrctxt（老字段仍在），网易云=music.lrc（已迁移）
                            var lrc = data.optString("lrctxt")
                            if (lrc.isEmpty()) data.optJSONObject("music")?.let { lrc = it.optString("lrc") }
                            item = MusicItem(
                                data.optString("name"), singer, data.optString("picture"),
                                musicUrl, lrc, data.optString("mid")
                            )
                        }
                    }
                }
            } catch (e: Exception) { log("获取详情失败: ${e.message}") }
            val fi = item
            mh.post { cb(fi) }
        }.start()
    }

    private fun httpGet(urlStr: String): String {
        val c = URL(urlStr).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 10000
        return if (c.responseCode == 200) c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } else ""
    }

    // ==================== 记录当前 Activity（面板用） ====================
    fun bindActivity(act: Activity) { _act = act }

    fun toast(msg: String) {
        _act?.let { a -> mh.post { android.widget.Toast.makeText(a, msg, android.widget.Toast.LENGTH_SHORT).show() } }
    }

    // ==================== MusicPanels 桥接方法 ====================
    fun _act(): Activity? = _act
    fun getCurrentSong(): MusicItem? = currentSong
    fun getCurrentPos(): Int = currentPos
    fun getDuration(): Int = duration
    fun isPlaying(): Boolean = isPlaying
    fun seekTo(pos: Int) { currentPos = pos; player?.seekTo(pos) }

    fun saveFav() { savePlaylist() }
    fun refreshMiniFav() { refreshMiniFavIcon() }

    fun loadIconBitmap(name: String): Bitmap? = loadIcon(name)

    fun addToPlaylist(item: MusicItem) {
        playlist.add(item)
        currentSong = item
        currentIndex = playlist.size - 1
        savePlaylist()
        updateMiniCardUI(item)
    }

    fun removeItem(index: Int) { if (index in 0 until playlist.size) { playlist.removeAt(index); savePlaylist() } }

    fun getLyricAt(pos: Int): String? = getCurrentLyricText(pos)

    fun getLyricLine(pos: Int): Int {
        val lrc = parseLyric(currentSong?.lrctxt ?: return -1)
        for ((i, p) in lrc.withIndex()) if (p.first <= pos) return i
        return -1
    }

    fun getFavList(): List<MusicItem> = playlist.filter { it.isFavorite }

    fun getHistoryItems(): List<MusicItem> {
        try {
            val s = WexStore.getString("music_history", "[]")
            val arr = JSONArray(s)
            val items = ArrayList<MusicItem>()
            for (i in 0 until arr.length()) {
                val key = arr.getString(i)
                val parts = key.split("|")
                val title = if (parts.size >= 2) parts[0] else key
                val artist = if (parts.size >= 2) parts[1] else ""
                val found = playlist.firstOrNull { it.title == title && (artist.isEmpty() || it.artist == artist) }
                if (found != null) items.add(found)
            }
            return items
        } catch (_: Exception) { return emptyList() }
    }

    fun indexOf(item: MusicItem): Int {
        return playlist.indexOfFirst { it.title == item.title && it.playUrl == item.playUrl }
    }

    fun setCurrentAndPlay(index: Int) { if (index in 0 until playlist.size) loadAndPlay(index) }

    fun toggle() {
        if (currentSong == null) return
        togglePlay()
    }

    fun clearAllCache() {
        playlist.clear(); currentIndex = -1; currentSong = null
        player?.apply { try { stop() } catch (_: Exception) {}; try { release() } catch (_: Exception) {} }; player = null
        isPlaying = false; stopProgressUpdate(); updatePlayButtonUI()
        WexStore.putString("music_playlist", "[]"); WexStore.putString("music_history", "[]")
        val emptyItem = MusicItem("暂无歌曲", "搜索添加歌曲", "", "", "", "")
        updateMiniCardUI(emptyItem)
    }

    private fun log(msg: String) {
        XposedBridge.log("WEX: $msg")
        try { WexStore.appendLog(msg) } catch (_: Exception) {}
    }
}