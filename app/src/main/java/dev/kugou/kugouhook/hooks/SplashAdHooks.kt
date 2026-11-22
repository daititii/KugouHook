package dev.kugou.kugouhook.hooks

import dev.kugou.kugouhook.ClassResolver
import dev.kugou.kugouhook.HookManager
import dev.kugou.kugouhook.ModuleLog
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Replaces the operation splash's successful fetch callback with the no-ad callback. */
class SplashAdHooks(
    private val resolver: ClassResolver,
    private val hooks: HookManager,
    private val log: ModuleLog
) {
    companion object {
        private const val LISTENER =
            "com.tencentmusic.ad.integration.operationsplash.operationSplash.controller.TMEOperSplashAdListener"
        // 20.6.4's concrete dispatcher sends both fetch results and onNoAD to the same listeners.
        private const val DISPATCHER = "ce.f"
        private const val AD_ERROR = "com.tencentmusic.ad.integration.error.AdError"
        private const val NO_AD_CODE = 204
        private val FETCH_CALLBACKS = mapOf(
            "onADFetch" to
                "com.tencentmusic.ad.integration.operationsplash.operationSplash.TMEOperSplashAdAsset",
            "onADFetchWithResult" to "com.qq.e.tg.splash.SplashOrder"
        )
    }

    fun install() {
        val dispatcher = resolver.findClass(DISPATCHER)
        val listener = resolver.findClass(LISTENER)
        if (dispatcher == null || listener == null || !listener.isAssignableFrom(dispatcher) ||
            Modifier.isAbstract(dispatcher.modifiers)
        ) {
            skipAll("concrete dispatcher $DISPATCHER not found")
            return
        }

        // Skip both hooks if the replacement callback cannot be prepared.
        val errorClass = resolver.findClass(AD_ERROR)
        val callback = concreteCallback(dispatcher, "onNoAD", errorClass)
        val errorConstructor = errorClass?.let {
            runCatching { it.getDeclaredConstructor(Int::class.javaPrimitiveType, String::class.java) }
                .getOrNull()
        }
        if (callback == null || errorConstructor == null) {
            skipAll("onNoAD(AdError) or AdError(int, String) not found")
            return
        }

        for ((name, parameter) in FETCH_CALLBACKS) {
            val method = concreteCallback(dispatcher, name, resolver.findClass(parameter))
            hooks.install("splash.block.$name", method, requireVoid = true) { chain ->
                if (fireNoAd(chain.thisObject, callback, errorConstructor)) {
                    log.info("blocked splash $name (onNoAD returned)")
                    null
                } else {
                    chain.proceed()
                }
            }
        }
    }

    private fun skipAll(reason: String) {
        for (name in FETCH_CALLBACKS.keys) hooks.reportLoss("splash.block.$name", reason)
    }

    private fun concreteCallback(owner: Class<*>, name: String, parameter: Class<*>?): Method? {
        if (parameter == null) return null
        return runCatching { owner.getDeclaredMethod(name, parameter) }.getOrNull()?.takeIf {
            it.returnType == Void.TYPE && Modifier.isPublic(it.modifiers) &&
                !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers)
        }
    }

    private fun fireNoAd(self: Any?, callback: Method, errorConstructor: Constructor<*>): Boolean {
        if (self == null) return false
        return runCatching {
            callback.invoke(self, errorConstructor.newInstance(NO_AD_CODE, "no ad"))
        }.onFailure {
            log.warn("fireNoAd failed; continuing original fetch callback: ${it.javaClass.simpleName}: ${it.message}")
        }.isSuccess
    }
}
