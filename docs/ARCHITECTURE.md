# 模块架构

这份文档讲**代码怎么组织、为什么这么组织**。逆向得到的宿主事实（类名、调用链、实测数据）
在 [REVERSE-NOTES.md](REVERSE-NOTES.md)，那份是原始实验记录，不随代码改动。

## 目录

```
app/src/main/java/dev/kugou/kugouhook/
├── KugouModule.kt          入口：进程门禁、日志/审计开关、各组 hook 的装配
├── HookManager.kt          统一安装入口：逐条隔离、记账、命中审计
├── ClassResolver.kt        目标解析：纯反射 + 缓存，不依赖 DexKit
├── ModuleLog.kt            三通道日志：框架日志 / logcat / 宿主机 cache 文件
├── ApkVersion.kt           从 APK 二进制 manifest 读 versionCode
└── hooks/
    ├── AdHooks.kt          推荐流广告：CopyOnWriteArrayList.add(E) 过滤
    ├── MineBannerHooks.kt  「我的」页动态 banner：delegate 返回 null
    ├── SplashAdHooks.kt    开屏运营广告：素材成功回调改走 onNoAD
    └── VipPromptHooks.kt   会员促销界面：会员标、运营卡片、队列推广、
                            购买弹窗、迷你栏试听提示（版本限定）
```

分层很简单：`KugouModule` 负责**什么时候**装，`hooks/*` 负责**装什么**，
`HookManager` + `ClassResolver` + `ModuleLog` 负责**装得上、装不上都说得清楚**。

## 入口：KugouModule

`onPackageReady` 里按顺序做四件事，任何一步失败都不阻止下一步：

1. **进程门禁**。酷狗有多个进程（`com.kugou.android`、`.support`、`.message`），
   只有主进程名等于包名时才继续。往子进程里注 hook 除了白耗内存没有任何收益。
2. **开日志**。先于版本检查打开 `cache/kugouhook.log` —— 否则版本判断本身出问题时，
   连"为什么没生效"都查不到。
3. **读版本号**。读出来**只作参考、不作硬门禁**，见下节。
4. **装 hook**。`AdHooks` / `MineBannerHooks` / `SplashAdHooks` 各自一个 `runCatching`，
   互不影响；最后打一行 `install summary`。

### 版本门禁是"建议性"的，不是"必须匹配"

这点和很多模块的直觉相反，是有意的：

- 酷狗的类名逐版本漂移，**但漂移是逐条的**。推荐流适配器改了，不代表开屏监听器也改了。
- 如果做成 fail-closed（版本号不认识就一条都不装），宿主一次小更新就会让**全部功能归零**，
  用户看到的是"模块突然完全没用"，而实际上大部分规则还是好的。
- 所以这里是 fail-open + 逐条降级：装上一条算一条，装不上的进 `lost` 列表写日志。
  版本号不匹配时只打一行 WARN，提示"这个版本没审过，结果可能不全"。

代价是：新版本上可能出现"部分生效"的模糊状态。这个代价用 `install summary` 一行来兜 ——
它精确告诉你丢了哪条。

### 审计开关

`cache/kugouhook.audit` 文件存在时，每条 hook 真实命中会写 `hook hit[n]: <id>`。
这不是调试残留，是排查"到底是我没拦住，还是这条链路根本没走这个入口"的唯一手段：
有 hit 说明宿主拿到 false 却仍然展示了广告（要换 hook 点），没有 hit 说明入口找错了
（要重新找）。两种情况的修法完全相反，靠猜是猜不出来的。

## HookManager：把"失败"变成一等公民

`HookManager` 存在的全部理由是**不让失败静默**。

| 机制 | 解决的问题 |
|---|---|
| 逐条 `try/catch` | 一条 hook 抛异常不会中断后面的安装 |
| `skipped` / `knownMissing` 分列 | 可预期的缺失（某类只存在于特定页面）不混进 `lost`，否则每次都是噪音，真出问题反而看不见 |
| `summary()` 一行摘要 | 宿主升级后不用重新静态审计整个目标集，看这一行就知道退化在哪 |
| `auditHit()` | 证明 hook 真的在链路上被调用了 |
| `requireVoid` 守卫 | no-op 型 hook 靠"不 proceed"实现拦截；若目标非 void，框架会把 null 当返回值塞回宿主，表现为 NPE / ClassCastException |
| `deoptimize` 开关 | ART 会把小方法内联进调用方，内联副本不经过 hook —— 需要时强制反内联 |
| `ExceptionMode.PROTECTIVE` | hook 体内抛异常时不让宿主进程崩 |

