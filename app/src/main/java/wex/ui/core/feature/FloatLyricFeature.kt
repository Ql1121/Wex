package wex.ui.core.feature

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import wex.ui.core.WexStore
import de.robv.android.xposed.XposedBridge

/**
 * 桌面悬浮歌词。
 * 依附微信（TYPE_APPLICATION_PANEL，无需悬浮窗权限，仅在微信界面内可见）。
 * 方案A：独立文件，自起每秒 Handler 读 MusicCardFeature 的歌词/进度，不侵入播放核心。
 * 状态（开/关、锁定/可拖动）用内存字段，不做持久化。
 */
object FloatLyricFeature {

    private val M = MusicCardFeature
    private val mh = Handler(Looper.getMainLooper())

    private var floatLyricView: View? = null
    private var floatLyricParams: WindowManager.LayoutParams? = null
    private var floatWindowManager: WindowManager? = null
    private var floatLyricTv: TextView? = null

    // 内存状态（不持久化，与源插件一致）
    private var floatLyricEnabled = false
    private var floatLyricLocked = false

    private var updateRunnable: Runnable? = null

    fun isEnabled(): Boolean = floatLyricEnabled
    fun isLocked(): Boolean = floatLyricLocked

    // ==================== 初始化悬浮词 View ====================
    private fun initFloatLyric(act: Activity) {
        try {
            val ctx: Context = act
            floatWindowManager = act.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val d = ctx.resources.displayMetrics.density
            val sw = ctx.resources.displayMetrics.widthPixels

            val ll = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.parseColor("#CCE8DEEE"))
                    cornerRadius = 20 * d
                }
            }

            // 音符图标
            ll.addView(TextView(ctx).apply {
                text = "\uD83C\uDFB5"
                textSize = 16f
                setPadding(0, 0, (8 * d).toInt(), 0)
            })

            // 歌词文字
            val tv = TextView(ctx).apply {
                text = "未播放"
                textSize = 13f
                setTextColor(Color.parseColor("#333333"))
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
            floatLyricTv = tv
            ll.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))

            floatLyricView = ll

            // WindowManager 参数（依附微信 Activity 窗口）
            val params = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                format = PixelFormat.TRANSLUCENT
                gravity = Gravity.TOP or Gravity.START
                width = (sw * 0.85).toInt()
                height = WindowManager.LayoutParams.WRAP_CONTENT
                x = (sw * 0.075).toInt()
                y = (100 * d).toInt()
            }
            floatLyricParams = params

            // 触摸拖动
            val lastX = intArrayOf(0)
            val lastY = intArrayOf(0)
            val startX = intArrayOf(0)
            val startY = intArrayOf(0)
            ll.setOnTouchListener { _, event ->
                if (floatLyricLocked) return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX[0] = event.rawX.toInt()
                        lastY[0] = event.rawY.toInt()
                        startX[0] = params.x
                        startY[0] = params.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX.toInt() - lastX[0]
                        val dy = event.rawY.toInt() - lastY[0]
                        params.x = startX[0] + dx
                        params.y = startY[0] + dy
                        try { floatWindowManager?.updateViewLayout(floatLyricView, params) } catch (_: Exception) {}
                        true
                    }
                    else -> false
                }
            }

            log("悬浮词初始化成功")
        } catch (e: Exception) {
            log("悬浮词初始化失败: $e")
        }
    }

    // ==================== 显示悬浮词 ====================
    fun showFloatLyric(act: Activity?) {
        val a = act ?: M._act() ?: return
        if (floatLyricView == null) initFloatLyric(a)
        val v = floatLyricView ?: return
        if (v.parent == null) {
            try {
                floatWindowManager?.addView(v, floatLyricParams)
                floatLyricEnabled = true
                startUpdate()
                log("悬浮词已显示")
            } catch (e: Exception) {
                log("悬浮词显示失败: $e")
            }
        }
    }

    // ==================== 隐藏悬浮词 ====================
    fun hideFloatLyric() {
        val v = floatLyricView
        if (v != null && v.parent != null) {
            try { floatWindowManager?.removeView(v) } catch (_: Exception) {}
        }
        floatLyricEnabled = false
        stopUpdate()
    }

    // ==================== 设置锁定状态 ====================
    fun setLocked(locked: Boolean) {
        floatLyricLocked = locked
    }

    fun toggleLock() {
        floatLyricLocked = !floatLyricLocked
        M.toast(if (floatLyricLocked) "悬浮词已锁定" else "悬浮词可拖动")
    }

    // ==================== 每秒更新歌词（方案A：本类自起循环） ====================
    private fun startUpdate() {
        if (updateRunnable != null) return
        updateRunnable = object : Runnable {
            override fun run() {
                updateFloatLyric()
                mh.postDelayed(this, 1000)
            }
        }
        mh.post(updateRunnable!!)
    }

    private fun stopUpdate() {
        updateRunnable?.let { mh.removeCallbacks(it) }
        updateRunnable = null
    }

    private fun updateFloatLyric() {
        val tv = floatLyricTv ?: return
        val song = M.getCurrentSong() ?: return
        val lyric = M.getLyricAt(M.getCurrentPos()) ?: song.title
        mh.post { floatLyricTv?.text = lyric }
    }

    private fun log(msg: String) {
        XposedBridge.log("WEX: $msg")
        try { WexStore.appendLog(msg) } catch (_: Exception) {}
    }
}