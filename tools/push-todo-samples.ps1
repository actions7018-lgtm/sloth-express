<#
.SYNOPSIS
    推送 20 条模拟快递样本到真机，并核对首页三组 / 列表家门口 / 桌面 Widget 计数。

.DESCRIPTION
    20 条样本的分布（需求：同时覆盖「三类投放场景」和「首页三个分组」）：

      首页「待取」  16 条 = 驿站站点 8 + 代收点 8        ← 待取件为主
      投放场景      家门口 2 条                          ← 口径外：不进首页待取、不进 Widget
      首页「派送中」 1 条（无取件码）
      首页「运输中」 1 条（无取件码）
      合计          20 条

    预期计数：首页 待取（16）/ 派送中（1）/ 运输中（1）；Widget 待办 16 项 → 6 条/页 → 3 页。
    计数是**绝对值**，必须带 `-Fresh` 跑：叠加在已有数据上时三组计数与 Widget 标题都会对不上（真机踩过）。
    家门口 2 条只在「快递」列表的全部筛选与详情页可见（needsPickup 口径排除，见 README §3.4）。

    样本文本内**不能有 ASCII 空格**：文本是作为 adb shell 的参数传的，空格会被切词。

.PARAMETER Fresh
    推送前 `pm clear`，保证计数就是本次这 20 条（默认会叠加在已有数据上，计数对不上）。

.NOTES
    与 device-smoke.ps1 相同的 PS 5.1 坑：
      1) XmlNode 返回要用一元逗号 `,`，否则 childless 节点被管道展开成 $null；
      2) 含中文 → 必须存成带 BOM 的 UTF-8，否则 PS 5.1 按 GBK 读坏断言文本；
      3) adb 2>&1 合流时 stderr 不能升级成终止错误 → Adb() 临时切 Continue。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\push-todo-samples.ps1 -Fresh
    powershell -ExecutionPolicy Bypass -File tools\push-todo-samples.ps1 -Serial 0123456789ABCDEF -Fresh
#>
[CmdletBinding()]
param(
    [string]$Serial = "",
    [string]$Adb = "D:\Android\Sdk\platform-tools\adb.exe",
    [switch]$Fresh,
    [switch]$SkipVerify
)

$ErrorActionPreference = "Stop"
$PKG = "com.parcelhub"
$LAUNCHER = "$PKG/.ui.MainActivity"
$SHARE = "$PKG/.ingest.ShareEntryActivity"

$GlobalArgs = @()
if ($Serial) { $GlobalArgs = @("-s", $Serial) }

# ---------------------------------------------------------------- 基础工具
function Adb([string[]]$argv) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { return (& $Adb @GlobalArgs @argv 2>&1 | ForEach-Object { "$_" } | Out-String) }
    finally { $ErrorActionPreference = $prev }
}
function Sh([string]$cmd) { Adb @("shell", $cmd) }
function Wait([int]$sec) { Start-Sleep -Seconds $sec }

