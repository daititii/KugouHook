package dev.kugou.kugouhook

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import dev.kugou.kugouhook.hooks.AdHooks
import dev.kugou.kugouhook.hooks.MineBannerHooks
import dev.kugou.kugouhook.hooks.SplashAdHooks
import java.io.File

/**
 * libxposed API 102 入口。
 *
 * 生命周期：
 *   1. [onModuleLoaded]  – 进程内加载模块时一次
 *   2. [onPackageLoaded] – 包已解析，默认 ClassLoader 可用（Q+）
 *   3. [onPackageReady]  – AppComponentFactory 已建，此时能拿到目标 ClassLoader
 *   4. [onHotReloading]/[onHotReloaded] – 原地热重载
 *
 * ## 装 hook 之前的三道门
 *   - 包名必须是 [TARGET_PACKAGE]
 *   - 进程名必须等于包名（即主进程）。酷狗另有 `:support` / `:message` 子进程，
 *     注入它们既无收益，又会因为它们没有完整 UI 上下文而引入风险。
 *   - versionCode 读出来只作**参考**，不作硬门禁 —— 见下。
 *
 * ## 为什么版本门控是"建议性"而不是"失败即关闭"
 * 每条 hook 自己解析目标，找不到就 skip 自己并记 WARN；[HookManager] 逐条 try/catch 隔离，
 * 并保留被跳过的清单。宿主升级因此只会逐条退化，而不是整个模块失效，
 * 一行 `install summary: ...` 就能指出到底丢了哪条。
 *
 * 反过来的设计（版本不认识就一条都不装）在小损失和总损失之间选错了：
 * 目标是"少拦几条广告"还是"一条都不拦"，答案很明显。
 */
class KugouModule : XposedModule() {

    private val log by lazy { ModuleLog(this) }

    @Volatile
    private var processName: String = UNKNOWN_PROCESS

    @Volatile
    private var hookManager: HookManager? = null

    companion object {
        private const val UNKNOWN_PROCESS = "<unknown>"
        const val TARGET_PACKAGE = "com.kugou.android"

        /** 逆向分析对应的宿主版本（酷狗 20.6.4 / versionCode 20649）。仅用于日志提示。 */
        private const val AUDITED_VERSION_CODE = 20649L

        /** 宿主 cache 下放这个文件即可打开命中审计，无需重新打包。 */
        private const val AUDIT_FLAG = "kugouhook.audit"
        private const val LOG_FILE = "kugouhook.log"
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        log.info(
            "module loaded: process=$processName api=$apiVersion " +
                "framework=$frameworkName v$frameworkVersion"
        )
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != TARGET_PACKAGE) return
        if (!param.isFirstPackage) return
        log.debug("package loaded: ${param.packageName} (process=$processName)")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val pkg = param.packageName
        if (pkg != TARGET_PACKAGE) return
        if (!param.isFirstPackage) return
        if (processName != pkg) {
            log.info("skip non-main process: $processName")
            return
        }

        // 状态文件要在任何可能失败的判断之前打开，否则"装失败"这件事本身进不了文件。
        log.statusFile = runCatching {
            File(param.applicationInfo.dataDir, "cache/$LOG_FILE")
        }.getOrNull()
        log.info("=== KugouHook start (pid=${android.os.Process.myPid()}) ===")

        val versionCode = readVersionCode(param)
        if (versionCode == AUDITED_VERSION_CODE) {
            log.info("host versionCode=$versionCode matches the audited build")
        } else {
            log.warn(
                "host versionCode=$versionCode != audited $AUDITED_VERSION_CODE; " +
                    "installing best-effort (per-hook degradation applies)"
            )
        }

        val classLoader = param.classLoader
        val resolver = ClassResolver(classLoader, log)

        val audit = runCatching {
            File(param.applicationInfo.dataDir, "cache/$AUDIT_FLAG").exists()
        }.getOrDefault(false)
        if (audit) log.info("hit audit enabled")

        val hm = HookManager(this, log, auditHits = audit)
        hookManager = hm

        // 每条规则独立安装，任何一条抛异常都不会影响后面 —— 由 HookManager 保证。
        runCatching { AdHooks(resolver, hm, log).install() }
            .onFailure { log.error("AdHooks failed", it) }
        runCatching { MineBannerHooks(resolver, hm, log).install() }
            .onFailure { log.error("MineBannerHooks failed", it) }
        runCatching { SplashAdHooks(resolver, hm, log).install() }
            .onFailure { log.error("SplashAdHooks failed", it) }

        log.info("install summary: ${hm.summary()}")
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        log.info("hot reloading: tearing down hooks")
        hookManager?.unhookAll()
        hookManager = null
        // 返回 true 表示允许框架继续本次热重载（新实例会被重新加载并重新装 hook）。
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        log.info("hot reloaded")
    }

    /**
     * 读宿主 versionCode。
     *
     * 注意：`ApplicationInfo` 上**没有** `versionCode` / `longVersionCode`（那是 `PackageInfo` 的字段），
     * 而 `onPackageReady` 阶段 Application 还不存在、拿不到 Context。所以直接解析 APK 的二进制
     * manifest —— 见 [ApkVersion]。
     */
    private fun readVersionCode(param: PackageReadyParam): Long =
        ApkVersion.readVersionCode(param.applicationInfo.sourceDir) ?: -1L
}