安装失败也会进 `skipped`：目标解析成功但 `module.hook()` 抛了，这是**真丢失**，
不能因为"找到方法了"就报 `skipped=0`。

## ClassResolver：纯反射，不引 DexKit

`findClass` / `findMethod` / `findField` / `findNoArgBooleanMethods`，结果全部缓存。

不引 DexKit 有两个原因：

1. **不需要**。目标类名是动态分析实机 dump 出来的确定值，不需要运行时扫 dex 找特征。
2. **少一层风险**。DexKit 要 native 库、要跨 ABI、要在目标进程里跑 dex 解析，
   而宿主带着 `libFireEye-rqd.so` 这种反调试库 —— 多引一个 native 依赖就多一处可能被判定异常。

缓存是必须的：反射查类在 23 万类的 APK 上不便宜，但每个目标只查一次。

## hooks/：四条主线，一个判据

前三组（`AdHooks` / `MineBannerHooks` / `SplashAdHooks`）处理广告素材本身，
第四组 `VipPromptHooks` 处理"不让你听/不让你用"的会员促销界面。两者的判据相同：
**只切断界面呈现，不动任何业务状态。**

### AdHooks

只 hook `CopyOnWriteArrayList.add(E)`（**单参数**重载）和 `addAll(Collection)`。

为什么只做数据层，见 [REVERSE-NOTES.md 第一节](REVERSE-NOTES.md)。要点：
`getItemCount()` 直接读这个列表，所以在 `add()` 丢弃广告 bean 后，列表长度、
`getDatas()`、绑定下标三者天然自洽，不需要同步任何计数。

**必须只 hook 单参数重载** —— 这是本项目最致命的一个 bug，详见
[REVERSE-NOTES.md 第二节](REVERSE-NOTES.md)。简单说：`add(int, E)` 用单参实现去 hook，
会丢掉 element 并插入 null；而 EventBus 2.x 的订阅表恰好也是 `CopyOnWriteArrayList`，
于是 `Subscription.subscriberMethod` 立刻 NPE，表现为"启动崩、切页崩、滚动崩"三个假暗桩。

广告判定是**双判据**，任一命中即算：

- 类简单名含 `AdBean`（`InnerAdBean`、`CompeteAdBean`）
- 字段 `module` 的字符串含 `_ad`（`module_inner_ad`、`module_ad_banner`）

两条并存是因为它们失效的方式不同：类名随混淆变化，`module` 是服务端下发、跨版本稳定，
但服务端也可能改命名。单用哪一条都会在某次更新后失效。

再加一条保守约束：bean 的包名必须属于 `com.kugou.android.app.newtingtab.bean.`。
这条是为了不误伤宿主其它 `CopyOnWriteArrayList`（首当其冲就是 EventBus 的订阅表）。

`addAll` 单独处理，因为它不经过 `add`：逐条加、跳过广告、返回 true。这里有意写了
`runCatching` 保护 —— 重入自己的 `add` 时如果类型不匹配，宁可退回原逻辑也不能崩。

### MineBannerHooks

`MineAdBannerMainDelegate` 的两个方法：取广告对象的返回 null，接收广告对象的 no-op。
不 proceed 是关键：一旦让原逻辑跑下去，后面就会真的去渲染。

这个类是通过在 `CopyOnWriteArrayList.add` 上观察 `MineAdBannerMainDelegate$c` 反查出来的，
不是靠猜类名 —— 早期把 `MineIndexHeadTopView` 当成目标，那是"最近播放/最近玩过"的 tab，
和广告无关。

### SplashAdHooks

`TMEOperSplashAdListener` 声明的是抽象方法，LSPosed 不能直接 hook。酷狗 20.6.4 的具体分发类是 `ce.f`，它把两个素材成功回调及 `onNoAD` 分发给同一批监听器。

模块按精确参数类型拦截具体类的 `onADFetch` 和 `onADFetchWithResult`。安装前确认 `onNoAD(AdError)` 与 `AdError(int, String)` 都存在。运行时回调 `onNoAD(AdError(204, "no ad"))`，只有回调正常返回才跳过原成功回调；替代回调失败则放行原逻辑，避免静默卡在启动等待。

拦截发生在素材成功回调阶段，**不保证广告网络请求未发出**。

### VipPromptHooks

这一组和上面三组的性质不同：**它不拦广告，它拦"卖东西"的界面。**

覆盖推荐页顶部运营推广位、会员中心/金币中心卡片、昵称旁会员标、播放队列推广横幅、
自动购买弹窗、迷你播放栏试听提示，以及搜索首页的视频广告位。

