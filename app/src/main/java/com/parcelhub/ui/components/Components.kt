/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.parcelhub.model.ShipmentStatus
import com.parcelhub.model.UserStatus

/** 状态文案（统一展示口径，UI 与通知共用） */
fun statusLabel(status: ShipmentStatus): String = when (status) {
    ShipmentStatus.UNKNOWN -> "已发现"
    ShipmentStatus.SHIPPED -> "已发货"
    ShipmentStatus.IN_TRANSIT -> "运输中"
    ShipmentStatus.OUT_FOR_DELIVERY -> "派送中"
    ShipmentStatus.ARRIVED -> "已到站"
    ShipmentStatus.PICKUP_READY -> "待取件"
    ShipmentStatus.DELIVERED -> "已签收"
    ShipmentStatus.RETURNED -> "已退回"
    ShipmentStatus.CANCELLED -> "已取消"
}

fun userStatusLabel(status: UserStatus): String = when (status) {
    UserStatus.UNPROCESSED -> "未处理"
    UserStatus.MARKED_READ -> "已查看"
    UserStatus.PICKED_UP -> "已取件"
    UserStatus.DISMISSED -> "已忽略"
}

/** 角标样式（纯数据，不含 Compose 类型，方便单测覆盖「用户状态优先」口径） */
enum class ChipStyle { USER_DONE, PICKUP, OUT_FOR_DELIVERY, DELIVERED, ERROR, PLAIN }

/**
 * 角标样式判定：**用户状态优先于平台状态**（SOP §4.3）。
 * 已取件 / 已忽略由用户操作产生，必须盖过平台状态，否则会出现
 * 「已取件的包裹还挂着黄色待取角标」（真机反馈的缺陷）。
 */
fun chipStyleOf(status: ShipmentStatus, userStatus: UserStatus): ChipStyle = when {
    userStatus == UserStatus.PICKED_UP || userStatus == UserStatus.DISMISSED -> ChipStyle.USER_DONE
    status == ShipmentStatus.PICKUP_READY || status == ShipmentStatus.ARRIVED -> ChipStyle.PICKUP
    status == ShipmentStatus.OUT_FOR_DELIVERY -> ChipStyle.OUT_FOR_DELIVERY
    status == ShipmentStatus.DELIVERED -> ChipStyle.DELIVERED
    status == ShipmentStatus.RETURNED || status == ShipmentStatus.CANCELLED -> ChipStyle.ERROR
    else -> ChipStyle.PLAIN
}

/** 角标文案（与 `chipStyleOf` 同源，UI 与测试共用一套口径） */
fun chipLabelOf(status: ShipmentStatus, userStatus: UserStatus): String = when (
    chipStyleOf(status, userStatus)
) {
    ChipStyle.USER_DONE -> if (userStatus == UserStatus.PICKED_UP) "已取件" else "已忽略"
    ChipStyle.PICKUP -> "待取"
    ChipStyle.OUT_FOR_DELIVERY -> "派送中"
    ChipStyle.DELIVERED -> "已签收"
    ChipStyle.ERROR, ChipStyle.PLAIN -> statusLabel(status)
}

@Composable
fun StatusChip(
    status: ShipmentStatus,
    modifier: Modifier = Modifier,
    userStatus: UserStatus = UserStatus.UNPROCESSED,
) {
    val style = chipStyleOf(status, userStatus)
    val label = chipLabelOf(status, userStatus)

    val background = when (style) {
        ChipStyle.USER_DONE, ChipStyle.DELIVERED -> MaterialTheme.colorScheme.tertiary
        ChipStyle.PICKUP -> MaterialTheme.colorScheme.secondary
        ChipStyle.OUT_FOR_DELIVERY -> MaterialTheme.colorScheme.primary
        ChipStyle.ERROR -> MaterialTheme.colorScheme.error
        ChipStyle.PLAIN -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    }
    // PLAIN（运输中 / 已发现这类中性态）用主色字；USER_DONE 是 tertiary 底，用 onTertiary（原口径）
    val textColor = when (style) {
        ChipStyle.PLAIN -> MaterialTheme.colorScheme.primary
        ChipStyle.USER_DONE -> MaterialTheme.colorScheme.onTertiary
        else -> MaterialTheme.colorScheme.onSecondary
    }

    Row(
        modifier = modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = textColor)
    }
}

@Composable
fun SectionHeader(title: String, count: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            text = "$title（$count）",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 复制到剪贴板（取件码复制，SOP §13.1） */
fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "已复制：$value", Toast.LENGTH_SHORT).show()
}

/** 打开来源平台 App（未安装时给出提示） */
fun openPlatform(context: Context, packageName: String?): Boolean {
    if (packageName.isNullOrBlank()) {
        Toast.makeText(context, "未知来源平台", Toast.LENGTH_SHORT).show()
        return false
    }
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
    return if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } else {
        Toast.makeText(context, "未安装该来源 App", Toast.LENGTH_SHORT).show()
        false
    }
}

@Composable
fun rememberClipboardAction(): (String, String) -> Unit {
    val context = LocalContext.current
    return { label, value -> copyToClipboard(context, label, value) }
}

/** 空状态占位（列表 / 详情通用） */
@Composable
fun EmptyState(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 详情分区卡片 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content,
        )
    }
}

/** 详情键值行 */
@Composable
fun DetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}
