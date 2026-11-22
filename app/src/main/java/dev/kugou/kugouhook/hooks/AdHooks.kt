package dev.kugou.kugouhook.hooks

import dev.kugou.kugouhook.ClassResolver
import dev.kugou.kugouhook.HookManager
import dev.kugou.kugouhook.ModuleLog
import io.github.libxposed.api.XposedInterface.Chain

/**
 * 酷狗广告拦截规则。
 *
 * ## 一切结论都来自实机 dump（见 docs/REVERSE-NOTES.md）
 *
 * 广告数据最终落在一个 `java.util.concurrent.CopyOnWriteArrayList`，而
 * `getItemCount()` **直接读这个列表** —— 所以在 `add()` 那一刻把广告 bean 丢掉，
 * 列表长度、`getDatas()`、绑定下标三者天然自洽，不需要同步任何计数。
 * 这是本模块只做数据层、不碰 View 树的根本原因。
 *
 * 广告 bean 的判定不写死类名，而是看服务端下发的 `module` 字符串：
 *
 *   InnerAdBean    -> module_inner_ad      （推荐流内嵌卡）
 *   CompeteAdBean  -> module_ad_banner     （竞品/tab 广告 banner）
 *
 * 类名会随版本混淆变化，`module` 是服务端数据，跨版本稳定得多。
 * 同时保留 `AdBean` 类名后缀作为第二条判据 —— 两条任一命中即视为广告。
 *
 * ## 为什么不 hook getItemCount
 * 早期版本在 `getItemCount()` 里做原地清除，结果在 RecyclerView 布局过程中
 * 改了列表长度，`dispatchLayoutStep3` detach 视图时 ViewHolder 状态不一致，
 * 触发 `EventBus.unregister` 的空指针崩溃。现在只在 `add()` 上拦，
 * 清除动作放进 `notify*`（宿主自己就要全量重排，此刻改才安全）。
 */
class AdHooks(
    private val resolver: ClassResolver,
    private val hooks: HookManager,
    private val log: ModuleLog
) {

    companion object {
        const val ADAPTER = "com.kugou.android.app.newtingtab.mc"
        const val ADAPTER_DATA_FIELD = "r"
        const val COW_LIST = "java.util.concurrent.CopyOnWriteArrayList"

        /** 广告 bean 的 module 串特征（服务端下发，抗混淆）。 */
        const val AD_MODULE_MARK = "_ad"

        /** 广告 bean 的类名特征。 */
        const val AD_CLASS_MARK = "AdBean"
    }

    fun install() {
        installAddFilter()
        installAddAllFilter()
    }

    /**
     * `CopyOnWriteArrayList.add(E)` —— 广告进列表的那一刻丢弃。
     *
     * ⚠️ 必须只 hook **单参数** 重载。
     * `CopyOnWriteArrayList` 有 `add(E)` 和 `add(int, E)` 两个重载；本项目早期把两个
     * 都指向同一个实现，结果对 `add(int, E)` 丢掉了 element，往列表里插入了 null。
     * 而 EventBus 2.x 的订阅表恰好也是 `CopyOnWriteArrayList<Subscription>`，
     * 于是 `Subscription.subscriberMethod` 立刻 NPE —— 启动崩、切页崩、滚动崩，
     * 看起来像三个独立的"暗桩"，其实是同一个 bug。
     */
    private fun installAddFilter() {
        val listClass = resolver.findClass(COW_LIST) ?: return
        val addOne = listClass.declaredMethods.firstOrNull {
            it.name == "add" && it.parameterTypes.size == 1
        }
        if (addOne == null) {
            hooks.reportLoss("ad.add.filter", "add(E) overload not found")
            return
        }
        addOne.isAccessible = true

        hooks.installBooleanFilter("ad.add.filter", addOne) { args ->
            val bean = args.firstOrNull()
            if (bean != null && isAdBean(bean) && looksLikeFeedList(args)) {
                log.info("blocked ad bean: ${bean.javaClass.simpleName}")
                true
            } else {
                false
            }
        }
    }

    /** `addAll(Collection)` 同理会绕过 `add`，需要单独处理。 */
    private fun installAddAllFilter() {
        val listClass = resolver.findClass(COW_LIST) ?: return
        val addAll = listClass.declaredMethods.firstOrNull {
            it.name == "addAll" && it.parameterTypes.size == 1
        } ?: return
        addAll.isAccessible = true

        hooks.install("ad.addAll.filter", addAll) { chain ->
            val coll = chain.args.firstOrNull() as? Collection<*>
            if (coll != null && coll.any { it != null && isAdBean(it) }) {
                val adCount = coll.count { it != null && isAdBean(it) }
                log.info("blocked addAll containing $adCount ad bean(s)")
                // 逐条加，跳过广告
                val list = chain.thisObject as? MutableCollection<Any?>
                if (list != null) {
                    coll.forEach { item ->
                        if (item != null && !isAdBean(item)) runCatching { list.add(item) }
                    }
                    true
                } else {
                    chain.proceed()
                }
            } else {
                chain.proceed()
            }
        }
    }

    /**
     * 广告 bean 判定。
     *
     * 双判据：类名后缀含 `AdBean`，或 `module` 字段含 `_ad`。
     * 任一命中即算 —— 类名判据在新版本被改掉时，`module` 还能兜住；反之亦然。
     */
    fun isAdBean(bean: Any): Boolean {
        val cn = bean.javaClass.simpleName
        if (cn.contains(AD_CLASS_MARK)) return true
        return runCatching {
            val f = bean.javaClass.findFieldUpTo(8) { it.name == "module" } ?: return false
            f.isAccessible = true
            (f.get(bean) as? String)?.contains(AD_MODULE_MARK) == true
        }.getOrDefault(false)
    }

    /**
     * 只对 feed 列表生效，避免误伤宿主其它 `CopyOnWriteArrayList`
     * （例如 EventBus 的订阅表 —— 往那里插广告 bean 的情况不存在，但判断必须保守）。
     *
     * 判据：列表元素属于 `com.kugou.android.app.newtingtab.bean` 包。
     * 这里拿不到列表本身，只能靠 bean 的包名间接判断。
     */
    private fun looksLikeFeedList(args: List<Any?>): Boolean {
        val bean = args.firstOrNull() ?: return false
        return bean.javaClass.name.startsWith("com.kugou.android.app.newtingtab.bean.")
    }

    private inline fun Class<*>.findFieldUpTo(maxDepth: Int, pred: (java.lang.reflect.Field) -> Boolean): java.lang.reflect.Field? {
        var c: Class<*>? = this
        var d = 0
        while (c != null && d < maxDepth) {
            c.declaredFields.firstOrNull(pred)?.let { return it }
            c = c.superclass
            d++
        }
        return null
    }
}