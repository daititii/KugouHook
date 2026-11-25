# 发布到 LSPosed 模块仓库

`modules.lsposed.org` 只读 `Xposed-Modules-Repo/<包名>` 这一组仓库，**不读本仓库**。
所以"发了 GitHub Release"和"LSPosed 模块列表里出现新版本"是两件独立的事。

v0.1.3 就是这样卡住的：上游 Release 正常，站点上仍停在 v0.1.2，而且**没有任何报错**。

## 一次完整的发版

```powershell
# 1. 改版本号（两处，CI 会比对）
#    app/src/main/resources/META-INF/xposed/module.prop   version / versionCode
#    app/build.gradle.kts                                 versionName / versionCode

# 2. 提交推送（只构建，不建 Release）
git push origin main

# 3. 手动触发 Actions 发版（勾 stable 发正式版）
gh workflow run release.yml -f stable=true

# 4. 等 Release 出来，再同步到模块仓库
pwsh scripts/publish-lsposed.ps1 -DryRun
pwsh scripts/publish-lsposed.ps1
```

第 4 步别忘。忘了的后果不是报错，是站点上永远显示旧版本。

## 脚本做了什么

- 从 `daititii/KugouHook` 的 `v<versionName>` Release **下载 APK**，不重复构建，
  所以两个仓库里的文件字节完全一致，发布说明里写的 sha256 也就是真的。
- 在镜像仓库建一个 tag 为 `<versionCode>-<versionName>` 的 Release，APK 作为资产一起上传。
- 同 tag 已存在时先删（`--cleanup-tag`，连 git tag 一起删），否则重建撞名。

## 官方硬性格式

| 要求 | 说明 |
|---|---|
| tag 必须是 `versionCode-versionName` | `4-0.1.3`，**不是** `v0.1.3`。写错不会报错，只是模块列表里不显示更新 |
| APK 必须和 Release 一起创建 | 只改已有 Release 的 asset 不会触发 bot 更新 tag |
| Release 标记 prerelease 不算正式版 | 预发布不会被当成 `latest` |

来源：[Xposed-Modules-Repo/submission](https://github.com/Xposed-Modules-Repo/submission/blob/master/README.md)

## 为什么不在 CI 里做

跨仓库写入需要 PAT（`GITHUB_TOKEN` 只能写本仓库）。选择走本地 `gh` 凭据：

- 不新增长期有效的跨仓库凭据
- 但**每次发版要记得手动跑一步**

如果哪天改成 CI 自动，需要建 fine-grained PAT 只授权
`Xposed-Modules-Repo/io.github.daititii.kugouhook` 的 `Contents: Read and write`，
存成 secret `XPOSED_REPO_PAT`，再在 workflow 里加一步。

## 站点什么时候更新

爬虫定时抓取，几分钟到几十分钟。不用反复刷。
`https://modules.lsposed.org/module/<包名>.json` 的 `latestRelease` 字段是最快的判据。

## 排查

```powershell
# 镜像仓库现有 release
gh release list --repo Xposed-Modules-Repo/io.github.daititii.kugouhook

# 站点数据
curl https://modules.lsposed.org/module/io.github.daititii.kugouhook.json
```

站点没更新但镜像仓库有：等爬虫，或者确认 tag 格式对不对、APK 是不是和 Release 一起传的。
