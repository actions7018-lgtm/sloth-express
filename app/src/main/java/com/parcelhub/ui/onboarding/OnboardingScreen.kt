/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.parcelhub.util.PermissionUtil

/**
 * 启动 / 权限引导（P1）。
 *
 * 必需权限只有一项：通知使用权（用户在系统设置手动开启）。
 * App 自身通知权限是可选项，拒绝后数据采集与首页展示不受影响（SOP §12.3）。
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current

    var listenerEnabled by remember { mutableStateOf(false) }
    var postEnabled by remember { mutableStateOf(false) }
    var a11yEnabled by remember { mutableStateOf(false) }

    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        listenerEnabled = PermissionUtil.isNotificationListenerEnabled(context)
        postEnabled = PermissionUtil.hasPostNotifications(context)
        a11yEnabled = PermissionUtil.isAccessibilityEnabled(context)
        onPauseOrDispose { }
    }

    val postLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        postEnabled = granted || PermissionUtil.hasPostNotifications(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 32.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "树懒快递助手", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            text = "不用手动输入单号：正常用手机购物，App 自动发现快递、合并状态，到站后突出取件码与地点。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- 步骤 1：通知使用权（必需） ----
        StepCard(
            index = "1",
            title = "开启通知使用权（必需）",
            description = "用于从淘宝、京东、菜鸟、丰巢等 App 的通知里发现快递事件。" +
                "只处理与快递相关的通知，不读取短信，不保存无关通知。",
            done = listenerEnabled,
            buttonText = if (listenerEnabled) "已开启" else "去系统设置开启",
            enabled = !listenerEnabled,
            onClick = { PermissionUtil.openNotificationAccessSettings(context) },
        )

        // ---- 步骤 2：App 通知权限（可选） ----
        StepCard(
            index = "2",
            title = "允许 App 提醒（可选）",
            description = "用于在包裹到站、出现取件码时主动提醒你。" +
                "拒绝后依然会收集与展示快递，只是不弹提醒。",
            done = postEnabled,
            buttonText = when {
                postEnabled -> "已允许"
                PermissionUtil.shouldExplainPostNotifications() -> "允许提醒"
                else -> "系统版本无需授权"
            },
            enabled = !postEnabled && PermissionUtil.shouldExplainPostNotifications(),
            onClick = {
                postLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            },
        )

        // ---- 步骤 3：自动填单号（可选） ----
        StepCard(
            index = "3",
            title = "自动填单号（可选）",
            description = "驿站无取件码、只有单号时，一键打开菜鸟并自动填入单号，" +
                "省去复制粘贴。只操作菜鸟的单号输入框，不点查询、不读其他应用内容，" +
                "需在系统设置中手动开启，随时可关。",
            done = a11yEnabled,
            buttonText = if (a11yEnabled) "已开启" else "去系统设置开启",
            enabled = !a11yEnabled,
            onClick = { PermissionUtil.openAccessibilitySettings(context) },
        )

        // ---- 步骤 4：隐私承诺 ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PrivacyTip, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("隐私承诺", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "仅处理与快递相关的信息",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.width(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (listenerEnabled) "开始使用" else "先跳过，稍后再开")
        }

        if (!listenerEnabled) {
            Text(
                text = "未开启通知使用权时，App 不会采集任何通知，可稍后在设置中开启。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepCard(
    index: String,
    title: String,
    description: String,
    done: Boolean,
    buttonText: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = index,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (done) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "已完成",
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            if (done) {
                OutlinedButton(onClick = onClick, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text(buttonText)
                }
            } else {
                Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(buttonText)
                }
            }
        }
    }
}
