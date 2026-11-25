# LSPosed 真机验收

> 这份文件按**功能批次**记录，每个批次带一个 `installed=N` 数字。数字随批次增长是正常的
> （4 → 6 → 12 → 14 → 22），因为它们不是同一个构建：每段都对应当时新增了规则的版本。
> 对比时先看段落标题，不要跨段比较数字。

## 2026-11-22：酷狗 20.6.4

- 宿主：`com.kugou.android` 20.6.4（versionCode 20649）；Android 16；LSPosed v2.2.1（7912，API 102）。
- 验收对象：本地 Debug APK 和 v0.1.1 本地 R8 构建，只勾选酷狗音乐作用域；未清除宿主数据。本地 R8 构建沿用调试签名，与 GitHub Release 产物的签名不同。

| 项目 | 结果 | 证据 |
|---|---|---|
| LSPosed 加载 | 通过 | 修复前两次冷启动均为 `hooks installed=4 skipped=2`，两条开屏规则报 `Cannot hook abstract methods`。改挂具体分发类后，连续三次均为 `hooks installed=6 skipped=0`。 |
| 开屏运营推广 | 通过实际回调验证 | 同一次冷启动出现 `blocked splash onADFetchWithResult (onNoAD returned)` 与 `blocked splash onADFetch (onNoAD returned)`，随后可正常使用首页。本轮不判断广告网络请求是否发生。 |
| 「我的」页顶部运营 banner | 通过关闭／开启对照 | 关闭模块时能读取「免费领酷狗会员」和「关闭广告」；开启后这两个控件消失，「会员中心」和「最近播放」仍可见。同轮出现 `blocked mine banner data (i -> null)` 与 `dropped mine banner write (i0)`。 |
| 推荐流 | 通过 | `ad.add.filter` 和 `ad.addAll.filter` 都已安装。实际刷新推荐流时记录到 `blocked ad bean:` 命中，广告卡不再出现，列表由正常内容自然接续，无空白卡。初轮未命中是因为当次下发内容里没有广告位，不是规则失效——这一点后来专门复验过。 |

## 会员标与促销提示复验

同一台设备与宿主版本的更新包冷启动记录为 `hooks installed=12 skipped=0`（此时新增了会员标
与促销卡规则，所以从上一段的 6 条涨到 12 条）。

- 推荐页：界面层级中原有的音乐会员标 `s69` 与听书会员标 `s68` 消失；播放按钮及底部导航仍可见。日志出现 `hidden home VIP badge` 与 `hidden audiobook VIP badge`。
- 「我的」页：切页后头像旁音乐会员标 `qtc` 和听书会员标 `s68` 都不在界面层级中，会员中心卡片及最近播放仍在。日志出现 `hidden mine VIP badge`。
- 短时提示：推荐页促销卡和迷你播放栏试听横条规则已安装。本轮没有 `suppressed home VIP promo card preparation` 或 `hidden mini-player VIP prompt`，也没有再次看到「运营商领会员」，暂不能确认该文案具体出自哪个控件。

## 「我的」页卡片与金币中心下拉入口

同一设备更新后冷启动记录为 `hooks installed=14 skipped=0`（新增「我的」页卡片规则）。

- 会员中心卡片 `hxb1` 和金币中心卡片 `hnm0` 均不在界面层级中，对应两条 `hidden mine … center card` 日志已命中。最近播放、歌单和迷你播放栏播放按钮保留。
- 更新前，在「我的」页顶部向下拉会打开金币中心覆盖页（`mp6`）；更新后同一手势命中 `suppressed mine pull-to-open coin center`，回弹后仍留在「我的」页。规则不拦截其他页面入口或改变金币数据。

## 搜索首页、运营商推广与播放提示

同一设备安装最终 Debug 构建后冷启动：`hooks installed=22 skipped=0`（搜索首页、运营商推广、
队列横幅和试听提示四条加入后）。随后安装 `v0.1.1` 本地 Release 构建（R8，沿用本机调试签名），
再次确认 `hooks installed=22 skipped=0`。

