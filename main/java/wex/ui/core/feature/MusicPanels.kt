package wex.ui.core.feature

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import wex.ui.core.WexStore
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * 音乐播放器弹窗层。
 * 只负责"弹窗 UI + 调用 MusicCardFeature 已暴露的方法"，不持有播放逻辑。
 * 包含：详情页 / 展开歌词 / 搜索 / 历史 / 收藏 / 缓存管理 / 悬浮词面板 / 定时关闭。
 * HQ音质：本版按占位 Toast 处理（原插件亦未实现）。
 */
object MusicPanels {

    private val mh = Handler(Looper.getMainLooper())
    private val M = MusicCardFeature

    // 详情页状态
    private var coverRotation: ObjectAnimator? = null
    private var detailsCurrentLyricTv: TextView? = null
    private var detailsLyricTvExpanded: TextView? = null
    private var lyricsExpanded = false
    private var currentDetailsDialog: Dialog? = null

    // 定时关闭（方案A：定时器状态放本类，到点调 MusicCardFeature.toggle 暂停，不碰核心）
    private var timerHandler: Handler? = null
    private var timerRunnable: Runnable? = null

    // 取顶层 Activity：优先入参，否则回退到 MusicCardFeature 记录的 Activity
    private fun topAct(act: Activity?): Activity? = act ?: M._act()

    // ==================== 详情页 ====================
    fun showDetails(act: Activity?) {
        log("showDetails 被调用")
        // 关闭旧详情页
        try { currentDetailsDialog?.let { if (it.isShowing) it.dismiss() } } catch (_: Exception) {}

        val currentSong = M.getCurrentSong()
        if (currentSong == null) { showSearch(act); return }

        try {
            val ctx = topAct(act) ?: run { M.toast("无法打开详情页"); return }
            val res = ctx.resources.displayMetrics
            val sw = res.widthPixels
            val sh = res.heightPixels
            val d = res.density

            val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
            dialog.window?.let { w ->
                val wp = w.attributes
                wp.gravity = Gravity.BOTTOM; wp.width = -1; wp.height = -1
                w.attributes = wp
                w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
            dialog.setCanceledOnTouchOutside(true)

            // 磨砂主容器
            val rootFrame = FrameLayout(ctx).apply { setPadding(0, (sh * 0.06).toInt(), 0, 0) }

            // 放大封面作磨砂背景 + 半透明遮罩
            val blurBg = FrameLayout(ctx).apply { layoutParams = FrameLayout.LayoutParams(-1, -1) }
            val bgCover = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            loadCover(bgCover, currentSong.coverUrl)
            blurBg.addView(bgCover)
            val mask = View(ctx).apply {
                setBackgroundColor(Color.parseColor("#AAEEF8F0"))
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            blurBg.addView(mask)
            rootFrame.addView(blurBg)

            // 前层卡片
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 40 * d) }
                }
                setBackgroundColor(Color.parseColor("#AAFFFFFF"))
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            }

            // --- 顶部栏 ---
            val topBar = RelativeLayout(ctx).apply {
                setPadding((sw * 0.04).toInt(), (sh * 0.015).toInt(), (sw * 0.04).toInt(), (sh * 0.01).toInt())
            }
            val iconSize = (36 * d).toInt()

