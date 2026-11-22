# Frida 参考实现

这套脚本是 `app/` 里 LSPosed 模块的**前身**，也是目前唯一在真机上验证过的实现。

为什么要保留两套：

- Frida 注入是**运行时**的，改一行脚本立刻重跑，不需要重装 APK、不需要重启框架 ——
  逆向阶段必须靠它，否则一次试错要几分钟。
- LSPosed 模块是**类加载前**注入，长期生效、不依赖电脑常驻，但每次改都要重新编译安装。

所以顺序是：先在 Frida 上把 hook 点、判定条件、崩溃边界全部试清楚，再原样搬进 Kotlin。
两边的 hook 点一一对应，见下方对照表。

## 文件

| 文件 | 作用 |
|---|---|
| `kugou_adblock.py` | 一键启动：spawn 宿主 → 等 `--delay` 毫秒 → 注入全部 hook → 前台常驻 |
| `frida_daemon.py` | 常驻 daemon：Java bridge 只注入一次，之后反复调 `rpc.exports` |
| `run.py` | daemon 客户端，0.2 s/次（对比每次都重新注入的 40 s/次） |
| `measure_jank.py` | 用 `dumpsys gfxinfo framestats` 量滚动掉帧 |
| `measure_tabswitch.py` | 量首页 ⇄ 我的切页耗时 |
| `js/v11_fixed.js` | ★ 数据层广告拦截（对应 `AdHooks.kt`） |
| `js/l2_mine_banner.js` | 「我的」页 banner（对应 `MineBannerHooks.kt`） |
| `js/l2_splash.js` | 开屏运营广告（对应 `SplashAdHooks.kt`） |

## 用法

前提：设备上跑着与客户端版本匹配的 `frida-server`（本项目用 17.23.2），电脑上装了 `frida`
Python 包。

```powershell
# 正式使用：spawn + 延迟注入，前台常驻
python -u kugou_adblock.py --delay 2500

# 只看统计，不重启应用
python -u kugou_adblock.py --stats

# 调试：attach 现有进程（快，但会错过启动期广告）
python -u frida_daemon.py
python -u run.py js\v11_fixed.js --java
```

### `--delay` 不是可选项

`--delay 0`（spawn 后立刻注入）会**卡死启动**：那时 App 的 ClassLoader 还没就绪，
`Java.use(...)` 拿不到目标类。实测 `1200` 是安全下限，默认给 `2500` 留余量。

代价是启动后最初 1~2 秒内仍可能有广告请求发出。数据层拦截在 `add()` 那一刻生效，
所以只要注入赶在列表填充之前，广告同样看不到。

## 与模块的对照

| Frida | Kotlin | hook 点 |
|---|---|---|
| `js/v11_fixed.js` | `AdHooks.kt` | `CopyOnWriteArrayList.add(E)` / `addAll(Collection)` |
| `js/l2_mine_banner.js` | `MineBannerHooks.kt` | `MineAdBannerMainDelegate.i()` / `i0(Ad)` |
| `js/l2_splash.js` | `SplashAdHooks.kt` | `TMEOperSplashAdListener.onADFetch` / `onADFetchWithResult` |

## 实测数据

[../docs/measurements/](../docs/measurements/) 里是这些脚本跑出来的原始结果：

| 场景 | 无 hook 基线 | v11 数据层拦截 |
|---|---|---|
| 滚动 40 s 掉帧率 | 5.4%（2166 帧） | **0.0%**（5532 帧） |
| 滚动帧耗时 p90 / p99 | 109 ms / 850 ms | **46 ms / 69 ms** |
| 首页 ⇄ 我的切页超预算帧 | 16.2% | 17.1% |

最后一行必须说明：**切页这一项在噪声范围内**，不能当成"变快了"。方向「我的」比「首页」
重约 2 倍，这是宿主自身的问题 —— 不加任何 hook 也能复现。

## 注意

- 宿主的 `com.kugou.snow.LooperThreadThief.snowLoop` 会抢主 Looper，
  任何依赖主线程时序的设计在这里都不稳。
- 宿主带 `libFireEye-rqd.so` 等反调试库。不要 hook `Activity.onCreate` —— 实测直接
  `raise(SIGSEGV)`。
- 不要用 `Java.enumerateLoadedClassesSync()`：23 万类上要 13 秒，脚本会因超时被销毁。
- 这些脚本只是参考实现，不具备模块的失败隔离能力；某条规则找不到目标时是直接报错。
  长期使用请用 `app/` 的 LSPosed 模块。
