package dev.kugou.kugouhook

import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 统一的 hook 安装入口：逐条 try/catch 隔离、热重载安全、命中可审计。
 *
 * 三条硬约束（都是本项目在实机上付过代价换来的）：
 *
 * 1. **一条 hook 失败绝不能影响其它 hook。**
 *    酷狗的类名随版本漂移，必然会有目标找不到；这时只该少一条规则，不该整个模块失效。
 *
 * 2. **装不上要记账，不能静默。**
 *    [summary] 一行给出 `installed=N skipped=M lost=[...]`。宿主升级后不用重新静态审计
 *    整个目标集，看这一行就知道丢了哪条。注意 [knownMissing] 与真正的丢失要分开——
 *    可预期的缺失如果混进 lost，每次都会看到噪音，真出问题反而看不出来。
 *
 * 3. **必须能证明 hook 真的在链路上。**
 *    这是本项目最惨痛的教训：早期有个拦截装上后统计恒为 0，看起来"生效了"，
 *    实际上目标方法压根没被调用过。打开 [auditHits] 后每条 hook 命中会写
 *    `hook hit[n]: <id>` —— 有 hit 说明宿主拿到了 false 却仍然展示了广告（得换 hook 点），
 *    没有 hit 说明这条链路根本没走这个入口（得找新入口）。两种情况的修法完全不同。
 */
class HookManager(
    private val module: XposedModule,
    private val log: ModuleLog,
    private val auditHits: Boolean = false
) {

    private val installed = mutableListOf<HookHandle>()
    private val installedIds = mutableListOf<String>()

    /** 目标解析不到而跳过的 id —— 宿主升级后是否"破了"就看这个列表。 */
    private val skipped = mutableListOf<String>()

    /** 其中属于可预期缺失的（例如某个类只存在于特定页面/版本），不计入 lost。 */
    private val knownMissing = mutableListOf<String>()

    private val hitCounts = ConcurrentHashMap<String, Int>()

    val installedHandles: List<HookHandle> get() = installed.toList()

    fun installedIdList(): List<String> = installedIds.toList()

    fun hitCountOf(id: String): Int = hitCounts[id] ?: 0

    fun auditEnabled(): Boolean = auditHits

    /** 真正的丢失：跳过了，且不属于可预期缺失。 */
    val unexpectedSkips: List<String> get() = skipped.filterNot { it in knownMissing }

    /**
     * 安装结果一行摘要。宿主升级后靠它定位退化点，不必重跑静态审计。
     */
    fun summary(): String {
        val lost = unexpectedSkips
        val expected = skipped.filter { it in knownMissing }
        return buildString {
            append("hooks installed=${installedIds.size} skipped=${skipped.size}")
            if (lost.isNotEmpty()) append(" lost=[${lost.joinToString(", ")}]")
            if (expected.isNotEmpty()) append(" known-missing=[${expected.joinToString(", ")}]")
        }
    }

    fun reportLoss(id: String, reason: String): HookHandle? = noteSkip(id, reason)

    private fun noteSkip(id: String, reason: String, knownMissing: Boolean = false): HookHandle? {
        skipped += id
        if (knownMissing) {
            this.knownMissing += id
            log.info("hook $id n/a on this host: $reason")
        } else {
            log.warn("skip hook $id: $reason")
        }
        return null
    }

    /**
     * 通用安装。目标为 null 时记 WARN 并返回 null，不影响调用方后续安装。
     *
     * @param requireVoid 目标必须是 void。no-op 型 hook 靠"不 proceed"实现拦截、
     *   函数体返回 null；若目标非 void，这个 null 会被框架当成返回值塞回宿主，
     *   表现为 NPE / ClassCastException —— 本项目早期正是这么崩的。
     */
    fun install(
        id: String,
        method: Method?,
        deoptimize: Boolean = false,
        requireVoid: Boolean = false,
        body: (Chain) -> Any?
    ): HookHandle? {
        if (method == null) return noteSkip(id, "method not found")
        if (requireVoid && method.returnType != java.lang.Void.TYPE) {
            return reportLoss(id, "not a void method: ${method.returnType.name}")
        }
        return installInternal(id, method, deoptimize, body)
    }

    /**
     * 过滤型 hook：默认走原方法，只有 [shouldBlock] 命中才短路返回 false。
     * 适用于 `add()` / `isAd()` 这类布尔闸门。
     */
    fun installBooleanFilter(
        id: String,
        method: Method?,
        deoptimize: Boolean = false,
        shouldBlock: (args: List<Any?>) -> Boolean
    ): HookHandle? {
        if (method == null) return noteSkip(id, "method not found")
        if (method.returnType != java.lang.Boolean.TYPE) {
            return noteSkip(id, "not a boolean primitive method")
        }
        return installInternal(id, method, deoptimize) { chain ->
            if (shouldBlock(chain.args)) false else chain.proceed()
        }
    }

    /** 恒定返回 boolean 的替换。 */
    fun replaceBoolean(
        id: String,
        method: Method?,
        value: Boolean,
        deoptimize: Boolean = false,
        knownMissingOnMiss: Boolean = false
    ): HookHandle? {
        if (method == null) return noteSkip(id, "method not found", knownMissingOnMiss)
        if (method.returnType != java.lang.Boolean.TYPE) {
            return noteSkip(id, "not a boolean primitive method (${method.returnType.simpleName})")
        }
        return installInternal(id, method, deoptimize) { value }
    }

    /**
     * 命中审计：每个 hook 真实被调用时记一行。
     *
     * 前 [AUDIT_MAX_LOGS] 次全记，之后每 [AUDIT_EVERY] 次记一行 —— 既能看到它活着，
     * 又不会被高频 hook（如 add()）刷屏。
     */
    private fun auditHit(id: String) {
        if (!auditHits) return
        val n = hitCounts.merge(id, 1, Int::plus) ?: 1
        if (n <= AUDIT_MAX_LOGS || n % AUDIT_EVERY == 0) {
            log.info("hook hit[$n]: $id")
        }
    }

    private fun installInternal(
        id: String,
        method: Method,
        deoptimize: Boolean,
        body: (Chain) -> Any?
    ): HookHandle? = try {
        if (deoptimize) {
            // 强制调用方不要内联被调方，否则 hook 可能不生效。
            runCatching { module.deoptimize(method) }
                .onFailure { log.warn("deoptimize failed for $id: ${it.javaClass.simpleName}") }
        }
        val handle = module.hook(method)
            .setId(id)
            .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(Hooker { chain ->
                auditHit(id)
                body(chain)
            })
        installed += handle
        installedIds += id
        log.info("hook installed: $id -> ${method.declaringClass.name}#${method.name}")
        handle
    } catch (t: Throwable) {
        // 安装失败也是真丢失，要进摘要，不能因为目标解析成功就报 skipped=0。
        skipped += id
        log.error("hook install failed: $id (${method.declaringClass.name}#${method.name})", t)
        null
    }

    /** 热重载时由 `onHotReloaded()` 调用，退掉所有 hook。 */
    fun unhookAll() {
        installed.forEach { runCatching { it.unhook() } }
        installed.clear()
        installedIds.clear()
        skipped.clear()
        knownMissing.clear()
        hitCounts.clear()
    }

    companion object {
        private const val AUDIT_MAX_LOGS = 3
        private const val AUDIT_EVERY = 200
    }
}