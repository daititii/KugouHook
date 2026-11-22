package dev.kugou.kugouhook

import io.github.libxposed.api.XposedModule
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 三通道日志：框架日志 / logcat / 宿主 cache 目录下的纯文本文件。
 *
 * 三条都写是刻意冗余的，原因都在实机踩过：
 *  - LSPosed 的部分分支（以及 MIUI 上的某些版本）不会 flush 模块日志；
 *  - 有些设备把系统日志整体关掉，logcat 即便有 root 也拿不到东西；
 *  - 排查「偶发漏拦」时必须拿到用户复现那一刻的现场，而这通常只能靠用户自己
 *    去文件管理器里捞一个 txt。
 *
 * 因此状态文件是最后兜底的那条通道，`KugouModule` 会在拿到 dataDir 的第一时间打开它。
 */
class ModuleLog(private val module: XposedModule?) {

    @Volatile
    var statusFile: File? = null

    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private fun fmt(level: String, msg: String): String =
        "${ts.format(Date())} [$level] $msg"

    private fun file(level: String, msg: String) {
        val f = statusFile ?: return
        runCatching { f.appendText(fmt(level, msg) + "\n") }
    }

    fun info(msg: String) {
        module?.log(android.util.Log.INFO, TAG, msg)
        android.util.Log.i(TAG, msg)
        file("INFO", msg)
    }

    fun debug(msg: String) {
        module?.log(android.util.Log.DEBUG, TAG, msg)
        android.util.Log.d(TAG, msg)
        file("DEBUG", msg)
    }

    fun warn(msg: String) {
        module?.log(android.util.Log.WARN, TAG, msg)
        android.util.Log.w(TAG, msg)
        file("WARN", msg)
    }

    fun error(msg: String, t: Throwable? = null) {
        val full = if (t != null) "$msg :: ${t.javaClass.simpleName}: ${t.message}" else msg
        module?.log(android.util.Log.ERROR, TAG, full)
        android.util.Log.e(TAG, full)
        file("ERROR", full)
    }

    companion object {
        const val TAG = "KugouHook"
    }
}
