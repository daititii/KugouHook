# 把已经发好的上游 Release 同步到 LSPosed 模块仓库。
#
# modules.lsposed.org 只读 Xposed-Modules-Repo/<包名>，不读本仓库。
# 少做这一步的后果是：上游发了新版、站点上永远停在旧版，而且没有任何报错。
#
# 用法（在仓库根目录）：
#   pwsh scripts/publish-lsposed.ps1                  # 取 module.prop 的版本，发最新正式版
#   pwsh scripts/publish-lsposed.ps1 -DryRun          # 只打印将要做什么
#
# 设计约定：
#   * 不重复构建。APK 直接取上游 Release 里的资产，保证两边字节一致（发布说明里写的就是这个）。
#   * tag 用 versionCode-versionName（4-0.1.3），不是 v0.1.3。这是官方硬性格式，
#     写错不会报错，只是模块列表里不显示更新。
#   * 已存在同 tag 就先删（连 git tag 一起删），否则重建撞名。
#
# 需要 `gh auth login` 过、且该账号对镜像仓库有写权限。

[CmdletBinding()]
param(
    [switch]$DryRun,
    [switch]$Prerelease
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$MirrorRepo = 'Xposed-Modules-Repo/io.github.daititii.kugouhook'
$UpstreamRepo = 'daititii/KugouHook'

function Get-Gh {
    $cmd = Get-Command gh -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($p in @(
            "$env:ProgramFiles\GitHub CLI\gh.exe",
            "$env:LOCALAPPDATA\Microsoft\WinGet\Links\gh.exe")) {
        if (Test-Path $p) { return $p }
    }
    throw '找不到 gh。装好 GitHub CLI 后重跑（winget install GitHub.cli）。'
}

$gh = Get-Gh

# 仓库根目录 = 本脚本的上一级
$root = Split-Path -Parent $PSScriptRoot
$propPath = Join-Path $root 'app/src/main/resources/META-INF/xposed/module.prop'
if (-not (Test-Path $propPath)) { throw "找不到 $propPath —— 请在仓库里运行" }

# 显式按 UTF-8 读：Get-Content 在 Windows PowerShell 5.1 上会按系统 ANSI 解码，
# 中文 name/description 行会串行，导致后面的 version 行解析不出来。
$prop = [IO.File]::ReadAllText($propPath, [Text.Encoding]::UTF8)
$fields = @{}
foreach ($line in ($prop -split "`r?`n")) {
    if ($line -match '^\s*([A-Za-z][A-Za-z0-9_]*)\s*=\s*(.*)$') {
        $fields[$matches[1]] = $matches[2].Trim()
    }
}
$versionName = ($fields['version'] -replace '^v', '')
$versionCode = $fields['versionCode']
if (-not $versionName -or -not $versionCode) { throw 'module.prop 里读不到 version / versionCode' }

$upstreamTag = "v$versionName"
$mirrorTag = "$versionCode-$versionName"

Write-Host "上游   : $UpstreamRepo $upstreamTag" -ForegroundColor Cyan
Write-Host "镜像   : $MirrorRepo $mirrorTag" -ForegroundColor Cyan

# 上游 Release 必须已经存在，且带 APK —— 没有就先别发镜像，避免两边不一致
& $gh release view $upstreamTag --repo $UpstreamRepo --json tagName,assets | Out-Null
if ($LASTEXITCODE -ne 0) { throw "上游没有 $upstreamTag。先手动触发 Actions 发版，再回来跑这个脚本。" }

$tmp = Join-Path ([IO.Path]::GetTempPath()) ("lsposed-publish-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
try {
    & $gh release download $upstreamTag --repo $UpstreamRepo --pattern '*.apk' --dir $tmp
    if ($LASTEXITCODE -ne 0) { throw "下载 $upstreamTag 的 APK 失败" }
    $apk = Get-ChildItem $tmp -Filter *.apk | Select-Object -First 1
    if (-not $apk) { throw "上游 $upstreamTag 没有 APK 资产" }

    $sha = (Get-FileHash $apk.FullName -Algorithm SHA256).Hash.ToLower()
    Write-Host "APK    : $($apk.Name)  $($apk.Length) bytes" -ForegroundColor Cyan
    Write-Host "sha256 : $sha" -ForegroundColor Cyan

    # 发布说明：优先用上游 body，追加上游链接和校验和
    $body = (& $gh release view $upstreamTag --repo $UpstreamRepo --json body | ConvertFrom-Json).body
    if (-not $body) { $body = '' }
    $notes = @(
        $body.Trim()
        ''
        "APK 与 [上游 $upstreamTag](https://github.com/$UpstreamRepo/releases/tag/$upstreamTag) 完全相同（sha256 ``$sha``），使用固定发行签名。"
    ) -join "`n"
    $notesPath = Join-Path $tmp 'notes.md'
    [IO.File]::WriteAllText($notesPath, $notes, [Text.UTF8Encoding]::new($false))

    if ($DryRun) {
        Write-Host "`n--- DryRun，以下为发布说明 ---" -ForegroundColor Yellow
        Write-Host $notes
        return
    }

    # 同 tag 已存在：先删，连 git tag 一起删，否则重建撞名
    & $gh release view $mirrorTag --repo $MirrorRepo 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "覆盖已有镜像 Release $mirrorTag" -ForegroundColor Yellow
        & $gh release delete $mirrorTag --repo $MirrorRepo --yes --cleanup-tag
        if ($LASTEXITCODE -ne 0) { throw "删除已有 $mirrorTag 失败" }
    }

    $createArgs = @(
        'release', 'create', $mirrorTag, $apk.FullName,
        '--repo', $MirrorRepo,
        '--title', "v$versionName",
        '--notes-file', $notesPath
    )
    if ($Prerelease) { $createArgs += '--prerelease' }

    & $gh @createArgs
    if ($LASTEXITCODE -ne 0) { throw "创建镜像 Release $mirrorTag 失败" }

    Write-Host "`n已同步：https://github.com/$MirrorRepo/releases/tag/$mirrorTag" -ForegroundColor Green
    Write-Host 'modules.lsposed.org 由爬虫定时抓取，通常几分钟到几十分钟后显示新版本。' -ForegroundColor DarkGray
}
finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
