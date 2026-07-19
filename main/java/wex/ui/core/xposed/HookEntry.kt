package wex.ui.core.xposed

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.graphics.Outline
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import wex.ui.core.WexStore
import java.text.SimpleDateFormat
import java.util.*
import java.nio.charset.Charset

class HookEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        const val TARGET_PACKAGE = "com.tencent.mm"
        const val TAG = "WEX"

        private val styledViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
        private var lastActivity: Activity? = null
        // 模块 APK 路径（initZygote 时由框架提供），用于跨进程加载模块 assets 里的图标
        private var modulePath: String? = null
        // 图标 Bitmap 缓存，避免每次弹菜单都重新解码
        private var cachedIcon: Bitmap? = null

        @JvmStatic
        fun log(msg: String) {
            XposedBridge.log("$TAG: $msg")
            try {
                val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                WexStore.appendLog("[$ts] $msg")
            } catch (_: Exception) {}
        }
    }

    // 框架最早回调，记录模块 APK 路径，供后续加载模块 assets 使用
    

    // 版本配置文件读取
    private data class MenuConfig(
        val contentView: String,
        val itemId: String,
        val iconId: String,
        val textId: String
    ) {
        constructor(map: Map<String, Any>) : this(
            map["contentView"] as? String ?: "",
            map["itemId"] as? String ?: "",
            map["iconId"] as? String ?: "",
            map["textId"] as? String ?: ""
        )
    }

    private fun loadMenuConfig(): MenuConfig? {
        return try {
            val ctx = lastActivity ?: return null
            val am = android.content.res.AssetManager::class.java.newInstance() as android.content.res.AssetManager
            val addPath = android.content.res.AssetManager::class.java.getMethod("addAssetPath", String::class.java)
            val modPath = modulePath ?: return null
            addPath.invoke(am, modPath)
            val input = am.open("version_map.json")
            val text = input.bufferedReader(java.nio.charset.Charset.forName("UTF-8")).use { it.readText() }
            val root = org.json.JSONObject(text)
            val versions = root.getJSONObject("versions")
            val verMap = HashMap<String, MenuConfig>()
            for (key in versions.keys()) {
                val obj = versions.getJSONObject(key)
                verMap[key] = MenuConfig(
                    obj.getString("contentView"),
                    obj.getString("itemId"),
                    obj.getString("iconId"),
                    obj.getString("textId")
                )
            }
            // 读取当前微信版本
            val pkg = ctx.packageManager.getPackageInfo("com.tencent.mm", 0)
            val curVer = pkg.versionName ?: return null
            // 精确匹配，没有则找默认
            verMap[curVer] ?: verMap["8.0.76"] ?: verMap.values.firstOrNull()
        } catch (e: Exception) {
            log("version_map.json 加载失败: ${e.message}")
            null
        }
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }


    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        log("微信进程已加载")