            val closeBtn = loadIcon(ctx, "关闭.png", iconSize).apply {
                layoutParams = RelativeLayout.LayoutParams(iconSize, iconSize).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_LEFT); addRule(RelativeLayout.CENTER_VERTICAL)
                }
                setOnClickListener { dialog.dismiss() }
            }
            topBar.addView(closeBtn)

            val titleBlock = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                layoutParams = RelativeLayout.LayoutParams(-2, -2).apply { addRule(RelativeLayout.CENTER_IN_PARENT) }
            }
            val titleTv = TextView(ctx).apply {
                text = currentSong.title; textSize = 18f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#1A1A1A")); gravity = Gravity.CENTER
            }
            titleBlock.addView(titleTv)
            val artistTv = TextView(ctx).apply {
                text = currentSong.artist + " \u00B7 网易云"; textSize = 13f
                setTextColor(Color.parseColor("#666666")); gravity = Gravity.CENTER
            }
            titleBlock.addView(artistTv)
            topBar.addView(titleBlock)

            val searchBtn = loadIcon(ctx, "搜索.png", iconSize).apply {
                layoutParams = RelativeLayout.LayoutParams(iconSize, iconSize).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_RIGHT); addRule(RelativeLayout.CENTER_VERTICAL)
                }
                setOnClickListener { showSearch(act) }
            }
            topBar.addView(searchBtn)
            card.addView(topBar)

            // --- Tab行：收藏 / 缓存 ---
            val tabRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins((sw * 0.04).toInt(), 0, (sw * 0.04).toInt(), 0)
                }
            }
            val favTab = createTabBtn(ctx, "收藏", true).apply { setOnClickListener { showFavList(act) } }
            val cacheTab = createTabBtn(ctx, "缓存", false).apply { setOnClickListener { showCacheManager(act) } }
            tabRow.addView(favTab); tabRow.addView(cacheTab)
            card.addView(tabRow)

            // --- 封面（放大圆形，占主要空间） ---
            val coverSize = (sw * 0.65).toInt()
            val coverContainer = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(coverSize, coverSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = (sh * 0.03).toInt()
                    bottomMargin = (sh * 0.04).toInt()
                }
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) }
                }
            }
            val coverView = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            loadCover(coverView, currentSong.coverUrl)
            coverContainer.addView(coverView)
            card.addView(coverContainer)

            // --- 功能按钮行：HQ / 定时 / 悬浮词（占位） ---
            val funcRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins((sw * 0.04).toInt(), 0, (sw * 0.04).toInt(), (sh * 0.02).toInt())
                }
            }
            funcRow.addView(createFuncBtn(ctx, "HQ 音质").apply { setOnClickListener { M.toast("HQ 音质开发中") } })
            funcRow.addView(createFuncBtn(ctx, "定时").apply { setOnClickListener { showTimerDialog(act) } })
            funcRow.addView(createFuncBtn(ctx, "悬浮词").apply { setOnClickListener { showFloatLyricPanel(act) } })
            card.addView(funcRow)

            // --- 歌词区（折叠：只显示当前一行居中） ---
            val lyricOuter = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
                setPadding((sw * 0.04).toInt(), (sh * 0.02).toInt(), (sw * 0.04).toInt(), 0)
            }
            val curLyricTv = TextView(ctx).apply {
                text = getDisplayLyric(M.getCurrentPos())
                textSize = 14f; setTextColor(Color.parseColor("#333333")); gravity = Gravity.CENTER
                isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                layoutParams = FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.CENTER }
                setOnClickListener { showExpandedLyrics(act) }
            }
            detailsCurrentLyricTv = curLyricTv
            lyricOuter.addView(curLyricTv)
            card.addView(lyricOuter)

            // --- 底部：进度条 + 控制栏 ---
            val bottomSection = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((sw * 0.04).toInt(), (sh * 0.015).toInt(), (sw * 0.04).toInt(), (sh * 0.015).toInt())
            }

            // 进度条
            val progressRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            val startTime = TextView(ctx).apply {
                text = formatTime(M.getCurrentPos()); textSize = 12f; setTextColor(Color.parseColor("#999999"))
            }
            val seekBar = SeekBar(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                max = M.getDuration(); progress = M.getCurrentPos()
                setPadding((sw * 0.03).toInt(), 0, (sw * 0.03).toInt(), 0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        if (fromUser) M.seekTo(p)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }
            val endTime = TextView(ctx).apply {
                text = formatTime(M.getDuration()); textSize = 12f; setTextColor(Color.parseColor("#999999"))
            }
            progressRow.addView(startTime); progressRow.addView(seekBar); progressRow.addView(endTime)
            bottomSection.addView(progressRow)

            // 控制栏：收藏 / 上一曲 / 播放 / 下一曲 / 播放列表
            val ctrlRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                setPadding(0, (sh * 0.02).toInt(), 0, (sh * 0.02).toInt())
            }
            val detailBtnSize = (48 * d).toInt()
            val detailPlaySize = (60 * d).toInt()
            fun fp() = LinearLayout.LayoutParams(0, -2, 1f).apply { gravity = Gravity.CENTER }

            val favBtn = loadIcon(ctx, if (currentSong.isFavorite) "已收藏.png" else "未收藏.png", detailBtnSize)
            favBtn.setOnClickListener {
                val s = M.getCurrentSong() ?: return@setOnClickListener
                s.isFavorite = !s.isFavorite
                loadIconBmp(if (s.isFavorite) "已收藏.png" else "未收藏.png")?.let { favBtn.setImageBitmap(it) }
                M.saveFav()
                M.refreshMiniFav()
            }
            ctrlRow.addView(favBtn, fp())

            val prevBtn = loadIcon(ctx, "上一曲.png", detailBtnSize).apply { setOnClickListener { M.playPrev() } }
            ctrlRow.addView(prevBtn, fp())

            val playBtn = loadIcon(ctx, if (M.isPlaying()) "点击暂停.png" else "点击播放.png", detailPlaySize)
            playBtn.setOnClickListener {
                if (M.getCurrentSong() == null) { M.toast("请先添加歌曲"); return@setOnClickListener }
                M.toggle()
                loadIconBmp(if (M.isPlaying()) "点击暂停.png" else "点击播放.png")?.let { playBtn.setImageBitmap(it) }
                if (M.isPlaying()) startCoverAnimation(coverView) else pauseCoverAnimation()
            }
            ctrlRow.addView(playBtn, fp())

            val nextBtn = loadIcon(ctx, "下一曲.png", detailBtnSize).apply { setOnClickListener { M.playNext() } }
            ctrlRow.addView(nextBtn, fp())

            val listBtn = loadIcon(ctx, "播放列表.png", detailBtnSize).apply { setOnClickListener { showHistoryDialog(act) } }
            ctrlRow.addView(listBtn, fp())

            bottomSection.addView(ctrlRow)
            card.addView(bottomSection)

            rootFrame.addView(card)
            dialog.setContentView(rootFrame)
            dialog.show()
            currentDetailsDialog = dialog

            // 进度 + 歌词每秒刷新（含"切歌跟随"与"播放状态跟随"）
            // 记住上次的歌与播放态，仅在变化时刷新对应 UI，避免每秒重复下载封面
            var lastSongKey = currentSong.let { it.title + "|" + it.coverUrl }
            var lastPlaying = M.isPlaying()
            val progressTask = object : Runnable {
                override fun run() {
                    val song = M.getCurrentSong()
                    // ① 切歌检测：歌变了 → 换封面/标题/歌手/收藏键，旋转重置从 0° 重转
                    if (song != null) {
                        val key = song.title + "|" + song.coverUrl
                        if (key != lastSongKey) {
                            lastSongKey = key
                            titleTv.text = song.title
                            artistTv.text = song.artist + " \u00B7 网易云"
                            loadCover(coverView, song.coverUrl)
                            loadIconBmp(if (song.isFavorite) "已收藏.png" else "未收藏.png")?.let { favBtn.setImageBitmap(it) }
                            if (M.isPlaying()) restartCoverAnimation(coverView)
                        }
                    }
                    // ② 进度/时间（仅播放时刷新）
                    if (M.isPlaying()) {
                        try {
                            val pos = M.getCurrentPos()
                            seekBar.progress = pos
                            startTime.text = formatTime(pos)
                            endTime.text = formatTime(M.getDuration())
                            seekBar.max = M.getDuration()
                        } catch (_: Exception) {}
                    }
                    // ③ 播放键图标 + 旋转跟随播放状态（暂停立刻停转）
                    val playing = M.isPlaying()
                    if (playing != lastPlaying) {
                        lastPlaying = playing
                        loadIconBmp(if (playing) "点击暂停.png" else "点击播放.png")?.let { playBtn.setImageBitmap(it) }
                        if (playing) startCoverAnimation(coverView) else pauseCoverAnimation()
                    }
                    // ④ 歌词
                    if (detailsCurrentLyricTv != null && song != null && !lyricsExpanded) {
                        detailsCurrentLyricTv?.text = getDisplayLyric(M.getCurrentPos())
                    }
                    mh.postDelayed(this, 1000)
                }
            }
            mh.post(progressTask)

            dialog.setOnDismissListener {
                mh.removeCallbacks(progressTask)
                pauseCoverAnimation()
                detailsCurrentLyricTv = null
                currentDetailsDialog = null
            }
            if (M.isPlaying()) startCoverAnimation(coverView)
        } catch (e: Exception) {
            log("showDetails 异常: $e"); M.toast("详情页加载失败")
        }
    }

    // 折叠模式下的歌词展示
    private fun getDisplayLyric(currentMs: Int): String {
        val s = M.getCurrentSong() ?: return "暂无歌词"
        if (s.lrctxt.isEmpty()) return "暂无歌词"
        val lyric = M.getLyricAt(currentMs)
        return lyric ?: "即将播放..."
    }

    // ==================== 展开全部歌词 ====================
    fun showExpandedLyrics(act: Activity?) {
        val currentSong = M.getCurrentSong()
        if (currentSong == null || currentSong.lrctxt.isEmpty()) { M.toast("暂无歌词"); return }
        lyricsExpanded = true

        val ctx = topAct(act) ?: return
        val res = ctx.resources.displayMetrics
        val sw = res.widthPixels; val sh = res.heightPixels; val d = res.density

        val lyricDialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        lyricDialog.window?.let { w ->
            w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            val wp = w.attributes
            wp.gravity = Gravity.BOTTOM; wp.width = -1; wp.height = (sh * 0.75).toInt()
            w.attributes = wp
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        lyricDialog.setCanceledOnTouchOutside(true)

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding((sw * 0.06).toInt(), (sw * 0.05).toInt(), (sw * 0.06).toInt(), (sw * 0.05).toInt())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 30 * d) }
            }
        }
        panel.addView(TextView(ctx).apply {
            text = "全部歌词 \u00B7 " + currentSong.title
            textSize = 16f; setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, (sw * 0.03).toInt())
        })

        val lyricScroll = ScrollView(ctx)
        val expanded = TextView(ctx).apply {
            text = currentSong.lrctxt
            textSize = 14f; setTextColor(Color.parseColor("#444444"))
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(12f, 1.5f)
            setPadding(0, (sw * 0.04).toInt(), 0, (sw * 0.08).toInt())
        }
        detailsLyricTvExpanded = expanded
        lyricScroll.addView(expanded)
        panel.addView(lyricScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        lyricDialog.setContentView(panel)
        lyricDialog.setOnDismissListener { lyricsExpanded = false }
        lyricDialog.show()

        mh.postDelayed({ scrollExpandedLyricToCurrent() }, 300)
    }

    private fun scrollExpandedLyricToCurrent() {
        val tv = detailsLyricTvExpanded ?: return
        if (M.getCurrentSong() == null) return
        try {
            var lineHeight = tv.lineHeight
            if (lineHeight == 0) lineHeight = 48
            val currentLine = M.getLyricLine(M.getCurrentPos())
            if (currentLine >= 0) {
                var scrollY = currentLine * lineHeight - 200
                if (scrollY < 0) scrollY = 0
                (tv.parent as? ScrollView)?.smoothScrollTo(0, scrollY)
            }
        } catch (_: Exception) {}
    }

    // ==================== 收藏列表 ====================
    fun showFavList(act: Activity?) {
        val favList = M.getFavList()
        if (favList.isEmpty()) { M.toast("暂无收藏歌曲"); return }

        val ctx = topAct(act) ?: return
        val res = ctx.resources.displayMetrics
        val sw = res.widthPixels; val sh = res.heightPixels; val d = res.density

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((sw * 0.04).toInt(), (sw * 0.04).toInt(), (sw * 0.04).toInt(), (sw * 0.04).toInt())
            setBackgroundColor(Color.parseColor("#FFFFFF"))
        }
        val scroll = ScrollView(ctx)
        val listLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        for (favItem in favList) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(ctx).apply {
                text = favItem.title + " - " + favItem.artist
                textSize = 15f; setTextColor(Color.parseColor("#333333"))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(TextView(ctx).apply {
                text = "\u25B6"; textSize = 18f; setTextColor(Color.parseColor("#1677FF"))
                setPadding((sw * 0.04).toInt(), (8 * d).toInt(), (sw * 0.04).toInt(), (8 * d).toInt())
                setOnClickListener {
                    val idx = M.indexOf(favItem)
                    if (idx >= 0) M.setCurrentAndPlay(idx)
                }
            })
            listLayout.addView(row)
        }
        scroll.addView(listLayout); layout.addView(scroll)

        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.window?.let { w ->
            w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            val wp = w.attributes
            wp.gravity = Gravity.BOTTOM; wp.width = -1; wp.height = (sh * 0.6).toInt()
            w.attributes = wp
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog.setContentView(layout); dialog.setCanceledOnTouchOutside(true); dialog.show()
    }

    // ==================== 搜索面板 ====================
    fun showSearch(act: Activity?) {
        val ctx = topAct(act) ?: run { M.toast("无法打开搜索"); return }
        val res = ctx.resources.displayMetrics
        val sw = res.widthPixels; val sh = res.heightPixels; val d = res.density

        val searchDialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        searchDialog.window?.let { w ->
            val wp = w.attributes
            wp.gravity = Gravity.BOTTOM; wp.width = -1; wp.height = (sh * 0.8).toInt()
            w.attributes = wp
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        searchDialog.setCanceledOnTouchOutside(true)

        val rootPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 30 * d) }
            }
        }

        // 平台切换
        val platRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((sw * 0.04).toInt(), (sh * 0.03).toInt(), (sw * 0.04).toInt(), (sh * 0.01).toInt())
        }
        rootPanel.addView(platRow)

        val currentPlat = arrayOf("wy")
        val qqBtn = createTabBtn(ctx, "QQ音乐", false)
        val wyBtn = createTabBtn(ctx, "网易云", true)
        qqBtn.setOnClickListener {
            currentPlat[0] = "qq"
            qqBtn.setTextColor(Color.parseColor("#FFFFFF")); qqBtn.background = createTagBg(Color.parseColor("#333333"), 20)
            wyBtn.setTextColor(Color.parseColor("#333333")); wyBtn.background = createTagBg(Color.parseColor("#EEEEEE"), 20)
        }
        wyBtn.setOnClickListener {
            currentPlat[0] = "wy"
            wyBtn.setTextColor(Color.parseColor("#FFFFFF")); wyBtn.background = createTagBg(Color.parseColor("#333333"), 20)
            qqBtn.setTextColor(Color.parseColor("#333333")); qqBtn.background = createTagBg(Color.parseColor("#EEEEEE"), 20)
        }
        platRow.addView(qqBtn); platRow.addView(wyBtn)

        // 搜索输入行
        val searchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((sw * 0.04).toInt(), 0, (sw * 0.04).toInt(), (sh * 0.02).toInt())
        }
        val inputField = EditText(ctx).apply {
            hint = "搜索音乐..."; textSize = 15f; setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding((sw * 0.04).toInt(), (sh * 0.01).toInt(), (sw * 0.04).toInt(), (sh * 0.01).toInt())
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, (sw * 0.02).toInt(), 0) }
        }
        searchRow.addView(inputField)
        val searchAction = TextView(ctx).apply {
            text = "搜索"; textSize = 15f; setTextColor(Color.parseColor("#1677FF"))
        }
        searchRow.addView(searchAction)
        rootPanel.addView(searchRow)

        // 结果区
        val resultContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scrollView = ScrollView(ctx).apply { addView(resultContainer) }
        rootPanel.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
        searchDialog.setContentView(rootPanel)
        searchDialog.show()

        val isSearching = booleanArrayOf(false)
        searchAction.setOnClickListener {
            if (isSearching[0]) { M.toast("正在搜索中..."); return@setOnClickListener }
            val keyword = inputField.text.toString().trim()
            if (keyword.isEmpty()) { M.toast("请输入关键词"); return@setOnClickListener }
            isSearching[0] = true; resultContainer.removeAllViews()
            resultContainer.addView(TextView(ctx).apply {
                text = "搜索中..."; gravity = Gravity.CENTER; setPadding(0, 40, 0, 40)
                setTextColor(Color.parseColor("#999999"))
            })

            M.search(keyword, currentPlat[0]) { jsonResult ->
                isSearching[0] = false; resultContainer.removeAllViews()
                if (jsonResult.isEmpty() || jsonResult == "[]") {
                    resultContainer.addView(TextView(ctx).apply {
                        text = "未找到结果"; gravity = Gravity.CENTER; setPadding(0, 40, 0, 40)
                        setTextColor(Color.parseColor("#999999"))
                    })
                    return@search
                }
                if (jsonResult == "ERROR") {
                    resultContainer.addView(TextView(ctx).apply {
                        text = "服务连接失败"; gravity = Gravity.CENTER; setPadding(0, 40, 0, 40)
                        setTextColor(Color.parseColor("#FF4444"))
                    })
                    return@search
                }
                try {
                    val items = JSONArray(jsonResult)
                    for (i in 0 until items.length()) {
                        val obj = items.getJSONObject(i)
                        val n = obj.optInt("n")
                        val name = obj.optString("name")
                        val singer = obj.optString("singer")
                        val plat = obj.optString("platform")

                        val row = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                            setPadding((sw * 0.04).toInt(), (12 * d).toInt(), (sw * 0.04).toInt(), (12 * d).toInt())
                            setBackgroundColor(Color.parseColor("#FFFFFF"))
                        }
                        val info = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                        }
                        info.addView(TextView(ctx).apply {
                            text = name; textSize = 14f; setTextColor(Color.parseColor("#333333"))
                        })
                        info.addView(TextView(ctx).apply {
                            text = singer; textSize = 11f; setTextColor(Color.parseColor("#999999"))
                        })
                        row.addView(info)

                        val addBtn = TextView(ctx).apply {
                            text = "\u2795"; textSize = 18f; setTextColor(Color.parseColor("#1677FF"))
                            setPadding((sw * 0.04).toInt(), (8 * d).toInt(), (sw * 0.04).toInt(), (8 * d).toInt())
                        }
                        addBtn.setOnClickListener {
                            searchDialog.dismiss(); M.toast("正在获取歌曲...")
                            M.getTrackDetail(keyword, n, plat) { item ->
                                if (item == null) { M.toast("获取失败"); return@getTrackDetail }
                                if (item.playUrl.isEmpty()) { M.toast("该歌曲暂无可用播放链接"); return@getTrackDetail }
                                M.addToPlaylistAndPlay(item)
                                M.toast("已添加: " + item.title)
                            }
                        }
                        row.addView(addBtn)
                        resultContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                            setMargins(0, 0, 0, (2 * d).toInt())
                        })
                    }
                } catch (_: Exception) { M.toast("解析失败") }
            }
        }
    }

    // ==================== 历史记录 ====================
    fun showHistoryDialog(act: Activity?) {
        try {
            val history = M.getHistoryItems()
            if (history.isEmpty()) { M.toast("暂无播放历史"); return }

            val ctx = topAct(act) ?: return
            val res = ctx.resources.displayMetrics
            val sw = res.widthPixels; val sh = res.heightPixels; val d = res.density

            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((sw * 0.04).toInt(), (sw * 0.04).toInt(), (sw * 0.04).toInt(), (sw * 0.04).toInt())
                setBackgroundColor(Color.parseColor("#FFFFFF"))
            }
            val scroll = ScrollView(ctx)
            val listLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

            for (historyItem in history) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(ctx).apply {
                    text = historyItem.title + " - " + historyItem.artist
                    textSize = 14f; setTextColor(Color.parseColor("#333333"))
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                row.addView(TextView(ctx).apply {
                    text = "\u25B6"; textSize = 18f; setTextColor(Color.parseColor("#1677FF"))
                    setPadding((sw * 0.04).toInt(), (8 * d).toInt(), (sw * 0.04).toInt(), (8 * d).toInt())
                    setOnClickListener {
                        val idx = M.indexOf(historyItem)
                        if (idx >= 0) M.setCurrentAndPlay(idx)
                        else { M.addToPlaylist(historyItem); M.setCurrentAndPlay(M.getPlaylist().size - 1) }
                    }
                })
                listLayout.addView(row)
            }
            scroll.addView(listLayout); layout.addView(scroll)

            val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
            dialog.window?.let { w ->
                w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                val wp = w.attributes
                wp.gravity = Gravity.BOTTOM; wp.width = -1; wp.height = (sh * 0.6).toInt()
                w.attributes = wp
                w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
            dialog.setContentView(layout); dialog.setCanceledOnTouchOutside(true); dialog.show()
        } catch (_: Exception) { M.toast("历史功能异常") }
    }

    // ==================== 缓存管理 ====================
    fun showCacheManager(act: Activity?) {
        val playlist = M.getPlaylist()
        if (playlist.isEmpty()) { M.toast("当前没有缓存歌曲"); return }

        val ctx = topAct(act) ?: return
        val res = ctx.resources.displayMetrics
        val sw = res.widthPixels; val sh = res.heightPixels; val d = res.density

        val cardPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding((sw * 0.04).toInt(), (sw * 0.04).toInt(), (sw * 0.04).toInt(), (sw * 0.04).toInt())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 20 * d) }
            }
        }
        cardPanel.addView(TextView(ctx).apply {
            text = "缓存管理 (" + playlist.size + " 首)"; textSize = 16f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, (sw * 0.03).toInt())
        })

        val scroll = ScrollView(ctx)
        val listLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val copyList = ArrayList(playlist)
        for (i in copyList.indices) {
            val cacheItem = copyList[i]
            val idx = i
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(ctx).apply {
                text = "${idx + 1}. " + cacheItem.title + " - " + cacheItem.artist
                textSize = 13f; setTextColor(Color.parseColor("#333333"))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(TextView(ctx).apply {
                text = "移除"; textSize = 13f; setTextColor(Color.parseColor("#FF4444"))
                setPadding((sw * 0.04).toInt(), (6 * d).toInt(), (sw * 0.04).toInt(), (6 * d).toInt())
                setOnClickListener {
                    M.removeItem(idx); M.toast("已移除: " + cacheItem.title); showCacheManager(act)
                }
            })
            listLayout.addView(row)
            listLayout.addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#EEEEEE")) },
                LinearLayout.LayoutParams(-1, (1 * d).toInt()))
        }
        scroll.addView(listLayout); cardPanel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        cardPanel.addView(TextView(ctx).apply {
            text = "一键清理全部"; textSize = 15f; setTextColor(Color.parseColor("#FFFFFF"))
            setBackgroundColor(Color.parseColor("#FF4444")); gravity = Gravity.CENTER
            setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (sw * 0.03).toInt() }
            setOnClickListener { M.clearAllCache(); M.toast("缓存已全部清理") }
        })

        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.window?.let { w ->
            w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            val wp = w.attributes
            wp.gravity = Gravity.CENTER; wp.width = (sw * 0.9).toInt(); wp.height = (sh * 0.65).toInt()
            w.attributes = wp
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog.setContentView(cardPanel); dialog.setCanceledOnTouchOutside(true); dialog.show()
    }

    // ==================== 悬浮词设置面板 ====================
    fun showFloatLyricPanel(act: Activity?) {
        val ctx = topAct(act) ?: return
        val res = ctx.resources.displayMetrics
        val sw = res.widthPixels; val d = res.density

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding((sw * 0.06).toInt(), (sw * 0.05).toInt(), (sw * 0.06).toInt(), (sw * 0.05).toInt())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 20 * d) }
            }
        }
        panel.addView(TextView(ctx).apply {
            text = "桌面悬浮歌词"; textSize = 18f; setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, (sw * 0.04).toInt())
        })

        val options = arrayOf("关闭悬浮词", "开启(自由拖动)", "开启(锁定防误触)")
        val btnH = (48 * d).toInt()

        val dialog = android.app.AlertDialog.Builder(ctx).create()

        for (i in options.indices) {
            val idx = i
            panel.addView(TextView(ctx).apply {
                text = options[i]; textSize = 16f; setTextColor(Color.parseColor("#333333"))
                setBackgroundColor(Color.parseColor("#F5F5F5")); gravity = Gravity.CENTER
                setPadding(0, btnH / 2, 0, btnH / 2)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * d).toInt() }
                setOnClickListener {
                    try {
                        when (idx) {
                            0 -> { FloatLyricFeature.hideFloatLyric(); M.toast("悬浮词已关闭") }
                            1 -> { FloatLyricFeature.setLocked(false); FloatLyricFeature.showFloatLyric(act); M.toast("悬浮词已开启(自由拖动)") }
                            2 -> { FloatLyricFeature.setLocked(true); FloatLyricFeature.showFloatLyric(act); M.toast("悬浮词已开启(锁定防误触)") }
                        }
                    } catch (e: Exception) { log("悬浮词切换失败: $e") }
                    dialog.dismiss()
                }
            })
        }

        dialog.setView(panel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    // ==================== 定时关闭面板 ====================
    fun showTimerDialog(act: Activity?) {
        val ctx = topAct(act) ?: return
        val res = ctx.resources.displayMetrics
        val sw = res.widthPixels; val d = res.density

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding((sw * 0.06).toInt(), (sw * 0.05).toInt(), (sw * 0.06).toInt(), (sw * 0.05).toInt())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 24 * d) }
            }
        }
        panel.addView(TextView(ctx).apply {
            text = "定时关闭播放"; textSize = 18f; setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, (sw * 0.04).toInt())
        })

        val labels = arrayOf("15 分钟", "30 分钟", "自定义", "关闭定时")
        val btnH = (48 * d).toInt()

        val dialog = android.app.AlertDialog.Builder(ctx).create()

        for (i in labels.indices) {
            val idx = i
            val label = labels[i]
            panel.addView(TextView(ctx).apply {
                text = label; textSize = 16f; setTextColor(Color.parseColor("#333333"))
                background = createTagBg(Color.parseColor("#F5F5F5"), 16); gravity = Gravity.CENTER
                setPadding(0, btnH / 2, 0, btnH / 2)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * d).toInt() }
                setOnClickListener {
                    when (idx) {
                        0 -> startSleepTimer(15)
                        1 -> startSleepTimer(30)
                        2 -> { dialog.dismiss(); showCustomTimerInput(act); return@setOnClickListener }
                        3 -> { timerRunnable?.let { timerHandler?.removeCallbacks(it) }; M.toast("定时已取消") }
                    }
                    dialog.dismiss()
                }
            })
        }

        dialog.setView(panel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    // 自定义分钟输入（MD3 圆角输入框，仅正整数）
    private fun showCustomTimerInput(act: Activity?) {
        val ctx = topAct(act) ?: return
        val res = ctx.resources.displayMetrics
        val sw = res.widthPixels; val d = res.density

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding((sw * 0.06).toInt(), (sw * 0.05).toInt(), (sw * 0.06).toInt(), (sw * 0.05).toInt())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 24 * d) }
            }
        }
        panel.addView(TextView(ctx).apply {
            text = "自定义定时（分钟）"; textSize = 18f; setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, (sw * 0.04).toInt())
        })

        // 圆角描边输入框（MD3 风），只允许正整数
        val input = EditText(ctx).apply {
            hint = "请输入整数分钟，如 45"
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
            setHintTextColor(Color.parseColor("#AAAAAA"))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER   // 纯数字键盘，天然拒绝小数点/负号
            gravity = Gravity.CENTER
            setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F7F7F7"))
                cornerRadius = 16 * d
                setStroke((1.5f * d).toInt(), Color.parseColor("#1A73E8"))
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        panel.addView(input)

        val btnH = (48 * d).toInt()
        val dialog = android.app.AlertDialog.Builder(ctx).create()

        // 按钮行：取消 / 确定
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (16 * d).toInt() }
        }
        btnRow.addView(TextView(ctx).apply {
            text = "取消"; textSize = 16f; setTextColor(Color.parseColor("#666666"))
            background = createTagBg(Color.parseColor("#F5F5F5"), 16); gravity = Gravity.CENTER
            setPadding(0, btnH / 3, 0, btnH / 3)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = (6 * d).toInt() }
            setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(TextView(ctx).apply {
            text = "确定"; textSize = 16f; setTextColor(Color.parseColor("#FFFFFF"))
            background = createTagBg(Color.parseColor("#1A73E8"), 16); gravity = Gravity.CENTER
            setPadding(0, btnH / 3, 0, btnH / 3)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = (6 * d).toInt() }
            setOnClickListener {
                val txt = input.text.toString().trim()
                val minutes = txt.toIntOrNull()
                if (minutes == null || minutes <= 0) { M.toast("请输入大于 0 的整数分钟"); return@setOnClickListener }
                startSleepTimer(minutes)
                dialog.dismiss()
            }
        })
        panel.addView(btnRow)

        dialog.setView(panel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    // 启动睡眠定时：到点仅暂停（不清进度/列表）
    private fun startSleepTimer(minutes: Int) {
        if (timerHandler == null) timerHandler = Handler(Looper.getMainLooper())
        timerRunnable?.let { timerHandler?.removeCallbacks(it) }
        timerRunnable = Runnable { if (M.isPlaying()) { M.toggle(); M.toast("定时关闭") } }
        timerHandler?.postDelayed(timerRunnable!!, minutes * 60 * 1000L)
        M.toast("将在 $minutes 分钟后关闭")
    }

    // ==================== 封面旋转动画 ====================
    // coverView 每次进详情页/切歌都可能是新实例，动画需绑定到当前 view。
    private fun startCoverAnimation(view: ImageView?) {
        if (view == null) return
        try {
            // 若动画不存在或绑定的不是当前 view，则重建绑定到当前 view
            if (coverRotation == null || coverRotation?.target !== view) {
                coverRotation?.cancel()
                coverRotation = ObjectAnimator.ofFloat(view, "rotation", 0f, 360f).apply {
                    duration = 15000
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                    interpolator = LinearInterpolator()
                }
            }
            // 布局完成后再启动，避免 show() 后立刻调用时宽高为 0
            view.post {
                coverRotation?.let { if (!it.isStarted) it.start() else it.resume() }
            }
        } catch (_: Exception) {}
    }

    // 切歌时：重置到当前 view，从 0° 重新开始转（A 方案）
    private fun restartCoverAnimation(view: ImageView?) {
        if (view == null) return
        try {
            coverRotation?.cancel()
            coverRotation = null
            view.rotation = 0f
            startCoverAnimation(view)
        } catch (_: Exception) {}
    }

    private fun pauseCoverAnimation() {
        coverRotation?.let { if (it.isStarted) it.pause() }
    }

    // ==================== 辅助 UI ====================
    private fun createFuncBtn(ctx: Context, text: String): TextView =
        TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(8, 0, 8, 0) }
            this.text = text; textSize = 13f
            setTextColor(Color.parseColor("#555555"))
            background = createTagBg(Color.parseColor("#33FFFFFF"), 20)
            gravity = Gravity.CENTER; setPadding(0, 12, 0, 12)
        }

    private fun createTabBtn(ctx: Context, text: String, selected: Boolean): TextView =
        TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(4, 0, 4, 0) }
            this.text = text; textSize = 14f
            setTextColor(Color.parseColor(if (selected) "#FFFFFF" else "#333333"))
            background = createTagBg(Color.parseColor(if (selected) "#1A73E8" else "#EEEEEE"), 20)
            gravity = Gravity.CENTER; setPadding(0, 10, 0, 10)
        }

    private fun createTagBg(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radiusDp.toFloat() }

    // 图标：走 MusicCardFeature 的 assets 加载
    private fun loadIconBmp(name: String): Bitmap? = M.loadIconBitmap(name)

    private fun loadIcon(ctx: Context, name: String, size: Int): ImageView =
        ImageView(ctx).apply {
            loadIconBmp(name)?.let { setImageBitmap(it) }
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

    // 封面异步加载
    private fun loadCover(iv: ImageView, url: String) {
        if (url.isEmpty()) return
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000; conn.connect()
                if (conn.responseCode == 200) {
                    val bmp = BitmapFactory.decodeStream(conn.inputStream)
                    if (bmp != null) mh.post { iv.setImageBitmap(bmp) }
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
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