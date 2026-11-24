package dev.kugou.kugouhook.hooks

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
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
    private val dialogInitHits = AtomicInteger()
    private val dialogFragmentHits = AtomicInteger()
    private val adCardHits = AtomicInteger()

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
        installDialogConstructor()
        installDialogFragmentShow()
        installAdCardInflate()
    }

    /**
     * 推荐流里的广告卡（「快手极速版」「免费领酷狗音乐VIP」那两张）。
     *
     * 它们的容器 id 是 `giw0`(0x7f0acb5a) / `giw1`(0x7f0acb5b)，但整套 `newtingtab` 包里
     * 搜不到这个 id，说明卡片是**别的模块**通过 binder 注册进来的。既然不知道是谁，
     * 就不猜——直接把带这个 id 的 View 的创建点打印出来，看调用栈。
     *
     * 只观测，不拦截。确认了创建者再决定拦哪一层。
     */
    private fun installAdCardInflate() {
        val id = "probe.adCard.parse"
        val inflaterClass = runCatching { Class.forName("android.view.LayoutInflater") }.getOrNull() ?: return
        val inflate = runCatching {
            inflaterClass.getDeclaredMethod(
                "inflate",
                Integer.TYPE,
                ViewGroup::class.java,
                java.lang.Boolean.TYPE
            ).apply { isAccessible = true }
        }.getOrNull() ?: run {
            log.info("probe inflate unavailable")
            return
        }
        hooks.install(id, inflate) { chain ->
            val result = chain.proceed()
            runCatching {
                val layout = chain.args[0] as? Int
                // 直接检查 inflate 出来的视图树里有没有广告卡的标志性控件。
                // 比按 layout id 猜可靠：布局 id 未知，但控件 id 已经确认（giw0/giw1）。
                val root = result as? View
                val hit = root != null && AD_CARD_MARKERS.any { root.findViewById<View>(it) != null }
                if (hit) {
                    val n = adCardHits.incrementAndGet()
                    if (n <= MAX_LOGS || n % LOG_EVERY == 0) {
                        log.info(
                            "probe adCard[$n] layout=0x${layout?.toString(16)} root=${root?.javaClass?.name}\n" +
                                stackOf()
                        )
                    }
                }
            }
            result
        }
    }

    /**
     * 抓 Dialog 的**构造**点，而不只是 show。
     *
     * 底部弹层（BottomSheet 形态）经常是先 new 出来、攒好数据再 show，两者不在同一处。
     * 只看 show 会丢掉真正的构造现场；构造点的栈也更能说明"是哪个业务流程要弹的"。
     */
    private fun installDialogConstructor() {
        val id = "probe.dialog.init"
        val ctor = runCatching {
            Dialog::class.java.getDeclaredConstructor(android.content.Context::class.java)
                .apply { isAccessible = true }
        }.getOrNull() ?: run {
            log.info("probe dialog.<init> unavailable")
            return
        }
        hooks.install(id, ctor) { chain ->
            runCatching {
                val n = dialogInitHits.incrementAndGet()
                if (n <= MAX_LOGS || n % LOG_EVERY == 0) {
                    log.info("probe dialogInit[$n] ${chain.thisObject?.javaClass?.name}\n${stackOf()}")
                }
            }
            chain.proceed()
        }
    }

    /**
     * DialogFragment 走的是 `show(FragmentManager, String)`，不经过 `Dialog.show`。
     * 酷狗的付费/会员类弹窗大量使用这条路径。
     */
    private fun installDialogFragmentShow() {
        val id = "probe.dialogFragment.show"
        val cls = runCatching { Class.forName("androidx.fragment.app.DialogFragment") }.getOrNull()
        val fm = runCatching { Class.forName("androidx.fragment.app.FragmentManager") }.getOrNull()
        val show = if (cls != null && fm != null) {
            runCatching { cls.getDeclaredMethod("show", fm, String::class.java).apply { isAccessible = true } }.getOrNull()
        } else null
        if (show == null) {
            log.info("probe dialogFragment.show unavailable")
            return
        }
        hooks.install(id, show, requireVoid = true) { chain ->
            runCatching {
                val n = dialogFragmentHits.incrementAndGet()
                if (n <= MAX_LOGS || n % LOG_EVERY == 0) {
                    log.info(
                        "probe dialogFragment[$n] ${chain.thisObject?.javaClass?.name} " +
                            "tag=${chain.args.getOrNull(1)}\n${stackOf()}"
                    )
                }
            }
            chain.proceed()
        }
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

        /**
         * 每种探针各记前 N 条。之前是全局上限 3，结果启动阶段的几个弹窗就把额度吃光了，
         * 真正想看的那个（用户手动触发的）根本轮不到。
         */
        const val MAX_LOGS = 12
        const val LOG_EVERY = 200
        const val SKIP_FRAMES = 2
        const val MAX_FRAMES = 12

        /**
         * 推荐流广告卡的标志性控件 id。
         *
         * `giw0`(0x7f0acb5a) 是标题栏、`giw1`(0x7f0acb5b) 是图片区、`giz0`(0x7f0acb60) 是关闭按钮。
         * 这三者在「快手极速版」和「免费领酷狗音乐VIP」两张卡上完全一致，是可靠的识别特征。
         * 整套 `newtingtab` 包里搜不到这些 id，说明卡片由别的模块通过 binder 注册，
         * 所以布局 id 未知——只能靠"inflate 出来的树里有没有这些控件"来抓。
         */
        val AD_CARD_MARKERS = intArrayOf(
            0x7f0acb5a, // giw0 标题栏
            0x7f0acb5b, // giw1 图片区
            0x7f0acb60, // giz0 关闭按钮
        )
    }
}