推荐页那个槽位（`id=HOME_OPERATION_AD`）值得单独说一句：**它不按文案判断。** 宿主一开始
下发的是「运营商积分兑会员」，后来服务端换成「免费领会员」，词表匹配就漏了。这个槽位本身
就是运营推广位，宿主往里下发什么都是推广，所以只要 tag 是 `HomeUserInfoAdBean` 且文案非空
就隐藏——文案再改也不用跟着改代码。

三条约束决定了它的写法：

1. **只对 20.6.4 生效。** 这些规则依赖具体控件 ID（如队列横幅的 `0x7f0aafe3`），
   换了版本 ID 可能指向别的控件。与其冒险误伤，不如在新版本上明确跳过——
   `KugouModule` 里只有版本号等于 `AUDITED_VERSION_CODE` 时才装这一组。
2. **拦的是"呈现"，不是"能力"。** 自动购买弹窗只在它即将弹出时短路，
   试听计时、播放状态、会员权限一律不碰。用户仍然只能按原有权限听歌。
3. **共享容器不能整体隐藏。** 搜索历史那次事故就是教训：`uo_` 同时承载历史、
   快捷入口和广告位，隐藏容器等于删掉用户的历史。现在只跳过广告素材的绑定，
   容器可见性不动。

队列横幅同理：`dyt0` 是队列内容容器，隐藏它会连歌曲列表一起消失；
真正要隐藏的是专用的 `e0`，通过 `QueueListSlidingLayout#I0(boolean)` 定位。

## 广告位总表：19 个位置名

这 19 个字符串来自 `q8.d`（`com.kugou.android.ads.ownads`）的构造函数，是宿主自己
登记的广告位清单。它是目前最可靠的"还有哪些位置"参照，比翻代码猜要准确。

定位方式：`rust_ASC getclass app.apk q8.d`，看私有构造里的 `this.g("...")` 序列。

### 覆盖状态

| 位置名 | 中文含义 | 模块状态 |
|---|---|---|
| `home_card` | 首页推荐流卡片 | ✅ AdHooks 数据层过滤 |
| `mine_page_top_banner` | 「我的」页顶部 banner | ✅ MineBannerHooks |
| `home_userinfo_bar` | 推荐页运营推广位 | ✅ VipPromptHooks（按槽位判断） |
| `search_banner` | 搜索首页 banner | ✅ VipPromptHooks |
| `mobile_live` | 播放页 live 卡片 | ✅ 间接覆盖 |
| `home_mstc_icon` | 首页动态图标 | ⚠️ 未验证 |
| `home_op_list` | 首页歌单位 | ⚠️ 未验证 |
| `mine_page_under_tab_banner` | 「我的」页 tab 下 banner | ⚠️ 未验证 |
| `task_center_entrance` | 任务中心入口 | ⚠️ 未验证 |
| `multi_page_pendant` | 多页挂件 | ❌ 未处理 |
| `multi_page_pendant_playbar_pet` | 播放栏宠物挂件 | ❌ 未处理 |
| `play_banner` | 播放页横幅 | ❌ 未处理 |
| `playpage_link` | 播放页链接卡 | ❌ 未处理 |
| `play_card` | 播放页卡片 | ❌ 未处理 |
| `play_top_bubble` | 播放页顶部气泡 | ❌ 未处理 |
| `comment_flow` | 评论流广告 | ✅ CommentAdHooks |
| `singer_page_banner` | 歌手页 banner | ❌ 未处理 |
| `mobile_window` | 全屏弹窗广告 | ❌ 未处理 |
| `mobile_bottom_tab` | 底部 tab 广告 | ❌ 未处理 |

### 请求侧：不要在这些地方拦

排查时评估过两条"一处拦全部"的思路，**都不成立**，记在这里避免以后重走：

1. **`h8.a`（广告请求构造器）** —— 30 个方法各返回一个 `retrofit2.Call`。在这里返回
   失败或 null，宿主拿到的是"请求失败"而不是"没有广告"，界面会留空白占位甚至崩，
   与 `AdHooks` 头注释里那次"布局中途改列表"属于同类错误。
2. **`q8.d.i(posName)`（`own_ads` 字段）** —— 一开始误以为它声明"我要哪些广告位"，
   读 `q8.e#b`/`c` 才发现它是**上报曝光/点击/关闭次数**的统计结构
   （`count` / `total_count` / `click` / `close`）。清空它只会让服务端认为这些位从未
   曝光过，可能触发更频繁的推送。**效果与预期相反。**
