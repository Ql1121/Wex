package wex.ui.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WEX 统一存储：使用微信 media 目录（半公开，微信进程与模块 App 进程均可读写），
 * 解决 Xposed 跨进程共享配置与日志的问题。
 *
 * 目录：/sdcard/Android/media/com.tencent.mm/WEX/
 *   - config.txt : 开关配置（key=value 纯文本）
 *   - log.txt    : 运行日志
 *   - .nomedia   : 阻止媒体扫描进相册
 */
object WexStore {
    const val DIR = "/sdcard/Android/media/com.tencent.mm/WEX"
    private const val CONFIG = "$DIR/config.txt"
    const val LOG = "$DIR/log.txt"

    /** 确保目录与 .nomedia 存在（事前防媒体扫描） */
    private fun ensureDir() {
        try {
            val d = File(DIR)
            if (!d.exists()) d.mkdirs()
            val nomedia = File(d, ".nomedia")
            if (!nomedia.exists()) nomedia.createNewFile()
        } catch (_: Exception) {}
    }

    /** 读取全部配置为 Map */
    private fun readAll(): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val f = File(CONFIG)
            if (f.exists()) {
                f.readLines().forEach { line ->
                    val i = line.indexOf('=')
                    if (i > 0) map[line.substring(0, i)] = line.substring(i + 1)
                }
            }
        } catch (_: Exception) {}
        return map
    }

    /** 读布尔开关 */
    fun getBool(key: String, def: Boolean): Boolean {
        return when (readAll()[key]) {
            "true" -> true
            "false" -> false
            else -> def
        }
    }

    /** 读字符串 */
    fun getString(key: String, def: String): String {
        return readAll()[key] ?: def
    }

    /** 写字符串 */
    fun putString(key: String, value: String) {
        ensureDir()
        try {
            val map = readAll()
            map[key] = value
            File(CONFIG).writeText(map.entries.joinToString("\n") { "${it.key}=${it.value}" })
        } catch (_: Exception) {}
    }

    /** 写布尔开关 */
    fun putBool(key: String, value: Boolean) {
        ensureDir()
        try {
            val map = readAll()
            map[key] = value.toString()
            File(CONFIG).writeText(map.entries.joinToString("\n") { "${it.key}=${it.value}" })
        } catch (_: Exception) {}
    }

    /** 追加一行日志（受"日志记录"开关控制；关闭时不写） */
    fun appendLog(line: String) {
        if (!getBool("log_enabled", true)) return
        appendLogForce(line)
    }

    /** 强制追加一行日志（不受开关控制，用于记录"开关状态变化"本身） */
    fun appendLogForce(line: String) {
        ensureDir()
        try {
            File(LOG).appendText(line + "\n")
        } catch (_: Exception) {}
    }

    /** 带时间戳强制写一行（供 App 侧写状态提醒用） */
    fun logStatus(msg: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        appendLogForce("[$ts] $msg")
    }

    /** 读取日志最后 maxLines 行（首页展示用） */
    fun readLogTail(maxLines: Int = 100): List<String> {
        return try {
            val f = File(LOG)
            if (!f.exists()) emptyList()
            else f.readLines().takeLast(maxLines)
        } catch (_: Exception) { emptyList() }
    }

    /** 清空日志文件 */
    fun clearLog() {
        try { File(LOG).writeText("") } catch (_: Exception) {}
    }
}