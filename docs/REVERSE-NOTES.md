# 酷狗音乐 (com.kugou.android v20.6.4) 去广告 Hook — 逆向实验记录

设备：小米 23117RK66C / Android 16 / arm64 / serial `1b862d7c`

> **这份文档是什么。** 它是逆向阶段的**原始记录**，按时间顺序写，保留了当时的错误判断和
> 推翻过程，不做润色 —— 因为"我一开始猜的是什么、后来怎么发现猜错了"这部分信息，
> 在读代码时是拿不到的。
>
> - 想直接用：看 [../README.md](../README.md)
> - 想读代码：看 [ARCHITECTURE.md](ARCHITECTURE.md)
> - 想复现实验：Frida 脚本**已移出本仓库**，放在工作区同级目录 `G:\hook_test\frida`。
>   下文所有 `frida/...` 路径都指那里，不进 git。
>
> 文中提到的部分探针脚本（如 `where.js`）属于一次性排查工具，已随整理移除；
> 结论都留在了正文里。

---

## 一、最终方案（已验证可用）

```powershell
# 一键启动：spawn + 延迟注入，前台常驻
python -u frida\kugou_adblock.py --delay 2500
```

**效果**：首页推荐流广告（`会员惊喜价`、短剧推广、游戏推广）全部消失，被正常内容自然接续，无空白卡、无卡顿、无崩溃。

### 核心原理：L1 数据层拦截

```
RecyclerView : com.kugou.android.app.newtingtab.widget.TingRecRecyclerView
Adapter      : com.kugou.android.app.newtingtab.mc
数据字段     : java.util.List r  (实际类型 java.util.concurrent.CopyOnWriteArrayList)
```

`getItemCount()` **直接读字段 `r`**，所以只要在 `r.add()` 那一刻丢弃广告 bean，
`size` / `getDatas()` / 绑定位置全部自洽，**不需要同步任何计数**。

### 广告判定（抗混淆）

| 广告类型 | Bean 类 | `module` 字段 |
|---|---|---|
| 推荐流内嵌卡 | `InnerAdBean` | `module_inner_ad` |
| 竞品/tab 广告 banner | `CompeteAdBean` | `module_ad_banner` |

```js
// 类名可能被混淆，但 module 是服务端下发的字符串，跨版本稳定
isAd = cls.contains('AdBean') || module.contains('_ad')
```