- 搜索首页：回归版曾误将历史和快捷入口所在的共享容器 `uo_` 隐藏。修订后只跳过广告素材绑定，不修改容器可见性。真机同一轮出现 `blocked search home banner material`，历史搜索和热搜榜都在，视频广告不在，输入关键词也能返回歌曲结果。
- 运营商推广：冷启动曾出现「运营商积分兑会员」；修订后命中 `hidden carrier exchange operation`。
- 推荐页运营位文案变更：服务端把该槽位的文案从「运营商积分兑会员」换成了「免费领会员」，
  旧判定靠词表（要求出现「运营商」或「移动/联通/电信积分」），新文案两个都不含，于是漏过。
  `UiProbeHooks` 之外的观测手段：在 `y7` 里直接打印槽位文案，一次就抓到
  `home operation slot text=[免费领会员] tag=HomeUserInfoAdBean`，确认它和原来的运营商兑换
  是**同一个槽位**（`id=HOME_OPERATION_AD`，同一个 bean）。
  修法不扩词表，而是认定该槽位本身就是运营推广位：只要 tag 是这个 bean 且文案非空就隐藏。
  三次冷启动各命中一次（`suppressed home operation promo text=[免费领会员]`），规则 `skipped=0`。
- 购买弹窗和试听提示：搜索周杰伦并点《晴天》，旧版打开购买页，修订版命中 `suppressed automatic playback purchase prompt` 和 `hidden mini-player VIP prompt`，仍进入原有试听流程。未验证所有试听文案、时长或播放入口。

## 点击 VIP 歌曲弹出的付费推广层

界面文本是「会员专属歌曲，付费享上亿曲库」，形态是**底部弹层**，内含领取 1 天卡、
豪华 VIP 月/季/年卡和微信/支付宝支付。

**它不是原生页面。** 整包搜不到这句文案的原因：它是 SSR H5 页面，地址写死在
`FeeFrontInterceptDialogUtils` 里的兜底值 `https://gateway.kugou.com/ssr_vippopup/intercept`，
文案由服务端下发。因此先前按 `aapt2 dump strings` 和 `res/a/*.xml` 反查全部落空是正常的，
不是查错了。

**旧规则为什么没拦住。** `FeeFrontInterceptDialogUtils$a#run()` 里首行就分流：

```java
if (!this.d.equals("...FeeInterceptWebActivity")) {
    e.putExtra("clazz", d); e.setAction("com.kugou.android.start_vip"); f.sendBroadcast(e);
} else { ... kh5.s1.X(...) }
```

旧规则只在 `d == FeeInterceptWebActivity` 时短路，**还额外要求请求对象出现在
`r0.v0` 的调用栈里**——那个约束本意是"只拦自动弹窗"。手动点 VIP 歌曲时调用栈里没有
`r0.v0`，条件恒为 false。

诊断依据不是猜的：日志里 `vip.ui.playbackPurchasePrompt.origin` 与
`vip.ui.playbackPurchasePrompt` **成对命中，却从未出现 `suppressed`**。
"钩子命中了但没生效"直接指向条件判断，而不是 hook 点选错。

**现在的做法**：`run()` 无条件返回，两条分支一起拦。实测日志：

```
suppressed playback purchase prompt (via=activity, target=...FeeInterceptWebActivity)
```

界面确认：点 VIP 歌曲不再弹出购买页。这只影响展示，不改会员状态、不影响试听，
用户仍可从会员中心自行开通。

- 播放队列：旧版出现「含14首会员歌曲，看广告可免费听」和「立即观看」；最终版两者同时消失，来源栏和歌曲列表仍在，命中 `hidden queue VIP banner`。只隐藏专用促销控件，不隐藏队列外层。其他共享歌单横幅的开会员文案未单独在真机复现。

## 运营开屏广告（「运营推广」全屏卡片）

