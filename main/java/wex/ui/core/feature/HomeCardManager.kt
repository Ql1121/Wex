package wex.ui.core.feature

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import wex.ui.core.WexStore
import wex.ui.core.core.ViewFinder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 首页卡片统一管理器。
 * 职责：hook 微信主界面 → 找到聊天列表 → 建一个 root 容器，按固定顺序装各卡片 → 只 addHeaderView 一次。
 * 各卡片模块只负责"提供 View + 开关 key"，插入/顺序/防重复由本管理器统一处理。
 * 顺序固定：日历 → 图片 → 音乐。
 */
object HomeCardManager {

    private const val TAG_INSERTED = "wex_home_cards"
    private var modulePath: String? = null

    fun init(lpparam: XC_LoadPackage.LoadPackageParam, modPath: String?) {
        modulePath = modPath
        // 把 modulePath 传给需要加载 assets 的卡片模块
        ImageCardFeature.setModulePath(modPath)
        CalendarCardFeature.setModulePath(modPath)
        MusicCardFeature.setModulePath(modPath)
        try {
            // 微信有 Tinker 热修复，hook 系统类 Activity.onResume + 类名判断（tinker 兼容）
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val act = param.thisObject as? Activity ?: return
                        if (act.javaClass.name != "com.tencent.mm.ui.LauncherUI") return
                        insertCards(act)
                    }
                }
            )
            log("首页卡片管理器 Hook 已注册（tinker 兼容）")
        } catch (e: Throwable) {
            log("首页卡片管理器 Hook 失败: ${e.message}")
        }
    }

    private fun insertCards(act: Activity) {
        val decor = act.window?.decorView as? ViewGroup ?: return
        decor.post {
            try {
                val listView = ViewFinder.findListView(decor) ?: run {
                    log("首页卡片：未找到聊天列表"); return@post
                }
                if ((listView.tag as? String) == TAG_INSERTED) return@post

                val d = act.resources.displayMetrics.density
                val root = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
                }

                var added = 0
                // 顺序固定：日历 → 图片 → 音乐
                if (WexStore.getBool("home_calendar_card", true)) {
                    addChild(root, CalendarCardFeature.getCard(act), d, added == 0); if (root.childCount > 0) added++
                }
                if (WexStore.getBool("home_image_card", true)) {
                    addChild(root, ImageCardFeature.getCard(act), d, added == 0); if (root.childCount > 0) added++
                }
                if (WexStore.getBool("home_music_card", true)) {
                    addChild(root, MusicCardFeature.getCard(act), d, added == 0); if (root.childCount > 0) added++
                }

                if (root.childCount == 0) { listView.tag = null; return@post }

                listView.javaClass.getMethod(
                    "addHeaderView", View::class.java, Any::class.java, Boolean::class.javaPrimitiveType
                ).invoke(listView, root, null, false)
                listView.tag = TAG_INSERTED
                log("首页卡片插入成功（${root.childCount}张），列表: ${listView.javaClass.name}")
            } catch (e: Exception) {
                log("首页卡片插入失败: ${e.message}")
            }
        }
    }

    /** 把单张卡加入 root，非首张加顶部间距 */
    private fun addChild(root: LinearLayout, card: View?, d: Float, isFirst: Boolean) {
        if (card == null) return
        (card.parent as? ViewGroup)?.removeView(card)
        val lp = LinearLayout.LayoutParams(-1, -2)
        if (!isFirst) lp.topMargin = (10 * d).toInt()
        root.addView(card, lp)
    }

    private fun log(msg: String) {
        XposedBridge.log("WEX: $msg")
        try { WexStore.appendLog(msg) } catch (_: Exception) {}
    }
}