hookBottomBar()
        hookPlusMenu(lpparam)
        // 首页卡片（统一管理器）
        log("准备初始化首页卡片...")
        try {
            wex.ui.core.feature.HomeCardManager.init(lpparam, modulePath)
            wex.ui.core.feature.ImagePicker.init(lpparam)
            wex.ui.core.feature.TopBarFeature.init(lpparam, modulePath)
            log("首页卡片 init 调用完成")
        } catch (e: Throwable) {
            log("首页卡片 init 抛异常: ${e}")
        }
    }

    // ==================== 底栏 Hook ====================
    private fun hookBottomBar() {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.thisObject.javaClass.name != "com.tencent.mm.ui.LauncherUI") return
                        lastActivity = param.thisObject as Activity
                        try {
                            val activity = param.thisObject as Activity
                            val handler = Handler(Looper.getMainLooper())
                            val decor = activity.window.decorView as? ViewGroup ?: return
                            tryApplyStyle(decor, handler, 0)
                        } catch (e: Exception) {
                            log("onResume 异常: ${e.message}")
                        }
                    }
                }
            )
            log("Activity.onResume Hook 已注册")
        } catch (e: Exception) {
            log("底栏 Hook 失败: ${e.message}")
        }
    }

    // ==================== 加号菜单诊断（摸清结构，暂不注入） ====================
    // 微信首页右上角 ➕ 菜单：发起群聊/添加朋友/扫一扫/收付款。
    // 承载方式未知（PopupWindow? RecyclerView? 静态布局?），先 hook PopupWindow 显示时机 dump 其结构。
    private fun hookPlusMenu(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 加号菜单是 PopupWindow(contentView=db5.c5)，容器 db5.y3 内堆叠 m7g 菜单项。
            // 弹出时克隆一个 m7g 当模板，改成"WEX 设置"并 addView 进容器。
            val popupClass = android.widget.PopupWindow::class.java
            val cb = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val popup = param.thisObject as android.widget.PopupWindow
                        val content = popup.contentView ?: return
                        // 延迟到布局完成后再注入
                        Handler(Looper.getMainLooper()).postDelayed({
                            injectPlusMenuItem(content)
                        }, 100)
                    } catch (_: Exception) {}
                }
            }
            XposedHelpers.findAndHookMethod(popupClass, "showAsDropDown",
                View::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, cb)
            XposedHelpers.findAndHookMethod(popupClass, "showAtLocation",
                View::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, cb)
            log("加号菜单 PopupWindow Hook 已注册")
        } catch (e: Exception) {
            log("加号菜单 Hook 失败: ${e.message}")
        }
    }

    // 往加号菜单容器（db5.y3）里克隆一个 m7g 模板，改为"WEX 设置"项
    private fun injectPlusMenuItem(content: View) {
        try {
            val ctx = content.context
            // 从版本配置读取当前版参数
            val cfg = loadMenuConfig()
            if (cfg == null) { log("加号菜单：未找到版本配置"); return }
            // 只处理加号菜单：contentView 类名匹配（避免误伤其他 PopupWindow）
            if (!content.javaClass.name.contains(cfg.contentView)) {
                log("加号菜单：contentView 不匹配 (" + content.javaClass.name + " != " + cfg.contentView + ")")
                return
            }

            // 找到菜单项容器：包含多个 m7g 子项的那个 ViewGroup
            val mItemId = ctx.resources.getIdentifier(cfg.itemId, "id", "com.tencent.mm")
            if (mItemId == 0) { log("加号菜单：找不到 m7g 资源 id"); return }
            val template = findFirstById(content, mItemId) as? ViewGroup ?: run {
                log("加号菜单：未找到 m7g 模板项"); return
            }
            val container = template.parent as? ViewGroup ?: run {
                log("加号菜单：m7g 无父容器"); return
            }
            // 防重复注入
            if (container.findViewWithTag<View>("wex_plus_item") != null) return

            // 克隆一个模板项：微信 m7g 是 LinearLayout，直接用 LayoutInflater 无法克隆，
            // 改用"取模板项的类型 + 反射 clone 结构"不现实，故采用最稳做法：
            // 直接照搬第一个 m7g 的可视结构，用它的 obc(文字TextView) 定位并复制样式。
            val obcId = ctx.resources.getIdentifier(cfg.textId, "id", "com.tencent.mm")
            val h5nId = ctx.resources.getIdentifier(cfg.iconId, "id", "com.tencent.mm")

            // 用固定高度新建一个 LinearLayout 作为菜单项
            val density = ctx.resources.displayMetrics.density
            // 模板项的实测高度（如 204px），用它作为我们项的高度，避免 WRAP_CONTENT 塌缩成半高
            val itemHeight = if (template.height > 0) template.height
                             else template.layoutParams?.height?.takeIf { it > 0 } ?: (56 * density).toInt()
            val item = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                tag = "wex_plus_item"
                // 独立 LayoutParams：宽度撑满、高度=模板实测高度（此前共享模板LP且高度WRAP导致仅半高）
                layoutParams = android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, itemHeight
                )
                // 关键：复制模板 m7g 的 padding —— 原生项的左留白来自这里（此前缺失导致图标贴边）
                setPadding(template.paddingLeft, template.paddingTop, template.paddingRight, template.paddingBottom)
                // 复用模板背景（点击态一致）
                try {
                    val bgField = template.background
                    if (bgField != null) background = bgField.constantState?.newDrawable()?.mutate()
                } catch (_: Exception) {}
                isClickable = true
                setOnClickListener { openFeatureSettings(ctx) }
            }

            // 文字：复制模板里 obc 的文字样式
            val tmplText = if (obcId != 0) findFirstById(template, obcId) as? android.widget.TextView else null
            val textView = android.widget.TextView(ctx).apply {
                text = "WEX 设置"
                if (tmplText != null) {
                    setTextColor(tmplText.currentTextColor)
                    textSize = tmplText.textSize / density  // px -> sp
                    typeface = tmplText.typeface
                }
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            // 图标：从模块 assets 加载（白色图标，深色菜单上直接显示，不染色）
            val tmplIcon = if (h5nId != 0) findFirstById(template, h5nId) as? View else null
            val iconBitmap = loadModuleIcon()
            if (tmplIcon != null && iconBitmap != null) {
                val iconView = ImageView(ctx).apply {
                    setImageBitmap(iconBitmap)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    // 复用模板图标的 LayoutParams（含尺寸与 margin），保证与原生项对齐
                    layoutParams = tmplIcon.layoutParams
                }
                item.addView(iconView)
                // 图标与文字之间的间距：复制模板文字容器相对图标的 marginStart（此前缺失导致文字贴图标）
                val tmplTextParent = tmplText?.parent as? View
                val gap = ((tmplTextParent?.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart) ?: (12 * density).toInt()
                textView.layoutParams = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = gap }
            } else if (tmplIcon != null) {
                // 加载失败则用等尺寸空占位，至少保证文字对齐
                val iconPad = View(ctx).apply { layoutParams = tmplIcon.layoutParams }
                item.addView(iconPad)
                log("加号菜单：图标加载失败，用空占位（modulePath=$modulePath）")
            }
            item.addView(textView)

            // container 是 AdapterView(ListView)，不能 addView，需用 ListView.addFooterView。
            // addFooterView 必须在设置 adapter 之前或用支持的方式；运行时已 setAdapter，
            // 故用 addFooterView 追加到尾部（ListView 支持运行时加 footer 并自动刷新）。
            if (container is android.widget.ListView) {
                container.addFooterView(item)
            } else {
                container.addView(item)
                log("加号菜单：WEX 设置项已 addView 到 ${container.javaClass.name}")
            }
        } catch (e: Exception) {
            log("加号菜单注入异常: ${e.message}")
        }
    }
    // 在视图树中按 id 查找第一个匹配的 View（BFS）
        // 诊断：打印加号菜单完整结构