同一设备、同一宿主版本，`hooks installed=27 skipped=0`。

这一位和 TME 运营开屏是**两条独立链路**，之前只拦了后者，所以画面上一直还有一张带「运营推广」角标的全屏卡片。

排查方式：打开 `ui.probe=1` 后，探针记录了卡片构建时的调用栈：

```
com.kugou.android.app.boot.SplashCard.A0(...)
  → SlideInteractiveView / SlideGuideAnimatorView
  → AnimatorView (SurfaceView，content-desc=SplashDrawer)
```

界面层级也印证了这点：`SplashCard`（content-desc）下是一个 `SurfaceView` 自绘区，加「运营推广」「跳过」和 `miitRootLayout` 合规区。**广告图由 SurfaceView 直接绘制，不经过任何回调**，所以回调层的拦截全部无效。

失败过的拦截点：

| 拦截点 | 结果 |
|---|---|
| `ce.f#onADFetch` / `onADFetchWithResult` | 命中，画面依旧 |
| `SplashCard$u#onADFetch` / `onADPresent` | 装上但零命中 |
| `xk0.b#a()`（按比例抽样） | 装上但零命中 |
| `SplashCard#A0` 等广告形态方法 | 命中，只去掉了「向上滑动」手势层 |

最终方案：`yk0.g#e(SplashV4)` 恒返回 false。装配入口 `BaseSplashUnitAgent#e0` 里的 `z16` 因此为 false，跳过全部广告图层装配，而 `SplashCard` 仍照常创建并 `setContentView`。

连续三次冷启动：每次 `splash.gate.content` 命中 3 次，1.5 秒内进入首页，无白屏、无卡启动。

## 推荐流内嵌推广卡

推荐流里出现过四种形态完全不同的推广卡：「红果免费短剧」「快手极速版」1 元秒杀、
「免费领酷狗音乐 VIP」酷狗×元宝联名、「42 天畅听卡」邀请好友。它们**共用同一套容器控件**，
UI dump 四次一致：

| 控件 | 资源 | 作用 |
|---|---|---|
| `giw0` | `0x7f0acb5a` | 标题栏 |
| `gj21` | `0x7f0acb67` | 标题文字 |
| `giz0` | `0x7f0acb60` | 关闭按钮 |
| `giw1` | `0x7f0acb5b` | 图片区 |

这组 ID **在整套 `com.kugou.android.app.newtingtab` 源码里搜不到**，也不在任何布局文件中
（按小端 uint32 扫过全部 20,363 个 `res/*.xml`），说明卡片由别的模块通过 binder 注册进来。
祖先链显示卡片是被直接塞进 `android:id/list`（RecyclerView）的，中间没有业务容器。

因此先前那条「按 bean 类名 / `module` 字段过滤」的规则拦不到它们：`ad.add.filter`
在一次启动里命中 1800 次，却一次 `blocked ad bean` 都没有。

**定位方式**：在 `LayoutInflater.inflate` 上挂探针，inflate 结束后检查结果树里有没有
上述标志控件。**两次抓到的是两个不同的 binder**：

```
mc.onCreateViewHolder → tv.e → binder.d.v → binder.d.h0    (layout 0x7f0d1101，会员推广)
mc.onCreateViewHolder → tv.e → binder.c.v → binder.c.h0    (layout 0x7f0d1100，GDT 竞品广告)
```

两者相邻、同构（都继承 `wz.k`），卡片布局 id 也相邻（`2131562753` / `2131562752`），
是同一批生成的兄弟类。**只拦 `d` 的时候 `c` 渲染的卡照样露出来——实测踩过一次。**

`c` 还有三个 inflate 分支（`b0`/`c0`/`a0`，分别对应 TME 多广告、任务中心、竞品卡），
按服务端下发的广告类型三选一，所以不能只拦某一个分支，要在 `h0` 这个总入口拦。