3. **广告 SDK 初始化层**（`TMEAds#init` / `GDTADManager#initWith`）—— 见下节。

结论：20.6.4 没有统一的广告拦截点。每个位的 bean 类型、绑定时机、生命周期都不同，
只能逐条处理。新增一位时，先用 `UiProbeHooks` 观测它由谁触发，再决定拦在数据层还是呈现层。

### 为什么不能拦广告 SDK 初始化

调研结论：**三套 SDK 互相独立，而且各自带"按广告对象自愈式重初始化"，初始化层不是一个可拦的汇聚点。**

| 平台 | 初始化入口 | 宿主调用点 |
|---|---|---|
| TME / Uni（Mars 2.37.1） | `com.tencentmusic.ad.TMEAds#init(Context, String, InitParams)` | `i9.c#c()`（唯一主路径） |
| GDT / 优量汇 | `com.qq.e.comm.managers.GDTADManager#initWith(Context, String)` | `xk0.j#a()`，**另有 5 处** |
| AMS | `com.tencentmusic.ad.a1.a#c(String)` | 自定义开屏 / playable |

各平台跳过初始化后的实际行为（都反编译核对过）：

- **TME**：不崩。`i1.m#call()` 有明确的 `PosConfig is null` 分支，回调
  `onAdError(10002)`，业务侧走正常失败路径（`BannerCompeteAdProtocol$i` → `onNext(0)`）。
  代价是**每条失败路径都会 `c1.f.a(...)` 重拉一次 posConfig**，广告位越多网络放大越明显；
  另外 `i9.c#l` 会因为 `TMEAds.getAdPurchaseInfo` 拿不到数据而每 1 秒重试一次，空转堆积。
- **GDT**：**会崩或卡死**。`TangramAdManager#buildAdLoader` 在未初始化时抛
  `IllegalArgumentException`，而唯一调用方 `GdtTangramAdLoader#c` 只 catch 了 JSON 解析，
  异常会冒到 `@WebCallCmd 1414` 的 JS bridge；`TGSplashAD` 在 `this.a == null` 时
  `fetchAdOnly` / `fetchAndShowIn` 都是裸 `return`，**不发任何回调**，开屏卡到超时。

还有三个结构性原因让这个方向本身就不成立：

1. **GDT 会自愈重初始化。** `com.qq.e.tg.AbstractAD$1#run` 在每个广告对象构造时兜底再调
   一次 `initWith`，`AMSSplashAdapter#createTgSplash` 也会自己 `initWith`。拦一处没有意义。
2. **初始化被非广告功能复用。** `GdtPreloadAdManager`（免费听激励领时长）会判
   `isInitialized()`，初始化失败直接 `onError(3)`，把**领时长流程堵死**——这是功能性误伤，
   不是单纯去广告。`com.tencent.ams.fusion.widget.apng.APNGDrawable` 还被
   `com.kugou.common.utils.k5#l` 当通用 APNG 播放器用（是否有纯 UI 用途未验证）。
3. **TME 原生/激励确实有汇聚点，但位置更低。** `TMENativeAD#loadAd` → `i1.m` →
   拦截器链 → `i1.e/i.r/i.p`。这属于 SDK 内部请求分发，不是初始化层，而且
   **覆盖不到开屏、GDT Tangram、酷狗自有 `home_card`**。

**判断标准：初始化层是"让所有广告一起死"，代价是非广告功能跟着死；
广告位数据层是"让某一条广告不成立"，代价是逐条维护。后者才符合本模块"只改界面"的定位。**

### 备选：SDK 请求层兜底（未实现，留档）

初始化层不行，**但请求层可以**——这是两回事，别混。查 `TMENativeAD#loadAd` 的引用方
（`rust_ASC findrefs method loadAd --class …TMENativeAD`）得到下面这张表，
20.6.4 上它是 TME 原生广告的**唯一发请求方法**：

| 宿主调用方 | 槽位 ID | 用户看到的位 |
|---|---|---|
| `HomeTabContractAdProtocol` | `10030102` `10030202` `10030502` | 首页卡片（`home_card`） |
| `BannerCompeteAdProtocol` | 由参数传入 | 竞品 banner |
| `BannerAdPresenter` | `10080102` | 搜索 banner（`search_banner`） |
| `AdDataControllerImpl` | `1416334873` `10020302` | 我的页 banner |
| `FreeModeTipsHelper` | `10065301` | 免流提示 |
| `PlayerEasterEggUtil` | `10011101` | 播放页彩蛋 |
| `g10.w` / `v20.g` / `g7.b$a` / `FXCommonFreeModeDialog` | `10065501` `10063301` … | 其他 |

