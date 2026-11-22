package dev.kugou.kugouhook.hooks

import dev.kugou.kugouhook.ClassResolver
import dev.kugou.kugouhook.HookManager
import dev.kugou.kugouhook.ModuleLog

/**
 * 开屏广告（运营推广）拦截 + onNoAD 回调。
 *
 * ## 为什么开屏必须单独处理
 * 推荐流广告拦在数据层就够了，但开屏广告是**占满整屏并阻塞进入**的：
 * 素材一旦请求成功，`SplashActivity` 会被它占住若干秒。单纯"不渲染"不够，
 * 必须让宿主走它自己的"无广告"分支，开屏才会立刻关闭。
 *
 * ## 关键技巧：主动回调 onNoAD
 * 这里 hook 的是 `onADFetch`（素材已到达）。做法是**不 proceed**，然后主动调用
 * 监听器自己的 `onNoAD(AdError)`。宿主收到 onNoAD 就当作没广告，直接进首页。
 *
 * 这也是很多去广告模块漏掉的一步：只让加载失败、却不回调失败通知，
 * UI 会一直卡在加载态等超时。
 *
 * 监听器签名（实机 dump）：
 *   com.tencentmusic.ad.integration.operationsplash.operationSplash.controller
 *     .TMEOperSplashAdListener
 *       void onADFetch(TMEOperSplashAdAsset)   ← 素材到达，拦这里
 *       void onNoAD(AdError)                   ← 主动回调它
 *       void onADSkip()
 *       void onADDismissed()
 *
 * AdError 是 Kotlin data class，唯一构造器 `AdError(int, String)`。
 */
class SplashAdHooks(
    private val resolver: ClassResolver,
    private val hooks: HookManager,
    private val log: ModuleLog
) {

    companion object {
        const val LISTENER =
            "com.tencentmusic.ad.integration.operationsplash.operationSplash.controller.TMEOperSplashAdListener"
        const val AD_ERROR = "com.tencentmusic.ad.integration.error.AdError"

        /** 204 = 无广告填充，是各广告 SDK 的通用约定。 */
        const val NO_AD_CODE = 204
    }

    fun install() {
        val listener = resolver.findClass(LISTENER) ?: run {
            hooks.reportLoss("splash.block", "listener class missing")
            return
        }

        installFetchBlock(listener)
        installFetchWithResultBlock(listener)
    }

    private fun installFetchBlock(listener: Class<*>) {
        val onFetch = listener.declaredMethods.firstOrNull {
            it.name == "onADFetch" && it.parameterTypes.size == 1
        } ?: run {
            hooks.reportLoss("splash.block.onADFetch", "onADFetch(asset) not found")
            return
        }
        onFetch.isAccessible = true

        hooks.install("splash.block.onADFetch", onFetch, requireVoid = true) { chain ->
            val self = chain.thisObject
            log.info("blocked splash onADFetch -> fire onNoAD")
            fireNoAd(self)
            // 不 proceed：素材不进入渲染
            null
        }
    }

    /** GDT 侧的开屏走另一个入口，一并拦掉。 */
    private fun installFetchWithResultBlock(listener: Class<*>) {
        val onFetchRes = listener.declaredMethods.firstOrNull {
            it.name == "onADFetchWithResult" && it.parameterTypes.size == 1
        } ?: return
        onFetchRes.isAccessible = true

        hooks.install("splash.block.onADFetchWithResult", onFetchRes, requireVoid = true) { chain ->
            val self = chain.thisObject
            log.info("blocked splash onADFetchWithResult -> fire onNoAD")
            fireNoAd(self)
            null
        }
    }

    /**
     * 构造 AdError 并回调 onNoAD。
     *
     * 全程 runCatching：即使 AdError 构造器签名在新版本变了，也只是这一次没回调成功，
     * 不能把异常抛回宿主（PROTECTIVE 模式虽然会兜，但这里主动吞更干净）。
     */
    private fun fireNoAd(self: Any?) {
        if (self == null) return
        runCatching {
            val errClass = resolver.findClass(AD_ERROR) ?: return
            val ctor = errClass.getDeclaredConstructor(
                java.lang.Integer.TYPE, String::class.java
            ).also { it.isAccessible = true }
            val err = ctor.newInstance(NO_AD_CODE, "no ad")
            val onNoAd = self.javaClass.declaredMethods.firstOrNull {
                it.name == "onNoAD" && it.parameterTypes.size == 1
            } ?: self.javaClass.methods.firstOrNull {
                it.name == "onNoAD" && it.parameterTypes.size == 1
            } ?: return
            onNoAd.isAccessible = true
            onNoAd.invoke(self, err)
            log.info("onNoAD fired")
        }.onFailure {
            log.warn("fireNoAd failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }
}