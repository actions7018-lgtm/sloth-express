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

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.parcelhub.App
import com.parcelhub.notification.AppNotificationManager
import com.parcelhub.util.PermissionUtil

/**
 * 设置页（P5）。
 * 只放采集、提醒、来源、诊断入口与隐私说明，不引入新业务功能。
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
    val health = App.graph.health.snapshot.collectAsState()

    var listenerEnabled by remember { mutableStateOf(false) }
    var postEnabled by remember { mutableStateOf(true) }
    var outForDelivery by remember { mutableStateOf(notifier.outForDeliveryEnabled) }
    var transit by remember { mutableStateOf(notifier.transitEnabled) }
    var autoQueryOn by remember { mutableStateOf(App.graph.autoQueryEnabled) }
    var a11yEnabled by remember { mutableStateOf(false) }

    // 快递灵动岛（SOP §11 双模式）：通知模式默认开；悬浮模式需用户主动授权
    var islandOn by remember { mutableStateOf(App.graph.island.enabled) }
    var overlayOn by remember { mutableStateOf(App.graph.island.overlayEnabled) }
    var overlayAllowed by remember { mutableStateOf(PermissionUtil.canDrawOverlays(context)) }
    var overlayPending by remember { mutableStateOf(false) }
    var overlayHint by remember { mutableStateOf(false) }
    val genericEnabled by sourceSettings.genericEnabled.collectAsState()

    LaunchedEffect(Unit) {
        listenerEnabled = PermissionUtil.isNotificationListenerEnabled(context)
        postEnabled = PermissionUtil.hasPostNotifications(context)
        a11yEnabled = PermissionUtil.isAccessibilityEnabled(context)
    }

    // 从系统悬浮窗授权页返回时：权限到手就把刚才那次开启补上（SOP §13 引导授权）
    LifecycleResumeEffect(Unit) {
        overlayAllowed = PermissionUtil.canDrawOverlays(context)
        if (overlayPending && overlayAllowed) {
            App.graph.island.overlayEnabled = true
            overlayOn = true
            overlayPending = false
            overlayHint = false
        }
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---------- 自动采集 ----------
        SectionTitle("自动采集")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                StatusRow(
                    label = "通知使用权",
                    ok = listenerEnabled,
                    actionText = if (listenerEnabled) null else "去开启",
                    onAction = { PermissionUtil.openNotificationAccessSettings(context) },
                )
                StatusRow(
                    label = "App 通知权限（仅提醒用）",
                    ok = postEnabled,
                    actionText = if (postEnabled) null else "去开启",
                    onAction = { PermissionUtil.openAppNotificationSettings(context) },
                )
                StatusRow(
                    label = "监听服务",
                    ok = health.value.listenerBound,
                    actionText = "查看诊断",
                    onAction = onOpenDiagnostics,
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

private const val PRIVACY_TEXT = "仅处理与快递相关的信息"
