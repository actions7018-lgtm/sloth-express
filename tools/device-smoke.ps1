<#
.SYNOPSIS
    快递聚合助手（ParcelHub）真机自动化冒烟测试。

.DESCRIPTION
    覆盖 SOP 的可验收路径：启动/引导 → 首页 → 分享导入 → 快递列表 → 详情 →
    「已取件归入已签收」→ 取件地址/放置位置展示顺序 → 三个操作按钮等宽 →
    设置开关文案一致性 → 来源管理 → 采集诊断。
    所有断言基于 uiautomator 的无障碍节点（文本 + 动态坐标点击），不硬编码分辨率。

.NOTES
    PowerShell 5.1 兼容要点（都是本项目踩过的坑）：
      1) 函数返回 XmlNode 会被管道按“子节点”展开 → childless 节点返回 $null，
         因此所有返回节点/文档的地方必须用一元逗号 `return ,$x`。
      2) 命令参数位里不能直接写 -or / -eq，必须先括成表达式。
      3) 坐标必须 [int] 强转，字符串相加会变成拼接（曾 tap 到 354438,6885718）。
      4) adb 走 2>&1 时，$ErrorActionPreference="Stop" 会把 stderr 当终止错误，
         因此 Adb() 临时切到 Continue。
      5) 脚本含中文，必须存成带 BOM 的 UTF-8，否则 PS 5.1 按 GBK 读坏断言文本。

.EXAMPLE
    powershell -File tools\device-smoke.ps1
    powershell -File tools\device-smoke.ps1 -Serial 0123456789ABCDEF -SkipInstall -KeepData
#>
[CmdletBinding()]
param(
    [string]$Serial = "",
    [string]$Adb = "D:\Android\Sdk\platform-tools\adb.exe",
    [string]$Apk = "",
    [switch]$SkipInstall,
    [switch]$KeepData,
    [string]$Report = "$env:TEMP\parcelhub-smoke"
)

$ErrorActionPreference = "Stop"
$PKG = "com.parcelhub"
$LAUNCHER = "$PKG/.ui.MainActivity"
$SHARE = "$PKG/.ingest.ShareEntryActivity"

# APK 路径：AGP 9 起输出被重命名为 ParcelHub-<version>-<buildType>.apk，按通配取
if (-not $Apk) {
    $apkDir = (Resolve-Path "$PSScriptRoot\..\app\build\outputs\apk\debug").Path
    $Apk = (Get-ChildItem -Path $apkDir -Filter *.apk | Select-Object -First 1).FullName
    if (-not $Apk) { throw "找不到 debug APK：$apkDir" }
}
$GlobalArgs = @()
if ($Serial) { $GlobalArgs = @("-s", $Serial) }

# ---------------------------------------------------------------- 基础工具
function Adb([string[]]$argv) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"   # 2>&1 合流的 stderr 不能升级成终止错误
    try { return (& $Adb @GlobalArgs @argv 2>&1 | ForEach-Object { "$_" } | Out-String) }
    finally { $ErrorActionPreference = $prev }
}
function Sh([string]$cmd) { Adb @("shell", $cmd) }
function Tap([int]$x, [int]$y) { Adb @("shell", "input", "tap", "$x", "$y") | Out-Null }
function Wait([int]$sec) { Start-Sleep -Seconds $sec }
function Swipe-Up() { Sh "input swipe 540 1650 540 600 300" | Out-Null; Wait 2 }
function Swipe-Down() { Sh "input swipe 540 600 540 1650 300" | Out-Null; Wait 2 }
# 长列表里找文本：最多上滑 5 次，避免“刚好不在可视区”导致的假失败
# （来源管理 19 行 ≈ 5.3 屏，只滑 3 次根本够不到排在后面的菜鸟/顺丰，实测假失败）
# 循环改成 `i -le tries`：最后一屏上滑后还要**再查一次**，否则最后一次滑动的结果永远不检查
# $exact=$false 表示子串匹配（隐私文案这类整段 Text 节点必须用子串）
function Has-Text-Scrolled([string]$text, [int]$tries = 5, [bool]$exact = $true) {
    for ($i = 0; $i -le $tries; $i++) {
        $d = Get-Ui
        if (Has-Text $d $text $exact) { return $true }
        if ($i -lt $tries) { Swipe-Up }
    }
    return $false
}

$script:Results = New-Object System.Collections.ArrayList
function Pass([string]$name, [string]$detail = "") {
    [void]$script:Results.Add([pscustomobject]@{ pass = $true; name = $name; detail = $detail })
    Write-Host "  [PASS] $name $detail" -ForegroundColor Green
}
function Fail([string]$name, [string]$detail = "") {
    [void]$script:Results.Add([pscustomobject]@{ pass = $false; name = $name; detail = $detail })
    Write-Host "  [FAIL] $name $detail" -ForegroundColor Red
}
function Step([string]$title) { Write-Host "`n== $title" -ForegroundColor Cyan }
function Check([bool]$cond, [string]$name, [string]$detail = "") {
    if ($cond) { Pass $name $detail } else { Fail $name $detail }
}

