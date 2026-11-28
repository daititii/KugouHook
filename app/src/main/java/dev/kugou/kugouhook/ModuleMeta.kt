package dev.kugou.kugouhook

import io.github.libxposed.api.XposedModule
import java.util.zip.ZipFile

/**
 * 从**模块自己的 APK** 里读 `META-INF/xposed/module.prop`，用于在日志里自报版本。
 *
 * ## 为什么值得单独做
 *
 * 排查「装了但没效果」时，第一件要知道的事是"跑起来的到底是哪个版本"。
 * 没有这行日志时，只能靠回忆或对方口述，而口述经常和实际不符：
 * 升级没成功、装了两个包、LSPosed 管理器显示的和进程里加载的不是同一份。
 *
 * `module.prop` 就是 LSPosed 模块中心显示的那一份，直接读它最不容易骗人；
 * 顺带把 APK 路径打出来，克隆/多用户/多安装来源的情况一眼可见。
 *
 * 读失败不抛异常 —— 诊断信息永远不能反过来弄坏宿主。
 */
internal object ModuleMeta {

    private const val ENTRY = "META-INF/xposed/module.prop"

    /**
     * 解析本模块自己的 APK 路径。两条路，按可靠性排序：
     *
     * 1. **框架给的 `moduleApplicationInfo`**（API 102 的 `XposedInterface#getModuleApplicationInfo`）。
     *    这是框架自己加载模块时用的那份 `ApplicationInfo`，`sourceDir` 就是模块 APK 的实际路径，
     *    多用户 / 克隆 / 分身场景下也是准的。
     *
     * 2. **`protectionDomain.codeSource` 反推**。纯 Java 做法，不依赖框架 API，
     *    在普通 JVM 上稳定可用。但在 LSPosed 里模块 dex 是被框架"塞"进宿主的
     *    ClassLoader 的，`CodeSource` 往往是 null —— 0.1.4 的日志正是全线
     *    `unknown (module APK path unavailable)`，即这条路在目标环境下从来没走通过。
     *
     * 所以第 1 条是主路，第 2 条只作兜底。
     */
    fun ownApkPath(module: XposedModule?): String? {
        // 主路：框架给的模块 ApplicationInfo。
        runCatching {
            val info = module?.moduleApplicationInfo
            val dir = info?.sourceDir
            if (!dir.isNullOrEmpty() && dir.endsWith(".apk")) return dir
        }

        // 兜底：code source。
        return runCatching {
            val src = ModuleMeta::class.java.protectionDomain?.codeSource?.location
                ?: return@runCatching null
            val path = java.io.File(src.toURI()).path
            path.takeIf { it.endsWith(".apk") }
        }.getOrNull()
    }

    /** 形如 `v0.1.2 (versionCode=3) apk=/data/app/…/base.apk`。 */
    fun describe(apkPath: String?): String {
        if (apkPath.isNullOrEmpty()) return "unknown (module APK path unavailable)"
        return runCatching {
            ZipFile(apkPath).use { zf ->
                val entry = zf.getEntry(ENTRY)
                    ?: return@use "unknown ($ENTRY missing) apk=$apkPath"
                val props = zf.getInputStream(entry).use { it.readBytes() }
                    .toString(Charsets.UTF_8)
                    .lineSequence()
                    .mapNotNull { line ->
                        val i = line.indexOf('=')
                        if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim() else null
                    }
                    .toMap()
                val version = props["version"] ?: "?"
                val code = props["versionCode"] ?: "?"
                "$version (versionCode=$code) apk=$apkPath"
            }
        }.getOrElse { t ->
            "unknown (${t.javaClass.simpleName}: ${t.message}) apk=$apkPath"
        }
    }
}
