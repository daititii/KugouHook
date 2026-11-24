package dev.kugou.kugouhook.hooks

import dev.kugou.kugouhook.ClassResolver
import dev.kugou.kugouhook.HookManager
import dev.kugou.kugouhook.ModuleLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 从数据层掐掉运营开屏广告（「运营推广」角标那种全屏卡片）。
 *
 * ## 成功方案：`yk0.g#e(SplashV4)` 恒返回 false
 *
 * 装配入口是 `BaseSplashUnitAgent#e0(SplashV4, UniParallelFetcher)`：
 *
 * ```java
 * boolean z16 = splashV4 != null && yk0.g.e(splashV4);
 * SplashCard card = new SplashCard(activity, window, zN, ..., miitCardInfo);  // 总是创建
 * if (z16) {
 *     card.setDrawerViewZOrderMediaOverlay(true);
 *     if (v4.B1()) card.D0(...)   // 摇一摇
 *     if (v4.h1()) card.A0(...)   // 上滑互动
 *     ... 按字段选择广告形态 ...
 * }
 * splashCard = card;
 * setContentView(splashCard);
 * ```
 *
 * `e()` 为 false 时卡片照常创建、照常 `setContentView`，只是不贴任何广告图层。
 * 真机验证：3 次冷启动，每次命中 3 次，1.5 秒内进入首页，无白屏、无卡启动。
 *
 * ## 为什么之前五次都失败（重要，别再走回去）
 *
 * 这条开屏**没有"下载完成 → 回调 → 展示"的环节**。`SplashCard` 一拿到 `SplashV4`
 * 就直接把图画出来，所以任何回调层的拦截都碰不到它：
 *
 * | 尝试过的拦截点 | 结果 |
 * |---|---|
 * | `ce.f#onADFetch` / `onADFetchWithResult` | 命中，但画面依旧 |
 * | `SplashCard$u#onADFetch` / `onADPresent` | 装上但零命中 |
 * | `xk0.b#a()`（按比例抽样） | 装上但零命中，这条路径根本不经过它 |
 * | `SplashCard#A0` 等广告形态方法 | 命中，只去掉了「向上滑动」手势层，广告图还在 |
 *
 * 根因：广告图由 `SplashDrawer`（SurfaceView）自绘，数据源是 `SplashV4` 本身。
 * 只有拦在**装配之前的内容判定**才有效。
 *
 * ## 副作用
 *
 * `e()` 同时是 `i()` / `m()` / `n()` 的前提，压掉它会让 `e0()` 里的 `zI`、`z12`
 * 也变 false。实测无碍：TME 那条分支本来也已由 [SplashAdHooks] 拦掉。
 */
class SplashGateHooks(
    private val resolver: ClassResolver,
    private val hooks: HookManager,
    private val log: ModuleLog
) {

    fun install() {
        installContentGate()
    }

    private fun installContentGate() {
        val id = "splash.gate.content"
        val gate = resolver.findClass(GATE_HELPER)
        val splashV4 = resolver.findClass(SPLASH_V4)
        if (gate == null || splashV4 == null) {
            hooks.reportLoss(id, "gate helper or SplashV4 not found")
            return
        }
        val e = runCatching { gate.getDeclaredMethod("e", splashV4) }
            .getOrNull()
            ?.takeIf { it.returnType == java.lang.Boolean.TYPE }
        if (e == null) {
            hooks.reportLoss(id, "yk0.g.e(SplashV4) not found")
            return
        }
        val seen = AtomicBoolean()
        hooks.install(id, e) { _ ->
            if (seen.compareAndSet(false, true)) log.info("blocked operation splash (content gate -> false)")
            false
        }
    }

    private companion object {
        /** `e0()` 里判断"这次开屏有没有内容"的静态工具类。 */
        const val GATE_HELPER = "yk0.g"

        /** 开屏数据对象。 */
        const val SPLASH_V4 = "com.kugou.android.splash.entity.v4.SplashV4"
    }
}