**做法**：`c` 和 `d` 各自的 `h0()`（造卡）与 `g0()`（绑定）都挂上。`h0` 里让宿主正常造出
真正的 holder（绕过去必然崩，它内部要 `findViewById` 绑定一堆控件），拿到后把 `itemView`
压成 0×0 且 `GONE`；`g0` 绑定时再压一次——`onCreateViewHolder` 只在首次创建时走，
`onBindViewHolder` 每次滚进可视区都走，只压一处的话宿主可能重设 LayoutParams 把卡片放回来。

**验证结果**：`hooks installed=28 skipped=0`，冷启动即命中：

```
hook hit[1]: ad.innerCard.create.d
hook hit[1]: ad.innerCard.bind.d
collapsed inner ad card (create/d)
```

界面确认卡片不再出现，推荐流正常滚动、无空白卡、无崩溃。

## 歌曲评论页信息流广告

评论页在正常评论之间插一条带「广告」角标的卡片（实测《鸣潮》下载推广，
`NativeUnifiedADData` / GDT 原生广告），广告位名是 `comment_flow`。

**链路**（`CommentADFragment`，20.6.4）：

```
hR() → postDelayed(Runnable) → HT() → ZT()          ← 私有、无参、void
  ZT(): if (!eU())                        → "initDatas: no show ad"
        else if (!j6.b.j())               → 宿主广告开关
          else if (!rd5.b.b())            → 会员免广告
            rx.Observable.v(new CommentADFragment$e(this))
              → h8.a#k(hash, albumId, mixId).execute()
              → http://ads.service.kugou.com/v3/comment_flow
              → CommentADFragment$c#c(bean)
              → cU(bean) / dU(bean, adData)  → CommentAdapter 固定位置
```

**为什么拦 `ZT()`**：它是唯一发起入口，跳过它宿主走的就是自己那条
"no show ad" 分支——请求根本不发，不产生空白占位，也不需要伪造返回值。
对比其他候选：拦 `h8.a#k` 会走请求失败分支；拦 `cU`/`dU` 时 GDT 已经跑完
`loadData`；`$e` 是内部类，名字随 R8 漂移。

**做法**：`comment.flow.gate` hook 私有 `ZT()`（按"名字 + 无参 + void"定位，
不写死签名）；`comment.flow.drop.cU` 作为第二道保险拦数据写入。

**验证结果**：`hooks installed=30 skipped=0`，进评论页即命中：

```
hook hit[1]: comment.flow.gate
blocked comment flow ad (ZT -> no request)
```

界面确认：评论页连续滚动 3 屏，UI dump 里 `text="广告"` 与「立即下载」均为 false，
评论列表、楼层回复、点赞数全部正常。

> 未做关闭模块的界面对照。判断依据是 hook 命中 + 界面无广告 + 宿主走
> "no show ad" 分支。

## 复验方法

1. 安装调试包，在 LSPosed 只勾选酷狗音乐。关闭作用域可以做界面对照，切换前后都需冷启动，不必清除宿主数据。
2. `adb shell am force-stop com.kugou.android`，再打开酷狗。查看 `adb logcat -d -s KugouHook:V`，按 `KugouHook start (pid=...)` 找到本次主进程的起始位置。检查具体规则的 `hook installed:` 行及 `install summary`，不要把不同启动的日志混在一起。
3. 开屏收到素材后，等待 `blocked splash … (onNoAD returned)`，确认能进入首页；只看到 `hook installed:` 说明本次仅验收了安装。进「我的」前后核对运营位和正常控件，区分顶部运营 banner 与其他会员推广。
4. 推荐流下拉刷新并向下滚动，等待实际业务拦截日志。`hook hit` 只证明列表方法被调用，不能证明广告被过滤；批量过滤日志也不能区分 `InnerAdBean` 与 `CompeteAdBean`。

开屏安装失败只能在 LSPosed 的真实加载流程中复现，本次以同设备、同宿主版本的冷启动作为安装回归验证，未添加会跳过框架的 JVM 模拟测试。
