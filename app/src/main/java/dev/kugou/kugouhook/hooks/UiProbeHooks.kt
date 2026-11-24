package dev.kugou.kugouhook.hooks

import android.app.Dialog
import android.view.View
import dev.kugou.kugouhook.ClassResolver
import dev.kugou.kugouhook.Flags
import dev.kugou.kugouhook.HookManager
import dev.kugou.kugouhook.ModuleLog
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * 观测钩子：把"某个弹窗/浮层是哪个类弹出来的"直接写进日志。
 *
 * ## 为什么需要它
 *
 * 之前找升级弹窗的流程是：反编译整包 → 搜关键词 → 猜类名 → 装机 → 没命中 → 回去再翻。
 * 一轮几分钟，而且**猜错时什么也学不到**。
 *
 * 换一个方向：不猜，让宿主自己报。弹窗出现时，调用栈里就有真实的构造者。
 * 这类通用出口的好处是覆盖全部来源 —— 无论是启动计时、服务端下发还是活动页触发，
 * 最终都要经过这些方法之一。
 *
 * ## 为什么默认关闭
 *
 * 这三处是**全应用高频调用**（`Dialog.show` 每次弹窗都会走）。常开会让日志迅速膨胀，
 * 并且在主线程上取堆栈本身有成本。所以它由 `kugouhook.flags` 里的 `ui.probe=1` 控制，
 * 只在排查阶段打开。
 *
 * ## 为什么只记日志、不拦截
 *
 * 观测和拦截是两件事，混在一起会导致"以为在观测，其实已经改了行为"。
 * 先看清是谁、在什么条件下弹的，再决定拦哪一处 —— 顺序反了就会像上次那样，
 * 装了一个猜出来的 hook，既不命中也不知道对不对。
 */
class UiProbeHooks(
    private val resolver: ClassResolver,
    private val hooks: HookManager,
    private val log: ModuleLog,
    private val flags: Flags
) {

    private val dialogHits = AtomicInteger()
    private val popupHits = AtomicInteger()
    private val windowHits = AtomicInteger()
    private val surfaceHits = AtomicInteger()

    fun install() {
        if (!flags.enabled(FLAG_KEY)) {
            log.info("ui probe disabled (set $FLAG_KEY=1 in kugouhook.flags to enable)")
            return
        }
        log.info("ui probe enabled: recording dialog/popup/window creators")
        installDialogShow()
        installPopupWindow()
        installWindowManager()
        installSurfaceView()
    }

    /**
     * 开屏广告用 `SurfaceView` 自绘（content-desc=`SplashDrawer`），不经过 View 绑定也不走 Dialog。
     * 它的构造点比任何业务回调都更靠近渲染，命中后能直接看到是谁在喂素材。
     */
    private fun installSurfaceView() {
        val id = "probe.surfaceView"
        val ctor = runCatching {
            android.view.SurfaceView::class.java
                .getDeclaredConstructor(android.content.Context::class.java)
                .apply { isAccessible = true }
        }.getOrNull()
        hooks.install(id, ctor) { chain ->
            val result = chain.proceed()
            runCatching {
                val view = result as? View
                val n = surfaceHits.incrementAndGet()
                if (n <= MAX_LOGS || n % LOG_EVERY == 0) {
                    log.info(
                        "probe surfaceView[$n] id=0x${view?.id?.toString(16)} " +
                            "desc=${view?.contentDescription}\n${stackOf()}"
                    )
                }
            }
            result
        }
    }

    private fun installDialogShow() {
        val id = "probe.dialog.show"
        val show = runCatching {
            Dialog::class.java.getDeclaredMethod("show").apply { isAccessible = true }
        }.getOrNull()
        hooks.install(id, show, requireVoid = true) { chain ->
            // 先 proceed：此时 Dialog 已经 attach，mWindow/mContext 才可读。
            val result = chain.proceed()
            runCatching {
                val dialog = chain.thisObject as? Dialog
                if (dialog != null) {
                    val n = dialogHits.incrementAndGet()
                    if (n <= MAX_LOGS || n % LOG_EVERY == 0) {
                        log.info(
                            "probe dialog[$n] ${dialog.javaClass.name} " +
                                "ctx=${dialog.context?.javaClass?.name}\n" +
                                stackOf()
                        )
                    }
                }
            }
            result
        }
    }

    private fun installPopupWindow() {
        val id = "probe.popup.showAt"
        val popupClass = resolver.findClass("android.widget.PopupWindow") ?: return
        val showAt = runCatching {
            popupClass.getDeclaredMethod(
                "showAtLocation",
                View::class.java,
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE
            ).apply { isAccessible = true }
        }.getOrNull()
        hooks.install(id, showAt, requireVoid = true) { chain ->
            val result = chain.proceed()
            runCatching {
                val n = popupHits.incrementAndGet()
                if (n <= MAX_LOGS || n % LOG_EVERY == 0) {
                    log.info("probe popup[$n] ${chain.thisObject?.javaClass?.name}\n${stackOf()}")
                }
            }
            result
        }
    }

    private fun installWindowManager() {
        // WindowManager 是接口，实际实现类在系统里；hook 接口方法在部分框架上不生效，
        // 所以这里按"实现类 + 方法签名"兜底，找不到就静默跳过（观测缺失不影响主功能）。
        val id = "probe.window.addView"
        val addView = runCatching {
            val wm = Class.forName("android.view.WindowManager")
            wm.getDeclaredMethod("addView", View::class.java, Class.forName("android.view.ViewGroup\$LayoutParams"))
        }.getOrNull()
        if (addView == null) {
            log.info("probe window.addView unavailable on this host")
            return
        }
        hooks.install(id, addView, requireVoid = true) { chain ->
            val result = chain.proceed()
            runCatching {
                val n = windowHits.incrementAndGet()
                if (n <= MAX_LOGS || n % LOG_EVERY == 0) {
                    val view = chain.args[0] as? View
                    log.info("probe addView[$n] view=${view?.javaClass?.name} id=0x${view?.id?.toString(16)}\n${stackOf()}")
                }
            }
            result
        }
    }

    /**
     * 取当前线程调用栈，跳过前若干层框架与自身。
     *
     * 只保留看起来有意义的帧：过滤掉 `java.` / `android.` / `dev.kugou.kugouhook.` 前缀，
     * 因为真正想知道的是**宿主自己的类**。全栈会把日志冲垮，反而不易读。
     */
    private fun stackOf(): String {
        val frames = Throwable().stackTrace
            .drop(SKIP_FRAMES)
            .filterNot { f ->
                val n = f.className
                n.startsWith("java.") || n.startsWith("android.") ||
                    n.startsWith("androidx.") || n.startsWith("dev.kugou.kugouhook.") ||
                    n.startsWith("io.github.libxposed.")
            }
            .take(MAX_FRAMES)
        if (frames.isEmpty()) return "    (no host frames)"
        return frames.joinToString("\n") { "    at $it" }
    }

    private companion object {
        const val FLAG_KEY = "ui.probe"
        const val MAX_LOGS = 3
        const val LOG_EVERY = 50
        const val SKIP_FRAMES = 2
        const val MAX_FRAMES = 10
    }
}
