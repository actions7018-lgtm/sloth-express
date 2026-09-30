/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.ui.settings

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.parcelhub.App
import com.parcelhub.BuildConfig
import com.parcelhub.ProjectLinks
import com.parcelhub.health.HealthAction
import com.parcelhub.health.HealthCollector
import com.parcelhub.health.HealthModule
import com.parcelhub.health.HealthStatus
import com.parcelhub.health.ModuleHealth
import com.parcelhub.notification.AppNotificationManager
import com.parcelhub.ocr.OcrConsentActivity
import com.parcelhub.ocr.ScreenCaptureService
import com.parcelhub.pending.PendingFocus
import com.parcelhub.sms.SmsPermissionManager
import com.parcelhub.util.PermissionUtil
import com.parcelhub.util.TimeUtil
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页（P5）。
 * 放运行检测（自动检测 SOP）、待补全手动识别（需求 §八 手动入口）、提醒、来源、
 * 诊断入口与隐私说明。
 */
@Composable
fun SettingsScreen(
    onOpenSources: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenOnboarding: () -> Unit,
) {
    val context = LocalContext.current
    val notifier = App.graph.notifier
    val sourceSettings = App.graph.sourceSettings
    val scope = rememberCoroutineScope()

    // 自动检测（检测 SOP §十六）：报告由 HealthCollector 缓存，进页/回前台立即刷新
    val healthReport by HealthCollector.report.collectAsState()
    var expandedModule by remember { mutableStateOf<HealthModule?>(null) }

    // 检测 §十八：核心服务连接/断开 → 立即重新检测（冷启动时采集可能早于服务连上，靠这里纠偏）
    val listenerSnapshot = App.graph.health.snapshot.collectAsState()
    LaunchedEffect(listenerSnapshot.value.listenerBound) {
        runCatching { HealthCollector.refresh(context) }
    }

    var postEnabled by remember { mutableStateOf(true) }
    var outForDelivery by remember { mutableStateOf(notifier.outForDeliveryEnabled) }
    var transit by remember { mutableStateOf(notifier.transitEnabled) }
    var autoQueryOn by remember { mutableStateOf(App.graph.autoQueryEnabled) }
    var autoReadOn by remember { mutableStateOf(App.graph.autoReadEnabled) }
    var ocrOn by remember { mutableStateOf(App.graph.ocrFallbackEnabled) }
    // SOP V2.0 §35 识别运行指标（时间戳 + 计数，进程重启后仍保留）
    val recognitionMetrics by App.graph.recognitionMetrics.snapshot.collectAsState()
    var a11yEnabled by remember { mutableStateOf(false) }

    // 快递灵动岛（SOP §11 双模式）：通知模式默认开；悬浮模式需用户主动授权
    var islandOn by remember { mutableStateOf(App.graph.island.enabled) }
    var overlayOn by remember { mutableStateOf(App.graph.island.overlayEnabled) }
    var overlayAllowed by remember { mutableStateOf(PermissionUtil.canDrawOverlays(context)) }
    var overlayPending by remember { mutableStateOf(false) }
    var overlayHint by remember { mutableStateOf(false) }
    val genericEnabled by sourceSettings.genericEnabled.collectAsState()

    LaunchedEffect(Unit) {
        postEnabled = PermissionUtil.hasPostNotifications(context)
        a11yEnabled = PermissionUtil.isAccessibilityEnabled(context)
    }

    // 从系统悬浮窗授权页返回时：权限到手就把刚才那次开启补上（SOP §13 引导授权）
    LifecycleResumeEffect(Unit) {
        // 真机踩坑（OPPO，2026-09-29）：无障碍/通知状态只在首组合查一次（LaunchedEffect），
        // 「去开启 → 系统里打开 → 返回」后状态行永远停在 ✗ 旧值——
        // resume 必须重查，与首页 / 引导页口径一致。
        postEnabled = PermissionUtil.hasPostNotifications(context)
        a11yEnabled = PermissionUtil.isAccessibilityEnabled(context)
        overlayAllowed = PermissionUtil.canDrawOverlays(context)
        // OCR 开关：从系统录屏授权页（API 29）返回后按真实结果纠偏
        ocrOn = App.graph.ocrFallbackEnabled
        if (overlayPending && overlayAllowed) {
            App.graph.island.overlayEnabled = true
            overlayOn = true
            overlayPending = false
            overlayHint = false
        }
        // 检测 §十八：回到设置页立即刷新一次（权限可能刚在系统设置里改过）
        scope.launch { runCatching { HealthCollector.refresh(context) } }
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---------- 运行检测（自动检测 SOP：查“有没有条件正常工作 + 最近有没有真正成功运行过”，不是查开关） ----------
        SectionTitle("运行检测")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val report = healthReport
                val now = System.currentTimeMillis()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = (report?.overall?.symbol ?: "?") + " " +
                            (report?.overall?.overallLabel ?: "尚未检测"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor(report?.overall),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (report == null) {
                                "上次检测：—"
                            } else {
                                "上次检测：${TimeUtil.relative(now, report.checkedAt)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = {
                                scope.launch { runCatching { HealthCollector.refresh(context) } }
                            },
                        ) {
                            Text("重新检测")
                        }
                    }
                }

                if (report == null) {
                    Text(
                        text = "正在检测…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    report.modules.forEach { module ->
                        HealthRow(
                            module = module,
                            expanded = expandedModule == module.module,
                            onToggle = {
                                expandedModule =
                                    if (expandedModule == module.module) null else module.module
                            },
                            onAction = { executeHealthAction(it, context) },
                        )
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                StatusRow(
                    label = "App 通知权限（仅提醒用）",
                    ok = postEnabled,
                    actionText = if (postEnabled) null else "去开启",
                    onAction = { PermissionUtil.openAppNotificationSettings(context) },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenOnboarding,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("重新运行权限引导")
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }

        // ---------- 自动查询 ----------
        SectionTitle("自动查询")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                ToggleRow(
                    title = "自动查询",
                    description = "驿站无取件码、只有单号时，推送“打开菜鸟并填单号”入口（默认开启）",
                    checked = autoQueryOn,
                    onCheckedChange = { value ->
                        autoQueryOn = value
                        App.graph.autoQueryEnabled = value
                    },
                )
                StatusRow(
                    label = "自动填单号（无障碍）",
                    ok = a11yEnabled,
                    actionText = if (a11yEnabled) null else "去开启",
                    onAction = { PermissionUtil.openAccessibilitySettings(context) },
                )
                Text(
                    text = "只在菜鸟查快递页填入已识别的单号，不点查询、不读其他应用内容，需在系统设置中手动开启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------- 待补全 · 手动识别（需求 §八 手动入口：无通知时的人工触发通路） ----------
        SectionTitle("待补全 · 自动 / 手动识别")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                ToggleRow(
                    title = "自动识别订单页（全自动）",
                    description = "打开来源购物 App 或有待补全单的 App 时，自动读取当前页面文本识别快递单号：读到单号即建单 / 补全，" +
                        "读不到（无物流信息）零副作用。关闭后仅手动按钮与详情页「去对应 App 查看」可触发。",
                    checked = autoReadOn,
                    onCheckedChange = { value ->
                        autoReadOn = value
                        App.graph.autoReadEnabled = value
                    },
                )
                Text(
                    text = "手动触发：选一个购物 App 拉起（10 分钟内有效），用于自动开关关闭时或想立即指定 App 识别的场景。" +
                        "读不到时把页面往下滑，让「快递单号」整行露出再等几秒。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                var menuOpen by remember { mutableStateOf(false) }
                val sources by remember { App.graph.repository.observeSources() }
                    .collectAsState(initial = emptyList())
                val installed = remember(sources) {
                    sources.filter { s ->
                        runCatching {
                            context.packageManager.getLaunchIntentForPackage(s.packageName) != null
                        }.getOrDefault(false)
                    }
                }
                Box {
                    OutlinedButton(onClick = { menuOpen = true }) {
                        Text("手动识别订单页")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (installed.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("未检测到已安装的购物 App") },
                                onClick = { menuOpen = false },
                            )
                        }
                        installed.forEach { source ->
                            DropdownMenuItem(
                                text = { Text(source.appName.ifBlank { source.packageName }) },
                                onClick = {
                                    menuOpen = false
                                    startPendingFocus(context, source.packageName)
                                },
                            )
                        }
                    }
                }
            }
        }

        // ---------- OCR 截图兜底 + 识别运行指标（SOP V2.0 §19/§35） ----------
        SectionTitle("OCR 截图兜底 · 识别指标")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val m = recognitionMetrics
                ToggleRow(
                    title = "OCR 截图兜底（读不到单号时）",
                    description = "页面文本里没有单号、且该来源有待补全单时，截当前屏 1 帧本地识别，" +
                        "结果进同一条解析链。不连续录制、不上传；同页 30 秒冷却，连续失败自动退避。" +
                        "首次开启需系统录屏授权（API 30+ 设备无障碍自带截图，无需授权）。",
                    checked = ocrOn,
                    onCheckedChange = { value ->
                        if (value) {
                            if (Build.VERSION.SDK_INT < 30) {
                                // API 29：弹一次系统录屏确认，同意后由授权页写入开关
                                context.startActivity(
                                    Intent(context, OcrConsentActivity::class.java),
                                )
                            } else {
                                App.graph.ocrFallbackEnabled = true
                                ocrOn = true
                            }
                        } else {
                            App.graph.ocrFallbackEnabled = false
                            ocrOn = false
                        }
                    },
                )
                if (ocrOn && Build.VERSION.SDK_INT < 30 && !ScreenCaptureService.alive) {
                    Text(
                        text = "录屏授权会话未运行：关一次开关再打开，完成系统授权弹窗。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    text = "识别运行指标（最近时间 / 连续失败计数，§35）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                MetricRow(
                    label = "最近页面读取",
                    value = fmtClock(m.lastPageReadAt) + (m.lastPageReadPkg
                        ?.let { " · ${it.substringAfterLast('.')}" } ?: ""),
                )
                MetricRow(label = "最近取得单号", value = fmtClock(m.lastTrackingFoundAt))
                MetricRow(
                    label = "连续未取到单号",
                    value = if (m.consecutiveNoTracking == 0) "—" else "${m.consecutiveNoTracking} 次",
                )
                MetricRow(
                    label = "OCR 截图",
                    value = if (m.ocrCount == 0L) {
                        "从未"
                    } else {
                        "${m.ocrCount} 次 · ${fmtClock(m.lastOcrAt)}"
                    },
                )
            }
        }

        // ---------- 提醒 ----------
        SectionTitle("提醒")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                ToggleRow(
                    title = "到站 / 取件码提醒",
                    description = "包裹到站、入柜、出现取件码时强提醒",
                    checked = true,
                    fixedOnLabel = "始终开启",
                    onCheckedChange = {},
                )
                ToggleRow(
                    title = "派送中提醒",
                    description = "快递开始派送时提醒一次（默认开启）",
                    checked = outForDelivery,
                    onCheckedChange = { value ->
                        outForDelivery = value
                        notifier.outForDeliveryEnabled = value
                    },
                )
                ToggleRow(
                    title = "运输中提醒",
                    description = "默认关闭，避免打扰",
                    checked = transit,
                    onCheckedChange = { value ->
                        transit = value
                        notifier.transitEnabled = value
                    },
                )
            }
        }

        // ---------- 灵动岛 ----------
        SectionTitle("灵动岛")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                ToggleRow(
                    title = "快递灵动岛",
                    description = "包裹到站、开始派送、取件完成时顶部短时提醒，点击进入详情（默认开启）",
                    checked = islandOn,
                    onCheckedChange = { value ->
                        islandOn = value
                        App.graph.island.enabled = value
                    },
                )
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                ToggleRow(
                    title = "悬浮显示",
                    description = "胶囊盖在其他应用上方；未授权或系统不允许时自动改用系统通知",
                    checked = overlayOn,
                    onCheckedChange = { value ->
                        if (value && !PermissionUtil.canDrawOverlays(context)) {
                            // 不强弹权限：跳系统页让用户自己开（SOP §13）
                            overlayPending = true
                            overlayHint = true
                            PermissionUtil.openOverlaySettings(context)
                        } else {
                            overlayPending = false
                            overlayHint = false
                            overlayOn = value
                            App.graph.island.overlayEnabled = value
                        }
                    },
                )
                if (overlayHint && !overlayAllowed) {
                    Text(
                        text = "请授予「显示在其他应用上层」，回到本页后开关会自动打开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "默认使用系统通知，不需要悬浮窗权限；关闭灵动岛不影响采集、提醒与桌面 Widget。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------- 来源 ----------
        SectionTitle("来源")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                ToggleRow(
                    title = "采集未知来源通知",
                    description = "仅在命中强快递关键词时才解析；关闭后只采集白名单 App",
                    checked = genericEnabled,
                    onCheckedChange = { sourceSettings.setGenericEnabled(it) },
                )
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已收录来源管理", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = onOpenSources) {
                        Text("进入")
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
        }

        // ---------- 诊断与数据 ----------
        SectionTitle("诊断与数据")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("采集健康状态", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = onOpenDiagnostics) {
                        Text("查看")
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }
                Text(
                    text = "解析规则版本：v${App.graph.ruleManager.version()}（本地 assets，不联网）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------- 隐私 ----------
        SectionTitle("隐私说明")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = PRIVACY_TEXT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------- 关于（版权 / 开源入口，URL 统一取自 ProjectLinks） ----------
        SectionTitle("关于")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                LinkRow("版权所有 © 2026 actionsk") {
                    ProjectLinks.openProjectPage(context)
                }
                LinkRow("开源项目") {
                    ProjectLinks.openProjectPage(context)
                }
                LinkRow("开源许可证 MPL-2.0") {
                    ProjectLinks.openLicensePage(context)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("版本", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** 识别运行指标行（SOP V2.0 §35）：左标签右值，紧凑纵排 */
@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 指标时间戳展示：从未 / 今天 HH:mm / 昨天 HH:mm / 更早 MM-dd HH:mm */
private fun fmtClock(ts: Long): String {
    if (ts <= 0L) return "从未"
    val now = System.currentTimeMillis()
    val day = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    val value = when (day.format(Date(ts))) {
        day.format(Date(now)) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
        day.format(Date(now - 86_400_000L)) ->
            "昨天 " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }
    return value
}

/**
 * 「关于」区整行可点击入口：文本 + 右侧箭头。
 * `clickable` 自带按压反馈（ripple）与无障碍可点击/可聚焦语义，
 * 箭头 Icon 带 contentDescription，屏幕阅读器可识别为可点击入口。
 */
@Composable
private fun LinkRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "打开链接",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusRow(
    label: String,
    ok: Boolean,
    actionText: String?,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = if (ok) "✓" else "✗", color = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
        }
        if (actionText != null) {
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    fixedOnLabel: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (fixedOnLabel != null) {
            // 不可关闭的强提醒：不渲染开关，避免“灰掉的开关”被误读成关闭
            Text(
                text = fixedOnLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(start = 8.dp),
            )
        } else {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/**
 * 模块行（检测 SOP §十六）：`符号 + 名称 + 状态词`，下一行是摘要；
 * 点击展开证据明细与 [去设置] 动作（检测 §十七 异常详情）。
 */
@Composable
private fun HealthRow(
    module: ModuleHealth,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAction: (HealthAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(module.status.symbol, color = statusColor(module.status))
                Spacer(Modifier.width(8.dp))
                Text(module.module.displayName, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = module.label,
                style = MaterialTheme.typography.labelMedium,
                color = statusColor(module.status),
            )
        }
        Text(
            text = module.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val subLine = module.subLine
        if (subLine != null) {
            Text(
                text = subLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            module.details.forEach { detail ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = detail.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(text = detail.value, style = MaterialTheme.typography.bodySmall)
                }
            }
            val action = module.action
            if (action != null) {
                TextButton(onClick = { onAction(action) }) { Text(action.label) }
            }
        }
    }
}

/** 状态配色：绿=正常、琥珀=提示（不吓人）、红=异常、灰=未开启/无法判断 */
@Composable
private fun statusColor(status: HealthStatus?): Color = when (status) {
    HealthStatus.HEALTHY -> MaterialTheme.colorScheme.tertiary
    HealthStatus.WARNING -> MaterialTheme.colorScheme.secondary
    HealthStatus.ERROR -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 异常详情的 [去设置] 动作（检测 SOP §17） */
private fun executeHealthAction(action: HealthAction, context: android.content.Context) {
    when (action) {
        HealthAction.OPEN_NOTIFICATION_ACCESS ->
            PermissionUtil.openNotificationAccessSettings(context)
        HealthAction.OPEN_ACCESSIBILITY -> PermissionUtil.openAccessibilitySettings(context)
        HealthAction.OPEN_SMS_PERMISSION -> SmsPermissionManager.openPermissionSettings(context)
        HealthAction.OPEN_BATTERY_SETTINGS ->
            PermissionUtil.openBatteryOptimizationSettings(context)
        HealthAction.OPEN_OVERLAY -> PermissionUtil.openOverlaySettings(context)
    }
}

/**
 * 手动触发识别（需求 §八 手动入口）：布防 [PendingFocus]（10 分钟）→ 拉起购物 App。
 *
 * 用户随后打开该 App 的订单 / 物流页，无障碍在焦点期内读取页面文本，
 * 走同一条解析链路补全单号（与详情页「去对应 App 查看」同一机制，这里只是免去
 * 「必须先有待补全卡片」的前置——空库也能手动触发识别建单）。
 * 未安装 / 拉起失败 → Toast「对应 App 已卸载或无法打开」，不崩溃（§六-3）。
 */
private fun startPendingFocus(context: android.content.Context, packageName: String) {
    PendingFocus.set(packageName, pendingId = 0L, now = System.currentTimeMillis())
    val intent = runCatching {
        context.packageManager.getLaunchIntentForPackage(packageName)
    }.getOrNull()
    if (intent == null) {
        Toast.makeText(context, "对应 App 已卸载或无法打开", Toast.LENGTH_SHORT).show()
        return
    }
    Toast.makeText(context, "已开始识别：打开订单 / 物流页，10 分钟内有效", Toast.LENGTH_SHORT).show()
    try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (t: Throwable) {
        Toast.makeText(context, "对应 App 已卸载或无法打开", Toast.LENGTH_SHORT).show()
    }
}

private const val PRIVACY_TEXT = "仅处理与快递相关的信息"
