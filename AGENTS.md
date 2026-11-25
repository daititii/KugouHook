# AGENTS.md

给接手这个仓库的编程 agent（也包括未来的我自己）看的工作约定。**用户文档在
[README.md](README.md)，架构原理在 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，本文只讲
"怎么动手、怎么验证、哪些不能碰"。**

## 项目定位

LSPosed 模块，目标进程 `com.kugou.android` 主进程。只做**界面层**的广告与会员促销隐藏：
不改变会员状态、不延长试听、不修改播放权限、不破解任何服务端校验。

## 环境事实

| 项 | 值 |
|---|---|
| 工作区 | `G:\hook_test`；仓库 `G:\hook_test\KugouHook` |
| 宿主版本 | 酷狗 20.6.4 / versionCode `20649`（`KugouModule.AUDITED_VERSION_CODE`） |
| 设备 | 小米 23117RK66C / Android 16 / arm64 / `adb -s 1b862d7c` |
| 框架 | LSPosed v2.2.1（7912），Modern API 102 |
| 构建 | `.\gradlew.bat --offline :app:assembleDebug :app:assembleRelease` |
| SDK 工具 | `G:\Android_SDK\build-tools\36.1.0`、`G:\Android_SDK\platform-tools\adb.exe` |
| 冷启动 | `adb -s 1b862d7c shell 'am force-stop com.kugou.android && am start -W -n com.kugou.android/.app.splash.SplashActivity'` |

本机内存 16 GB。**JADX 已弃用**，反编译一律走 rust_ASC（见下文），不再有"跑后台任务前
先关图形界面"这类约束。

## 动代码前必须知道的三件事

1. **hook 点是精确反射 + 资源 ID，不是模糊扫描。** 类名逐版本漂移，但漂移是逐条的。
   `HookManager` 逐条 `try/catch`，某条找不到只跳过它并记 `lost`，其余照常装。
   不要写成"版本号不认识就一条都不装"。
2. **UI 隐藏不等于功能可用。** 隐藏按钮或横幅时，必须确保原逻辑不 proceed
   （否则宿主仍会渲染），同时**不能**截断试听计时、改变播放状态或伪造会员返回。
3. **共享容器不要整体 `View.GONE`。** 搜索历史那次事故就是这么来的：`uo_` 是历史和
   快捷入口共用的容器，隐藏它等于删掉用户的历史。拦截要落在**专用**控件或专用回调上。

## 验证纪律

**没在真机上验证过的改动不进 `main`。**

最小验证回路：

1. `adb install -r` 装 Debug 包 → 冷启动 → `adb logcat -d -s KugouHook:V` 找
   `KugouHook start (pid=...)`，确认本次启动的 `install summary`。
2. 需要逐条命中证据时：`adb shell su -c 'touch /data/data/com.kugou.android/cache/kugouhook.audit'`
   再冷启动。**没有 `hook hit` 说明入口找错了；有 `hook hit` 但界面没变，说明 hook 点不对。**
   两种情况修法相反，不许靠猜。
3. 关掉模块作用域做对照（冷启动各一次），确认差异是模块造成的而不是缓存。
4. 具体观察写进 [docs/DEVICE-VERIFICATION.md](docs/DEVICE-VERIFICATION.md)，写"看到了什么"，
   不写"应该可以"。

日志位置：`adb shell su -c 'cat /data/data/com.kugou.android/cache/kugouhook.log'`

## 发布流程

| 场景 | 操作 | 结果 |
|---|---|---|
| 日常推送 | push `main` | 只构建 + R8 入口检查，产物在 Actions Artifacts，**不建 Release** |
| 发预发布 | Actions 手动触发 | 建 `v<versionName>` 预发布 |
| 发正式版 | 手动触发 + 勾 `stable` | 建正式版并标 latest |
| 同版本重建 | 手动触发 + 勾 `force` | 删旧 Release 重建；不勾则失败并提示升版本 |
| 上 LSPosed 模块列表 | `pwsh scripts/publish-lsposed.ps1` | 同步到镜像仓库，站点才会显示新版本 |

**版本号必须两处同步改**，CI 第一步就比对：

- `app/src/main/resources/META-INF/xposed/module.prop` 的 `version` / `versionCode`（LSPosed 读）
- `app/build.gradle.kts` 的 `versionName` / `versionCode`（Android 读）

