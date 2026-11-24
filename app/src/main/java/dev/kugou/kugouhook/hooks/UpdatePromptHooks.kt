package dev.kugou.kugouhook.hooks

import dev.kugou.kugouhook.ClassResolver
import dev.kugou.kugouhook.HookManager
import dev.kugou.kugouhook.ModuleLog
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 隐藏「发现新版本」弹窗，但保留用户主动检查更新和强制升级。
 *
 * ## 宿主链路（酷狗 20.6.4）
 *
 * 所有升级弹窗最终都经由同一个构造点：
 *
 * ```
 * com.kugou.version.upgrade.VersionUpgradeFeature   ← IVersionUpgrade 的 Hive 实现
 *   ├─ checkUpdateDialog(Activity, int)  →  b.d().b(activity, pageId)
 *   └─ createAutoChecker(Activity)       →  ll6.a    （自动检查器）
 *      createManualChecker(Activity, cb) →  ll6.b    （手动检查器）
 * ```
 *
 * 三条路径汇合到 `com.kugou.version.upgrade.b#k(Activity, 版本信息, boolean)`：
 *
 * - 自动（Fragment 恢复）：`b(activity, pageId)` → `j()` → `k(..., false)`
 * - 自动（网络返回）　　：`ll6.a#h()` → `j()` → `k(..., false)`
 * - 手动（用户点检查）：`ll6.b#h()` → `k(..., true)`
 *
 * 所以第三个参数就是"是否手动"，这是唯一可靠的区分依据。
 * 弹窗自身的布局是 `R.layout.a9t`：关闭键 `ev4`、按钮 `ev3`、正文 `ev5`。
 *
 * ## 为什么只拦这一处
 *
 * 不 hook `AbsFrameworkFragment.onFragmentResume()`，因为那是所有页面的基类，
 * 在它上面动手影响面远超升级功能本身。`k()` 是唯一构造弹窗的地方，
 * 拦在这里既覆盖全部自动入口，又不受页面结构变化影响。
 *
 * ## 保留的行为
 *
 * - 用户主动检查更新（arg3 == true）照常弹窗
 * - 强制升级（`o()` 为真或 `b() == 1`）照常弹窗：这种弹窗是为了阻止不兼容版本
 *   继续使用，拦掉会导致功能异常而不是"更清爽"
 *
 * ## 关于"随机弹出"
 *
 * 升级弹窗受 `com.kugou.version.upgrade.b#f(版本信息)` 频控（距上次弹窗不足 N 天就不弹），
 * 冷启动不保证出现。**不要用"这次没看到弹窗"当作验证通过** —— 只有日志里出现
 * `update.autoPrompt` 的 hit，才说明这次真的走到了判定分支。
 */
class UpdatePromptHooks(
    private val resolver: ClassResolver,
    private val hooks: HookManager,
    private val log: ModuleLog
) {

    fun install() {
        installUpdateDialogGate()
    }

    private fun installUpdateDialogGate() {
        val id = "update.autoPrompt"
        val manager = resolver.findClass("com.kugou.version.upgrade.b")
        if (manager == null) {
            hooks.reportLoss(id, "version upgrade manager not found")
            return
        }

        // 弹窗的唯一构造点。刻意不写死第二个参数的类型名：那个类在单个 dex
        // 反编译结果里显示为 ll6.f，运行时未必是同一个名字。
        val dialog = manager.declaredMethods.firstOrNull { m ->
            m.name == "k" &&
                m.parameterCount == 3 &&
                m.parameterTypes[0] == android.app.Activity::class.java &&
                m.parameterTypes[2] == Boolean::class.javaPrimitiveType
        }
        if (dialog == null) {
            hooks.reportLoss(id, "upgrade dialog builder k(Activity, ?, boolean) not found")
            return
        }
        dialog.isAccessible = true
        val infoType = dialog.parameterTypes[1]

        // 强制升级判据。源码里是：
        //   o() -> forceUpdate 字段 != 0
        //   b() -> alertMode 字段，等于 1 表示强制
        // 注意 ll6.f 里无参 boolean 方法还有 p()/q()/r()（isgray/patchUpdate/isSuccess），
        // 所以**不能**用"任意 boolean 为真"来近似，那会让拦截几乎永不生效。
        val forceUpdate = zeroArg(infoType, "o", java.lang.Boolean.TYPE)
        val alertMode = zeroArg(infoType, "b", Integer.TYPE)
        if (forceUpdate == null || alertMode == null) {
            // 方法名对不上就放弃这条规则：宁可漏拦，也不能把强制升级或手动检查误当成广告拦掉。
            hooks.reportLoss(
                id,
                "forced-update accessors o()/b() not found on ${infoType.name}; " +
                    "refusing to guess"
            )
            return
        }
        forceUpdate.isAccessible = true
        alertMode.isAccessible = true

        val seen = AtomicBoolean()
        hooks.install(id, dialog) { chain ->
            val manual = chain.args[2] as? Boolean ?: false
            val info = chain.args[1]

            // 取不到判定结果时按"强制"处理，也就是放行。
            val forced = if (info == null) {
                true
            } else {
                runCatching {
                    forceUpdate.invoke(info) == true || alertMode.invoke(info) == 1
                }.getOrDefault(true)
            }

            if (manual || forced) {
                chain.proceed()
            } else {
                if (seen.compareAndSet(false, true)) log.info("suppressed automatic update prompt")
                null
            }
        }
    }

    private fun zeroArg(owner: Class<*>, name: String, returnType: Class<*>): Method? =
        runCatching { owner.getDeclaredMethod(name) }
            .getOrNull()
            ?.takeIf { it.returnType == returnType }
}
