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
    └── SplashAdHooks.kt    开屏运营广告：素材成功回调改走 onNoAD
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

## hooks/：三条规则，一个判据

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
| 目标数量 | 69 条 hook | 6 条 | 酷狗已确认的 L1 汇聚点就这些，先做对再做多 |
| DexKit | 依赖 | 不依赖 | 类名来自实机 dump，不需要运行时扫 dex |
| 覆盖策略 | 被动广告位统一返回"无广告" | 逐条找 L1 汇聚点 | 酷狗各广告位数据源确实不同，没有统一闸门 |
| 版本门禁 | 建议性 | 建议性 | 相同，理由见上 |
