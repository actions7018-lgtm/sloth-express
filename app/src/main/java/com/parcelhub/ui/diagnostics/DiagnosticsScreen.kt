/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.ui.diagnostics

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.parcelhub.App
import com.parcelhub.util.PermissionUtil
import com.parcelhub.util.TimeUtil

/**
 * 诊断 / 采集健康（P7，SOP §19）。
 *
 * 只展示状态与计数，不展示通知正文；
 * 只有检测到异常时才提示“可能受到后台限制”，不强迫所有用户提前配置白名单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val health = App.graph.health.snapshot.collectAsState()
    val pipeline = App.graph.pipeline

    var postEnabled by remember { mutableStateOf(true) }
    var shipmentCount by remember { mutableStateOf(0) }
    var eventCount by remember { mutableStateOf(0) }
    var taskCount by remember { mutableStateOf(0) }
    var dbOk by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        postEnabled = PermissionUtil.hasPostNotifications(context)
        runCatching {
            shipmentCount = App.graph.repository.countShipments()
            eventCount = App.graph.repository.countEvents()
            taskCount = App.graph.database.queryTaskDao().count()
        }.onFailure { dbOk = false }
    }

    val snapshot = health.value
    val now = System.currentTimeMillis()
    val listenerAccess = PermissionUtil.isNotificationListenerEnabled(context)
    val autoQueryOn = App.graph.autoQueryEnabled
    val a11yOn = PermissionUtil.isAccessibilityEnabled(context)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("采集诊断") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("自动采集状态", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        HealthRow("通知使用权", listenerAccess, okText = "已开启", badText = "未开启")
                        HealthRow("App 通知权限", postEnabled, okText = "已授予", badText = "未授予（仅影响提醒）")
                        HealthRow(
                            "监听服务",
                            snapshot.listenerBound,
                            okText = "运行中",
                            badText = "未连接",
                        )
                        HealthRow("解析服务", pipeline.pending < 128, okText = "就绪", badText = "队列积压")
                        HealthRow("数据库", dbOk, okText = "正常", badText = "读取异常")
                        HealthRow(
                            "自动查询",
                            autoQueryOn,
                            okText = "已开启",
                            badText = "已关闭（设置页可开）",
                        )
                        HealthRow(
                            "自动填单号",
                            a11yOn,
                            okText = "已开启",
                            badText = "未开启（设置页可开）",
                        )
                        Spacer(Modifier.height(6.dp))
                        TimeRow("最近一次收到事件", relativeOrNever(snapshot.lastEventAt, now))
                        TimeRow("最近一次成功解析", relativeOrNever(snapshot.lastParsedAt, now))
                        if (snapshot.lastErrorAt > 0L) {
                            TimeRow("最近一次异常", relativeOrNever(snapshot.lastErrorAt, now))
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("统计", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        TimeRow("包裹数", shipmentCount.toString())
                        TimeRow("事件数", eventCount.toString())
                        TimeRow("查询任务数", taskCount.toString())
                        TimeRow("累计收到通知", snapshot.totalEvents.toString())
                        TimeRow("成功解析", snapshot.totalParsed.toString())
                        TimeRow("跳过（非快递）", snapshot.totalSkipped.toString())
                        TimeRow("队列丢弃", snapshot.totalDropped.toString())
                        TimeRow("队列积压", pipeline.pending.toString())
                        TimeRow("解析规则版本", "v${App.graph.ruleManager.version()}")
                    }
                }
            }

            if (snapshot.isStalled(now)) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = "可能受到后台限制",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "监听服务在运行，但较长时间没有收到新事件。" +
                                    "系统可能限制了本 App 的后台活动，可到系统设置中检查。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { PermissionUtil.openNotificationAccessSettings(context) },
                            ) {
                                Text("检查系统设置")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "诊断信息只包含状态与计数，不含通知正文；" +
                        "导出诊断样本需经你主动确认后才会进行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HealthRow(
    label: String,
    ok: Boolean,
    okText: String,
    badText: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (ok) "✓" else "✗",
                color = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (ok) okText else badText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun TimeRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun relativeOrNever(time: Long, now: Long): String =
    if (time <= 0L) "从未" else TimeUtil.relative(now, time)