签名材料在 `%LOCALAPPDATA%\KugouHook\Signing\`（`release.jks` + DPAPI 保护的
`release-password.dpapi`），仓库 Actions secrets 已配置。**任何情况下不打印密钥、口令或 token。**

`applicationId`（`io.github.daititii.kugouhook`）是 Android 与 LSPosed 的模块身份，
改名等于换一个模块：用户必须先禁用旧包再启用新包。Kotlin 包名
`dev.kugou.kugouhook`、`java_init.list` 入口、R8 规则、作用域 `com.kugou.android` 与之无关，不要跟着改。

### LSPosed 模块仓库是另一个仓库

`modules.lsposed.org` **只读 `Xposed-Modules-Repo/io.github.daititii.kugouhook`**，不读本仓库。
上游发版和站点更新是两件独立的事，漏了后者不会报错，只是站点上永远停在旧版本
（v0.1.2 → v0.1.3 就是这样卡住的）。

发完上游 Release 后再跑一次：

```powershell
pwsh scripts/publish-lsposed.ps1 -DryRun   # 先看要发什么
pwsh scripts/publish-lsposed.ps1           # 实际同步
```

脚本从上游 Release 直接取 APK（不重复构建，保证两边字节一致），tag 按官方硬性格式
`versionCode-versionName`（`4-0.1.3`，**不是** `v0.1.3`）。格式写错不会报错，只是模块列表里
不显示更新。已存在同 tag 会先删再建（连 git tag 一起删）。

站点由爬虫定时抓取，同步后几分钟到几十分钟才显示，不用反复刷。

## 逆向工具：优先用 rust_ASC，不要用 JADX

宿主 APK：`G:\hook_test\kugou-20649-base.apk`（161 MB，29 个 dex，**233,727 个类**）。

**[rust_ASC](https://github.com/MG1937/rust_ASC) 是首选工具**，可执行文件在
`G:\hook_test\_ref\rust_ASC\rust_ASC.exe`。无状态、不建索引、不落盘缓存，直接 mmap APK
跨 dex 并行扫描，内存峰值几百 MB。已作为 MCP server 接入（工具前缀 `rust_ASC`）。

本机实测（就是这个 APK）：

| 操作 | 耗时 |
|---|---|
| `listclass --prefix yk0` | 0.47 s |
| `getclass yk0.g` | 0.6 s |
| 全量 `export`（233,727 个类，715 MB） | **150 s** |

对照：同一份 APK 用 JADX 全量导出跑了 46 分钟，到 72%（43,873/60,686）就正常退出，
漏掉 10/20 个本模块实际用到的宿主类（`yk0.g`、`newtingtab.mc`、`version.upgrade.b` 全缺），
且 `resources/` 因命令带 `-r` 为空。**JADX 在本项目里已经没有使用价值。**

```powershell
$exe = 'G:\hook_test\_ref\rust_ASC\rust_ASC.exe'
$apk = 'G:\hook_test\kugou-20649-base.apk'

# 按界面文案直接找代码引用点——这是最常用的入口，比猜类名快一个数量级
& $exe findrefs $apk string "免费领会员"

# 按类型 / 方法 / 字段 / 继承关系查
& $exe findrefs $apk type com.kugou.version.upgrade.IVersionUpgrade
& $exe findrefs $apk impl com.kugou.version.upgrade.IVersionUpgrade
& $exe findrefs $apk method k --class com.kugou.version.upgrade.b

# 单类反编译 / 列类 / 列方法 / 列字符串池 / 解清单
& $exe getclass $apk yk0.g
& $exe listclass --prefix com.kugou.android.app.newtingtab $apk
& $exe listmethod --class com.kugou.version.upgrade.b $apk
& $exe liststring -f 会员 $apk
& $exe getmanifest $apk

