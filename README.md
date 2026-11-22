# KugouHook

面向酷狗音乐 Android 客户端的 LSPosed 去广告模块。

架构与失败隔离策略参考 [FanqieHook](https://github.com/afwfv/FanqieHook)，逆向过程与实测数据见
[docs/REVERSE-NOTES.md](docs/REVERSE-NOTES.md)，模块内部设计见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 功能

- 拦截推荐流内嵌广告卡（`InnerAdBean` / `module_inner_ad`）
- 拦截竞品与 tab 广告 banner（`CompeteAdBean` / `module_ad_banner`）
- 去除「我的」页顶部动态运营 banner（"免费领会员" 那条）
- 拦截开屏运营推广素材的成功回调，改走 `onNoAD`；不拦截前置网络请求
- 隐藏推荐页和「我的」页昵称旁的音乐、听书会员标（仅酷狗 20.6.4）
- 隐藏「我的」页会员中心、金币中心卡片，并阻止顶部下拉打开金币中心（仅酷狗 20.6.4）；不更改会员状态、金币余额或播放权限
- 隐藏搜索首页视频广告，保留搜索历史、热搜榜和正常搜索结果（仅酷狗 20.6.4）
- 隐藏运营商积分换会员、播放队列会员推广、自动购买弹窗和迷你播放栏试听提示（仅酷狗 20.6.4）；不延长试听或改变播放权限
- 不轮询 View 树；推荐流按广告 bean 的类名或 `module` 特征筛选

## 设计取舍

三条硬约束，来自实机踩坑而不是偏好：

1. **推荐流在数据层拦，不轮询 View 树。** 轮询 `getDecorView` 全树一次要遍历 949 个节点、耗时
   657 ms。推荐流在数据写入时过滤，会员标则只在对应控件绑定时隐藏。
2. **不在布局过程中改列表。** 早期在 `getItemCount()` 里原地删元素，在 RecyclerView 布局中途改长度，
   触发 `EventBus.unregister` 空指针崩溃。现在推荐流的过滤发生在列表写入时。
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
| 模块包名 | `io.github.daititii.kugouhook`（v0.1.2 起） |

## 当前状态

| 形态 | 状态 |
|---|---|
| Frida 参考实现 | 推荐流、我的页和开屏已有实机观察；历史性能数据见 [逆向记录](docs/REVERSE-NOTES.md)，不作为 LSPosed 的性能结论。 |
| LSPosed 调试包 | 在酷狗 20.6.4、Android 16、LSPosed v2.2.1（API 102）上装齐 22 条规则；开屏、搜索、会员提示和「我的」页调整均有实机验证。推荐流广告过滤仍待命中复验。 |
| LSPosed 发布包 | v0.1.1 的本地 R8 构建已验证入口和签名，装机确认 22 条规则正常加载。 |

验收方法和日志判据见 [真机验收记录](docs/DEVICE-VERIFICATION.md)。

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

推送到 `main` 且改动涉及 `app/`、构建脚本或 workflow 时，CI 只构建、不发布：

- **push → 只构建**：跑编译、R8 入口类检查，APK 挂在该次 run 的 Artifacts 里下载。**不建 Release、不动 tag。**
- **手动触发 → 才发布**：默认发预发布（prerelease），勾选 `stable` 才发正式版。

每次提交都产出一个 Release 会让版本列表迅速变成一堆没人验证过的包，"最新版"也就失去意义。
构建产物本来就能在 run 里拿到，Release 只留决定要发的。

| 场景 | 操作 | 结果 |
|---|---|---|
| 日常推送 | 直接 push `main` | 只构建，产物在 run 的 Artifacts |
| 发预发布 | Actions → 手动触发 | 创建 `v<versionName>` 预发布（不算 latest） |
| 发正式版 | 手动触发 + 勾 `stable` | 创建正式版并标记 latest |
| 同版本要重建 | 手动触发 + 勾 `force` | 删掉旧 Release 后重建 |
| 同版本没勾 force | 手动触发 | 直接失败并提示先升版本号 |

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
