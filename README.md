# KugouHook

面向酷狗音乐 Android 客户端的 LSPosed 模块，用于隐藏广告和会员促销提示。

只改界面：不改变会员状态、不延长试听时间、不修改播放权限、不干预任何服务端校验。

## 功能

- 推荐流内嵌广告卡与竞品 banner
- 开屏运营推广（「运营推广」全屏卡片，含上滑/摇一摇等互动形态）
- 「我的」页顶部动态运营 banner（"免费领会员"那条）
- 搜索首页视频广告（保留搜索历史、热搜榜和正常搜索结果）
- 「我的」页会员中心、金币中心卡片，以及顶部下拉弹出金币中心引导动画的入口
- 推荐页和「我的」页昵称旁的音乐、听书会员标
- 推荐页顶部的运营推广位（运营商积分兑会员、「免费领会员」等文案）
- 歌曲评论页信息流里的广告卡（带「广告」角标的下载推广）
- 播放队列里的会员歌曲推广横幅
- 自动购买会员弹窗
- 迷你播放栏的试听提示

播放行为不变：会员歌曲仍按酷狗原有逻辑试听或跳转。

## 兼容范围

| 项目 | 内容 |
|---|---|
| 适配版本 | 酷狗音乐 `com.kugou.android` v20.6.4（versionCode 20649） |
| 其他版本 | 部分规则可能失效。失效的规则会被单独跳过并写进日志，其余照常生效 |
| Android | 8.0（API 26）及以上，仅 `arm64-v8a` |
| LSPosed | Modern API 102 及以上 |
| 作用域 | `com.kugou.android` |
| 模块包名 | `io.github.daititii.kugouhook` |

需要说明的是：会员促销相关的几条规则只对 20.6.4 生效，其他版本会跳过它们。
这是有意的——那些规则依赖具体的控件 ID，在没验证过的版本上强行套用可能误伤正常界面。

## 安装

1. 安装并启用 LSPosed（需支持 Modern API 102）
2. 安装 Release 里的 `app-release.apk`
3. 在 LSPosed 中启用本模块，勾选作用域 `com.kugou.android`
4. 强制停止酷狗音乐后重新打开

## 排查

看不到效果时，先确认模块是否真的装上了。日志需要 root：

```bash
adb shell su -c 'cat /data/data/com.kugou.android/cache/kugouhook.log'
```

先找 `KugouHook start (pid=...)` 定位本次启动，再看 `install summary`：
`skipped` 里列出的规则就是这次没装上的。想知道每条规则是否真的被调用：

```bash
adb shell su -c 'touch /data/data/com.kugou.android/cache/kugouhook.audit'
```

再冷启动一次，日志里会出现 `hook hit`。

## 构建

需要 JDK 17+ 与 Android SDK 35：

```bash
./gradlew :app:assembleDebug      # 不混淆，装机调试用
./gradlew :app:assembleRelease    # R8 混淆 + 资源收缩，发布用
```

发布流程、签名配置和版本号约定见 [AGENTS.md](AGENTS.md)，
同步到 LSPosed 模块仓库的步骤见 [docs/RELEASING.md](docs/RELEASING.md)。

## 已知未处理

播放页贴片、播放页底部横幅、「我的」页遮罩广告。这几处数据源各不相同，
需要各自单独分析，不能用同一条规则通吃。

## 致谢

架构（`HookManager` 的逐条隔离与记账、`ClassResolver`、建议性版本门禁）以及
`ApkVersion.kt` 的 AXML manifest 解析移植自 [FanqieHook](https://github.com/afwfv/FanqieHook)（MIT），
已在 [LICENSE](LICENSE) 保留原作者版权声明。

## 免责声明

仅供学习研究。绕过广告可能违反酷狗音乐《用户协议》，安装使用前请自行评估账号风险。
本项目不分发宿主 APK，也不修改宿主安装包。