# 全量导出（需要全文 grep 时用，8 线程约 150 s）
& $exe export --thread 8 $apk G:\hook_test\kugou-20649-asc
```

全量导出在 `G:\hook_test\kugou-20649-asc`（233,727 个 `.java`），可以放心 grep。

**JADX 图形界面不再需要**。它自身可占到 4.8 GB（`-XX:MaxRAMPercentage=70.0`），
历史上多次把整机拖到卡死——那个时代结束了。下面几条留作记录，说明为什么放弃它：

1. 整包 `aapt2 dump resources`、`search_in=code` 全量搜索、无分页拉 Manifest 都会打满索引线程
2. `-Xmx4g` 在 `load()` 阶段 OOM；`-Xmx9g` 能跑但容易和图形界面抢内存
3. 按 dex 拆分导出会产出跨 dex 引用断裂的废码（实测 2,795 个文件全是空壳）

## 已确认的逆向事实

- `com.kugou.android.app.splash.SplashActivity` 在清单里存在，但**代码里不存在**同名类，
  启动页是动态代理的壳。查启动期逻辑不要从这个类名入手。
- **开屏广告有两条独立链路**，不要以为拦了一条就干净了：
  1. TME 运营开屏：`UniParallelFetcher` 存在时走 `ce.f` 分发，拦 `onADFetch` 有效。
  2. 全屏「运营推广」卡片：`BaseSplashUnitAgent#e0` 的 `z16` 分支，由 `SplashV4` 直接
     驱动 `SplashCard` 绘制（`SplashDrawer` 是 SurfaceView 自绘）。**这条没有任何
     "下载→回调→展示"环节**，所有回调层的 hook 都碰不到它。有效拦点是
     `yk0.g#e(SplashV4)` 返回 false。
- 宿主带 `libFireEye-rqd.so` 等反调试库；Frida 时代实测 hook `Activity.onCreate` 会
  `raise(SIGSEGV)`。`Java.enumerateLoadedClassesSync()` 在 23 万类上要 13 秒。
- `com.kugou.snow.LooperThreadThief.snowLoop` 会抢主 Looper，任何依赖主线程时序的设计都不稳。
- 不要往子进程（`:support`、`:message`）注 hook，没有收益只有风险。
- **没有统一的广告拦截点。** `q8.d` 的构造函数登记了 19 个广告位名，是目前最可靠的
  "还有哪些位置"参照；19 个位的数据结构、绑定时机各不相同，只能逐条处理。
  三条看起来"一处拦全部"的路都验证过不可行（请求层 `h8.a`、统计字段 `q8.d.i`、
  广告 SDK 初始化层），原因见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
  的「广告位总表」与「为什么不能拦广告 SDK 初始化」两节。
  **别再去试初始化层**：GDT 侧拦了会抛未捕获异常、开屏静默卡到超时，还会堵死
  `GdtPreloadAdManager` 的免费听领时长；且 `AbstractAD$1` 会在每个广告对象构造时自愈重 init。
- **付费推广层是 SSR H5，不是原生页面。** 点击 VIP 歌曲弹出的「会员专属歌曲，付费享
  上亿曲库」底部弹层，地址写死在 `FeeFrontInterceptDialogUtils` 里
  （`https://gateway.kugou.com/ssr_vippopup/intercept`），所以整包搜不到那句文案——
  文案由服务端下发。拦的是 `FeeFrontInterceptDialogUtils$a#run()` 直接返回。
- **`%1$s` 这类带占位符的文案是资源，不是代码常量。** `findrefs string` 找不到时，
  按 `aapt2 dump strings` → `aapt2 dump resources` 的顺序反查资源名，再用
  `layout/xxx` 反查布局。资源 ID 在 axml 里是小端 uint32，直接按字节搜比调 aapt2 快得多。

## 定位方法：先观测，再拦截

找广告/弹窗的入口时，**不要靠反编译猜类名**。正确顺序是：

1. 写 `cache/kugouhook.flags` 内容 `ui.probe=1`，冷启动。
2. `UiProbeHooks` 会 hook `Dialog.show`、`PopupWindow.showAtLocation`、`SurfaceView` 构造，
   命中时打印**去掉系统帧的调用栈**——宿主自己的类名和调用链直接出现在日志里。
3. 按日志里的类名做单类反编译（`rust_ASC getclass`），确认该拦哪一处。
4. 拦完在界面上肉眼确认，**不要用"日志有 hit"当作成功**。

反过来也成立：**如果你已经知道界面上的文案，先试 `findrefs string "<文案>"`。**
「免费领会员」就是这么一步定位的——直接拿到 `Lcom/kugou/framework/statistics/easytrace/b;-><clinit>`，
展开看完整串是「试听弹窗-免费领会员入口」，说明那是埋点 key，不是页面文案。

这一步能省掉几轮"猜错→重编译→装机→没命中"的循环。

Frida 脚本已移出本仓库，存在 `G:\hook_test\frida`，不进 git，仅为历史参考。

## 不许做的事

- 不提交任何宿主 APK、反编译产物、日志文件到仓库。
- 不发布"编译通过但没上机验证"的版本。
- 不在 README 里写内部规则数量、类名清单这类只对开发者有意义的细节。
- 不为了"让功能看起来更多"去 hook 与广告无关的路径。
