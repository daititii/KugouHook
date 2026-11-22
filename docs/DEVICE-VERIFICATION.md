# LSPosed 真机验收

## 2026-11-22：酷狗 20.6.4

- 宿主：`com.kugou.android` 20.6.4（versionCode 20649）；Android 16；LSPosed v2.2.1（7912，API 102）。
- 验收对象：本地 Debug APK 和 v0.1.1 本地 R8 构建，只勾选酷狗音乐作用域；未清除宿主数据。本地 R8 构建沿用调试签名，与 GitHub Release 产物的签名不同。

| 项目 | 结果 | 证据 |
|---|---|---|
| LSPosed 加载 | 通过 | 修复前两次冷启动均为 `hooks installed=4 skipped=2`，两条开屏规则报 `Cannot hook abstract methods`。改挂具体分发类后，连续三次均为 `hooks installed=6 skipped=0`。 |
| 开屏运营推广 | 通过实际回调验证 | 同一次冷启动出现 `blocked splash onADFetchWithResult (onNoAD returned)` 与 `blocked splash onADFetch (onNoAD returned)`，随后可正常使用首页。本轮不判断广告网络请求是否发生。 |
| 「我的」页顶部运营 banner | 通过关闭／开启对照 | 关闭模块时能读取「免费领酷狗会员」和「关闭广告」；开启后这两个控件消失，「会员中心」和「最近播放」仍可见。同轮出现 `blocked mine banner data (i -> null)` 与 `dropped mine banner write (i0)`。 |
| 推荐流 | 安装通过，实际拦截待复验 | `ad.add.filter` 和 `ad.addAll.filter` 都已安装；能刷新、滚动并切到「我的」，未记录 `blocked ad bean:` 或 `blocked addAll containing`。无业务命中不表示已拦截，也不表示必然失效。 |

## 会员标与促销提示复验

同一台设备与宿主版本的更新包冷启动记录为 `hooks installed=12 skipped=0`。

- 推荐页：界面层级中原有的音乐会员标 `s69` 与听书会员标 `s68` 消失；播放按钮及底部导航仍可见。日志出现 `hidden home VIP badge` 与 `hidden audiobook VIP badge`。
- 「我的」页：切页后头像旁音乐会员标 `qtc` 和听书会员标 `s68` 都不在界面层级中，会员中心卡片及最近播放仍在。日志出现 `hidden mine VIP badge`。
- 短时提示：推荐页促销卡和迷你播放栏试听横条规则已安装。本轮没有 `suppressed home VIP promo card preparation` 或 `hidden mini-player VIP prompt`，也没有再次看到「运营商领会员」，暂不能确认该文案具体出自哪个控件。

## 「我的」页卡片与金币中心下拉入口

同一设备更新后冷启动记录为 `hooks installed=14 skipped=0`。

- 会员中心卡片 `hxb1` 和金币中心卡片 `hnm0` 均不在界面层级中，对应两条 `hidden mine … center card` 日志已命中。最近播放、歌单和迷你播放栏播放按钮保留。
- 更新前，在「我的」页顶部向下拉会打开金币中心覆盖页（`mp6`）；更新后同一手势命中 `suppressed mine pull-to-open coin center`，回弹后仍留在「我的」页。规则不拦截其他页面入口或改变金币数据。

## 搜索首页、运营商推广与播放提示

同一设备安装最终 Debug 构建后冷启动：`hooks installed=22 skipped=0`。随后安装 `v0.1.1` 本地 Release 构建（R8，沿用本机调试签名），再次确认 `hooks installed=22 skipped=0`。

- 搜索首页：回归版曾误将历史和快捷入口所在的共享容器 `uo_` 隐藏。修订后只跳过广告素材绑定，不修改容器可见性。真机同一轮出现 `blocked search home banner material`，历史搜索和热搜榜都在，视频广告不在，输入关键词也能返回歌曲结果。
- 运营商推广：冷启动曾出现「运营商积分兑会员」；修订后命中 `hidden carrier exchange operation`。
- 购买弹窗和试听提示：搜索周杰伦并点《晴天》，旧版打开购买页，修订版命中 `suppressed automatic playback purchase prompt` 和 `hidden mini-player VIP prompt`，仍进入原有试听流程。未验证所有试听文案、时长或播放入口。
- 播放队列：旧版出现「含14首会员歌曲，看广告可免费听」和「立即观看」；最终版两者同时消失，来源栏和歌曲列表仍在，命中 `hidden queue VIP banner`。只隐藏专用促销控件，不隐藏队列外层。其他共享歌单横幅的开会员文案未单独在真机复现。

## 复验方法

1. 安装调试包，在 LSPosed 只勾选酷狗音乐。关闭作用域可以做界面对照，切换前后都需冷启动，不必清除宿主数据。
2. `adb shell am force-stop com.kugou.android`，再打开酷狗。查看 `adb logcat -d -s KugouHook:V`，按 `KugouHook start (pid=...)` 找到本次主进程的起始位置。检查具体规则的 `hook installed:` 行及 `install summary`，不要把不同启动的日志混在一起。
3. 开屏收到素材后，等待 `blocked splash … (onNoAD returned)`，确认能进入首页；只看到 `hook installed:` 说明本次仅验收了安装。进「我的」前后核对运营位和正常控件，区分顶部运营 banner 与其他会员推广。
4. 推荐流下拉刷新并向下滚动，等待实际业务拦截日志。`hook hit` 只证明列表方法被调用，不能证明广告被过滤；批量过滤日志也不能区分 `InnerAdBean` 与 `CompeteAdBean`。

开屏安装失败只能在 LSPosed 的真实加载流程中复现，本次以同设备、同宿主版本的冷启动作为安装回归验证，未添加会跳过框架的 JVM 模拟测试。
