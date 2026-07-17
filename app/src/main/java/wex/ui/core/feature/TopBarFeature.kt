package wex.ui.core.feature

import android.app.Activity
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import wex.ui.core.WexStore
import wex.ui.core.core.ViewFinder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 顶栏改造（第一步：搜索框）。
 * 目标：隐藏微信原生右上角搜索按钮（contentDescription="搜索" 的 jha），
 *      在聊天列表顶部插入一个自定义搜索框卡片，点击后调用原生搜索（performClick 原按钮）。
 *
 * 定位方式：靠 contentDescription="搜索"（不依赖混淆 id，跨版本稳）。
 * 插入方式：方案X — addHeaderView 到聊天列表顶部（会随列表滚动），先跑通闭环。
 * 防重复：用 decor.findViewWithTag(TAG_SEARCH) 判断，不占用 listView.tag（避免与 HomeCardManager 冲突）。
 */
object TopBarFeature {

    private const val TAG_SEARCH = "wex_top_search"
    private const val TAG_PROFILE = "wex_top_profile"

    // 原生搜索按钮引用（找到后存起来，供 performClick 调起原生搜索）
    private var searchBtn: View? = null

    // 模块 APK 路径（跨进程加载 assets 图标用）
    private var modulePath: String? = null
    private var cachedIcon: Bitmap? = null