# uiautomator dump（偶发 "could not get idle state" → 重试 3 次）
function Get-Ui {
    for ($k = 0; $k -lt 3; $k++) {
        Sh "rm -f /sdcard/ui.xml" | Out-Null
        $out = Sh "uiautomator dump /sdcard/ui.xml"
        if ($out -like "*dumped to*") {
            $tmp = "$env:TEMP\push_samples_ui.xml"
            cmd /c "`"$Adb`" $GlobalArgs exec-out cat /sdcard/ui.xml > `"$tmp`""
            return ,([xml](Get-Content $tmp -Raw -Encoding UTF8))
        }
        Wait 3
    }
    return ,$null
}
function Has-Text($doc, [string]$text) {
    if ($null -eq $doc) { return $false }
    foreach ($n in $doc.SelectNodes("//*[@text]")) {
        if ($n.text -and $n.text.Contains($text)) { return $true }
    }
    return $false
}
function Tap-Text($doc, [string]$text, [int]$minY = 0) {
    foreach ($n in $doc.SelectNodes("//*[@text]")) {
        if (-not $n.text -or -not $n.text.Contains($text)) { continue }
        $m = [regex]::Matches($n.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
        if ($m.Count -ne 1) { continue }   # 1 个 bounds = 1 个 Match（4 个捕获组在 Groups 里）
        $y = ([int]$m[0].Groups[2].Value + [int]$m[0].Groups[4].Value) / 2
        if ($y -lt $minY) { continue }
        $x = ([int]$m[0].Groups[1].Value + [int]$m[0].Groups[3].Value) / 2
        Sh "input tap $x $y" | Out-Null
        return $true
    }
    return $false
}
function Screenshot([string]$name) {
    cmd /c "`"$Adb`" $GlobalArgs exec-out screencap -p > `"$env:TEMP\$name.png`""
    "截图: $env:TEMP\$name.png"
}
# 轮询直到页面出现指定文本：uiautomator dump 偶发 "could not get idle state"，
# 单次取不到就判失败会误报（真机踩过），所以统一改成有界重试。
function Wait-For-Text([string]$text, [int]$tries = 4) {
    for ($i = 0; $i -lt $tries; $i++) {
        $d = Get-Ui
        if (Has-Text $d $text) { return ,$d }
        Wait 3
    }
    return ,$null
}

# ---------------------------------------------------------------- 样本数据
# 站点类：地址里带「驿站」，Parser 抽出短站点 + 取件码 → 首页「待取」
$Station = @(
    "余杭区东连街道菜鸟驿站已收到您的包裹，取件码：10-1-1001，运单号YT1000000001",
    "南门驿站已收到您的包裹，取件码：10-1-1002，运单号YT1000000002",
    "西门驿站已收到您的包裹，取件码：10-1-1003，运单号YT1000000003",
    "北门驿站已收到您的包裹，取件码：10-1-1004，运单号YT1000000004",
    "东门驿站已收到您的包裹，取件码：10-1-1005，运单号YT1000000005",
    "文一西路驿站已收到您的包裹，取件码：10-1-1006，运单号YT1000000006",
    "海创园驿站已收到您的包裹，取件码：10-1-1007，运单号YT1000000007",
    "紫金港驿站已收到您的包裹，取件码：10-1-1008，运单号YT1000000008"
)
# 代收点类：投放场景之一，同样进「待取」
$Agent = @(
    "您的包裹已到东门代收点，取件码2-3-2001，运单号SF2000000000001",
    "您的包裹已到北门代收点，取件码2-3-2002，运单号SF2000000000002",
    "您的包裹已到南门代收点，取件码2-3-2003，运单号SF2000000000003",
    "您的包裹已到西门代收点，取件码2-3-2004，运单号SF2000000000004",
    "您的包裹已到广场代收点，取件码2-3-2005，运单号SF2000000000005",
    "您的包裹已到园区代收点，取件码2-3-2006，运单号SF2000000000006",
    "您的包裹已到学府代收点，取件码2-3-2007，运单号SF2000000000007",
    "您的包裹已到江畔代收点，取件码2-3-2008，运单号SF2000000000008"
)
# 家门口：投放场景之一，但被 needsPickup 口径排除（不进首页待取、不进 Widget）
$Door = @(
    "您的快递已放在家门口，取件码9901，运单号771122330001",
    "您的快递已放在家门口，取件码9902，运单号771122330002"
)
# 在途（无取件码）：分别落进首页「派送中」「运输中」
$OnWay = @(
    "圆通速递：您的快递派送中，运单号YT3000000001",
    "中通快递：您的快递运输中，运单号773000000001"
)

$Samples = @()
$Station | ForEach-Object { $Samples += @{ cat = "站点"; text = $_ } }
$Agent   | ForEach-Object { $Samples += @{ cat = "代收点"; text = $_ } }
$Door    | ForEach-Object { $Samples += @{ cat = "家门口"; text = $_ } }
$OnWay   | ForEach-Object { $Samples += @{ cat = "在途"; text = $_ } }

Write-Host "样本合计: $($Samples.Count) 条（站点 $($Station.Count) / 代收点 $($Agent.Count) / 家门口 $($Door.Count) / 在途 $($OnWay.Count)）"

$state = Adb @("get-state")
if ($state -notmatch "device") { throw "未检测到设备（adb get-state: $state）" }

if ($Fresh) {
    Write-Host "清空 App 数据（-Fresh）…"
    Sh "pm clear $PKG" | Out-Null
    Wait 4
}

# ---------------------------------------------------------------- 推送
Write-Host "推送样本…"
$i = 0
foreach ($s in $Samples) {
    $i++
    Adb @("shell", "am", "start", "-a", "android.intent.action.SEND", "-t", "text/plain",
        "-n", $SHARE, "--es", "android.intent.extra.TEXT", $s.text) | Out-Null
    Wait 2
    Write-Host ("  [{0,2}/20] {1,-5} {2}" -f $i, $s.cat, $s.text)
}
Wait 4

if ($SkipVerify) { Write-Host "跳过校验（-SkipVerify）"; exit 0 }

# ---------------------------------------------------------------- 公共断言工具
function Report([string]$name, [bool]$ok) {
    if ($ok) { Write-Host "  [PASS] $name" -ForegroundColor Green }
    else { Write-Host "  [FAIL] $name" -ForegroundColor Red }
    return $ok
}
# 带上滑的找文本：首页三组要往下滑、列表要往下滑，停在当前屏直接 dump 会漏判（真机踩过）
function Find-Text-Scrolled([string]$text, [int]$tries = 10) {
    for ($k = 0; $k -lt $tries; $k++) {
        $d = Get-Ui
        if (Has-Text $d $text) { return ,$d }
        Sh "input swipe 540 1650 540 600 300" | Out-Null
        Wait 2
    }
    return ,$null
}
# 先滑回顶部：让每次「从上往下找」的起点一致（上一轮停在哪不确定）
function Scroll-To-Top {
    for ($k = 0; $k -lt 4; $k++) { Sh "input swipe 540 600 540 1650 300" | Out-Null; Wait 2 }
}
# 精确文本点击：底部导航「快递」用 contains 会误点到含「快递」的卡片文案（真机踩过）
function Tap-Exact($doc, [string]$text, [int]$minY = 0) {
    if ($null -eq $doc) { return $false }
    foreach ($n in $doc.SelectNodes("//*[@text]")) {
        if ($n.text -ne $text) { continue }
        $m = [regex]::Matches($n.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
        if ($m.Count -ne 1) { continue }   # 1 个 bounds = 1 个 Match（4 个捕获组在 Groups 里）
        $y = ([int]$m[0].Groups[2].Value + [int]$m[0].Groups[4].Value) / 2
        if ($y -lt $minY) { continue }
        $x = ([int]$m[0].Groups[1].Value + [int]$m[0].Groups[3].Value) / 2
        Sh "input tap $x $y" | Out-Null
        return $true
    }
    return $false
}

$all = $true

# ---------------------------------------------------------------- 校验：首页三组
Write-Host "校验首页三组…"
# -S 冷启动：不带 -S 的话 am start 只是把上次的任务带回前台（上次停在列表页，
# 首页断言就全空 —— 真机踩过），必须 force-stop 后重开才保证落在首页
Sh "am start -S -n $LAUNCHER" | Out-Null
Wait 6
$doc = Get-Ui
# -Fresh 会重置「看过引导」标记 → 首次进来是引导页，先跳过才能到首页
if (Has-Text $doc "先跳过，稍后再开") {
    Write-Host "  检测到首启引导页 → 点「先跳过，稍后再开」"
    [void](Tap-Text $doc "先跳过，稍后再开")
    Wait 4
    $doc = Get-Ui
}
$okPickup = $null -ne (Wait-For-Text "待取（16）")
if (-not $okPickup) {
    # 冷启动后仍可能不在首页 Tab → 点底部「首页」再等一次
    [void](Tap-Exact (Get-Ui) "首页" 2000)
    $okPickup = $null -ne (Wait-For-Text "待取（16）")
}
Scroll-To-Top
$okOut = $null -ne (Find-Text-Scrolled "派送中（1）")
Scroll-To-Top
$okTransit = $null -ne (Find-Text-Scrolled "运输中（1）")
$all = (Report "首页待取（16）" $okPickup) -and $all
$all = (Report "首页派送中（1）" $okOut) -and $all
$all = (Report "首页运输中（1）" $okTransit) -and $all
Screenshot "samples_home" | Out-Null

# ---------------------------------------------------------------- 校验：家门口 2 条
# 家门口样本被 needsPickup 口径排除（不进首页待取、不进 Widget），只能在列表 + 详情里看到，
# 所以按取件码 9901/9902 找卡片，再进详情断言「放置位置 家门口」。
Write-Host "校验列表页家门口 2 条…"
Scroll-To-Top
$doc = Get-Ui
$inList = $null -ne (Wait-For-Text "全部包裹")
if (-not $inList) {
    # 底部导航必须**精确匹配**（contains 会点到含「快递」的卡片文案）
    if (-not (Tap-Exact $doc "快递" 2000)) { [void](Tap-Exact (Get-Ui) "快递" 2000) }
    Wait 4
    $inList = $null -ne (Wait-For-Text "全部包裹")
}
$all = (Report "切到「全部包裹」列表页" $inList) -and $all
$door1 = Find-Text-Scrolled "取件码 9901"
$door2 = Find-Text-Scrolled "取件码 9902"
$all = (Report "列表页出现家门口取件码 9901" ($null -ne $door1)) -and $all
$all = (Report "列表页出现家门口取件码 9902" ($null -ne $door2)) -and $all
# 进 9901 的详情，断言「放置位置 家门口」
if ($door1) {
    foreach ($n in $door1.SelectNodes("//*[@text='取件码 9901']")) {
        $m = [regex]::Matches($n.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
        if ($m.Count -eq 1) {   # 1 个 bounds → 1 个 Match（4 个捕获组在 Groups 里）
            $x = ([int]$m[0].Groups[1].Value + [int]$m[0].Groups[3].Value) / 2
            $y = ([int]$m[0].Groups[2].Value + [int]$m[0].Groups[4].Value) / 2
            Sh "input tap $x $y" | Out-Null
            break
        }
    }
    Wait 4
    $detail = Wait-For-Text "放置位置"
    $doorOk = ($null -ne $detail) -and (Has-Text $detail "家门口")
    $all = (Report "详情页「放置位置 = 家门口」" $doorOk) -and $all
    Sh "input keyevent KEYCODE_BACK" | Out-Null
    Wait 3
}
Screenshot "samples_list" | Out-Null

# ---------------------------------------------------------------- 校验：桌面 Widget 16 项待办
Write-Host "校验桌面 Widget…"
Sh "input keyevent KEYCODE_HOME" | Out-Null
Wait 3
$widgetTitle = ""
for ($k = 0; $k -lt 8; $k++) {
    $doc = Get-Ui
    foreach ($n in $doc.SelectNodes("//*[@resource-id='com.parcelhub:id/widget_title']")) { $widgetTitle = $n.text }
    if ($widgetTitle) { break }
    Sh "input swipe 950 1850 150 1850 300" | Out-Null   # 桌面翻页（小组件在靠后的屏）；
    # 起点必须避开 y≈1200：压在组件上会把它**拖走**、或弹出「窗口小工具」选择器（真机踩过）
    Wait 2
}
$all = (Report "Widget 标题 = 16 项待办（实际：$widgetTitle）" ($widgetTitle -eq "16 项待办")) -and $all
Screenshot "samples_widget" | Out-Null

Write-Host ""
if ($all) { Write-Host "===== 20 条样本校验全部通过 =====" -ForegroundColor Green }
else { Write-Host "===== 存在失败项，见上方 [FAIL] =====" -ForegroundColor Red; exit 1 }
