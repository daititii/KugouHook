# 更新日志

> 本文件面向使用者：只写「适配了哪个版本 + 用户能感知到的变化」，不写实现细节。
> 实现细节与踩坑记录见 [docs/REVERSE-NOTES.md](docs/REVERSE-NOTES.md) 和
> [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 分支与发布纪律

一条硬规矩：**没在真机上验证过的版本不进 main。**

原因不是形式主义。本项目最致命的一个 bug（重载参数转发错误导致 EventBus 全线空指针）就是
"编译通过、逻辑看着对、上机即崩"的典型；只看代码永远发现不了，必须上机跑一遍。

1. 日常改动提交到 `debug` 分支
2. 真机验证通过后再合入 `main`
3. 合 main 前：递增 `versionCode` / `version`，并把该版本的说明写进本文件
4. 该版本上机验证的具体观察写进对应段落，不要写"应该可以"

## 0.1.0

v0.1.0 - 首个版本：推荐流广告 + 我的页 banner + 开屏广告

- **推荐流广告拦截**：`InnerAdBean`（`module_inner_ad`）与 `CompeteAdBean`（`module_ad_banner`）
  在进入数据列表的那一刻被丢弃，不再渲染
- **「我的」页顶部 banner 去除**：`MineAdBannerMainDelegate` 直接返回 null，那条"免费领会员"
  运营位不再出现
- **开屏运营推广广告阻断**：不再向下游发广告请求，直接回 `onNoAd(AdError, 204)`
- **流畅度不受影响**：数据层拦截，不遍历 View 树、不修改布局；实测滚动 p99 帧耗时
  69 ms（无 hook 基线 850 ms），掉帧率 0.0%（基线 5.4%）
- 对应宿主版本：酷狗音乐 `com.kugou.android` v20.6.4（versionCode 20649）

**验证状态**（必读）：

- Frida 参考实现（`frida/`）在上述三项上均已实机验证
- 本模块（`app/`）的 Kotlin 实现编译通过，但**尚未上机验证**；首个 Release 发布前需补齐
  一轮装机验证，并在此处补记 `install summary` 与实机观察