private fun findFirstById(root: View, id: Int): View? {
        if (root.id == id) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findFirstById(root.getChildAt(i), id)?.let { return it }
            }
        }
        return null
    }

    // 从模块 APK 的 assets 加载图标 Bitmap（跨进程：微信进程用 AssetManager 打开模块 APK）
    private fun loadModuleIcon(): Bitmap? {
        cachedIcon?.let { return it }
        val path = modulePath ?: run { log("加号菜单：modulePath 为空，无法加载图标"); return null }
        return try {
            val am = AssetManager::class.java.newInstance()
            val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
            addAssetPath.invoke(am, path)
            am.open("wex_setting_icon.png").use { input ->
                val bmp = BitmapFactory.decodeStream(input)
                cachedIcon = bmp
                bmp
            }
        } catch (e: Exception) {
            log("加号菜单：加载图标异常 ${e.message}")
            null
        }
    }

    // ==================== #wex 聊天指令（已移除：8.0.74 类名失效，改用加号菜单入口） ====================

    // 弹出功能设置面板：用 Dialog 附着在当前微信 Activity 上（依附微信、不跳出、无需权限）
    private fun openFeatureSettings(context: Context?) {
        // 必须用 Activity 上下文，Dialog 才能附着到微信界面
        val act = (context as? Activity) ?: lastActivity ?: return
        try {
            Handler(Looper.getMainLooper()).post {
                try { showFeatureDialog(act) } catch (e: Exception) { log("弹出面板失败: ${e.message}") }
            }
        } catch (e: Exception) {
            log("弹出面板异常: ${e.message}")
        }
    }

    // 原生 View 手绘 MD3 卡片风格的功能设置面板
    private fun showFeatureDialog(act: Activity) {
        val density = act.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        val uiMode = act.resources.configuration.uiMode
        val isDark = (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val pageBg = if (isDark) Color.parseColor("#121212") else Color.parseColor("#FFFFFF")
        val itemBg = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#F5F5F5")
        val titleColor = if (isDark) Color.parseColor("#E0E0E0") else Color.parseColor("#212121")
        val subColor = if (isDark) Color.parseColor("#64B5F6") else Color.parseColor("#1976D2")
        val textColor = if (isDark) Color.parseColor("#E0E0E0") else Color.parseColor("#212121")
        val descColor = if (isDark) Color.parseColor("#888888") else Color.parseColor("#757575")
        val accent = if (isDark) Color.parseColor("#64B5F6") else Color.parseColor("#1976D2")

        // 用系统透明全屏主题构造，从主题层剥离默认 Dialog 的背景/遮罩（三星灰条元凶）
        val dialog = android.app.Dialog(act, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)

        // 整页容器（全屏、页面式）
        val statusBarH = act.resources.getIdentifier("status_bar_height", "dimen", "android")
            .let { if (it > 0) act.resources.getDimensionPixelSize(it) else dp(24f) }
        val page = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // 不在 page 顶部留 padding（那会形成一条独立灰色横条）；
            // 改为让 topBar 顶到状态栏、用状态栏高度做上 padding，背景色连续无缝
        }

        // 顶部标题栏：大字号标题 + 蓝色英文副标题（无返回箭头）
        // 上 padding = 状态栏高度 + 16dp，使标题在状态栏下方，且整块背景连续填充状态栏区域
        val topBar = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), statusBarH + dp(16f), dp(16f), dp(12f))
        }
        topBar.addView(android.widget.TextView(act).apply {
            text = "功能设置"
            textSize = 26f
            setTextColor(titleColor)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        topBar.addView(android.widget.TextView(act).apply {
            text = "WeChat Enhance Center"
            textSize = 13f
            setTextColor(subColor)
            setPadding(0, dp(2f), 0, 0)
        })
        page.addView(topBar)

        // 内容滚动区
        val scroll = android.widget.ScrollView(act).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), dp(20f))
        }

        // 分组标题
        content.addView(android.widget.TextView(act).apply {
            text = "界面美化"
            textSize = 14f
            setTextColor(subColor)
            setPadding(dp(4f), dp(8f), 0, dp(8f))
        })

        // 开关项：悬浮圆角底栏
        content.addView(buildSwitchItem(
            act, itemBg, textColor, descColor, accent, dp = ::dp,
            title = "悬浮圆角底栏",
            desc = "微信底栏改为悬浮圆角样式，含气泡光晕",
            key = "bottom_bar_enabled"
        ))

        // 分组标题：首页卡片
        content.addView(android.widget.TextView(act).apply {
            text = "首页卡片"
            textSize = 14f
            setTextColor(subColor)
            setPadding(dp(4f), dp(16f), 0, dp(8f))
        })

        // 开关项：图片卡片
        content.addView(buildSwitchItem(
            act, itemBg, textColor, descColor, accent, dp = ::dp,
            title = "图片卡片",
            desc = "聊天列表顶部显示图片卡",
            key = "home_image_card"
        ))
        // 开关项：日历卡片（功能待实现）
        content.addView(buildSwitchItem(
            act, itemBg, textColor, descColor, accent, dp = ::dp,
            title = "日历卡片",
            desc = "聊天列表顶部显示日历卡（黄历/天气/一言）",
            key = "home_calendar_card"
        ))
        // 开关项：音乐播放器
        content.addView(buildSwitchItem(
            act, itemBg, textColor, descColor, accent, dp = ::dp,
            title = "音乐播放器",
            desc = "聊天列表顶部显示音乐播放器",
            key = "home_music_card"
        ))
        // 全部关闭：一键关掉所有首页卡片
        content.addView(buildActionButton(act, itemBg, Color.parseColor("#FF4444"), ::dp, "全部关闭") {
            WexStore.putBool("home_calendar_card", false)
            WexStore.putBool("home_image_card", false)
            WexStore.putBool("home_music_card", false)
            wex.ui.core.feature.ImageCardFeature.clearCache()
            wex.ui.core.feature.CalendarCardFeature.clearCache()
            wex.ui.core.feature.MusicCardFeature.clearCache()
            wex.ui.core.feature.ImagePicker.toast(act, "已全部关闭，正在重启微信...")
            wex.ui.core.feature.ImagePicker.hotRestartWechat(act)
        })

        // 分组标题：顶栏（个人信息自定义）
        content.addView(android.widget.TextView(act).apply {
            text = "顶栏"
            textSize = 14f
            setTextColor(subColor)
            setPadding(dp(4f), dp(16f), 0, dp(8f))
        })
        // 总开关
        content.addView(buildSwitchItem(
            act, itemBg, textColor, descColor, accent, dp = ::dp,
            title = "顶栏个人信息",
            desc = "在微信标题栏左侧显示头像/昵称/状态",
            key = "top_profile_enabled"
        ))
        // 搜索框开关
        content.addView(buildSwitchItem(
            act, itemBg, textColor, descColor, accent, dp = ::dp,
            title = "顶栏搜索框",
            desc = "在聊天列表顶部显示自定义搜索框，点击调用原生搜索",
            key = "top_search_bar"
        ))
        // 标题文字（居中，如"微信"→"微博"；空则保持原样）
        content.addView(buildActionButton(act, itemBg, accent, ::dp, "设置标题文字") {
            showTextInput(act, "设置标题文字", "top_title", "留空则保持微信原样")
        })
        // 昵称
        content.addView(buildActionButton(act, itemBg, accent, ::dp, "设置昵称") {
            showTextInput(act, "设置昵称", "top_nickname", "留空则不显示")
        })
        // 状态文字
        content.addView(buildActionButton(act, itemBg, accent, ::dp, "设置状态文字") {
            showTextInput(act, "设置状态文字", "top_status", "如：在线；留空则不显示")
        })
        // 状态圆点颜色：绿/红切换
        content.addView(buildActionButton(act, itemBg, accent, ::dp, "切换圆点颜色（绿/红）") {
            val cur = WexStore.getString("top_dot_color", "green")
            val next = if (cur == "red") "green" else "red"
            WexStore.putString("top_dot_color", next)
            wex.ui.core.feature.ImagePicker.toast(act, "圆点已设为${if (next == "red") "红色" else "绿色"}，正在重启微信...")
            wex.ui.core.feature.ImagePicker.hotRestartWechat(act)
        })
        // 更换头像
        content.addView(buildActionButton(act, itemBg, accent, ::dp, "更换顶栏头像") {
            dialog.dismiss()
            wex.ui.core.feature.ImagePicker.pick(act, "top_avatar_path") {
                wex.ui.core.feature.ImagePicker.toast(act, "头像已更新，正在重启微信...")
                wex.ui.core.feature.ImagePicker.hotRestartWechat(act)
            }
        })
        // 搜索框提示文字
        content.addView(buildActionButton(act, itemBg, accent, ::dp, "设置搜索框提示文字") {
            showTextInput(act, "搜索框提示文字", "top_search_hint", "留空则显示“搜索”")
        })

        // 分组标题：更换背景（所有"更换背景"按钮统一放这一组，布局整齐）
        content.addView(android.widget.TextView(act).apply {
            text = "更换背景"
            textSize = 14f
            setTextColor(subColor)
            setPadding(dp(4f), dp(16f), 0, dp(8f))
        })
        // 更换图片卡背景
        content.addView(buildActionButton(act, itemBg, accent, ::dp, "更换图片卡背景") {
            dialog.dismiss()
            wex.ui.core.feature.ImagePicker.pick(act, "image_card_bg") {
                wex.ui.core.feature.ImageCardFeature.clearCache()
                wex.ui.core.feature.ImagePicker.toast(act, "背景已更新，正在重启微信...")
                wex.ui.core.feature.ImagePicker.hotRestartWechat(act)
            }
        })
        // 更换日历卡背景
        content.addView(buildActionButton(act, itemBg, accent, ::dp, "更换日历卡背景") {
            dialog.dismiss()
            wex.ui.core.feature.ImagePicker.pick(act, "calendar_bg") {
                wex.ui.core.feature.CalendarCardFeature.clearCache()
                wex.ui.core.feature.ImagePicker.toast(act, "背景已更新，正在重启微信...")
                wex.ui.core.feature.ImagePicker.hotRestartWechat(act)
            }
        })

        // 分组标题：其他
        content.addView(android.widget.TextView(act).apply {
            text = "其他"
            textSize = 14f
            setTextColor(subColor)
            setPadding(dp(4f), dp(16f), 0, dp(8f))
        })
        // 更新定位（日历卡天气用）
        content.addView(buildActionButton(act, itemBg, accent, ::dp, "更新定位") {
            wex.ui.core.feature.ImagePicker.toast(act, "正在定位...")
            wex.ui.core.feature.LocationHelper.updateLocation(act) { ok, msg ->
                wex.ui.core.feature.ImagePicker.toast(act, msg)
                if (ok) {
                    // 定位成功：清日历缓存，重启微信刷新天气
                    wex.ui.core.feature.CalendarCardFeature.clearCache()
                    wex.ui.core.feature.ImagePicker.hotRestartWechat(act)
                }
            }
        })

        scroll.addView(content)
        page.addView(scroll)

        dialog.setContentView(page)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(pageBg))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // 进入/退出动画：营造"跳转到页面"的观感
            setWindowAnimations(android.R.style.Animation_Activity)
            try {
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(this, false)
                clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                // 暴力指令：不受系统边框约束，内容画满整屏（含状态栏），彻底消除灰条
                addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                // 清除背景变暗
                clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 0f }
                addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                statusBarColor = Color.TRANSPARENT
                navigationBarColor = Color.TRANSPARENT
                // 刘海屏：内容延伸到刘海区域，避免黑边
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    attributes = attributes.apply {
                        layoutInDisplayCutoutMode =
                            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                // 状态栏图标颜色随深浅色（浅色面板用深色图标）
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !isDark) {
                    decorView.systemUiVisibility = decorView.systemUiVisibility or
                        android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
            } catch (_: Exception) {}
        }
        dialog.show()
        log("功能设置面板已弹出（全屏依附微信）")
    }

    // 构建一个开关行（原生 View 复刻 MD3 卡片项），状态读写走 WexStore
    private fun buildSwitchItem(
        act: Activity, itemBg: Int, textColor: Int, descColor: Int, accent: Int,
        dp: (Float) -> Int, title: String, desc: String, key: String
    ): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            background = GradientDrawable().apply {
                setColor(itemBg); cornerRadius = dp(20f).toFloat()
            }
            // 浮雕/浮起效果：阴影 + 底部外边距（给阴影留空间）
            elevation = dp(4f).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12f) }
        }
        val textCol = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(android.widget.TextView(act).apply {
            text = title; textSize = 16f; setTextColor(textColor)
        })
        textCol.addView(android.widget.TextView(act).apply {
            text = desc; textSize = 12f; setTextColor(descColor); setPadding(0, dp(2f), 0, 0)
        })
        row.addView(textCol)
        row.addView(android.widget.Switch(act).apply {
            isChecked = WexStore.getBool(key, true)
            setOnCheckedChangeListener { btn, checked ->
                // 仅用户手动拨动才触发（排除 isChecked 初始化时的回调）
                if (!btn.isPressed) return@setOnCheckedChangeListener
                WexStore.putBool(key, checked)
                wex.ui.core.feature.ImagePicker.toast(act, "设置已保存，正在重启微信...")
                wex.ui.core.feature.ImagePicker.hotRestartWechat(act)
            }
        })
        return row
    }

    // 构建一个操作按钮（MD3 卡片风，浮起阴影），用于"更换背景"等动作
    private fun buildActionButton(
        act: Activity, itemBg: Int, accent: Int,
        dp: (Float) -> Int, text: String, onClick: () -> Unit
    ): View {
        return android.widget.TextView(act).apply {
            this.text = text
            textSize = 15f
            setTextColor(accent)
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(14f), 0, dp(14f))
            background = GradientDrawable().apply {
                setColor(itemBg); cornerRadius = dp(20f).toFloat()
            }
            elevation = dp(4f).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12f) }
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    // MD3 圆角输入框弹窗：输入文字存入 WexStore(key)，确定后热重启微信
    private fun showTextInput(act: Activity, title: String, key: String, hint: String) {
        val density = act.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        val sw = act.resources.displayMetrics.widthPixels

        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding(dp(24f), dp(20f), dp(24f), dp(20f))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(24f).toFloat()) }
            }
        }
        panel.addView(android.widget.TextView(act).apply {
            text = title; textSize = 18f; setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, dp(16f))
        })

        val input = android.widget.EditText(act).apply {
            this.hint = hint
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
            setHintTextColor(Color.parseColor("#AAAAAA"))
            setText(WexStore.getString(key, ""))
            setSingleLine(true)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F7F7F7"))
                cornerRadius = dp(14f).toFloat()
                setStroke((1.5f * density).toInt(), Color.parseColor("#1A73E8"))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        panel.addView(input)

        val dialog = android.app.AlertDialog.Builder(act).create()
        val btnRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16f) }
        }
        btnRow.addView(android.widget.TextView(act).apply {
            text = "取消"; textSize = 16f; setTextColor(Color.parseColor("#666666"))
            gravity = android.view.Gravity.CENTER; setPadding(0, dp(12f), 0, dp(12f))
            background = GradientDrawable().apply { setColor(Color.parseColor("#F5F5F5")); cornerRadius = dp(14f).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6f) }
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(android.widget.TextView(act).apply {
            text = "确定"; textSize = 16f; setTextColor(Color.parseColor("#FFFFFF"))
            gravity = android.view.Gravity.CENTER; setPadding(0, dp(12f), 0, dp(12f))
            background = GradientDrawable().apply { setColor(Color.parseColor("#1A73E8")); cornerRadius = dp(14f).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6f) }
            isClickable = true
            setOnClickListener {
                WexStore.putString(key, input.text.toString().trim())
                dialog.dismiss()
                wex.ui.core.feature.ImagePicker.toast(act, "已保存，正在重启微信...")
                wex.ui.core.feature.ImagePicker.hotRestartWechat(act)
            }
        })
        panel.addView(btnRow)

        dialog.setView(panel)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        // 在微信进程内弹窗需 overlay 类型
        try { dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) } catch (_: Exception) {}
        dialog.show()
    }

    private fun tryApplyStyle(decor: ViewGroup, handler: Handler, attempt: Int) {
        val tabView = findTabView(decor)
        if (tabView != null && styledViews.add(tabView)) {
            (tabView as ViewGroup).post {
                applyStyle(tabView)
                log("底栏样式已应用 (attempt=$attempt)")
            }
            return
        }
        val delays = longArrayOf(500, 2000)
        if (attempt < delays.size) {
            handler.postDelayed({ tryApplyStyle(decor, handler, attempt + 1) }, delays[attempt])
        } else {
            log("底栏未找到（已重试${delays.size}次）")
        }
    }

    private fun findTabView(root: ViewGroup): View? {
        val queue: ArrayDeque<View> = ArrayDeque()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.poll()
            if (v.javaClass.name == "com.tencent.mm.ui.LauncherUIBottomTabView") return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    v.getChildAt(i)?.let { queue.add(it) }
                }
            }
        }
        return null
    }

    

    private fun applyStyle(tabView: ViewGroup) {
        // 读取开关：统一走 WexStore（media 目录，微信进程与模块 App 进程共享）
        if (!WexStore.getBool("bottom_bar_enabled", true)) return

        if (tabView.childCount < 1) return
        val container = tabView.getChildAt(0) as? ViewGroup ?: return
        if (container.childCount < 4) return

        val ctx = tabView.context
        val density = ctx.resources.displayMetrics.density

        val uiMode = ctx.resources.configuration.uiMode
        val isDark = (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bgColor = if (isDark) Color.parseColor("#2C2C2C") else Color.parseColor("#FFFFFF")
        val strokeColor = if (isDark) Color.parseColor("#444444") else Color.parseColor("#E0E0E0")

        val gd = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = 100.0f
            setStroke(3, strokeColor)
        }
        container.background = gd

        val lp = container.layoutParams
        if (lp is ViewGroup.MarginLayoutParams) {
            val side = (20 * density).toInt()
            val bottom = (20 * density).toInt()
            lp.setMargins(side, 10, side, bottom)
            container.layoutParams = lp
        }

        // 外层 tabView：用 outline 裁成与 container 对齐的"缩进胶囊"，
        // 把胶囊外那圈矩形（含背景）整个裁掉，实现纯胶囊、无外框。
        tabView.background = null
        tabView.setBackgroundColor(0)
        val ml = (20 * density).toInt()   // 与 container margin 对齐：左右 20dp
        val mtop = 10                     // 与 container margin 对齐：上 10px
        val mbot = (20 * density).toInt() // 与 container margin 对齐：下 20dp
        tabView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val capsuleH = view.height - mtop - mbot
                outline.setRoundRect(ml, mtop, view.width - ml, view.height - mbot, capsuleH / 2f)
            }
        }
        tabView.clipToOutline = true
        tabView.clipChildren = false
        tabView.clipToPadding = false
        // 父层仅解除裁剪，不动背景（父层是全屏页面背景，动了会误伤）
        var p: Any? = tabView.parent
        repeat(3) {
            if (p is ViewGroup) {
                val pv = p as ViewGroup
                pv.clipChildren = false
                pv.clipToPadding = false
                p = pv.parent
            }
        }

        setupBubbleGlow(tabView, container)
    }

    private fun setupBubbleGlow(tabView: ViewGroup, container: ViewGroup) {
        if (container.childCount < 4) return
        val btns = Array(4) { container.getChildAt(it) }
        tabView.post {
            try {
                val decor = tabView.rootView as? ViewGroup ?: return@post
                val ctx = tabView.context
                val density = ctx.resources.displayMetrics.density

                var shadow = decor.findViewWithTag<View>("shadow_bubble")
                if (shadow == null) {
                    shadow = View(ctx).apply {
                        visibility = View.GONE; tag = "shadow_bubble"
                    }
                    decor.addView(shadow, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                var glow = decor.findViewWithTag<View>("glow_bubble")
                if (glow == null) {
                    glow = View(ctx).apply {
                        visibility = View.GONE; tag = "glow_bubble"
                    }
                    decor.addView(glow, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }

                val shadowFinal = shadow
                val glowFinal = glow
                val densityFinal = density
                val ctxFinal = ctx

                var cachedBmp: Bitmap? = null
                var cachedW = 0
                var cachedH = 0

                var glowAnimator: ValueAnimator? = null
                var animPhase = 0f
                var lastIdx = -1
                val handler = Handler(Looper.getMainLooper())
                val hideRunnable = Runnable {
                    stopGlow(glowAnimator)
                    glowAnimator = null
                    shadowFinal.visibility = View.GONE
                    glowFinal.visibility = View.GONE
                    lastIdx = -1
                }

                val listener = View.OnTouchListener { _, event ->
                    try {
                        val rawX = event.rawX; val rawY = event.rawY
                        var hoverIdx = -1
                        for (j in btns.indices) {
                            val loc = IntArray(2); btns[j].getLocationOnScreen(loc)
                            if (rawX >= loc[0] && rawX <= loc[0] + btns[j].width &&
                                rawY >= loc[1] && rawY <= loc[1] + btns[j].height) {
                                hoverIdx = j; break
                            }
                        }
                        handler.removeCallbacks(hideRunnable)
                        when (event.action) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                                if (hoverIdx != lastIdx) {
                                    lastIdx = hoverIdx
                                    if (hoverIdx >= 0) {
                                        val btnLoc = IntArray(2); btns[hoverIdx].getLocationOnScreen(btnLoc)
                                        val btnW = btns[hoverIdx].width; val btnH = btns[hoverIdx].height
                                        val bw = (btnW * 1.1f).toInt(); val bh = (btnH * 1.1f).toInt()
                                        val x = btnLoc[0] + (btnW - bw) / 2; val y = btnLoc[1] + (btnH - bh) / 2
                                        val shadowOffsetY = (2 * densityFinal).toInt()

                                        (shadowFinal.layoutParams as FrameLayout.LayoutParams).apply {
                                            width = bw + (6 * densityFinal).toInt(); height = bh + (6 * densityFinal).toInt()
                                            leftMargin = x - (3 * densityFinal).toInt(); topMargin = y + shadowOffsetY - (3 * densityFinal).toInt()
                                        }
                                        shadowFinal.background = createOutline(
                                            (shadowFinal.layoutParams as FrameLayout.LayoutParams).width,
                                            (shadowFinal.layoutParams as FrameLayout.LayoutParams).height)
                                        shadowFinal.visibility = View.VISIBLE

                                        (glowFinal.layoutParams as FrameLayout.LayoutParams).apply {
                                            width = bw; height = bh; leftMargin = x; topMargin = y
                                        }
                                        startGlow(ctxFinal, glowFinal, bw, bh,
                                            { glowAnimator = it }, { animPhase = it }, { animPhase },
                                            { cachedBmp }, { cachedBmp = it }, { cachedW }, { cachedW = it }, { cachedH }, { cachedH = it })
                                        glowFinal.visibility = View.VISIBLE
                                    } else {
                                        stopGlow(glowAnimator); glowAnimator = null
                                        shadowFinal.visibility = View.GONE; glowFinal.visibility = View.GONE
                                    }
                                }
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                stopGlow(glowAnimator); glowAnimator = null
                                shadowFinal.visibility = View.GONE; glowFinal.visibility = View.GONE
                                lastIdx = -1
                            }
                        }
                        handler.postDelayed(hideRunnable, 500)
                    } catch (_: Exception) {}
                    false
                }
                btns.forEach { it.setOnTouchListener(listener) }
                log("气泡光晕已就绪")
            } catch (e: Exception) { log("气泡初始化失败: ${e.message}") }
        }
    }

    private fun startGlow(ctx: Context, v: View, w: Int, h: Int,
                          setAnim: (ValueAnimator?) -> Unit,
                          setPhase: (Float) -> Unit,
                          getPhase: () -> Float,
                          getBmp: () -> Bitmap?,
                          setBmp: (Bitmap?) -> Unit,
                          getBmpW: () -> Int,
                          setBmpW: (Int) -> Unit,
                          getBmpH: () -> Int,
                          setBmpH: (Int) -> Unit) {
        setPhase(0f)
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART; duration = 2000
            addUpdateListener {
                setPhase(getPhase() + 0.05f)
                v.background = createAnimatedGlowCached(ctx, w, h, getPhase(), getBmp, setBmp, getBmpW, setBmpW, getBmpH, setBmpH)
            }
            start()
        }
        setAnim(a)
    }

    private fun stopGlow(a: ValueAnimator?) { a?.cancel() }

    private fun createOutline(w: Int, h: Int): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(Color.parseColor("#00FFFFFF"))
        cornerRadius = h / 2.0f; setStroke(4, Color.parseColor("#15000000"))
    }

    private fun createAnimatedGlowCached(ctx: Context, width: Int, height: Int, phase: Float,
        getBmp: () -> Bitmap?, setBmp: (Bitmap?) -> Unit,
        getBmpW: () -> Int, setBmpW: (Int) -> Unit,
        getBmpH: () -> Int, setBmpH: (Int) -> Unit): Drawable {
        if (width <= 0 || height <= 0) return ColorDrawable(Color.TRANSPARENT)
        var bmp = getBmp()
        if (bmp == null || getBmpW() != width || getBmpH() != height) {
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            setBmp(bmp); setBmpW(width); setBmpH(height)
        } else {
            bmp.eraseColor(Color.TRANSPARENT)
        }
        val c = Canvas(bmp); val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f; val m = minOf(w, h)
        val spots = arrayOf(
            floatArrayOf(0f, 0.25f, 0.5f, 1.00f, 0.80f, 0.75f, 0.26f),
            floatArrayOf(120f, 0.32f, 0.55f, 0.70f, 0.85f, 1.00f, 0.24f),
            floatArrayOf(240f, 0.28f, 0.45f, 0.85f, 0.75f, 1.00f, 0.24f),
            floatArrayOf(60f, 0.22f, 0.4f, 0.75f, 0.95f, 0.80f, 0.22f),
            floatArrayOf(300f, 0.18f, 0.6f, 0.90f, 0.95f, 1.00f, 0.18f),
            floatArrayOf(180f, 0.35f, 0.35f, 1.00f, 0.85f, 0.80f, 0.20f))
        for (s in spots) {
            val rr = s[1] + Math.sin((phase * 1.5 + s[0]).toDouble()).toFloat() * 0.08f
            val a = Math.toRadians(s[0].toDouble()); val d = m * rr
            val scx = cx + Math.cos(a).toFloat() * d; val scy = cy + Math.sin(a).toFloat() * d
            val sz = m * s[2]; var rx = sz * 0.8f; var ry = sz * 1.2f
            if (rx < 3) rx = 3f; if (ry < 3) ry = 3f
            val cc = Color.argb((s[6] * 255).toInt(), (s[3] * 255).toInt(), (s[4] * 255).toInt(), (s[5] * 255).toInt())
            c.save(); c.translate(scx, scy); c.scale(rx / maxOf(rx, ry), ry / maxOf(rx, ry))
            val mr = maxOf(rx, ry)
            val grad = RadialGradient(0f, 0f, mr, intArrayOf(cc, Color.TRANSPARENT), floatArrayOf(0.3f, 1.0f), Shader.TileMode.CLAMP)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = grad; style = Paint.Style.FILL }
            c.drawCircle(0f, 0f, mr, p); c.restore()
        }
        return BitmapDrawable(ctx.resources, bmp)
    }
}