    fun init(lpparam: XC_LoadPackage.LoadPackageParam, modPath: String?) {
        modulePath = modPath
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java, "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val act = param.thisObject as? Activity ?: return
                        if (act.javaClass.name != "com.tencent.mm.ui.LauncherUI") return
                        applyTopBar(act)
                    }
                }
            )
            log("顶栏改造 Hook 已注册")
        } catch (e: Throwable) {
            log("顶栏改造 Hook 失败: ${e.message}")
        }

        // Hook TextView.setText：把顶栏标题"微信"替换成自定义标题（从源头拦截，避免被微信刷回）
        // 极简判断：仅当文字恰为"微信"且用户设了自定义标题时才改，其它 TextView 不受影响
        try {
            XposedHelpers.findAndHookMethod(
                TextView::class.java, "setText",
                CharSequence::class.java, TextView.BufferType::class.java,
                Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cs = param.args[0] as? CharSequence ?: return
                        if (cs.toString() != "微信") return
                        if (!WexStore.getBool("top_profile_enabled", true)) return
                        val custom = WexStore.getString("top_title", "")
                        if (custom.isNotEmpty()) param.args[0] = custom
                    }
                }
            )
            log("顶栏标题 setText Hook 已注册")
        } catch (e: Throwable) {
            log("顶栏标题 setText Hook 失败: ${e.message}")
        }
    }

    // 从模块 assets 加载搜索图标（跨进程）
    private fun loadSearchIcon(): Bitmap? {
        cachedIcon?.let { return it }
        val path = modulePath ?: return null
        return try {
            val am = AssetManager::class.java.newInstance()
            AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(am, path)
            am.open("topbar_search.png").use { BitmapFactory.decodeStream(it) }?.also { cachedIcon = it }
        } catch (e: Exception) { log("搜索图标加载失败: ${e.message}"); null }
    }

    private fun applyTopBar(act: Activity) {
        val decor = act.window?.decorView as? ViewGroup ?: return
        decor.post {
            try {
                // ===== 搜索框（受 top_search_bar 开关控制）=====
                if (WexStore.getBool("top_search_bar", true)) {
                    // 1) 找到原生搜索按钮并隐藏
                    val btn = ViewFinder.findViewByDesc(decor, "搜索")
                    if (btn != null) {
                        searchBtn = btn
                        btn.visibility = View.GONE
                        log("顶栏：原搜索按钮已隐藏 ${btn.javaClass.name}")
                    } else { log("顶栏：未找到搜索按钮(contentDescription=搜索)") }

                    // 2) 防重复
                    if (decor.findViewWithTag<View>(TAG_SEARCH) == null) {
                        // 3) 插入自定义搜索框
                        val listView = ViewFinder.findListView(decor)
                        if (listView != null) {
                            val bar = buildSearchBar(act)
                            listView.javaClass.getMethod(
                                "addHeaderView", View::class.java, Any::class.java, Boolean::class.javaPrimitiveType
                            ).invoke(listView, bar, null, false)
                            log("顶栏：自定义搜索框已插入（列表顶部）")
                        } else { log("顶栏：未找到聊天列表") }
                    }
                }

                // ===== 头像昵称（受 top_profile_enabled 开关控制）=====
                if (WexStore.getBool("top_profile_enabled", true)) {
                    try { insertProfile(act, decor) } catch (e: Exception) { log("顶栏：插入头像昵称失败 ${e.message}") }
                }
            } catch (e: Exception) {
                log("顶栏改造失败: ${e.message}")
            }
        }
    }

    // 构建搜索框卡片（圆角灰底 + 放大镜 + "搜索"提示），点击调起原生搜索
    private fun buildSearchBar(act: Activity): View {
        val d = act.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()

        // 外层容器：左右留间距（居中约 90% 宽），上下留点空隙
        val wrapper = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = TAG_SEARCH
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        }

        // 搜索框本体：圆角灰底，内容整体居中
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER   // 内容居中
            setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, v.height / 2f)
                }
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#F2F2F2"))
                cornerRadius = dp(20f).toFloat()
            }
            isClickable = true
            setOnClickListener {
                // 调起微信原生搜索：点击被隐藏的原按钮
                try { searchBtn?.performClick() } catch (e: Exception) { log("调起原生搜索失败: ${e.message}") }
            }
        }

        // 搜索图标（真图标，18dp；加载失败则不显示图标只留文字）
        loadSearchIcon()?.let { bmp ->
            box.addView(ImageView(act).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(18f), dp(18f))
            })
        }
        // 提示文字：灰黑色（可自定义，空则"搜索"）
        box.addView(TextView(act).apply {
            text = WexStore.getString("top_search_hint", "").ifEmpty { "搜索" }
            textSize = 14f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.CENTER
            setPadding(dp(6f), 0, 0, 0)
        })

        wrapper.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return wrapper
    }

    // ==================== 顶栏左侧：头像 + 昵称 + 状态（全部可自定义） ====================
    // 放进顶栏 Toolbar 里的 RelativeLayout 最左侧，不挤压居中标题
    private fun insertProfile(act: Activity, decor: ViewGroup) {
        // 找撑满宽度的 Toolbar（ef=Toolbar 宽384dp）
        val toolbar = ViewFinder.findViewByClassName(decor, "Toolbar") as? ViewGroup
            ?: run { log("顶栏：未找到 Toolbar"); return }

        // 标题文字自定义：由 setText Hook 从源头拦截替换（见 init），此处无需再处理

        // 防重复
        if (toolbar.findViewWithTag<View>(TAG_PROFILE) != null) return

        // Toolbar 里那个撑满宽度的 RelativeLayout 才是真正装内容的容器
        var container: ViewGroup = toolbar
        for (i in 0 until toolbar.childCount) {
            val c = toolbar.getChildAt(i)
            if (c is android.widget.RelativeLayout) { container = c; break }
        }

        val profile = buildProfile(act) ?: return   // 无任何内容(头像/昵称/状态全空)则不插
        if (container is android.widget.RelativeLayout) {
            profile.layoutParams = android.widget.RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT)
                addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
            }
        } else {
            profile.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(profile)
        log("顶栏：头像昵称已插入 容器=${container.javaClass.name}")
    }

    // 加载头像：优先自定义文件(top_avatar_path)，无则不显示（返回 null）
    private fun loadAvatar(): Bitmap? {
        val custom = WexStore.getString("top_avatar_path", "")
        if (custom.isNotEmpty()) {
            try {
                val f = java.io.File(custom)
                if (f.exists()) return BitmapFactory.decodeFile(custom)
            } catch (e: Exception) { log("自定义头像加载失败: ${e.message}") }
        }
        return null
    }

    // 构建"头像 + 昵称 + 状态"块；三者按 WexStore 配置显示，全空则返回 null
    private fun buildProfile(act: Activity): View? {
        val d = act.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()

        val nickname = WexStore.getString("top_nickname", "")
        val status = WexStore.getString("top_status", "")
        val dotRed = WexStore.getString("top_dot_color", "green") == "red"
        val avatarBmp = loadAvatar()

        // 三者全空则不显示整个块
        if (avatarBmp == null && nickname.isEmpty() && status.isEmpty()) return null

        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = TAG_PROFILE
            setPadding(dp(12f), 0, 0, 0)
            layoutParams = android.widget.RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { addRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT); addRule(android.widget.RelativeLayout.CENTER_VERTICAL) }
        }

        // 头像（有才显示）
        if (avatarBmp != null) {
            val avatarSize = dp(44f)
            row.addView(ImageView(act).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(8f).toFloat()) }
                }
                layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize)
                setImageBitmap(avatarBmp)
            })
        }

        // 昵称 + 状态（竖排，有才显示）
        if (nickname.isNotEmpty() || status.isNotEmpty()) {
            val textCol = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6f), 0, 0, 0)
            }
            if (nickname.isNotEmpty()) {
                textCol.addView(TextView(act).apply {
                    text = nickname
                    textSize = 13f
                    setTextColor(Color.parseColor("#1A1A1A"))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    isSingleLine = true
                })
            }
            // 状态行：圆点 + 状态文字（状态文字空则整行不显示，圆点也不显示）
            if (status.isNotEmpty()) {
                val statusRow = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                statusRow.addView(View(act).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor(if (dotRed) "#F44336" else "#4CAF50"))
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(6f), dp(6f))
                })
                statusRow.addView(TextView(act).apply {
                    text = status
                    textSize = 10f
                    setTextColor(Color.parseColor("#999999"))
                    setPadding(dp(3f), 0, 0, 0)
                })
                textCol.addView(statusRow)
            }
            row.addView(textCol)
        }

        return row
    }

    private fun log(msg: String) {
        XposedBridge.log("WEX: $msg")
        try { WexStore.appendLog(msg) } catch (_: Exception) {}
    }
}