# ---------------------------------------------------------------- UI 快照
$script:UiFile = Join-Path $Report "ui.xml"
function Get-Ui([int]$retries = 3) {
    for ($i = 0; $i -lt $retries; $i++) {
        if (Test-Path $script:UiFile) { Remove-Item $script:UiFile -Force -ErrorAction SilentlyContinue }
        # 设备端旧层级必须先删：页面切换动画中 uiautomator 会失败（could not get idle state），
        # 不删就会把上一屏的旧层级拉回来 → 断言“串屏”，本项目已踩过这个坑。
        Sh "rm -f /sdcard/ph_ui.xml" | Out-Null
        $out = Sh "uiautomator dump /sdcard/ph_ui.xml"
        if ($out -notmatch "dumped") { Wait 1; continue }   # dump 没写成，直接重试
        Adb @("pull", "/sdcard/ph_ui.xml", $script:UiFile) | Out-Null
        if (Test-Path $script:UiFile) {
            try {
                $d = [xml](Get-Content $script:UiFile -Raw -Encoding UTF8)
                return ,$d          # 一元逗号：否则 XmlDocument 会被按子节点展开
            } catch { }
        }
        Wait 1
    }
    throw "uiautomator dump 失败"
}
function All-Nodes($doc) { @($doc.SelectNodes("//node")) }
function Find-Text($doc, [string]$text, [bool]$exact = $true, [int]$index = 0) {
    $hits = @(All-Nodes $doc | Where-Object {
        $t = $_.GetAttribute("text")
        if ($exact) { $t -eq $text } else { $t -like "*$text*" }
    })
    if ($hits.Count -gt $index) { return ,$hits[$index] }  # 一元逗号：见文件头说明
    return ,$null
}
function Has-Text($doc, [string]$text, [bool]$exact = $true) { return $null -ne (Find-Text $doc $text $exact) }
function Node-Index($doc, $node) {
    $all = All-Nodes $doc
    for ($i = 0; $i -lt $all.Count; $i++) { if ([object]::ReferenceEquals($all[$i], $node)) { return $i } }
    return -1
}
function Bounds($node) {
    $m = [regex]::Match($node.GetAttribute("bounds"), "\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
    if (-not $m.Success) { return $null }
    return @([int]$m.Groups[1].Value, [int]$m.Groups[2].Value, [int]$m.Groups[3].Value, [int]$m.Groups[4].Value)
}
function Center($node) {
    $b = Bounds $node
    if (-not $b) { return $null }
    return @([int](($b[0] + $b[2]) / 2), [int](($b[1] + $b[3]) / 2))
}
function Tap-Node($node) { $c = Center $node; if ($c) { Tap $c[0] $c[1] } }
function Clickable-Of($node) {
    $p = $node
    while ($p -and $p.LocalName -eq "node") {
        if ($p.GetAttribute("clickable") -eq "true") { return ,$p }
        $p = $p.ParentNode
    }
    return ,$null
}
function Tap-Text($doc, [string]$text, [bool]$exact = $true, [int]$index = 0) {
    $n = Find-Text $doc $text $exact $index
    if (-not $n) { return $false }
    Tap-Node $n
    Wait 2
    return $true
}
function Tap-Card($doc, [string]$text) {
    $n = Find-Text $doc $text $true 0
    if (-not $n) { $n = Find-Text $doc $text $false 0 }
    if (-not $n) { return $false }
    $c = Clickable-Of $n
    if (-not $c) { return $false }
    Tap-Node $c
    Wait 2
    return $true
}
<#
  导航兜底：确保当前落在某个底部 tab 页，拿不到目标文案就「最多 4 步：能点 tab 就点 tab，
  点不到（tab 被详情/来源/诊断页藏起来）就 BACK」，每步等 2s 再重取 dump。

  为什么需要它：tab 页用 saveState/restoreState 切换，BACK 偶发会落到别的页面。
  一旦跑偏，后面所有「入口找不到 / count=0」的断言会**连锁失败**——
  实测一次跑偏连挂 20+ 条，把真正的问题淹掉。
  mustHave 用**子串**匹配，避免被 label 里的空格/斜杠写法坑到。
#>
function Go-Tab([string]$tab, [string]$mustHave) {
    $d = Get-Ui
    for ($i = 0; $i -lt 4 -and -not (Has-Text $d $mustHave $false); $i++) {
        if (Has-Text $d $tab) { Tap-Text $d $tab | Out-Null }
        else { Sh "input keyevent KEYCODE_BACK" | Out-Null }
        Wait 2
        $d = Get-Ui
    }
    return ,$d
}
function Next-Text($doc, [string]$label) {
    $n = Find-Text $doc $label $true 0
    if (-not $n) { return $null }
    $idx = Node-Index $doc $n
    $all = All-Nodes $doc
    for ($i = $idx + 1; $i -lt $all.Count; $i++) {
        $t = $all[$i].GetAttribute("text")
        if ($t -ne "") { return $t }
    }
    return $null
}
function Count-Text($doc, [string]$prefix) {
    return @(All-Nodes $doc | Where-Object { $_.GetAttribute("text") -like "$prefix*" }).Count
}
# 行内取值：Compose 的 HealthRow 是 label → 勾号图标(✓) → value 三段，
# 中间那格不能当值用，所以按“允许值集合”在随后几格里取。
function Row-Value($doc, [string]$label, [string[]]$allowed, [int]$span = 5) {
    $n = Find-Text $doc $label $true 0
    if (-not $n) { return $null }
    $idx = Node-Index $doc $n
    $all = All-Nodes $doc
    for ($i = $idx + 1; $i -lt [Math]::Min($idx + $span, $all.Count); $i++) {
        $t = $all[$i].GetAttribute("text")
        if ($allowed -contains $t) { return $t }
    }
    return $null
}
# Compose 的 Switch 不是 *Switch* 类，而是 android.view.View + checkable=true：
# 找与标签同排（中心 y 距离 < 130px）的可勾选节点。
function Row-Switch($doc, [string]$label) {
    $n = Find-Text $doc $label $true 0
    if (-not $n) { return ,$null }
    $nb = Bounds $n
    if (-not $nb) { return ,$null }
    $cy = [int](($nb[1] + $nb[3]) / 2)
    $best = $null; $bestD = [int]::MaxValue
    foreach ($x in (All-Nodes $doc)) {
        if ($x.GetAttribute("checkable") -ne "true") { continue }
        $b = Bounds $x
        if (-not $b) { continue }
        $d = [math]::Abs([int](($b[1] + $b[3]) / 2) - $cy)
        if ($d -lt $bestD) { $bestD = $d; $best = $x }
    }
    if ($best -and $bestD -lt 130) { return ,$best }
    return ,$null
}
function Screenshot([string]$tag) {
    try {
        $png = Join-Path $Report "$tag.png"
        Sh "screencap -p /sdcard/ph_shot.png" | Out-Null
        Adb @("pull", "/sdcard/ph_shot.png", $png) | Out-Null
    } catch { }
}
function Assert($cond, [string]$name, [string]$detail = "", $doc = $null, [string]$tag = "") {
    if (-not $cond -and $doc) { Screenshot "$tag-fail" }
    Check $cond $name $detail
}

# ---------------------------------------------------------------- 样本数据
# 文本内不能有 ASCII 空格（adb shell 传参会被切词）。
$Samples = @(
    @{ file = "ph_s1.txt"; text = "菜鸟驿站：您的包裹已到驿站，取件码：16-4-9626，运单号771234567890" },
    @{ file = "ph_s2.txt"; text = "您的包裹已到东门代收点，取件码2-2-7508，运单号SF1234567890123" },
    @{ file = "ph_s3.txt"; text = "圆通速递：您的快递派送中，运单号YT4482236617" },
    @{ file = "ph_s4.txt"; text = "中通快递：您的快递运输中，运单号771234567890" }
)
# 放置位置（取件地址下方）两类文案
$PlaceSamples = @(
    @{ file = "ph_s5.txt"; text = "余杭区东连街道菜鸟驿站已收到您的包裹，取件码：5-6-1122，运单号YT998877665544" },
    @{ file = "ph_s6.txt"; text = "您的快递已放在家门口，取件码9988，运单号771122334455" }
)

function Push-Sample($sample) {
    # 直接把文本作为 adb 参数传递（PowerShell 不求值、无 ASCII 空格所以不会被切词）
    Adb @("shell", "am", "start", "-a", "android.intent.action.SEND", "-t", "text/plain",
        "-n", $SHARE, "--es", "android.intent.extra.TEXT", $sample.text) | Out-Null
    Wait 3
}

# ================================================================ 主流程
New-Item -ItemType Directory -Force -Path $Report | Out-Null
Write-Host "报告目录: $Report"

Step "0. 环境与安装"
$state = Adb @("get-state")
if ($state -notmatch "device") { throw "未检测到设备（adb get-state: $state）" }
if (-not $SkipInstall) {
    $inst = Adb @("install", "-r", $Apk)
    if ($inst -notmatch "Success") { throw "安装失败: $inst" }
    Pass "安装 APK" (Split-Path $Apk -Leaf)
} else { Pass "跳过安装（-SkipInstall）" }

if (-not $KeepData) {
    Sh "pm clear $PKG" | Out-Null
    Pass "已清空应用数据（全新首启）"
} else { Pass "保留应用数据（-KeepData）" }

# Mock 数据自动注入抑制（MockDataSeeder，仅 Debug 生效的开关）：
# 冒烟断言基于空库绝对计数（空态/待取（N）等），启动前关掉 Debug 自动注入；
# 脚本末尾（写完报告后）恢复为 1。中途 throw 不会恢复——手动
# `adb shell settings put global parcelhub_mock_seed 1` 即可。
Sh "settings put global parcelhub_mock_seed 0" | Out-Null

# 通知使用权：必须走系统 cmd 接口授权。
# 实测（华为 EMUI）：直接 `settings put secure enabled_notification_listeners` 虽能立刻读回，
# 但系统随后会把未走正式授权流程的组件回收，导致首页出现权限横幅、把卡片顶出屏幕。
$LIS_COMP = "$PKG/$PKG.ingest.NotificationListener"
Sh "cmd notification allow_listener $LIS_COMP" | Out-Null
$listeners = Sh "settings get secure enabled_notification_listeners"
if ($listeners -notmatch $PKG) {
    # 兜底：个别旧 ROM 无 allow_listener 子命令时直接写 secure 设置
    $base = $listeners.Trim()
    if ([string]::IsNullOrWhiteSpace($base) -or $base -eq "null") { $base = "" }
    $sep = ""
    if ($base -ne "") { $sep = ":" }
    Sh "settings put secure enabled_notification_listeners `"$base$sep$LIS_COMP`"" | Out-Null
    $listeners = Sh "settings get secure enabled_notification_listeners"
}
$listenerGranted = $listeners -match $PKG
Check $listenerGranted "通知使用权（系统侧）" $(if ($listenerGranted) { "已授权" } else { "未授权，后续用例按实际状态判定" })

# 无障碍服务不在这里开启：实测在第 0 步 am start 会把 App 提前拉起，
# 打乱第 1 步「首次启动」与第 11/12 步的页面状态。改到第 13 步（App 已在前台时）开启。

Step "1. 首次启动与引导"
Sh "am start -n $LAUNCHER" | Out-Null
Wait 4
$doc = Get-Ui
$onbMain = Find-Text $doc "开始使用" $true 0
if (-not $onbMain) { $onbMain = Find-Text $doc "先跳过，稍后再开" $true 0 }
if ($onbMain) {
    Tap-Node $onbMain
    Wait 3
    $doc = Get-Ui
    Pass "首启进入权限引导并完成跳转"
} else {
    Pass "首启未拦截（通知使用权已就绪，直接进入首页）"
}
Assert (Has-Text $doc "今日概况") "首页可见「今日概况」" "" $doc "home"

Step "2. 首页空态"
$doc = Get-Ui
Assert (Has-Text $doc "暂无待取包裹") "首页空态提示：暂无待取包裹" "" $doc "home-empty"
Assert (Has-Text $doc "来源管理") "首页入口：来源管理" "" $doc "home-empty"
Assert (Has-Text $doc "采集诊断") "首页入口：采集诊断" "" $doc "home-empty"

Step "3. 注入 4 条真实文案样本（分享导入链路）"
foreach ($s in $Samples) { Push-Sample $s }
Pass "已注入 4 条样本（到站 / 代收点 / 派送中 / 运输中）"

Step "4. 快递列表页展示"
Tap-Text $doc "快递" | Out-Null
$doc = Get-Ui
Assert (Has-Text $doc "全部包裹") "进入全部包裹列表" "" $doc "list"
$shipCount = Count-Text $doc "单号 "
Assert ($shipCount -eq 3) "列表出现 3 个包裹（按运单号去重）" "count=$shipCount" $doc "list"
Assert (Has-Text $doc "取件码 16-4-9626") "列表显示取件码" "" $doc "list"
Assert (Has-Text $doc "取件地址 菜鸟驿站") "列表取件码下方显示取件地址（菜鸟驿站）" "" $doc "list"
Assert (Has-Text $doc "取件地址 东门代收点") "列表显示代收点地址" "" $doc "list"
Assert (Has-Text $doc "已到站") "到站状态识别" "" $doc "list"
Assert (Has-Text $doc "派送中") "派送中状态识别" "" $doc "list"

# 切筛选后必须等一拍：列表由 Room Flow 异步刷新，不等待会读到上一屏（实测偶发假失败）
Tap-Text $doc "待取" | Out-Null
Wait 2
$doc = Get-Ui
$c1 = Count-Text $doc "单号 "
Assert ($c1 -eq 2) "筛选「待取」= 2 件" "count=$c1" $doc "list-pickup"
Tap-Text $doc "运输中" | Out-Null
Wait 2
$doc = Get-Ui
$c2 = Count-Text $doc "单号 "
Assert ($c2 -eq 1) "筛选「运输中」= 1 件（派送中归入）" "count=$c2" $doc "list-transit"
Tap-Text $doc "已签收" | Out-Null
Wait 2
$doc = Get-Ui
Assert (Has-Text $doc "暂无包裹") "初始「已签收」为空" "" $doc "list-done"

Step "5. 首页分组与展示顺序"
Tap-Text $doc "首页" | Out-Null
Swipe-Down
Swipe-Down   # 首页滚动位置跨 Tab 会被保留，先回到顶部
$doc = Get-Ui
Assert (Has-Text $doc "待取（2）") "首页分组：待取（2）" "" $doc "home-groups"
$codeNode = Find-Text $doc "取件码" $true 0
$addrNode = Find-Text $doc "取件地址" $true 0
$ci = if ($codeNode) { Node-Index $doc $codeNode } else { -1 }
$ai = if ($addrNode) { Node-Index $doc $addrNode } else { -1 }
Assert ($ci -ge 0 -and $ai -gt $ci) "首页取件码下方显示取件地址（顺序：取件码 → 取件地址）" "code@$ci addr@$ai" $doc "home-order"
$addrVal = Next-Text $doc "取件地址"
Assert (@("菜鸟驿站", "东门代收点") -contains $addrVal) "首页显示取件地址值" "value=$addrVal" $doc "home-order"
Assert (Has-Text-Scrolled "派送中（1）") "首页分组：派送中（1）" "" $doc "home-groups"
Assert (Has-Text-Scrolled "运输中（0）") "首页分组：运输中（0）（补推「运输中」不倒退）" "" $doc "home-groups"

Step "6. 状态不倒退（旧/低优先级通知不覆盖已到站）"
Push-Sample $Samples[3]
Tap-Text (Get-Ui) "快递" | Out-Null
$doc = Get-Ui
$ztoLine = Find-Text $doc "中通快递" $true 0
$statusIdx = Node-Index $doc $ztoLine
$all = All-Nodes $doc
$statusText = ""
for ($i = $statusIdx + 1; $i -lt [Math]::Min($statusIdx + 5, $all.Count); $i++) {
    $t = $all[$i].GetAttribute("text")
    if ($t -like "已到站*" -or $t -eq "运输中") { $statusText = $t; break }
}
Check ($statusText -like "已到站*") "中通状态未被「运输中」回退" "status=$statusText"
Assert (Has-Text $doc "单号 771234567890" $false) "运单号 771234567890 已展示" "" $doc "list"

Step "7. 点击「已取件」→ 归入「已签收」"
if (-not (Tap-Card (Get-Ui) "中通快递")) { Fail "打开中通详情页" "未找到卡片" }
$doc = Get-Ui
Assert (Has-Text $doc "用户状态") "详情页展示用户状态" "" $doc "detail"
Assert (Has-Text $doc "未处理") "初始用户状态=未处理" "" $doc "detail"
Assert (Has-Text $doc "菜鸟驿站") "详情页展示取件地址" "" $doc "detail"
# 详情页的 label / value 是两个节点（列表页才是合并的一行）
Assert ((Has-Text $doc "运单号") -and (Has-Text $doc "771234567890")) "详情页展示运单号" "" $doc "detail"
if (-not (Tap-Text $doc "标记已取件")) { Fail "点击「标记已取件」" "按钮未找到" }
Wait 2
$doc = Get-Ui
$doneNodes = @(All-Nodes $doc | Where-Object { $_.GetAttribute("text") -eq "已取件" }).Count
Assert ($doneNodes -ge 1) "用户状态已更新为「已取件」" "count=$doneNodes" $doc "detail-picked"

# 返回列表，验证归组（BACK 偶发不落在列表页 → Go-Tab 兜底，避免后面连锁失败）
Sh "input keyevent KEYCODE_BACK" | Out-Null
Wait 2
$doc = Go-Tab "快递" "全部包裹"
Assert (Has-Text $doc "全部包裹" $false) "BACK 后回到列表页" "" $doc "list-after-pick"
if (-not (Tap-Text $doc "待取")) { Fail "切换到「待取」筛选" }
Wait 2
$doc = Get-Ui
$pickupCount = Count-Text $doc "单号 "
Assert ($pickupCount -eq 1) "已取件后「待取」只剩 1 件" "count=$pickupCount" $doc "list-after-pick"
Assert (-not (Has-Text $doc "取件码 16-4-9626")) "中通已从「待取」移出" "" $doc "list-after-pick"
if (-not (Tap-Text $doc "已签收")) { Fail "切换到「已签收」筛选" }
Wait 2
$doc = Get-Ui
$doneListCount = Count-Text $doc "单号 "
Assert ($doneListCount -eq 1) "已取件后「已签收」= 1 件" "count=$doneListCount" $doc "list-after-pick"
Assert (Has-Text $doc "中通快递") "中通出现在「已签收」分组" "" $doc "list-after-pick"
Assert (Has-Text $doc "已取件") "列表卡片显示「已取件」标记" "" $doc "list-after-pick"

# 首页联动（先回到顶部，否则分组标题不在可视区）
Tap-Text $doc "首页" | Out-Null
Swipe-Down
Swipe-Down
$doc = Get-Ui
Assert (Has-Text $doc "待取（1）") "首页联动：待取（1）" "" $doc "home-after-pick"

Step "8. 设置页开关与文案一致性"
# Go-Tab：上一步若跑偏（BACK/点 tab 落到别的页），这里先纠偏再断言
$doc = Go-Tab "设置" "取件码提醒"
# 「自动采集」已按自动检测 SOP 改造为「运行检测」：总状态 + 七模块行（可展开证据）
Assert (Has-Text $doc "运行检测") "设置页：运行检测分区" "" $doc "settings"
Assert (Has-Text $doc "通知监听") "设置页：运行检测模块行（通知监听）" "" $doc "settings"
Assert (Has-Text $doc "重新检测") "设置页：重新检测按钮" "" $doc "settings"

$fixed = Row-Switch $doc "到站 / 取件码提醒"
$alwaysOn = Has-Text $doc "始终开启"
Assert (($null -eq $fixed) -and $alwaysOn) "「到站/取件码提醒」不渲染开关，显示「始终开启」" "switch=$(if ($fixed) { 'yes' } else { 'none' }) alwaysOn=$alwaysOn" $doc "settings"

$ofd = Row-Switch $doc "派送中提醒"
$ofdChecked = if ($ofd) { $ofd.GetAttribute("checked") } else { "n/a" }
Assert ($ofdChecked -eq "true") "「派送中提醒」默认开启（与文案一致）" "checked=$ofdChecked" $doc "settings"

$tr = Row-Switch $doc "运输中提醒"
$trChecked = if ($tr) { $tr.GetAttribute("checked") } else { "n/a" }
Assert ($trChecked -eq "false") "「运输中提醒」默认关闭（与「默认关闭」文案一致）" "checked=$trChecked" $doc "settings"

Swipe-Up
$doc = Get-Ui
# 灵动岛设置区块（SOP §11/§13：总开关默认开、悬浮显示默认关）
for ($i = 0; $i -lt 3 -and -not (Has-Text $doc "快递灵动岛"); $i++) {
    Swipe-Up
    $doc = Get-Ui
}
$islSection = Has-Text $doc "快递灵动岛"
$isl = Row-Switch $doc "快递灵动岛"
$islChecked = if ($isl) { $isl.GetAttribute("checked") } else { "n/a" }
Assert ($islSection -and $islChecked -eq "true") "设置页：「灵动岛」区块存在且默认开启" "section=$islSection checked=$islChecked" $doc "settings"
$ovl = Row-Switch $doc "悬浮显示"
$ovlChecked = if ($ovl) { $ovl.GetAttribute("checked") } else { "n/a" }
Assert ($ovlChecked -eq "false") "设置页：「悬浮显示」默认关闭（需用户主动开启）" "checked=$ovlChecked" $doc "settings"
# 灵动岛区块让设置页变长：滚到底部再断言隐私/版本（最多补滚 3 次）
for ($i = 0; $i -lt 3 -and -not (Has-Text $doc "隐私说明"); $i++) {
    Swipe-Up
    $doc = Get-Ui
}
Assert (Has-Text $doc "隐私说明") "设置页：隐私说明" "" $doc "settings"
Assert (Has-Text-Scrolled "仅处理与快递相关的信息" 3 $false) "设置页隐私文案：仅处理与快递相关的信息" "" $doc "settings"
Assert ((-not (Has-Text $doc "不发起任何网络请求" $false)) -and (-not (Has-Text $doc "不读取短信" $false))) "设置页隐私文案只留快递范围（去掉零网络/不读取短信等旧口径）" "" $doc "settings"
Assert (Has-Text $doc "解析规则版本" $false) "设置页：解析规则版本" "" $doc "settings"

Step "8b. 权限引导页（设置 → 重新运行权限引导）"
$doc = Go-Tab "设置" "取件码提醒"
# 「重新运行权限引导」在设置页里的位置会随分区增减而漂移，固定上滑两次会踩空
# （2026-09-28 实测 FAIL=「设置页入口未找到」）→ 改成双向找：先往顶（上滑）再往底（下滑），
# 各最多 5 次，且**每次滑完都重新 dump 再查**（同 Has-Text-Scrolled 的假失败加固）。
for ($i = 0; $i -le 5 -and -not (Has-Text $doc "重新运行权限引导"); $i++) {
    if ($i -lt 5) { Swipe-Down }
    $doc = Get-Ui
}
for ($i = 0; $i -le 5 -and -not (Has-Text $doc "重新运行权限引导"); $i++) {
    if ($i -lt 5) { Swipe-Up }
    $doc = Get-Ui
}
if (Tap-Text $doc "重新运行权限引导") {
    $doc = Get-Ui
    Assert (Has-Text-Scrolled "开启通知使用权（必需）") "引导页渲染：通知使用权说明" "" $doc "onboarding"
    Assert (Has-Text-Scrolled "隐私承诺") "引导页渲染：隐私承诺" "" $doc "onboarding"
    Assert (Has-Text-Scrolled "仅处理与快递相关的信息" 3 $false) "引导页隐私文案：仅处理与快递相关的信息" "" $doc "onboarding"
    $doc = Get-Ui
    # 隐私口径：不再承诺“零网络”（物流查询将实联网，见 EXPRESS_SMART_QUERY SOP）
    Assert (-not (Has-Text $doc "零网络" $false)) "引导页隐私文案已去掉「零网络」" "" $doc "onboarding"
    $doc = Get-Ui
    $go = Find-Text $doc "开始使用" $true 0
    if (-not $go) { $go = Find-Text $doc "先跳过，稍后再开" $true 0 }
    if (-not $go) {
        Swipe-Up
        $doc = Get-Ui
        $go = Find-Text $doc "开始使用" $true 0
        if (-not $go) { $go = Find-Text $doc "先跳过，稍后再开" $true 0 }
    }
    Assert ($null -ne $go) "引导页主按钮可见" "" $doc "onboarding"
    if ($go) { Tap-Node $go; Wait 3 }
    $doc = Get-Ui
    Assert (Has-Text $doc "今日概况") "「开始使用」返回首页" "" $doc "onboarding"
    Tap-Text $doc "设置" | Out-Null
    Wait 2
    $doc = Get-Ui
} else {
    Fail "打开权限引导页" "设置页入口未找到"
    Screenshot "onboarding-fail"
}

Step "9. 来源管理"
$doc = Go-Tab "设置" "取件码提醒"
if (-not (Tap-Text $doc "进入")) {
    Swipe-Up
    Tap-Text (Get-Ui) "进入" | Out-Null
}
$doc = Get-Ui
Assert (Has-Text $doc "来源管理") "进入来源管理" "" $doc "sources"
Assert (Has-Text $doc "中通快递") "来源管理：中通快递" "" $doc "sources"
# 短信来源（快递短信 SOP §6）：行存在 + **默认开**（这是该功能的硬要求）。
# 行按 app_name 自然排序落在申通与菜鸟之间，所以它同时会把菜鸟往后推一屏——
# 下面两条断言的滚动重试必须够（见 Has-Text-Scrolled 注释）。
Assert (Has-Text-Scrolled "短信") "来源管理：短信" "" $null "sources"
# Row-Switch 必须用**当前屏**的 dump：Has-Text-Scrolled 内部自己 dump+上滑，
# 外面那个 $doc 还停在进页面时的首屏（短信行不在首屏）→ 直接拿来找开关必然 null。
$docSms = Get-Ui
$smsSwitch = Row-Switch $docSms "短信"
$smsOn = $false
if ($smsSwitch) { $smsOn = $smsSwitch.GetAttribute("checked") -eq "true" }
Assert $smsOn "来源管理：短信开关默认开" "" $docSms "sources"
Assert (Has-Text-Scrolled "菜鸟") "来源管理：菜鸟" "" $doc "sources"
Assert (Has-Text-Scrolled "顺丰速运") "来源管理：顺丰速运" "" $doc "sources"
Sh "input keyevent KEYCODE_BACK" | Out-Null
Wait 2

Step "10. 采集诊断"
# 来源页返回的是设置页（可能停在滚动位置），先纠偏到设置页，回顶部再取一次最新 dump
$doc = Go-Tab "设置" "取件码提醒"
Swipe-Down
$doc = Get-Ui
if (-not (Tap-Text $doc "查看诊断" $true)) { Fail "打开采集诊断" "设置页入口未找到" } else { Wait 2 }
$doc = Get-Ui
Assert (Has-Text $doc "自动采集状态") "诊断页标题" "" $doc "diagnostics"
Assert (Has-Text $doc "监听服务") "诊断：监听服务状态" "" $doc "diagnostics"
$laVal = Row-Value $doc "通知使用权" @("已开启", "未开启")
Assert ($laVal -eq "已开启") "诊断：通知使用权 = 已开启" "value=$laVal" $doc "diagnostics"
$lsVal = Row-Value $doc "监听服务" @("运行中", "未连接")
Assert ($lsVal -eq "运行中") "诊断：监听服务 = 运行中" "value=$lsVal" $doc "diagnostics"
$pkgCount = Next-Text $doc "包裹数"
Assert ($pkgCount -eq "3") "诊断：包裹数 = 3" "value=$pkgCount" $doc "diagnostics"
$evtCount = Next-Text $doc "事件数"
$evtOk = $false
try { $evtOk = ([int]$evtCount -ge 4) } catch { $evtOk = $false }
Assert $evtOk "诊断：事件数 ≥ 4" "value=$evtCount" $doc "diagnostics"
$ruleVer = Next-Text $doc "解析规则版本"
Assert ($ruleVer -like "v*") "诊断：规则版本可见" "value=$ruleVer" $doc "diagnostics"

Step "11. 回到首页复核"
# 返回栈深度不固定：第 9 步「来源管理」会把栈加深，实测诊断 → 首页需要 2~3 跳。
# 早期写法固定只按 2 次 BACK，栈变深时会误判；改为有界循环 + 兜底（点底部导航 / 重新拉起）
for ($i = 0; $i -lt 4; $i++) {
    $doc = Get-Ui
    if (Has-Text $doc "今日概况") { break }
    Sh "input keyevent KEYCODE_BACK" | Out-Null
    Wait 2
}
$doc = Get-Ui
if (-not (Has-Text $doc "今日概况")) { Tap-Text $doc "首页" | Out-Null; Wait 2 }
$doc = Get-Ui
if (-not (Has-Text $doc "今日概况")) { Sh "am start -n $LAUNCHER" | Out-Null; Wait 3 }
Swipe-Down
Swipe-Down
$doc = Get-Ui
Assert (Has-Text $doc "今日概况") "返回首页" "" $doc "final"

Step "12. 放置位置（取件地址下一行）与按钮等宽"
foreach ($p in $PlaceSamples) { Push-Sample $p }
# 权限横幅（监听权限在运行中被系统回收时出现）会把第 3 张卡片顶出可视区，
# 而首页横幅状态只在 onResume 刷新，因此这里重新授权 + 冷启动首页后再断言。
Sh "cmd notification allow_listener $LIS_COMP" | Out-Null
Sh "am force-stop $PKG" | Out-Null
Sh "am start -n $LAUNCHER" | Out-Null
Wait 5
Tap-Text (Get-Ui) "首页" | Out-Null
Wait 1
# 冷启动后停在顶部；自动填单号横幅占掉一屏高度，单次 dump 看不全两张卡片。
# 取顶部 + 上滑一次两次 dump，按 bounds 去重后计数（重叠不 double-count）。
$docTop = Get-Ui
Swipe-Up
$docLow = Get-Ui
$placeBounds = @()
foreach ($d in @($docTop, $docLow)) {
    foreach ($n in (All-Nodes $d | Where-Object { $_.GetAttribute("text") -eq "放置位置" })) {
        $b = $n.GetAttribute("bounds")
        if ($placeBounds -notcontains $b) { $placeBounds += $b }
    }
}
$placeCount = $placeBounds.Count
# 后续断言用含完整站点卡片的那一屏（站点卡有取件地址/放置位置/取件码/按钮）
if (Has-Text $docLow "余杭区东连街道菜鸟驿站") { $doc = $docLow } else { $doc = $docTop }
Assert ($placeCount -ge 1) "首页「放置位置」行（站点样本可见）" "count=$placeCount" $doc "home-place"
# 口径：家门口投递不计入待取（needsPickup 排除 DOOR，首页/列表/Widget 三处同口径），
# 所以家门口样本**不该**出现在首页；它改到详情页断言（见本步末尾）。
# 用子串匹配：卡片上的值是整行「家门口」，别被同名标签/前缀骗过。
Assert (-not (Has-Text $docTop "家门口" $false) -and -not (Has-Text $docLow "家门口" $false)) `
    "家门口投递不进首页待取（口径）" "absent" $doc "home-place-door"
Swipe-Down
Swipe-Down
Swipe-Down
$docA = Get-Ui
Assert (-not (Has-Text $docA "家门口" $false)) "回顶后首页仍无家门口卡" "absent" $docA "home-place-door"
# 站点样本卡：从顶部开始**有界上滑查找**（固定次数会被横幅顶下去 / 或一屏就滑过头）
$docB = $docA
for ($i = 0; $i -lt 3 -and -not (Has-Text $docB "余杭区东连街道菜鸟驿站"); $i++) {
    Swipe-Up
    $docB = Get-Ui
}
Assert (Has-Text $docB "余杭区东连街道菜鸟驿站") "放置位置显示完整站点地址" $docB "home-place"
# 回顶部取第一张卡（站点卡：取件地址/放置位置/取件码/按钮齐全）做行内断言
Swipe-Down
Swipe-Down
$doc = Get-Ui

$aNode = Find-Text $doc "取件地址" $true 0
$pNode = Find-Text $doc "放置位置" $true 0
$ai2 = if ($aNode) { Node-Index $doc $aNode } else { -1 }
$pi2 = if ($pNode) { Node-Index $doc $pNode } else { -1 }
Assert ($ai2 -ge 0 -and $pi2 -gt $ai2) "顺序：取件地址 → 放置位置（下一行）" "addr@$ai2 place@$pi2" $doc "home-place"

# 取件码标签与数字必须垂直居中（用户需求：不要底对齐）
$lbl = Find-Text $doc "取件码" $true 0
$lblIdx = if ($lbl) { Node-Index $doc $lbl } else { -1 }
$all2 = All-Nodes $doc
$codeVal = $null
for ($i = $lblIdx + 1; $i -lt [Math]::Min($lblIdx + 4, $all2.Count); $i++) {
    $t = $all2[$i].GetAttribute("text")
    if ($t -ne "" -and $t -ne "取件码") { $codeVal = $all2[$i]; break }
}
if ($lblIdx -ge 0 -and $codeVal) {
    $lb = Bounds $lbl; $cb = Bounds $codeVal
    $lc = [int](($lb[1] + $lb[3]) / 2); $cc = [int](($cb[1] + $cb[3]) / 2)
    Assert ([math]::Abs($lc - $cc) -le 4) "首页取件码标签与数字垂直居中" "label=$lc code=$cc delta=$([math]::Abs($lc-$cc))" $doc "home-code-align"
} else {
    Fail "首页取件码标签与数字垂直居中" "label@$lblIdx 未取到数值节点"
}

# 三个操作按钮等宽（用户需求：复制取件码大小与旁边两个一致）
$copyText = Find-Text $doc "复制取件码" $true 0
if ($copyText) {
    $cy = (Center $copyText)[1]
    $w = @()
    foreach ($x in (All-Nodes $doc)) {
        if ($x.GetAttribute("class") -notlike "*Button*") { continue }
        $c = Center $x
        if (-not $c) { continue }
        if ([math]::Abs($c[1] - $cy) -le 60) { $b = Bounds $x; $w += ($b[2] - $b[0]) }
    }
    $uniq = @($w | Select-Object -Unique)
    Assert (($w.Count -ge 3) -and ($uniq.Count -eq 1)) "首页三个操作按钮等宽" "widths=$($w -join ',')" $doc "home-buttons"
} else {
    Fail "首页三个操作按钮等宽" "未找到「复制取件码」"
    Screenshot "home-buttons-fail"
}
$copyNode = Find-Text $doc "复制取件码" $true 0
Assert ($null -ne $copyNode) "「复制取件码」文案完整展示（未折行/截断）" "" $doc "home-buttons"

# ---- 12b. 家门口样本：首页不展示（口径），详情页必须能看到「放置位置 家门口」 ----
Tap-Text (Get-Ui) "快递" | Out-Null
Wait 1
$docList = Get-Ui
for ($i = 0; $i -lt 3 -and -not (Has-Text $docList "771122334455" $false); $i++) {
    Swipe-Up
    $docList = Get-Ui
}
if (Tap-Text $docList "771122334455" $false) {
    $docDoor = Get-Ui
    Assert (Has-Text $docDoor "放置位置") "家门口样本详情页有「放置位置」行" "" $docDoor "door-place"
    Assert (Has-Text $docDoor "家门口") "放置位置显示门口投放" $docDoor "door-place"
} else {
    Fail "打开家门口样本详情" "列表里没找到单号 771122334455"
    Screenshot "door-place-fail"
}
# 第 13 步要写无障碍开关，要求 App 停在首页
Sh "input keyevent KEYCODE_BACK" | Out-Null
Wait 1
Tap-Text (Get-Ui) "首页" | Out-Null
Wait 1

Step "13. 自动查询（SOP T03/T06）"
# 无障碍服务（自动填单号）：重装 APK 后系统会把本服务移出 enabled 列表（Android 标准行为），
# 且荣耀/华为 ROM 只认“App 已在前台”时写入的开关——App 不在前台写会被数秒内回收成 null。
# 因此放在第 13 步（此时 App 正停在首页）开启，组件名用系统写回的缩写形式。
$A11Y_COMP = "$PKG/.autoquery.cainiao.CainiaoAccessibilityService"
Sh "settings put secure enabled_accessibility_services $A11Y_COMP" | Out-Null
Sh "settings put secure accessibility_enabled 1" | Out-Null
Wait 5
$a11yBound = (Sh "dumpsys accessibility") -match "CainiaoAccessibilityService"
Check $a11yBound "无障碍服务（系统侧已开启并绑定）" $(if ($a11yBound) { "Bound services 含本服务" } else { "未绑定" })

# 推一条“派送中、无取件码”样本 → 应建查询任务 + 发 auto_query 通知
Sh "logcat -c" | Out-Null
Push-Sample @{ file = "ph_aq.txt"; text = "圆通速递：您的快递派送中，运单号YT9900112233" }
Wait 8
$aqLog = Sh "logcat -d" | Select-String -Pattern "\[QUERY_TASK\] created" | Select-Object -First 3
Assert ($null -ne $aqLog) "自动查询任务已创建（T03）" "" $null "autoquery"
$aqNotif = Sh "dumpsys notification" | Select-String -Pattern "channel=auto_query" | Select-Object -First 2
Assert ($null -ne $aqNotif) "自动查询入口通知已发出" "" $null "autoquery"
# 设置页新增「自动查询」分区（设置页会保留上次滚动位置，先回顶部）
Tap-Text (Get-Ui) "设置" | Out-Null
Wait 2
Swipe-Down
Swipe-Down
$doc = Get-Ui
Assert (Has-Text $doc "自动查询") "设置页：自动查询分区" $doc "autoquery-settings"
Assert (Has-Text $doc "自动填单号（无障碍）") "设置页：自动填单号状态行（T06）" $doc "autoquery-settings"
# 诊断页新增任务计数（「查看诊断」按钮在顶部监听服务行，顶部 dump 里才有）
if (-not (Tap-Text $doc "查看诊断" $true)) { Fail "打开采集诊断（自动查询）" "设置页入口未找到" } else { Wait 2 }
# 诊断页新增任务计数（统计卡在首屏外，有界下滑找）
$taskCount = $null
for ($i = 0; $i -lt 3 -and [string]::IsNullOrEmpty($taskCount); $i++) {
    $taskCount = Next-Text (Get-Ui) "查询任务数"
    if ([string]::IsNullOrEmpty($taskCount)) { Swipe-Up }
}
$taskOk = $false
try { $taskOk = ([int]$taskCount -ge 1) } catch { $taskOk = $false }
Assert $taskOk "诊断：查询任务数 ≥ 1" "value=$taskCount" $null "autoquery-diag"
Assert (Has-Text-Scrolled "自动查询") "诊断：自动查询状态行" "" $null "autoquery-diag"
# 无障碍真实状态（T06）：只断言「状态行存在」会漏掉“服务没被系统收录”这类硬故障
$a11yVal = ""
for ($i = 0; $i -lt 3; $i++) {
    $a11yVal = Row-Value (Get-Ui) "自动填单号" @("已开启", "未开启")
    if ($a11yVal) { break }
    Swipe-Up
}
Assert ($a11yVal -eq "已开启") "诊断：无障碍服务 = 已开启（系统收录并绑定）" "value=$a11yVal" $null "autoquery-diag"

# ================================================================ 汇总
$pass = @($script:Results | Where-Object { $_.pass }).Count
$fail = @($script:Results | Where-Object { -not $_.pass }).Count
$lines = @()
$lines += "# 快递聚合助手 真机冒烟报告"
$lines += "时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$lines += "设备: $(Adb @('shell', 'getprop', 'ro.product.model'))  序列号: $Serial"
$lines += "结果: PASS=$pass FAIL=$fail"
$lines += ""
$script:Results | ForEach-Object {
    $flag = if ($_.pass) { "PASS" } else { "FAIL" }
    $lines += "- [$flag] $($_.name) $($_.detail)"
}
$reportFile = Join-Path $Report "report.md"
[System.IO.File]::WriteAllLines($reportFile, $lines, (New-Object System.Text.UTF8Encoding($false)))
# 恢复 Debug 启动自动注入 Mock 数据（冒烟期间是关闭的，见第 0 步）
Sh "settings put global parcelhub_mock_seed 1" | Out-Null
Write-Host "`n===== 冒烟结果: PASS=$pass FAIL=$fail =====" -ForegroundColor $(if ($fail -eq 0) { "Green" } else { "Red" })
Write-Host "报告: $reportFile"
if ($fail -gt 0) {
    $script:Results | Where-Object { -not $_.pass } | ForEach-Object {
        Write-Host "  FAIL: $($_.name) $($_.detail)" -ForegroundColor Red
    }
}
exit $fail
