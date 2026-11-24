package dev.kugou.kugouhook

import java.io.File

/**
 * 运行期开关：`<hostDataDir>/cache/kugouhook.flags`，`key=0|1`，`#` 开头为注释。
 *
 * ## 为什么需要它
 *
 * 观测类 hook（比如 [hooks.UiProbeHooks]）在排查阶段有用，长期开着却是纯开销。
 * 没有运行期开关时，开关它的唯一办法是重新出包、装机、冷启动 ——
 * 一轮下来几分钟，而且每换一个开关都要重来一遍。
 *
 * 文件不存在时全部走 [defaults]，模块行为与没有这个机制时完全一致。
 *
 * ## 为什么把默认值写在代码里
 *
 * 宿主 cache 目录可能因为清数据、换设备而消失。此时程序必须仍能判断"该不该开"，
 * 不能因为读不到文件就变成另一种行为。所以 `defaults` 是唯一真相，
 * 文件只做覆盖。
 */
class Flags(hostDataDir: String?) {

    private val file: File? = hostDataDir?.let { File("$it/cache/$FLAG_FILE") }

    @Volatile
    private var values: Map<String, Boolean> = emptyMap()

    @Volatile
    private var loaded = false

    fun enabled(key: String): Boolean {
        if (!loaded) reload()
        return values[key] ?: defaults[key] ?: false
    }

    @Synchronized
    fun reload() {
        loaded = true
        val f = file
        if (f == null || !f.exists()) {
            values = emptyMap()
            return
        }
        values = runCatching {
            f.readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.contains('=') }
                .mapNotNull { line ->
                    val k = line.substringBefore('=').trim()
                    val v = line.substringAfter('=').trim()
                    if (k.isEmpty()) null else k to (v == "1" || v.equals("true", true))
                }
                .toMap()
        }.getOrElse { emptyMap() }
    }

    /** 本次实际生效的条目，供日志自报。 */
    fun describe(): String {
        if (!loaded) reload()
        if (values.isEmpty() && defaults.isEmpty()) return "none"
        return buildString {
            append("defaults=[")
            append(defaults.entries.joinToString(", ") { "${it.key}=${if (it.value) 1 else 0}" })
            append("]")
            if (values.isNotEmpty()) {
                append(" file=[")
                append(values.entries.joinToString(", ") { "${it.key}=${if (it.value) 1 else 0}" })
                append("]")
            }
        }
    }

    companion object {
        private const val FLAG_FILE = "kugouhook.flags"

        /** 观测 hook 的默认值。排查时用 flags 文件打开，平时关闭。 */
        private val defaults = mapOf(
            "ui.probe" to false
        )
    }
}