> 不要写死混淆后的类名 —— 这正是 [X-AdFree](https://github.com/Xposed-Modules-Repo/io.github.yylsping.xadfree)
> 强调的「不写死混淆类名」，写死的基本一周就废。

### 三个注入点（按优先级）

| 层级 | 位置 | 开销 | 状态 |
|---|---|---|---|
| **L1** ✅ | `CopyOnWriteArrayList.add(E)` | ≈0 | 广告 bean 直接不入列表，ViewHolder 从不创建，**最安全，已在用** |
| L1 兜底 ✅ | `notify*` 时清扫 | ≈0 | app 自己就要全量重排，此刻删安全 |
| L2 ⚠️ | `TMENativeAdListener.onAdLoaded` + 主动 `onAdError` | 0 | 机制已验证，但**当前版本 feed 路径不触发**，详见 §7.4 |
| L2 ⚠️ | `binder.c.e0()` | 0 | 能拦竞品广告卡，但会让下游拿到空值，**已弃用** |

---

## 二、最关键的一个坑：重载参数转发

**这一个 bug 导致了本项目全部崩溃。** 必须先看这个。

`CopyOnWriteArrayList` 有两个 `add` 重载：

```
add(E)              ← 1 个参数
add(int index, E)   ← 2 个参数
```

错误写法（我当时就是这么写的）：

```js
COW.add.overloads.forEach(function (o) {
  o.implementation = function (a) {        // ← 只取了一个参数
    ...
    return o.call(this, a);                // ← 2 参数重载时 element 被丢掉
  };
});
```

对 `add(int, E)` 来说，`a` 只是 index，`element` 被丢弃 → **往列表里插入 `null`**。

而 **EventBus 2.x 的订阅表正好是 `CopyOnWriteArrayList<Subscription>`**，于是立刻出现：

```
java.lang.NullPointerException: Attempt to read from field
  'de.greenrobot.event.SubscriberMethod Subscription.subscriberMethod'
  on a null object reference
    at de.greenrobot.event.EventBus.subscribe(SourceFile:87)
    at com.kugou.android.appwidget.x0.a
    at com.kugou.android.app.MainController.a1
```

同一个根因在三个地方爆出来，看起来像三个独立的「暗桩」，其实是同一个 bug：

- `EventBus.subscribe` → 启动期崩溃（`BootThread-4`）
- `EventBus.postToSubscription` → 切 Tab 崩溃（`KT-87-196`）
- `EventBus.unsubscribeByEventType` → 滚动崩溃（`ProgramViewHolder.onViewDetachedFromWindow`）

### 正确写法

```js
// 1) 只 hook 单参数重载
var ovs = COW.add.overloads;
for (var i = 0; i < ovs.length; i++) {
  if (ovs[i].argumentTypes.length !== 1) continue;   // add(int,E) 一律不碰
  ...
}

// 2) 原方法调用必须完整转发实参
function callOrig(ov, impl, self, args) {
  if (typeof ov.apply === 'function') return ov.apply(self, args);
  if (impl !== null && typeof impl.apply === 'function') return impl.apply(self, args);
  var a = [self];
  for (var i = 0; i < args.length; i++) a.push(args[i]);
  return ov.call.apply(ov, a);
}
```

### A/B 对照（同一台设备、同一版本、同为 spawn 立即注入）

| 版本 | 结果 |
|---|---|
| v10（有重载 bug） | 启动期崩：`EventBus.subscribe` NPE，进程死 |
| **v11（已修复）** | **不崩**，`add2Skipped: 1`（正确跳过 `add(int,E)`） |

`add2Skipped` 这个计数就是为了盯住这件事不再复发。

---

## 三、崩溃史与归因（诚实记录）

| 时间 | 现象 | 真实原因 |
|---|---|---|
| 15:41 | `SIGSEGV code 0 (SI_USER)`，Thread-17 | `libFireEye-rqd.so` 主动自杀（hook `Activity.onCreate`） |
| 16:29 | `SIGSEGV code 2`，主线程，栈溢出 | 在 `getItemCount()` 里做重活 + 原地删列表 → liblog 递归耗尽栈 |
| 16:39 | `EventBus.unregister` NPE | **我的对照测试**从活列表里删了 `ProgramBean`，ViewHolder 状态不一致 |
| 16:45 / 16:56 | `EventBus` 三处 NPE | **重载转发 bug**（见第二节） |

### 反面对照实验（重要）

杀光 Frida、`am force-stop` 冷启动，**只用 adb 滑动 60 次**：

```
round=1 pid=53307   round=2 pid=53307   round=3 pid=53307   ← 全程不崩
```

所以「应用自己就会崩」不成立，**崩溃是注入方式造成的**，可以修。

---

## 四、绝对不要做的四件事

1. **不要轮询遍历 View 树**
   实测 949 个节点 / 657ms 一次；1.5s 轮询 ≈ 每秒 8000 次 JNI 打在 UI 线程上 → 直接卡死。
   这也是我最初「极卡」的唯一原因。

2. **不要在布局过程中改列表**
   `getItemCount()` 内部 `remove()` → RecyclerView 在 `dispatchLayoutStep3` 里
   detach 视图 → `onViewDetachedFromWindow` → `EventBus.unregister` 崩。
   只能在 `notify*`（app 自己就要全量重排）里删。

3. **不要 hook 热方法**
   `getItemCount` 之类每帧调用，FireEye 的日志监测会跟着刷屏，最终 log 自递归爆栈。
   实测 v11 全程 `addCalls` 只有个位数到几十次。

4. **不要在 spawn 后立刻 `Java.use()`**
   此时 App 的 ClassLoader 还没就绪，会把启动流程卡死在「就是歌多」画面。
   必须 `--delay` 让 App 先起来（实测 6000ms 稳）。

5. **不要 hook `Activity.onCreate`**
   `libFireEye-rqd.so` 会 `raise(SIGSEGV)`。纯 attach、读取、`onResume` 都安全。

---

## 五、广告是从哪来的（调用链实测）

### InnerAdBean（推荐流内嵌卡）

```
InnerAdBean.<init>
 ← KG11InnerAdCardManager$e.c:23
 ← KG11InnerAdCardManager$e$a.onResourceReady      ← 等 Glide 图片下载完才建 bean
 ← com.bumptech.glide.request.GenericRequest
 ← android.os.Looper.loop
 ← com.kugou.snow.LooperThreadThief.snowLoop        ← 酷狗"偷 Looper"
```

> 注意 `LooperThreadThief` —— 酷狗自己把主线程 Looper 偷去做别的事，
> 所以任何依赖主线程时序的方案都会踩坑。

### CompeteAdBean（竞品/tab 广告）

```
CompeteAdBean.<init>
 ← binder.c$a.a:27 ← binder.c.e0:326
 ← TingTabAdBannerManager$b.c:96
 ← TingTabAdProtocol.u:50 ← ... ← rx (RxJava onNext)
```

`binder.c.e0()` 是**无参 void**，整段负责构建广告卡 —— 拦它确实能去掉广告，
但下游会拿到空值（实测崩溃），所以放弃，只用 L1。

### 相关 SDK 素材类型

- `com.tencentmusic.ad.integration.nativead.TMENativeAdAsset`
- `com.qq.e.tg.nativ.NativeUnifiedADData`（优量汇 GDT）

---

## 六、验证方法（可复现）

不做「看着像没了」的主观判断，用**确定性单测**：在**独立新建的列表**上验证，
完全不碰活列表。

```js
var t = COW.$new();                  // 与活列表隔离
t.add(normalBean);                   // → true, size 1   正常数据放行
t.add(InnerAdBean.$new('module_inner_ad', 'probe'));   // → false, size 1  广告拦截
t.add(CompeteAdBean.$new('module_ad_banner','probe')); // → false, size 1  广告拦截
liveSizeAfter === 5                  // 活列表未受影响
```

同时用属性反馈验证：

```
idle  → "听「水月陵」的人也在听"  ← 正常内容自然接续，无空白
表格 → "VIP歌曲专属推荐"         ← 压力测试后仍正常
```

---

## 七、性能数据（dumpsys 实测）

### 7.1 开销对比

| 指标 | 旧方案（L4 轮询） | v11（L1 数据层） |
|---|---|---|
| 单次开销 | 657 ms / 949 节点 | — |
| 调用频率 | 每 1.5s 全量遍历 | 仅 `add` 时 1 次判断 |
| 实测调用量 | ≈8000 JNI/秒 | `addCalls` 个位数～几十次（整个会话） |
| 主观体感 | 极卡 | 无感 |

`notify*` 清扫只在 app 自己刷新时触发，实测整个会话 `notifies` 仅 3 次。

### 7.2 滚动流畅度（`measure_jank.py`，滚动 40 秒）

```
                     帧数     Janky    p50   p90   p99
无 hook（热态）       2166     5.4%     25    109   850 ms
v11（热态）           5532     0.0%     14     46    69 ms
```

**结论：v11 不但没拖慢，反而更流畅。** 原因很实在 ——
广告卡片本身是重量级 View（视频封面 + 自动播放器），拦在数据层后
这些 View 根本不创建，渲染负担反而下降。所以帧数几乎翻倍、jank 归零。

> **采样口径提醒（别过度解读）**：两次采样的帧数差了一倍多（2166 vs 5532），
> 说明「无 hook」那一次滚动过程中掉帧严重、实际帧率只有约 54 fps，而 v11 那一次
> 跑满了屏幕刷新率。这恰恰是结论本身，但也意味着**两行的绝对值不宜直接横比**——
> 它们反映的是「有广告卡时滚不动 / 没广告卡时滚得动」两种不同负载，不是同一负载下的
> 纯 hook 开销。想看纯开销，看 7.3 的「只切换 hook 开关」对照组。
>
> 真正能拿来说"hook 不卡"的硬指标是：**v11 下 5532 帧里 janky 占比 0.0%，p99 69 ms**。
> 5532 帧里一帧超预算都没有，这排除了"hook 自己引入卡顿"的可能。

### 7.3 切页耗时（`measure_tabswitch.py`，首页 ⇄ 我的）

用 `gfxinfo framestats` 取逐帧 `IntendedVsync -> FrameCompleted`，
每次切页前 `reset`，窗口内的帧全部归属该次切页。

**先看一个和 hook 无关、但用户能明显感知的事实：**

| 方向 | 每次切页 >16.7ms 的帧 |
|---|---|
| 首页 → 我的 | 8 ~ 13 帧 |
| 我的 → 首页 | 4 ~ 5 帧 |

**「切到我的」比「回首页」重一倍**，这是酷狗自身的问题（无 hook 时同样如此），
不是 hook 造成的。

**hook 开销对照（同一进程、同一时段、只切换 hook 开关）：**

```
                     超预算帧占比    窗口 p90 均值
无 hook              16.2%          118.0 ms
v11                  17.1%           37.2 ms
```

差值 0.9 个百分点，**落在测量噪声内**；v11 的 p90 还低得多。

> **注意**：早先我还测到过一个 `6.4%` 的"基线"，那是更早时段、App 状态不同时的
> 数值，**不能用来和后面的数据对比** —— 切页测量存在明显的"越测越热"漂移
> （同条件下依次测到 26.4% → 17.1% → 16.2%）。
> 唯一可信的对比是**紧邻的 A/B**：16.2%（无 hook）对 17.1%（v11）。
所以 hook 对切页性能没有可测量的影响。

#### ⚠️ 这个测量方法踩过的两个坑（别重犯）

1. **不能用截图哈希判断"渲染完成"**
   底部有播放进度条和封面动画，像素永远在变，"画面稳定"根本等不到。
   实测 12 次切页只确认了 2 次，其余全是顶到 4~5 秒超时的假数据。
   → 改用 `gfxinfo framestats` 的逐帧时间戳。

2. **必须在进程完全热起来之后测**
   冷启动那次把 splash 的 2515ms 单帧也算了进来，得出 31.6% 的失真值；
   同一份代码热态重测只有 17.1%。

另外用一组数据验证测量口径是干净的：静置 1.3 秒窗口内只有 1~18 帧，
说明切页窗口里采到的帧几乎全部来自切页动作本身。

#### ⚠️ tab 坐标必须从 View 树取，不能靠截图估算

我一开始按截图比例估算，`y=1994` 实际点到了底部播放条，
导致两个 tab 从未真正切换、测出一堆 4.5 秒超时假值。
真实坐标由 `js/find_tabs.js` dump `getGlobalVisibleRect` 得到：

```
首页  (360, 3143)
我的  (1080, 3143)     ← y 与估算值差了 1100+ 像素
```

#### ⚠️ `dumpsys window | grep mCurrentFocus` 不可信

在首页明明已经显示出来时，它仍报 `SplashActivity`。
**判断当前页面一律以截图为准**，不要信这个读数，否则会得出
"应用没起来" 的错误结论。

### 7.4 L2（广告 SDK 入口 + onNoAd）探查结果 —— 未打通

**目标形态**（和 [红果净化](https://github.com/Xposed-Modules-Repo/io.github.bancheng97.hongguo) 同思路）：
让 `load*` 判失败，再**主动回调 onNoAd**，这样 UI 不会卡在 loading。

**已确认的事实**（都是实测 dump 出来的）：

```
com.tencentmusic.ad.integration.nativead.TMENativeAdListener   (interface)
    void onAdError(AdError)        ← onNoAd 等价物
    void onAdLoaded(List)
    void onAdEvent(int, Map)

com.tencentmusic.ad.integration.error.AdError   (Kotlin data class)
    AdError(int errorCode, String errorMsg)     ← 唯一构造器，可正常构造
    实测: AdError(204,'no ad') -> toString "AdError(errorCode=204, errorMsg='no ad')"

com.kugou.android.app.tabting.x.protocol.BannerCompeteAdProtocol$b
    a(List)              ← 加载成功
    b(LoadAdParams$Builder)
    c(AdError)           ← onNoAd 等价物
```

**为什么没跑通**：

我 hook 了 `TingTabAdBannerManager$LoadTmeAdCallback` /
`$LoadTmeAutoAdCallback` / `$LoadGdtAdCallback` 的加载成功回调，
但在首页 feed 路径上 **`loadedOk` 始终为 0** —— 切 tab、刷新都不触发。

原因是**路径判断错了**：这三个回调属于 `TingTabAdBannerManager`（tab 广告 banner），
而首页 feed 广告走的是 `KG11InnerAdCardManager`，两条完全独立的链路。
（前面 §五 的调用栈已经证明了这一点，我当时没把两件事对上。）

**另外两个坑**：

- `Load*AdCallback` 其实是**接口**（只含 `a()`），`c(AdError)` 并不在它们身上，
  而在 `BannerCompeteAdProtocol$b`。我一开始搞混了，导致回调调用静默失败
  （`C.c` 是 `undefined`）。
- `Java.enumerateLoadedClassesSync()` 在这个 23 万类的 APK 上要 **13 秒**，
  期间脚本会话被销毁（`InvalidOperationError: script has been destroyed`）。
  **必须用白名单，不能全量枚举类。**

**结论**：L2 的机制（构造 AdError + 主动回调）已验证可用，
但**要把 L2 接到 feed 路径上，需要继续挖 `KG11InnerAdCardManager` 的
加载入口**，这是独立的一块工作。当前版本**只靠 L1 就已经达到去广告效果**，
L2 属于"从源头阻断请求"的优化，不影响功能完整性。

---

## 八、广告体系与原生保护

### 广告包

| 包名 | 角色 |
|---|---|
| `com.kugou.android.ads.*` | 酷狗自有 |
| `com.tencentmusic.ad.*` | 腾讯音乐广告 SDK |
| `com.tencent.ams.fusion.service.splash.*` | 腾讯广告融合（开屏） |
| `com.qq.e.*` | 优量汇 GDT |
| `com.kugou.framework.musicfees.freelisten.rewardad.*` | 「免费听」激励广告 |

### 原生保护库

| 库 | 作用 |
|---|---|
| `libFireEye-rqd.so` (482 KB) | 主反调试/反 hook，触发时 `raise(SIGSEGV)` |
| `libfireeye-memory.so` | 内存扫描 |
| `libtmachine.so` / `libtmesec.so` / `libtmeshield.so` | 设备指纹与加固 |
| `libshadowhook.so` / `libbytehook.so` | 自家 inline hook，与 Frida 冲突 |
| `libxcrash.so` | 崩溃捕获 |

---

## 九、工具链

> 这些脚本不在本仓库里，位于 `G:\hook_test\frida`，仅为复现历史实验留存。

```
frida/kugou_adblock.py        一键启动（spawn + 延迟注入 + 统计落盘）
frida/frida_daemon.py         常驻 daemon（bridge 只注入一次，rpc.exports 反复调用）
frida/run.py                  daemon 客户端（0.2s/次）
frida/js/v11_fixed.js         ★ 最终 hook 脚本（数据层）
frida/js/l2_splash.js         开屏运营广告
frida/js/l2_mine_banner.js    「我的」页 banner
frida/measure_jank.py         dumpsys gfxinfo 掉帧测量
frida/measure_tabswitch.py    切页耗时测量
```

```powershell
# 正式使用
python -u frida\kugou_adblock.py --delay 2500

# 调试用（attach 方式，快但会错过启动期广告）
python -u frida\frida_daemon.py
python -u frida\run.py js\v11_fixed.js --java
```

### 早期踩的坑（已解决，供参考）

1. **`send` 是只读全局** —— 不能覆盖，用 `_cap()` 收集输出。
2. **Frida 17 `recv` 只触发一次** —— 必须用 `rpc.exports`，否则命令静默丢失（40s/次 → 0.2s/次）。
3. **控制台 GBK** —— 中文乱码/崩溃，`run.py` 已用 `backslashreplace` 兜底。
4. **`Java.isInstanceOf` 不存在** —— 此 bridge 版本无此方法。
5. **链式调用每层都要 `Java.cast`** —— 否则 `TypeError: not a function`。
6. **接口返回的 widget 没有 `getClass()`** —— 先 `Java.cast(x, java.lang.Object)`。
7. **`CopyOnWriteArrayList.hashCode()` 是按内容的** —— 不能用它做列表身份判断，必须 `System.identityHashCode`。
8. **`add` 重载见第二节** —— 最致命的一个。

---

## 十、仍未处理的广告位

| 位置 | 类 |
|---|---|
| 开屏（运营推广） | `com.kugou.android.app.splash.SplashActivity` |
| 播放页贴片 | `PlayerStickAdView` |
| 我的页遮罩 | `MineMaskAdView` |
| 播放页底部横幅 | `TMENativeAdContainer` |

这些走的是不同数据源，需要各自找对应的 L1 汇聚点，不能靠同一个 hook 通吃。

---

## 十一、局限

- **运行时注入只对当前进程有效**，重启 app 需重新注入。
- **spawn 立即注入会卡死启动**，必须 `--delay`；这也意味着最开始的 1~2 秒内
  仍可能有广告请求发出（但 L1 在 `add` 层拦截，已经能覆盖绝大多数）。
- 长期生效需要 LSPatch/LSPosed 静态注入（在类加载前就 hook 好），但要处理签名校验与 FireEye。
- 静态分析不可用：JADX 在 23 万类上 4GB 堆直接 OOM，本记录全部结论来自动态分析。
