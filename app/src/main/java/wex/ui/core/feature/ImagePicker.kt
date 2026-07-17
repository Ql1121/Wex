package wex.ui.core.feature

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import wex.ui.core.WexStore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

/**
 * 相册选图工具（借微信当前 Activity 的 startActivityForResult + hook onActivityResult 拿结果）。
 * 选完图存 media 目录，写配置，回调用于清缓存+热重启刷新。
 */
object ImagePicker {

    private const val REQ_CODE = 0x7E01  // 自定义 requestCode
    private const val IMG_DIR = "${WexStore.DIR}/图片"

    // 当前一次选图的上下文
    private var pendingKey: String? = null
    private var pendingCallback: ((String) -> Unit)? = null
    private var inited = false

    /** 全局注册一次：hook Activity.onActivityResult 拦截我们的选图结果 */
    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (inited) return
        inited = true
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val req = param.args[0] as Int
                        if (req != REQ_CODE) return
                        val resultCode = param.args[1] as Int
                        val data = param.args[2] as? Intent
                        val act = param.thisObject as? Activity ?: return
                        val key = pendingKey ?: return
                        val cb = pendingCallback
                        pendingKey = null
                        pendingCallback = null
                        if (resultCode == Activity.RESULT_OK && data != null) {
                            val saved = handleResult(act, data.data, key)
                            if (saved != null && cb != null) {
                                Handler(Looper.getMainLooper()).post { cb(saved) }
                            }
                        }
                    }
                }
            )
            log("选图 onActivityResult Hook 已注册")
        } catch (e: Throwable) {
            log("选图 Hook 注册失败: ${e.message}")
        }
    }

    /** 打开相册选图（用微信当前 Activity 直接 startActivityForResult） */
    fun pick(act: Activity, configKey: String, onDone: (String) -> Unit) {
        try {
            pendingKey = configKey
            pendingCallback = onDone
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            act.startActivityForResult(intent, REQ_CODE)
        } catch (e: Exception) {
            pendingKey = null
            pendingCallback = null
            log("打开相册失败: ${e.message}")
            toast(act, "无法打开相册")
        }
    }

    /** 保存选中图到 media 目录，写配置，返回保存路径 */
    private fun handleResult(act: Activity, uri: Uri?, configKey: String): String? {
        if (uri == null) return null
        return try {
            val input = act.contentResolver.openInputStream(uri) ?: run {
                toast(act, "无法读取图片"); return null
            }
            val dir = File(IMG_DIR)
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, "$configKey.png")
            input.use { ins -> target.outputStream().use { out -> ins.copyTo(out) } }
            WexStore.putString(configKey, target.absolutePath)
            log("背景已保存: ${target.absolutePath}")
            target.absolutePath
        } catch (e: Exception) {
            log("保存图片失败: ${e.message}")
            toast(act, "图片处理失败")
            null
        }
    }

    /** 热重启微信：移除任务 + 延迟重新打开 */
    fun hotRestartWechat(act: Activity) {
        try {
            val pkg = act.packageName
            val am = act.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            try {
                am.appTasks?.forEach { task ->
                    val info = task.taskInfo
                    if (info?.baseIntent?.component?.packageName == pkg) task.finishAndRemoveTask()
                }
            } catch (_: Exception) {}
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val intent = Intent().apply {
                        setComponent(android.content.ComponentName(pkg, "com.tencent.mm.ui.LauncherUI"))
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    act.startActivity(intent)
                } catch (e: Exception) {
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }, 300)
        } catch (e: Exception) {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    fun toast(act: Activity, msg: String) {
        try {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {}
    }

    private fun log(msg: String) {
        XposedBridge.log("WEX: $msg")
        try { WexStore.appendLog(msg) } catch (_: Exception) {}
    }
}