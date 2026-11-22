# KugouHook

面向酷狗音乐 Android 客户端的 LSPosed 去广告模块。

架构与失败隔离策略参考 [FanqieHook](https://github.com/afwfv/FanqieHook)，逆向过程与实测数据见
[docs/REVERSE-NOTES.md](docs/REVERSE-NOTES.md)，模块内部设计见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 功能

- 拦截推荐流内嵌广告卡（`InnerAdBean` / `module_inner_ad`）
- 拦截竞品与 tab 广告 banner（`CompeteAdBean` / `module_ad_banner`）
- 去除「我的」页顶部动态运营 banner（"免费领会员" 那条）
- 阻断开屏运营推广广告（不发广告请求，直接回 `onNoAd`）
- **只拦广告，不碰其它数据**：同一判定同时要求 `module` 串特征与 bean 包名，宿主正常内容
  （歌单、歌曲、长音频分类）不受影响

## 设计取舍

三条硬约束，来自实机踩坑而不是偏好：

1. **只在数据层拦，不轮询 View 树。** 轮询 `getDecorView` 全树一次要遍历 949 个节点、耗时
   657 ms，直接毁掉流畅度。数据层在 `add()` 那一刻丢弃，列表长度与 `getItemCount()` 天然自洽。
2. **不动布局。** 早期版本在 `getItemCount()` 里原地删元素，在 RecyclerView 布局中途改长度，
   触发 `EventBus.unregister` 空指针崩溃。现在删除只发生在 `add()` / `notify*`。
3. **适配失败要能降级。** 某个目标类在新版本里消失，只跳过它并写日志，其余 hook 照常生效；
   不会因为一条规则找不到就让整个模块失效。

## 兼容范围

| 项目 | 内容 |
|---|---|
| 逆向对应版本 | 酷狗音乐 `com.kugou.android` v20.6.4（versionCode 20649） |
| 其他版本 | 预期可用。类名找不到时逐条降级，日志会写明跳过了哪一条 |
| Android | 8.0（API 26）及以上，仅 `arm64-v8a` |
| LSPosed | Modern API 102+ |
| 作用域 | `com.kugou.android` |
| 模块包名 | `dev.kugou.kugouhook` |

## 当前状态

必须先说清楚，避免误用：

| 形态 | 状态 |
|---|---|
| Frida 脚本（`frida/`） | **实机验证通过**。推荐流广告、我的页 banner、开屏广告均确认消失；滚动 5532 帧掉帧率 0.0%、p99 69 ms，可排除"hook 自己引入卡顿" |
| LSPosed 模块（`app/`） | **编译通过，尚未上机验证**。代码是把已验证的 Frida 逻辑按 Xposed API 重写，行为等价但未在本机跑过 |

也就是说：想要立刻能用，用 `frida/`；`app/` 这一份是长期方案，还需一轮装机验证。

## 安装与使用（LSPosed 模块）

1. 安装 LSPosed 框架（需支持 Modern API 102）
2. 安装 Release 里的 `app-release.apk`
3. 在 LSPosed 中启用模块，勾选作用域 `com.kugou.android`
4. 强制停止酷狗音乐后重新打开

排查用日志（需 root）：

```bash
adb shell su -c 'cat /data/data/com.kugou.android/cache/kugouhook.log'
```

想看每条规则的命中情况，先建一个开关文件再重启宿主：

```bash
adb shell su -c 'touch /data/data/com.kugou.android/cache/kugouhook.audit'
```

## 构建与发布

本地构建（需 JDK 17+、Android SDK 35）：

```bash
./gradlew :app:assembleDebug      # 不混淆，装机调试用
./gradlew :app:assembleRelease    # R8 混淆 + 资源收缩，发布用
```

推送到 `main` 且改动涉及 `app/`、构建脚本或 workflow 时，CI 会自动构建 release APK 并发布到
本仓库的 Releases（tag 为 `v<versionName>`，版本号从 `module.prop` 读）。同版本已发布则跳过，
需要覆盖就手动触发 workflow 并勾选 `force`。

### 发布前必须做的两件事

**1. 配置 `KEYSTORE_BASE64` secret（强烈建议）**

未配置时 CI 会用 runner 现生成的 debug keystore 签名。Android 在更新已安装包时会校验签名，
签名一变，设备上就必须**先卸载旧模块再装新的**——用户看到的是"更新后模块失效"。

配置方法：本地生成一个长期 keystore，base64 后填进仓库 secret。

```bash
keytool -genkeypair -v -keystore kugouhook.jks -alias kugouhook \
        -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 kugouhook.jks     # 结果填 KEYSTORE_BASE64
```

另外三个 secret 可选：`KEYSTORE_PASSWORD`、`KEYSTORE_ALIAS`、`KEYSTORE_KEY_PASSWORD`。

**2. 两处版本号必须一起改**

| 位置 | 谁读它 |
|---|---|
| `app/src/main/resources/META-INF/xposed/module.prop` | LSPosed（模块中心显示的版本） |
| `app/build.gradle.kts` 的 `versionCode` / `versionName` | Android 系统（决定这是不是一个新包） |

只改一处是最容易犯又最难发现的错：只改前者，用户看不到更新；只改后者，系统认为装的还是旧包。
CI 的第一步就会比对这两处，不一致直接失败。

### CI 里为什么专门检查 dex

`proguard-rules.pro` 写错时，Debug 构建**完全正常**（不走 R8），只有装到手机上才会发现模块
静默不生效——因为 framework 是按 `java_init.list` 里的类名字符串去找入口类的，被 R8 改名就找不到。
所以 workflow 会在构建后直接检查该字符串是否真的存在于 `classes.dex` 中，把这类问题挡在发布之前。

## 仍未处理的广告位

| 位置 | 类 | 说明 |
|---|---|---|
| 播放页贴片 | `PlayerStickAdView` | 数据源不同，需单独找 L1 汇聚点 |
| 我的页遮罩 | `MineMaskAdView` | 同上 |
| 播放页底部横幅 | `TMENativeAdContainer` | 同上 |

这几处不能靠同一条 hook 通吃，各自需要独立的汇聚点分析。

## 致谢

架构（`HookManager` 的逐条隔离与记账、`ClassResolver`、建议性版本门禁）以及 `ApkVersion.kt`
的 AXML manifest 解析移植自 [FanqieHook](https://github.com/afwfv/FanqieHook)（MIT），
已在 [LICENSE](LICENSE) 里保留原作者版权声明。

## 免责声明

仅供学习研究。绕过广告可能违反酷狗音乐《用户协议》，安装使用前请自行评估账号风险。
本项目不分发宿主 APK，也不修改宿主安装包。
