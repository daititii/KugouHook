package dev.kugou.kugouhook.hooks

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import dev.kugou.kugouhook.ClassResolver
import dev.kugou.kugouhook.HookManager
import dev.kugou.kugouhook.ModuleLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Cosmetic-only hooks for the audited 20.6.4 layouts. */
class VipPromptHooks(
    private val resolver: ClassResolver,
    private val hooks: HookManager,
    private val log: ModuleLog
) {
    private val seenBadge = AtomicBoolean()
    private val seenMineBadge = AtomicBoolean()
    private val seenBookBadge = AtomicBoolean()
    private val seenPromo = AtomicBoolean()
    private val seenBar = AtomicBoolean()
    private val seenMineVipCard = AtomicBoolean()
    private val seenMineCoinCard = AtomicBoolean()
    private val seenCoinPull = AtomicBoolean()
    private val seenMineCoinEnter = AtomicBoolean()
    private val seenCarrierBubble = AtomicBoolean()
    private val seenCarrierOperation = AtomicBoolean()
    private val seenCarrierOperationText = AtomicBoolean()
    private val seenCarrierBanner = AtomicBoolean()
    private val seenVipSongCount = AtomicBoolean()
    private val seenQueueBanner = AtomicBoolean()
    private val seenTrialLabel = AtomicBoolean()
    private val feeBanners = Collections.synchronizedMap(WeakHashMap<View, Unit>())
    private val operationSlots = Collections.synchronizedMap(WeakHashMap<View, Unit>())
    private val boundBadges = Collections.synchronizedMap(WeakHashMap<View, Unit>())

    fun install() {
        installVisibilityGuard()
        installHomeBadge()
        installMineBadge()
        installAudiobookBadge()
        installHomePromoCard()
        installMiniPlayerPrompt()
        installMineCards()
        installMineCoinPull()
        installMineCoinEnter()
        installCarrierBubble()
        installFeeBanner()
        installQueueVipBanner()
        installPlaybarTrialLabel()
        installPlaybackPurchasePrompt()
        installSearchHomeAd()
    }

    private fun installHomeBadge() {
        val id = "vip.ui.homeBadge"
        val holder = resolver.findClass(
            "com.kugou.android.app.newtingtab.binder.QuickItemViewBinder\$QuickItemViewHolder"
        )
        val bean = resolver.findClass("com.kugou.android.app.newtingtab.bean.TingRecQuickBean")
        val method = if (holder != null && bean != null) exactVoid(holder, "S6", bean) else null
        val itemView = runCatching { holder?.getField("itemView") }.getOrNull()
        if (itemView == null || !View::class.java.isAssignableFrom(itemView.type)) {
            hooks.reportLoss(id, "ViewHolder.itemView not found")
            return
        }

        hooks.install(id, method?.takeUnless { Modifier.isStatic(it.modifiers) }, requireVoid = true) { chain ->
            val result = chain.proceed()
            val root = itemView.get(chain.thisObject) as? View
            val badge = root?.findViewById<View>(HOME_VIP_BADGE) as? ImageView
            hideBadge(badge, seenBadge, "home VIP badge")
            result
        }
    }

    private fun installVisibilityGuard() {
        // The host's asynchronous plate animations can make a bound badge visible again.
        val visibility = exactVoid(View::class.java, "setVisibility", Integer.TYPE)
        hooks.install("vip.ui.keepBadgeHidden", visibility, requireVoid = true) { chain ->
            val view = chain.thisObject as? View
            val hidden = view != null && (isBadgeId(view.id) && boundBadges.containsKey(view) ||
                view.id == FEE_BANNER && feeBanners.containsKey(view) && feeBannerKind(view) != null ||
                view.id == HOME_OPERATION_AD && operationSlots.containsKey(view))
            if (chain.args[0] != View.GONE && hidden) {
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun installMineBadge() {
        val delegate = resolver.findClass("us.td")
        val userInfo = resolver.findClass("com.kugou.android.app.navigation.UserInfoHelper")
        val lifecycle = resolver.findClass("ge1.a")
        val method = if (delegate != null && userInfo != null && lifecycle != null) {
            exactVoid(delegate, "l", View::class.java, userInfo, lifecycle)
        } else null
        hooks.install("vip.ui.mineBadge", method, requireVoid = true) { chain ->
            val root = chain.args[0] as? View
            val result = chain.proceed()
            val container = root?.findViewById<ViewGroup>(MINE_VIP_CONTAINER)
            val badge = container?.findViewById<View>(MINE_VIP_BADGE) as? ImageView
            hideBadge(badge, seenMineBadge, "mine VIP badge")
            if (badge?.parent === container && container?.childCount == 1) {
                hideBadge(container, seenMineBadge, "mine VIP badge")
            }
            result
        }
    }

    private fun installAudiobookBadge() {
        val holder = resolver.findClass("hs.d")
        val model = resolver.findClass("hs.c")
        val method = if (holder != null && model != null) exactVoid(holder, "n", model) else null
        val slot = runCatching { holder?.getDeclaredField("h")?.apply { isAccessible = true } }.getOrNull()
        if (slot == null || !ViewGroup::class.java.isAssignableFrom(slot.type)) {
            hooks.reportLoss("vip.ui.audiobookBadge", "audiobook badge container not found")
            return
        }
        hooks.install("vip.ui.audiobookBadge", method, requireVoid = true) { chain ->
            val result = chain.proceed()
            val container = slot.get(chain.thisObject) as? ViewGroup
            if (container?.id == BOOK_VIP_CONTAINER && container.findViewById<View>(BOOK_VIP_BADGE) != null) {
                hideBadge(container, seenBookBadge, "audiobook VIP badge")
            }
            result
        }
    }

    private fun installHomePromoCard() {
        // This delegate prepares the timed recommendation-page VIP promotional card.
        val method = resolver.findClass("pc5.j")?.let { exactVoid(it, "J") }
        hooks.install("vip.ui.homePromoCard", method, requireVoid = true) {
            if (seenPromo.compareAndSet(false, true)) log.info("suppressed home VIP promo card preparation")
            null
        }
    }

    private fun installMiniPlayerPrompt() {
        val id = "vip.ui.miniPlayerPrompt"
        val renderer = resolver.findClass("uc.l0")
        val contact = resolver.findClass("com.kugou.framework.musicfees.vip.contact.e")
        val params = resolver.findClass("uc.r0")
        val text = resolver.findClass("com.kugou.framework.musicfees.vip.contact.d")
        val flag = resolver.findClass("kotlin.jvm.internal.z")
        val method = if (renderer != null && contact != null && params != null && text != null && flag != null) {
            exactVoid(renderer, "r", renderer, contact, params, ViewGroup::class.java, text, text, flag)
                ?.takeIf { Modifier.isStatic(it.modifiers) }
        } else null
        hooks.install(id, method, requireVoid = true) { chain ->
            val prompt = chain.args[3] as? ViewGroup
            val result = chain.proceed()
            hide(prompt?.takeIf { it.id == MINI_PLAYER_VIP_PROMPT }, seenBar, "mini-player VIP prompt")
            result
        }
    }

    private fun installMineCards() {
        val id = "vip.ui.mineCards"
        val delegate = resolver.findClass("us.dd")
        val method = delegate?.let {
            exactVoid(it, "R", LayoutInflater::class.java, ViewGroup::class.java, Bundle::class.java)
        }
        val root = runCatching { delegate?.getDeclaredField("C")?.apply { isAccessible = true } }.getOrNull()
        if (root == null || !View::class.java.isAssignableFrom(root.type)) {
            hooks.reportLoss(id, "mine header root not found")
            return
        }
        hooks.install(id, method, requireVoid = true) { chain ->
            val result = chain.proceed()
            val header = root.get(chain.thisObject) as? View
            hideBadge(header?.findViewById<View>(MINE_VIP_CARD) as? ViewGroup, seenMineVipCard, "mine VIP center card")
            hideBadge(header?.findViewById<View>(MINE_COIN_CARD) as? ViewGroup, seenMineCoinCard, "mine coin center card")
            result
        }
    }

    /**
     * 「我的」页下拉时顶部滑出的金币中心引导横幅（黄色 PAG 动画，
     * 「一大波金币等你领取 / 下拉进入金币中心」）。
     *
     * ## 它是什么
     *
     * `PAGRefreshHeaderView` 实现 `ILoadingLayout`，它**就是这个列表的下拉刷新头**，
     * 只是被换成了金币广告皮肤：
     *
     * ```
     * PAGRefreshHeaderView(Context)
     *   this.g = getConfigMineRefreshPagUrl()      ← 动画素材地址
     * PAGRefreshHeaderView#g()   new PAGView(this); addView(pagView)
     * PAGRefreshHeaderView#j()   setComposition(pag); setProgress(0); play()
     * PAGRefreshHeaderView#l(f)  下拉时逐帧 setTranslationY + play()
     * ```
     *
     * 素材是 `org.libpag.PAGView` 播放的 `.pag` 动画，**文案画在动画帧里**，
     * 所以 UI dump 抓不到文字、`findrefs string` 也搜不到——查的时候别白跑。
     *
     * ## 为什么拦 `getConfigMineRefreshPagUrl()`
     *
     * 它是这条链路的起点，返回 PAG 素材地址：
     *
     * ```java
     * static String getConfigMineRefreshPagUrl() {
     *     String url = config("listen.mine.refresh.pag_url");
     *     return TextUtils.isEmpty(url) ? "https://…/….pag" : url;
     * }
     * ```
     *
     * **配置读不到时它会回退到写死的默认地址**，所以改配置不管用。返回空串后：
     * `g()` 仍会创建 PAGView 但拿不到素材，`j()` 的 `isNotEmpty(f) && exists(f)`
     * 直接不成立 → 不播放、不显示动画。刷新头本身、下拉手势、刷新功能全部保留。
     *
     * ## 试过但不对的路
     *
     * 拦 `x#createLoadingLayout` 让它走 `super`：该方法在一次下拉里被调用上千次，
     * 替换返回值既没改变画面、又放大了调用量。**它不是刷新头工厂**——
     * 全包 `new PAGRefreshHeaderView` 只有 `x:21` 一处，但那个分支实际走不到，
     * 说明「我的」页的刷新头是从别的地方挂上去的。
     *
     * 教训：`hit` 次数异常高（上千次/秒）本身就是信号，说明拦的不是这件事的入口。
     */
    private fun installMineCoinPull() {
        val id = "vip.ui.mineRefreshArtwork"
        val header = resolver.findClass(PAG_REFRESH_HEADER)
        if (header == null) {
            hooks.reportLoss(id, "PAGRefreshHeaderView not found")
            return
        }
        // 两个素材来源都掐，理由不同：
        //   getConfigMineRefreshPagUrl()  -> PAG 动画，**实测生效的就是这条**
        //   getConfigMineRefreshImgUrl()  -> webp 静图，PAG 加载失败时的兜底
        // 实机只命中了前者（后者那次没打印），保留后者是为了素材下发方式变化时不至于漏。
        var installed = 0
        for (name in MINE_REFRESH_ARTWORK_GETTERS) {
            val getter = header.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == 0 && it.returnType == String::class.java
            }
            if (getter == null) continue
            getter.isAccessible = true
            hooks.install("$id.$name", getter) {
                if (seenCoinPull.compareAndSet(false, true)) {
                    log.info("disabled mine pull-down coin artwork ($name -> empty)")
                }
                ""
            }
            installed++
        }
        if (installed == 0) hooks.reportLoss(id, "no artwork getter found")
    }

    /**
     * 下拉到底后"进入金币中心"那一步的拦截，独立于上面的刷新头替换。
     *
     * 两条规则性质不同，不能互相替代：
     * - `vip.ui.mineRefreshHeader` 换掉下拉刷新头，去掉黄色引导横幅的视觉
     * - `vip.ui.mineCoinPull` 拦回弹回调，阻止真正进入金币中心
     *
     * 上一版只保留了前者、删掉了后者，结果手势照样能进金币中心。
     */
    private fun installMineCoinEnter() {
        val method = resolver.findClass(MINE_COIN_ENTER)?.let { exactVoid(it, "run") }
        hooks.install("vip.ui.mineCoinPull", method, requireVoid = true) {
            if (seenMineCoinEnter.compareAndSet(false, true)) {
                log.info("suppressed mine pull-to-open coin center")
            }
            null
        }
    }

    private fun isBadgeId(id: Int): Boolean = when (id) {
        HOME_VIP_BADGE, MINE_VIP_BADGE, MINE_VIP_CONTAINER, BOOK_VIP_CONTAINER, MINE_VIP_CARD, MINE_COIN_CARD,
        QUEUE_VIP_BANNER -> true
        else -> false
    }

    private fun hideBadge(view: View?, seen: AtomicBoolean, label: String) {
        if (view == null) return
        boundBadges[view] = Unit
        if (view.visibility == View.GONE) return
        view.visibility = View.GONE
        if (seen.compareAndSet(false, true)) log.info("hidden $label")
    }

    private fun hide(view: View?, seen: AtomicBoolean, label: String) {
        if (view == null || view.visibility != View.VISIBLE) return
        view.visibility = View.GONE
        if (seen.compareAndSet(false, true)) log.info("hidden $label")
    }

    private fun installCarrierBubble() {
        val holder = resolver.findClass(HOME_QUICK_HOLDER)
        val data = holder?.let { field(it, "k1") }
        val copy = data?.type?.let { field(it, "rec_text") }?.takeIf { it.type == String::class.java }
        val bubble = holder?.let { exactVoid(it, "S7") }
        if (data == null || copy == null) {
            hooks.reportLoss("vip.ui.carrierBubble", "quick-item text model not found")
        } else {
            hooks.install("vip.ui.carrierBubble", bubble, requireVoid = true) { chain ->
                if (isCarrierPromo(data.get(chain.thisObject)?.let { copy.get(it) as? String }.orEmpty())) {
                    if (seenCarrierBubble.compareAndSet(false, true)) log.info("suppressed carrier exchange bubble")
                    null
                } else chain.proceed()
            }
        }

        val slot = holder?.let { field(it, "N0") }?.takeIf { ViewGroup::class.java.isAssignableFrom(it.type) }
        if (slot == null) {
            hooks.reportLoss("vip.ui.carrierOperation", "quick-item operation slot not found")
            return
        }
        hooks.install("vip.ui.carrierOperation", exactVoid(holder, "y7"), requireVoid = true) { chain ->
            val result = chain.proceed()
            val view = slot.get(chain.thisObject) as? ViewGroup
            if (view?.id == HOME_OPERATION_AD) {
                val tag = view.tag
                val text = tag?.takeIf { it.javaClass.simpleName == "HomeUserInfoAdBean" }?.let {
                    val getter = it.javaClass.getMethod("getIcon_text")
                    getter.invoke(it) as? String
                }.orEmpty()
                // 这个槽位（id=HOME_OPERATION_AD）本身就是运营推广位，宿主往里下发什么都算推广，
                // 所以不按文案词表判断：服务端已经换过一次文案（"积分兑会员" → "免费领会员"），
                // 按词表匹配必然滞后一轮。
                if (isOperationPromo(view, text)) {
                    operationSlots[view] = Unit
                    if (seenCarrierOperationText.compareAndSet(false, true)) {
                        log.info("suppressed home operation promo text=[$text]")
                    }
                    hide(view, seenCarrierOperation, "home operation promo")
                } else {
                    // ViewHolder 会被复用。槽位改下发正常内容时必须撤销守护，否则会一直挡住。
                    operationSlots.remove(view)
                }
            }
            result
        }
    }

    private fun installFeeBanner() {
        val id = "vip.ui.feeBanner"
        val delegate = resolver.findClass("com.kugou.framework.musicfees.feestrengthen.FeeStrengthenBarDelegate")
        val model = resolver.findClass("fe5.a")
        val method = if (delegate != null && model != null) {
            exactMethod(delegate, Boolean::class.javaPrimitiveType!!, "n0", model, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
        } else null
        val slot = delegate?.let { field(it, "d") }?.takeIf { ViewGroup::class.java.isAssignableFrom(it.type) }
        if (slot == null) {
            hooks.reportLoss(id, "fee banner root not found")
            return
        }
        hooks.install(id, method) { chain ->
            val result = chain.proceed()
            val view = (slot.get(chain.thisObject) as? ViewGroup)?.takeIf { it.id == FEE_BANNER }
            if (result == true && view != null) {
                feeBanners[view] = Unit
                val kind = feeBannerKind(view)
                if (kind != null) {
                    view.visibility = View.GONE
                    val seen = if (kind == FeeBannerKind.CARRIER) seenCarrierBanner else seenVipSongCount
                    if (seen.compareAndSet(false, true)) log.info("suppressed fee banner: ${kind.name}")
                }
            }
            result
        }
    }

    private fun installQueueVipBanner() {
        val id = "vip.ui.queueVipBanner"
        val owner = resolver.findClass("com.kugou.android.app.player.domain.queue.QueueListSlidingLayout")
        val bannerField = owner?.let { field(it, "e0") }?.takeIf { View::class.java.isAssignableFrom(it.type) }
        if (owner == null || bannerField == null) {
            hooks.reportLoss(id, "queue promotion view not found")
            return
        }
        val method = exactVoid(owner, "I0", Boolean::class.javaPrimitiveType!!)
        hooks.install(id, method, requireVoid = true) { chain ->
            val result = chain.proceed()
            val banner = (bannerField.get(chain.thisObject) as? View)?.takeIf { it.id == QUEUE_VIP_BANNER }
            hideBadge(banner, seenQueueBanner, "queue VIP banner")
            result
        }
    }

    private fun installPlaybarTrialLabel() {
        val id = "vip.ui.playbarTrialLabel"
        val owner = resolver.findClass("com.kugou.android.app.playbar.e1")
        val long = Long::class.javaPrimitiveType!!
        val method = owner?.let { exactMethod(it, Boolean::class.javaPrimitiveType!!, "v", long, long, long) }
        val slot = owner?.let { field(it, "j") }?.takeIf { TextView::class.java.isAssignableFrom(it.type) }
        if (slot == null) {
            hooks.reportLoss(id, "playbar trial label not found")
            return
        }
        hooks.install(id, method) { chain ->
            val result = chain.proceed()
            val view = (slot.get(chain.thisObject) as? TextView)?.takeIf { it.id == PLAYBAR_TRIAL_LABEL }
            hide(view, seenTrialLabel, "playbar trial label")
            result
        }
    }

    /**
     * 点击 VIP 歌曲后弹出的付费推广层（「会员专属歌曲，付费享上亿曲库」那个底部弹层）。
     *
     * ## 这个弹层是什么
     *
     * 它不是一个原生页面，是 SSR H5：`FeeFrontInterceptDialogUtils` 里写死了兜底地址
     * `https://gateway.kugou.com/ssr_vippopup/intercept`。所以整包里搜「付费享上亿曲库」
     * 搜不到——文案是服务端下发的，本地只有 URL。这也是为什么先按文案反查一直落空。
     *
     * ## 之前的规则为什么没生效
     *
     * `FeeFrontInterceptDialogUtils$a#run()` 有两条互斥分支：
     *
     *     if (!d.equals("...FeeInterceptWebActivity")) {
     *         e.putExtra("clazz", d);
     *         e.setAction("com.kugou.android.start_vip");
     *         f.sendBroadcast(e);          // ← 发广播，由别处接收后再起页面
     *     } else {
     *         ... kh5.s1.X(...)            // ← 直接起 WebActivity
     *     }
     *
     * 旧规则只在 `d == FeeInterceptWebActivity`（else 分支）时才拦，而实际点击 VIP 歌曲
     * 走的是**广播分支**，所以条件永远不成立。日志里 `origin` 和 `run` 成对命中、
     * 却从没出现过 `suppressed`，就是这个原因。
     *
     * ## 现在的做法
     *
     * 两条分支一起拦：`run()` 直接返回，弹层不再展示。这只是不展示购买页，
     * 不改变会员状态、不影响试听，用户仍可从会员中心自行购买。
     */
    private fun installPlaybackPurchasePrompt() {
        val id = "vip.ui.playbackPurchasePrompt"
        val utilsName = "com.kugou.framework.musicfees.feefront.FeeFrontInterceptDialogUtils"
        val utils = resolver.findClass(utilsName)
        val runner = resolver.findClass("$utilsName\$a")
        val cart = resolver.findClass("com.kugou.common.musicfees.framework.IShoppingCart")
        val policy = resolver.findClass("qg3.i")
        val entity = resolver.findClass("com.kugou.framework.musicfees.feefront.FeeFrontInterceptDialogEntity")
        val options = resolver.findClass("com.kugou.framework.musicfees.feefront.FeeFrontTingThreeEntity")
        val target = runner?.let { field(it, "d") }?.takeIf { it.type == String::class.java }
        val run = runner?.let { exactVoid(it, "run") }
        if (utils == null || cart == null || policy == null || entity == null || options == null ||
            target == null || run == null) {
            hooks.reportLoss(id, "playback popup presentation not found")
            return
        }

        // 保留落点观测：第一次命中时把 target 打出来，以后版本漂移了能立刻看出走的是哪条分支。
        val capture = exactMethod(utils, Boolean::class.javaPrimitiveType!!, "V", cart, policy, entity, options)
        hooks.install("$id.origin", capture) { chain ->
            chain.proceed()
        }

        val seenBroadcast = AtomicBoolean()
        val seenActivity = AtomicBoolean()
        hooks.install(id, run, requireVoid = true) { chain ->
            val where = runCatching { target.get(chain.thisObject) as? String }.getOrNull().orEmpty()
            val viaActivity = where == FEE_INTERCEPT_ACTIVITY
            val flag = if (viaActivity) seenActivity else seenBroadcast
            if (flag.compareAndSet(false, true)) {
                log.info(
                    "suppressed playback purchase prompt " +
                        "(via=${if (viaActivity) "activity" else "broadcast"}, target=$where)"
                )
            }
            null
        }
    }

    private fun installSearchHomeAd() {
        val id = "ad.search.homeBanner"
        val fragment = resolver.findClass("com.kugou.android.netmusic.search.SearchMainFragment")
        val ad = resolver.findClass("com.kugou.android.netmusic.search.bannerad.BannerAdPresenter\$s")
        val slot = fragment?.let { field(it, "q0") }?.takeIf { ViewGroup::class.java.isAssignableFrom(it.type) }
        if (fragment == null || ad == null || slot == null) {
            hooks.reportLoss(id, "search banner callback or container not found")
            return
        }
        val seen = AtomicBoolean()
        hooks.install(id, exactVoid(fragment, "Td", ad, ad, ad), requireVoid = true) { chain ->
            val page = slot.get(chain.thisObject) as? ViewGroup
            if (page?.id != SEARCH_HOME_CONTENT || chain.args.all { it == null }) {
                chain.proceed()
            } else {
                // q0 also holds history and shortcuts. Skip ad binding without hiding the page.
                if (seen.compareAndSet(false, true)) log.info("blocked search home banner material")
                null
            }
        }
    }

    private enum class FeeBannerKind { CARRIER, VIP_SONG_COUNT }

    private fun feeBannerKind(view: View): FeeBannerKind? {
        val text = view.findViewById<TextView>(FEE_BANNER_COPY)?.text?.toString().orEmpty()
        return when {
            isCarrierPromo(text) -> FeeBannerKind.CARRIER
            VIP_SONG_COUNT.containsMatchIn(text) -> FeeBannerKind.VIP_SONG_COUNT
            else -> null
        }
    }

    private fun isCarrierPromo(text: String): Boolean =
        ("运营商" in text || "移动积分" in text || "联通积分" in text || "电信积分" in text) &&
            "会员" in text && ("积分" in text || "领会员" in text)

    /**
     * 推荐页顶部运营推广位的判定。
     *
     * 不按文案词表：服务端把「运营商积分兑会员」换成「免费领会员」时，旧词表直接漏过。
     * 这个槽位本身就是推广位，所以判据是"有文案"，只在下面这几种明确属于正常内容的情况下放行。
     *
     * 注意这里的放宽是有意的——宁可多隐藏一条推广，也不能因为文案变化就漏掉。
     * 如果以后这个槽位开始下发正常内容，症状是"推荐页少了某个入口"，届时按实际文案补 [exempt]。
     */
    private fun isOperationPromo(view: View, text: String): Boolean {
        if (text.isBlank()) return false
        // 宿主把槽位用于正常导航时通常没有 bean tag；带 tag 说明走的是运营位下发通道。
        if (view.tag?.javaClass?.simpleName == "HomeUserInfoAdBean") return true
        return false
    }

    private fun field(owner: Class<*>, name: String) =
        runCatching { owner.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()

    private fun exactVoid(owner: Class<*>, name: String, vararg params: Class<*>): Method? =
        exactMethod(owner, Void.TYPE, name, *params)

    private fun exactMethod(owner: Class<*>, returnType: Class<*>, name: String, vararg params: Class<*>): Method? =
        runCatching { owner.getDeclaredMethod(name, *params) }.getOrNull()?.takeIf {
            it.returnType == returnType && !Modifier.isAbstract(it.modifiers)
        }

    companion object {
        private const val HOME_QUICK_HOLDER =
            "com.kugou.android.app.newtingtab.binder.QuickItemViewBinder\$QuickItemViewHolder"
        private const val HOME_OPERATION_AD = 0x7f0ad85a
        private const val FEE_BANNER = 0x7f0a634d // id/tm1
        private const val FEE_BANNER_COPY = 0x7f0a6351 // id/tm5
        private const val QUEUE_VIP_BANNER = 0x7f0aafe3 // QueueListSlidingLayout.e0
        private const val PLAYBAR_TRIAL_LABEL = 0x7f0acb8e
        private const val SEARCH_HOME_CONTENT = 0x7f0a68fa // id/uo_
        private const val FEE_INTERCEPT_ACTIVITY =
            "com.kugou.framework.musicfees.ui.feeintercept.FeeInterceptWebActivity"
        private val VIP_SONG_COUNT = Regex("含\\s*\\d+\\s*首\\s*(?:VIP|会员)歌曲[，,]\\s*开通?会员享(?:全曲|完整版)", RegexOption.IGNORE_CASE)
        private const val HOME_VIP_BADGE = 0x7f0a5b87 // id/s69
        private const val MINE_VIP_BADGE = 0x7f0a5451 // id/qtc
        private const val MINE_VIP_CONTAINER = 0x7f0a5452 // id/qtd
        private const val BOOK_VIP_CONTAINER = 0x7f0ad87a // id/hnv1
        private const val BOOK_VIP_BADGE = 0x7f0a5b86 // id/s68
        private const val MINE_VIP_CARD = 0x7f0adb36 // id/hxb1
        private const val MINE_COIN_CARD = 0x7f0ad867 // id/hnm0
        private const val MINI_PLAYER_VIP_PROMPT = 0x7f0a6d0f // id/vfi

        /** 「我的」页下拉刷新头，内嵌 PAG 动画 / webp 静图作为金币广告皮肤。 */
        private const val PAG_REFRESH_HEADER = "com.kugou.android.app.tabting.x.mine.PAGRefreshHeaderView"

        /** 两个素材地址 getter：前者是 PAG 动画，后者是兜底的 webp 静图。 */
        private val MINE_REFRESH_ARTWORK_GETTERS = listOf(
            "getConfigMineRefreshPagUrl",
            "getConfigMineRefreshImgUrl",
        )

        /** 下拉到底、回弹进入金币中心的回调。 */
        private const val MINE_COIN_ENTER = "com.kugou.android.app.tabting.x.mine.i\$e"
    }
}
