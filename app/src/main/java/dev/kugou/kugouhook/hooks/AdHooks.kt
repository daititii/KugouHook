package dev.kugou.kugouhook.hooks

import android.view.View
import android.view.ViewGroup
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

        /**
         * 推荐流里两个专门的广告 binder。
         *
         * 它们相邻、同构（都继承 `wz.k`）、各管一类广告，`h0` 造卡 / `g0` 绑定：
         *
         * - `binder.c` — Compete/GDT 广告：红果免费短剧、快手极速版、任务中心卡
         * - `binder.d` — Inner/会员推广：免费领酷狗音乐VIP、42天畅听卡
         *
         * 卡片布局 id 也相邻（2131562752 / 2131562753），说明是同一批生成的兄弟类。
         * 只拦其中一个，另一个渲染的卡照样会露出来。
         */
        val INNER_AD_BINDERS = listOf(
            "com.kugou.android.app.newtingtab.binder.c",
            "com.kugou.android.app.newtingtab.binder.d",
        )

        /** 广告 bean 的 module 串特征（服务端下发，抗混淆）。 */
        const val AD_MODULE_MARK = "_ad"

        /** 广告 bean 的类名特征。 */
        const val AD_CLASS_MARK = "AdBean"

        /** 观测用：已记录过的 bean 类型，避免刷屏。 */
        val seenKinds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        const val MAX_KINDS = 40
    }

    fun install() {
        installAddFilter()
        installAddAllFilter()
        installInnerAdCardBlock()
    }

    /**
     * 推荐流里的「运营推广/会员推广」广告卡。
     *
     * ## 这类卡片为什么躲过了前面两条规则
     *
     * 它们不走 `CopyOnWriteArrayList.add`，而是由 `binder.d` 这个 **专门的广告 binder**
     * 直接 inflate 出 ViewHolder 塞给 RecyclerView。`binder.d` 实现
     * `KG11InnerAdCardManager$InnerAdLoadFinishCallback`，`h0()` 的唯一职责就是
     * `inflate(2131562753)` 再 `new InnerAdViewHolder(...)`，三个产出 bean 的方法
     * (`j0`/`c0`/`f0`) 全部 `return 0`。所以它是纯广告 binder，拦它不会误伤正常卡片。
     *
     * ## 怎么确认的
     *
     * 三张形态完全不同的卡（「快手极速版」1 元秒杀、「免费领酷狗音乐VIP」元宝联名、
     * 「42天畅听卡」邀请好友）用的是同一套容器控件 `giw0`/`giw1`/`giz0`/`gj21`。
     * 在 `LayoutInflater.inflate` 上挂探针，按这些标志控件识别，直接抓到调用栈：
     *
     *     mc.onCreateViewHolder → tv.e.onCreateViewHolder → binder.d.v → binder.d.h0
     *
     * ## 为什么返回空 ViewHolder 而不是 null
     *
     * `onCreateViewHolder` 返回 null 会让 RecyclerView 在 `tryGetViewHolderForPosition`
     * 之后立刻 NPE。这里构造一个 0×0 的空 View，宿主拿到的是"存在但看不见"的 holder，
     * 布局流程照常走完，只是不占空间。
     *
     * 只影响展示：不改变广告请求、不影响推荐流其它卡片、不碰任何会员或播放逻辑。
     */
    private fun installInnerAdCardBlock() {
        val layoutInflaterType = runCatching { Class.forName("android.view.LayoutInflater") }.getOrNull()
        val viewGroupType = runCatching { Class.forName("android.view.ViewGroup") }.getOrNull()
        if (layoutInflaterType == null || viewGroupType == null) {
            hooks.reportLoss("ad.innerCard.block", "inflater types unavailable")
            return
        }

        // 推荐流里有**两个**广告 binder，都要拦：
        //   binder.c  Compete·GDT 广告（红果免费短剧、快手极速版这类）
        //   binder.d  Inner·会员推广（「免费领酷狗音乐VIP」「42天畅听卡」）
        // 它们都继承自 wz.k，各自有 h0(inflater, parent) 造卡、g0(holder, bean) 绑定。
        // 只拦 d 的时候 c 渲染的卡照样露出来——实测踩过一次。
        var installed = 0
        for (binderName in INNER_AD_BINDERS) {
            val binder = resolver.findClass(binderName) ?: run {
                hooks.reportLoss("ad.innerCard.block[$binderName]", "binder missing")
                continue
            }
            val tag = binderName.substringAfterLast('.')

            val create = binder.declaredMethods.firstOrNull {
                it.name == "h0" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == layoutInflaterType &&
                    it.parameterTypes[1] == viewGroupType
            }
            if (create == null) {
                hooks.reportLoss("ad.innerCard.create.$tag", "h0(LayoutInflater, ViewGroup) not found")
                continue
            }
            create.isAccessible = true
            val seen = java.util.concurrent.atomic.AtomicBoolean()
            hooks.install("ad.innerCard.create.$tag", create) { chain ->
                // 先让宿主按原样造出真正的 holder——它内部要 findViewById 绑定一堆控件，
                // 绕过去必然崩。造好再把 itemView 压成 0×0 且不可见。
                val holder = chain.proceed()
                collapse(holder, seen, "create/$tag")
                holder
            }
            installed++

            // 绑定时再压一次：onCreateViewHolder 只在首次创建时走，onBindViewHolder
            // 每次滚进可视区都会走。宿主可能在绑定里重置 LayoutParams，只压一处会被覆盖。
            val bind = binder.declaredMethods.firstOrNull {
                it.name == "g0" && it.parameterTypes.size == 2
            }
            if (bind == null) {
                hooks.reportLoss("ad.innerCard.bind.$tag", "g0(holder, bean) not found")
                continue
            }
            bind.isAccessible = true
            hooks.install("ad.innerCard.bind.$tag", bind, requireVoid = true) { chain ->
                val result = chain.proceed()
                collapse(chain.args.getOrNull(0), null, null)
                result
            }
            installed++
        }

        if (installed == 0) hooks.reportLoss("ad.innerCard.block", "no ad binder hooked")
    }

    /** 把 holder 的 itemView 压成 0×0 并隐藏；直接改 View 树，不碰列表结构。 */
    private fun collapse(holder: Any?, seen: java.util.concurrent.atomic.AtomicBoolean?, label: String?) {
        runCatching {
            val itemView = holder?.javaClass?.getField("itemView")?.get(holder) as? View ?: return
            itemView.layoutParams = ViewGroup.LayoutParams(0, 0)
            itemView.visibility = View.GONE
            if (seen != null && label != null && seen.compareAndSet(false, true)) {
                log.info("collapsed inner ad card ($label)")
            }
        }
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
            if (bean != null && looksLikeFeedList(args)) {
                // 观测：把推荐流里实际进列表的 bean 类型和 module 记下来（每种只记一次）。
                // 广告判定漏掉某一类时，这条日志直接告诉你它长什么样，不用再猜。
                val key = bean.javaClass.simpleName
                if (seenKinds.size < MAX_KINDS && seenKinds.add(key)) {
                    val module = runCatching {
                        val f = bean.javaClass.findFieldUpTo(8) { it.name == "module" }
                        f?.apply { isAccessible = true }?.get(bean) as? String
                    }.getOrNull()
                    log.info("feed bean: $key module=${module ?: "-"} ad=${isAdBean(bean)}")
                }
            }
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