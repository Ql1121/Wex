package wex.ui.core.feature

import android.app.Activity
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import wex.ui.core.core.ViewFinder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 首页图片卡片：在微信聊天列表顶部插入一张圆角图片卡。
 * 独立模块，自管 hook 与视图；跨版本用递归找 ListView（不依赖资源 id）。
 * 图片从模块 assets 加载（home_image_card.png），跨进程读取。
 */
object ImageCardFeature {

    private var modulePath: String? = null
    private var cachedBitmap: Bitmap? = null
    private var cachedCard: View? = null

    /** 由管理器传入模块 APK 路径（加载 assets 用） */
    fun setModulePath(modPath: String?) {
        modulePath = modPath
    }

    /** 供 HomeCardManager 调用：返回图片卡 View（不自己插入，插入由管理器统一处理） */
    fun getCard(act: Activity): View? {
        return getImageCard(act)
    }

    /** 构建图片卡（圆角 + CENTER_CROP），带缓存 */
    private fun getImageCard(ctx: Context): View? {
        cachedCard?.let { return it }
        try {
            val d = ctx.resources.displayMetrics.density
            val cw = (365 * d).toInt()
            val ch = (145 * d).toInt()
            val r = 20 * d

            val wrapper = LinearLayout(ctx).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            }
            val card = FrameLayout(ctx).apply {
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, r)
                    }
                }
background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT); cornerRadius = r
                }
            }
            val bmp = loadCardBitmap("home_image_card.png", cw, ch)
            if (bmp != null) {
                val iv = ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(bmp)
                }
                card.addView(iv, FrameLayout.LayoutParams(-1, -1))
            }
            wrapper.addView(card, LinearLayout.LayoutParams(cw, ch))
            cachedCard = wrapper
            return wrapper
        } catch (e: Exception) {
            log("图片卡创建异常: ${e.message}")
            return null
        }
    }

    /** 加载卡片图：优先读用户自定义背景（media 目录），没有才用 assets 默认图 */
    private fun loadCardBitmap(assetName: String, reqW: Int, reqH: Int): Bitmap? {
        cachedBitmap?.let { if (!it.isRecycled) return it }
        // 1) 自定义背景（选图保存的路径）
        val customPath = wex.ui.core.WexStore.getString("image_card_bg", "")
        if (customPath.isNotEmpty()) {
            val f = java.io.File(customPath)
            if (f.exists()) {
                val bmp = decodeSampled(customPath, reqW, reqH)
                if (bmp != null) { cachedBitmap = bmp; return bmp }
            }
        }
        // 2) assets 默认图
        return loadAssetBitmap(assetName, reqW, reqH)
    }

    /** 按目标尺寸采样解码本地文件图片 */
    private fun decodeSampled(path: String, reqW: Int, reqH: Int): Bitmap? {
        return try {
            val optBound = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, optBound)
            var sample = maxOf(optBound.outWidth / reqW, optBound.outHeight / reqH)
            if (sample < 1) sample = 1
            val opt = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, opt)
        } catch (e: Exception) {
            log("图片卡：解码自定义图异常 ${e.message}"); null
        }
    }

    /** 清缓存（换背景后调用，配合热重启刷新） */
    fun clearCache() {
        cachedCard = null
        cachedBitmap = null
    }

    /** 从模块 assets 加载并按目标尺寸采样解码图片 */
    private fun loadAssetBitmap(name: String, reqW: Int, reqH: Int): Bitmap? {
        val path = modulePath ?: run { log("图片卡：modulePath 为空"); return null }
        return try {
            val am = AssetManager::class.java.newInstance()
            AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(am, path)
            // 先读尺寸算采样率
            val optBound = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            am.open(name).use { BitmapFactory.decodeStream(it, null, optBound) }
            var sample = maxOf(optBound.outWidth / reqW, optBound.outHeight / reqH)
            if (sample < 1) sample = 1
            val opt = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = am.open(name).use { BitmapFactory.decodeStream(it, null, opt) }
            cachedBitmap = bmp
            bmp
        } catch (e: Exception) {
            log("图片卡：加载图片异常 ${e.message}")
            null
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("WEX: $msg")
        try { wex.ui.core.WexStore.appendLog(msg) } catch (_: Exception) {}
    }
}