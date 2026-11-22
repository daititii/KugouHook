package dev.kugou.kugouhook.hooks

import dev.kugou.kugouhook.ClassResolver
import dev.kugou.kugouhook.HookManager
import dev.kugou.kugouhook.ModuleLog

/**
 * 「我的」页顶部动态 banner 广告（"免费领会员"那一条）。
 *
 * 它不在推荐流的列表里，因此 L1 的 `CopyOnWriteArrayList.add` 拦不到，
 * 必须单独处理。归属：
 *
 *   com.kugou.android.ads.mine.dynamicbannerad.MineAdBannerMainDelegate
 *     static MineTopBannerEntity$Data$Ad i(delegate)    ← 读广告数据
 *     void i0(MineTopBannerEntity$Data$Ad)              ← 写广告数据
 *
 * 做法：让 `i()` 恒返回 null（宿主认为没有广告数据），`i0()` 直接吞掉（不写入）。
 * 两者都是无副作用的纯数据操作，比隐藏 View 稳，也不会留下空白占位。
 *
 * 注意这里**不 hook 任何 UI 方法** —— 一旦碰到 View 可见性，就得保证在主线程，
 * 还要处理 ViewHolder 复用，代价和风险都远高于改数据。
 */
class MineBannerHooks(
    private val resolver: ClassResolver,
    private val hooks: HookManager,
    private val log: ModuleLog
) {

    companion object {
        const val DELEGATE = "com.kugou.android.ads.mine.dynamicbannerad.MineAdBannerMainDelegate"
    }

    fun install() {
        val delegate = resolver.findClass(DELEGATE) ?: run {
            hooks.reportLoss("mine.banner.block", "delegate class missing")
            return
        }

        // i(delegate) 是 static，参数是被代理对象；返回值是广告数据。
        val getter = delegate.declaredMethods.firstOrNull {
            it.name == "i" && it.parameterTypes.size == 1 && it.returnType != java.lang.Void.TYPE
        }
        if (getter != null) {
            getter.isAccessible = true
            hooks.install("mine.banner.nullData", getter) {
                log.info("blocked mine banner data (i -> null)")
                null
            }
        } else {
            hooks.reportLoss("mine.banner.nullData", "i(delegate) not found")
        }

        // i0(Ad) 写入广告数据，直接吞掉。
        val setter = delegate.declaredMethods.firstOrNull {
            it.name == "i0" && it.parameterTypes.size == 1
        }
        if (setter != null && setter.returnType == java.lang.Void.TYPE) {
            setter.isAccessible = true
            hooks.install("mine.banner.dropWrite", setter, requireVoid = true) {
                log.info("dropped mine banner write (i0)")
                null
            }
        } else if (setter != null) {
            hooks.reportLoss("mine.banner.dropWrite", "i0 is not void")
        }
    }
}