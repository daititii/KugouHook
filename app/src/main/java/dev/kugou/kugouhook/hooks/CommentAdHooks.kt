package dev.kugou.kugouhook.hooks

import dev.kugou.kugouhook.ClassResolver
import dev.kugou.kugouhook.HookManager
import dev.kugou.kugouhook.ModuleLog
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 歌曲评论页信息流里的广告卡（广告位 `comment_flow`）。
 *
 * ## 现象
 *
 * 打开某首歌的评论页，在正常评论之间插着一条带「广告」角标的卡片
 * （实测是《鸣潮》的下载推广，标 `NativeUnifiedADData` / GDT 原生广告）。
 *
 * ## 数据从哪来
 *
 * `CommentADFragment`（抽象基类）在 `hR()` 里投递一个一次性任务，最终调用
 * **私有方法 `ZT()`**，这是评论广告的**唯一**发起入口：
 *
 * ```java
 * // CommentADFragment#ZT()  —— 20.6.4
 * private void ZT() {
 *     if (!this.eU()) {                       // ① 这个页面要不要广告（红歌/生成码判定）
 *         log("initDatas: no show ad:");
 *     } else if (!j6.b.j()) {                 // ② 宿主自己的广告开关
 *         if (!rd5.b.b()) {                   // ③ 会员免广告
 *             ...
 *             rx.Observable.v(new CommentADFragment$e(this))   // ④ 真正发请求
 *                 .j(...).i1(Schedulers.io())
 *                 .B0(AndroidSchedulers.mainThread())
 *                 .g1(new CommentADFragment$c(this), new CommentADFragment$d(this));
 *         }
 *     }
 * }
 * ```
 *
 * 请求本身在 `CommentADFragment$e#a()`：
 * `new h8.a().k(hash, albumId, mixId).execute()` → `http://ads.service.kugou.com/v3/comment_flow`。
 * 回调 `CommentADFragment$c#c()` 拿到数据后调 `cU(bean)` / `dU(bean, adData)`，
 * 由 `cU` / `dU` 把 `CommentEntity` 塞进 adapter 的固定位置。
 *
 * ## 为什么拦 `ZT()` 而不是别的
 *
 * | 候选 | 结果 |
 * |---|---|
 * | `h8.a#k(...)` 返回 null | 会产生 "request failed"，宿主走失败分支，可能留空白 |
 * | `CommentADFragment$e#a()` 提前 return | 能拦住，但只覆盖网络成功那条路；且 `$e` 是内部类，名字随 R8 变 |
 * | `CommentADFragment#cU/dU` 不写入 | 见效，但要同时拦两个方法，且 GDT 那条会先跑完 `loadData` 再被丢掉 |
 * | **`ZT()` 直接 return** | **请求根本不发**，没有空白占位，宿主日志里显示 "no show ad" 的正常分支 |
 *
 * `ZT()` 是私有方法，但它是**普通 void 方法**，没有返回值要伪造，跳过它就等于
 * 走 `eU()` 返回 false 的那条既有分支 —— 这是宿主自己写好的"不展示广告"路径。
 *
 * ## 不影响什么
 *
 * - 评论列表、评论加载、回复展开：都由别的请求负责，不经过 `ZT()`。
 * - 会员状态、试听、播放：完全不涉及。
 * - 其他广告位（推荐流、开屏等）：各自有独立入口，不受这里影响。
 */
class CommentAdHooks(
    private val resolver: ClassResolver,
    private val hooks: HookManager,
    private val log: ModuleLog
) {

    companion object {
        /**
         * 歌曲评论页基类。`CommentsListFragment` 是它唯一的子类。
         *
         * 选基类而不是子类，是因为广告发起逻辑（`ZT()`）整个写在基类里，
         * 子类只提供 `eU()` 这个"要不要广告"的判定。宿主以后要是再加一个
         * 子类，这里不用跟着改。
         */
        const val HOST = "com.kugou.android.app.player.comment.CommentADFragment"

        /** 广告发起的私有入口。无参数、无返回值。 */
        const val ENTRY = "ZT"

        /**
         * 数据落盘方法，作为第二道保险。
         *
         * `ZT()` 拦的是"发不发请求"。万一宿主以后改了调用顺序、或者这个广告位
         * 从别的路径又拉了一次数据，`cU` / `dU` 这两处写入还会兜住。
         * 两者都是 `void`、单参数，跳过就是"不写入这条广告"。
         */
        val WRITERS = listOf(
            "cU" to "com.kugou.android.ads.model.bean.a\$b",
            "dU" to "com.kugou.android.ads.model.bean.a\$e",
        )
    }

    fun install() {
        installRequestGate()
        installWriteGate()
    }

    /**
     * 掐掉广告请求的发起。
     *
     * `ZT()` 是私有方法，用 `declaredMethods` 按名字 + 无参数 + void 三个条件定位，
     * 不写死签名类型（宿主混淆后参数类型也可能变，但"无参数 void"这个形状很稳）。
     */
    private fun installRequestGate() {
        val id = "comment.flow.gate"
        val host = resolver.findClass(HOST)
        if (host == null) {
            hooks.reportLoss(id, "CommentADFragment not found")
            return
        }

        val entry = host.declaredMethods.firstOrNull {
            it.name == ENTRY && it.parameterCount == 0 && it.returnType == java.lang.Void.TYPE
        }
        if (entry == null) {
            hooks.reportLoss(id, "$ENTRY() not found on ${host.simpleName}")
            return
        }
        entry.isAccessible = true

        val seen = AtomicBoolean()
        hooks.install(id, entry, requireVoid = true) {
            if (seen.compareAndSet(false, true)) {
                log.info("blocked comment flow ad (ZT -> no request)")
            }
            null
        }
    }

    /**
     * 第二道保险：拦数据写入。
     *
     * 这一步在 `ZT()` 已经生效时不会命中，所以日志只在真正用到时才出现
     * —— 它命中就说明 `ZT()` 那条路被宿主绕开了，值得看一眼。
     */
    private fun installWriteGate() {
        val host = resolver.findClass(HOST) ?: return
        for ((methodName, paramType) in WRITERS) {
            val id = "comment.flow.drop.$methodName"
            val method = findWriter(host, methodName, paramType)
            if (method == null) {
                // 单参数签名对不上时不报 loss：`ZT()` 已经覆盖了主路径，
                // 这里只是冗余保险，形状变了属于预期内的漂移。
                continue
            }
            hooks.install(id, method, requireVoid = true) {
                log.info("dropped comment flow ad write ($methodName)")
                null
            }
        }
    }

    /**
     * 定位写入方法。
     *
     * 优先按"名字 + 单参数 + void"匹配；参数类型对不上时退回只看名字和形状，
     * 因为 `a$b` / `a$e` 这类混淆名在宿主升级后可能变，但"接收一个广告 bean
     * 然后什么都不返回"这个形状很稳定。
     */
    private fun findWriter(host: Class<*>, name: String, paramType: String): Method? {
        val candidates = host.declaredMethods.filter {
            it.name == name && it.parameterCount == 1 && it.returnType == java.lang.Void.TYPE
        }
        if (candidates.isEmpty()) return null

        val expected = runCatching { Class.forName(paramType, false, host.classLoader) }.getOrNull()
        val exact = candidates.firstOrNull { expected != null && it.parameterTypes[0] == expected }
        return (exact ?: candidates.first()).also { it.isAccessible = true }
    }
}