**13 个宿主类 → 1 个方法。** 做法是拦截 `loadAd(int, LoadAdParams)`，反射取
`listener` 字段后在主线程回调 `onAdError(AdError(-101, "…"))`，业务侧本来就有这个分支。

GDT 侧对应的是 `NativeUnifiedAD#loadData(int, LoadAdParams)`，但**回调是构造时以参数
传入的私有字段**，`loadData` 自己不带 listener，反射取字段的稳定性明显低于 TME 侧
（`NativeUnifiedAD` 是混淆过的 SDK 内部类）。评估结论：可行但脆弱。

#### 为什么没有直接做

1. **两个维度不能互相替代。** `q8.d` 登记的是 19 个**产品维度**的位（用户看得见的位置），
   SDK 层是**技术维度**（数据从哪来）。同一个位可能同时走 TME 和 GDT——搜索 banner
   在两边都有引用。按槽位 ID 拦，日志里只有 `10080102`，看不出对应哪个广告位，
   "19 个里覆盖了几个"要逐个反推。
2. **会误伤激励功能。** `g7.b$a` 和 `FXCommonFreeModeDialog` 用 TME 原生广告做的是
   **免费听领时长**，不是广告位。统一拦 `loadAd` 会把这个功能一起打掉。
3. **会降低已完成的 6 位的精确性。** 现在这些位是按数据存在性判断的，精确、有独立日志、
   可单独验证。换成 SDK 层统一拦，反而变粗。

#### 如果以后要做，建议形态

加一条 `ad.sdk.tme` 规则，**默认关闭**，由 `kugouhook.flags` 打开：

- 日常仍按 19 个位逐个精确处理，保持可验证性
- 宿主大版本突然冒出一批 TME 广告时，临时打开这个开关一次性止血，不用等逐位适配
- 不改默认行为，不影响已验证的部分

GDT 侧先不做。

### 为什么 `comment_flow` 能整条掐掉

上面说"不能拦请求"，`comment_flow` 是个例外，区别在于**拦的位置不同**：

- ❌ `h8.a#k(...)` 返回 null —— 这里已经是"发出请求"本身，拦了宿主拿到失败。
- ✅ `CommentADFragment#ZT()` 直接 return —— 这里是"**要不要发请求**"的判断函数，
  跳过它宿主走的是自己那条 `if (!eU()) log("no show ad")` 的既有分支，
  请求根本不发，没有任何失败态需要处理。

判断标准：**方法里如果已经有"不展示"的合法分支，就在分流之前拦；否则不要拦请求层。**

## ApkVersion：为什么手写 AXML 解析

`onPackageReady` 阶段 Application 对象还不存在，拿不到 Context，而且
`ApplicationInfo` 上**没有** `versionCode` / `longVersionCode` 字段（那是 `PackageInfo` 的）。
实测两条反射路线都不通：

- `PackageManager.getPackageArchiveInfo` → 需要 Context，拿不到
- `ActivityThread.currentApplication()` → `IllegalStateException: currentApplication returned null`

两者都会把版本号塌成 -1，让门禁静默失效。所以直接解析 APK 的二进制 manifest：
只用公开稳定的 ZIP + AXML chunk 布局，不碰 hidden API，也不需要 Context。
移植自 FanqieHook 的 `ApkVersion`。

因为版本门禁本身只是建议性的，这里解析失败（返回 -1）也不会影响功能，只是日志里版本号显示 -1。

## 日志：ModuleLog

三个通道同时写，因为三个读者在不同位置：

| 通道 | 读者 |
|---|---|
| 框架日志（`log()`） | LSPosed 管理器里直接看 |
| logcat | 连着 adb 调试时 |
| `cache/kugouhook.log` | 手机上没有 logcat 时，`adb shell su -c cat` 一条命令拿到 |

日志写在宿主自己的 `cache/` 目录下，不需要额外存储权限。

## 与 FanqieHook 的差异

抄了架构，没抄全部内容。有意不同的地方：

| 项 | FanqieHook | 本项目 | 原因 |
|---|---|---|---|
| 目标数量 | 69 条 hook | 22 条规则 | 酷狗已确认的 L1 汇聚点就这些，先做对再做多 |
| DexKit | 依赖 | 不依赖 | 类名来自实机 dump，不需要运行时扫 dex |
| 覆盖策略 | 被动广告位统一返回"无广告" | 逐条找 L1 汇聚点 | 酷狗各广告位数据源确实不同，没有统一闸门 |
| 版本门禁 | 建议性 | 建议性 | 相同，理由见上 |
