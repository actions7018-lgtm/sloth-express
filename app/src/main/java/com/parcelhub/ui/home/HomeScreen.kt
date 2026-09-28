/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.ui.home

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.parcelhub.data.entity.ShipmentEntity
import com.parcelhub.data.entity.isActiveInTransit
import com.parcelhub.data.entity.isActiveOutForDelivery
import com.parcelhub.data.entity.needsPickup
import com.parcelhub.data.entity.statusEnum
import com.parcelhub.data.entity.userStatusEnum
import com.parcelhub.model.UserStatus
import com.parcelhub.ui.components.SectionHeader
import com.parcelhub.ui.components.StatusChip
import com.parcelhub.ui.components.copyToClipboard
import com.parcelhub.ui.components.openPlatform
import com.parcelhub.ui.components.statusLabel
import com.parcelhub.util.PermissionUtil
import com.parcelhub.util.TimeUtil
import kotlinx.coroutines.launch

/**
 * 首页（SOP §13）：不是通知列表，而是“当前需要处理什么”。
 *
 * 顺序：今日概况 → 待取 → 派送中 → 运输中。
 * 事件写入 Room 后由 Flow 自动增量刷新，不需要手动刷新（SOP §25 Phase 5 验收）。
 */
@Composable
fun HomeScreen(
    onOpenSources: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenDetail: (Long) -> Unit,
) {
    val context = LocalContext.current
    val repository = App.graph.repository
    val scope = rememberCoroutineScope()

    val shipments by remember(repository) {
        repository.observeRecent(HOME_LIMIT)
    }.collectAsState(initial = emptyList())

    var listenerEnabled by remember { mutableStateOf(false) }
    var postEnabled by remember { mutableStateOf(true) }
    var a11yEnabled by remember { mutableStateOf(false) }
    var autoQueryOn by remember { mutableStateOf(true) }

    LifecycleResumeEffect(Unit) {
        listenerEnabled = PermissionUtil.isNotificationListenerEnabled(context)
        postEnabled = PermissionUtil.hasPostNotifications(context)
        a11yEnabled = PermissionUtil.isAccessibilityEnabled(context)
        autoQueryOn = App.graph.autoQueryEnabled
        onPauseOrDispose { }
    }

    val now = System.currentTimeMillis()
    val pickupReady = shipments.filter { it.needsPickup }
    // 派送中 / 运输中都要排除「已取件」：标记取件后包裹归入已签收，不再挂在这两组
    val outForDelivery = shipments.filter { it.isActiveOutForDelivery }
    val inTransit = shipments.filter { it.isActiveInTransit }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---------- 顶部：今日概况 ----------
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "今日概况",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.SpaceBetween) {
                        StatCell("待取", pickupReady.size, Modifier.weight(1f))
                        StatCell("派送中", outForDelivery.size, Modifier.weight(1f))
                        StatCell("运输中", inTransit.size, Modifier.weight(1f))
                    }
                }
            }
        }

        // ---------- 权限引导（P1） ----------
        if (!listenerEnabled) {
            item {
                PermissionCard(
                    title = "开启通知使用权，自动发现快递",
                    description = "只读取与快递相关的通知，不读取短信，不保存无关通知。",
                    buttonText = "去开启",
                    onClick = { PermissionUtil.openNotificationAccessSettings(context) },
                )
            }
        } else if (!postEnabled) {
            item {
                PermissionCard(
                    title = "开启 App 通知权限（可选）",
                    description = "关闭后仍会正常收集与展示快递，只是不会主动弹出到站提醒。",
                    buttonText = "去开启",
                    onClick = { PermissionUtil.openAppNotificationSettings(context) },
                )
            }
        }
        // 自动查询开着但自动填单号没开：提示一次，不挡正常内容（SOP T06）
        if (listenerEnabled && autoQueryOn && !a11yEnabled) {
            item {
                PermissionCard(
                    title = "开启自动填单号（可选）",
                    description = "在菜鸟查快递页自动填入单号，省去复制粘贴。只操作菜鸟输入框。",
                    buttonText = "去开启",
                    onClick = { PermissionUtil.openAccessibilitySettings(context) },
                )
            }
        }

        // ---------- 待取 ----------
        item { SectionHeader("待取", pickupReady.size) }
        if (pickupReady.isEmpty()) {
            item { EmptyHint("暂无待取包裹") }
        } else {
            items(pickupReady, key = { "pickup-${it.id}" }) { shipment ->
                PickupCard(
                    shipment = shipment,
                    now = now,
                    onOpen = { onOpenDetail(shipment.id) },
                    onMarkPickedUp = {
                        scope.launch {
                            // 数据库真正写成功才算成功（SOP §9 灵动岛/取件持久化）：
                            // 失败必须如实提示，不能 UI 显示成功但库里没变
                            val ok = repository.setUserStatus(shipment.id, UserStatus.PICKED_UP)
                            if (!ok) {
                                android.widget.Toast
                                    .makeText(context, "标记失败，请重试", android.widget.Toast.LENGTH_SHORT)
                                    .show()
                            }
                        }
                    },
                )
            }
        }

        // ---------- 派送中 ----------
        item { SectionHeader("派送中", outForDelivery.size) }
        if (outForDelivery.isEmpty()) {
            item { EmptyHint("暂无派送中包裹") }
        } else {
            items(outForDelivery, key = { "ofd-${it.id}" }) { shipment ->
                BriefCard(shipment, now) { onOpenDetail(shipment.id) }
            }
        }

        // ---------- 运输中 ----------
        item { SectionHeader("运输中", inTransit.size) }
        if (inTransit.isEmpty()) {
            item { EmptyHint("暂无运输中包裹") }
        } else {
            items(inTransit, key = { "transit-${it.id}" }) { shipment ->
                BriefCard(shipment, now) { onOpenDetail(shipment.id) }
            }
        }

        // ---------- 快捷入口 ----------
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenSources, modifier = Modifier.weight(1f)) {
                    Text("来源管理")
                }
                OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.weight(1f)) {
                    Text("采集诊断")
                }
            }
        }
    }
}

@Composable
private fun StatCell(title: String, count: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    buttonText: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.NotificationsActive, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(description, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Button(onClick = onClick) { Text(buttonText) }
        }
    }
}

/** 待取卡片（SOP §13.1）：突出快递公司 / 状态 / 取件码 / 取件地点 */
@Composable
private fun PickupCard(
    shipment: ShipmentEntity,
    now: Long,
    onOpen: () -> Unit,
    onMarkPickedUp: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        onClick = onOpen,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "包裹 · " + (shipment.carrier ?: "快递"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                StatusChip(shipment.statusEnum, userStatus = shipment.userStatusEnum)
            }

            Spacer(Modifier.height(10.dp))

            // 取件码标签与大号数字垂直居中（用户需求，不要底对齐）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "取件码", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = shipment.pickupCode ?: "—",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "取件地址",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = shipment.pickupLocation ?: "—",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            // 放置位置：完整投放点（余杭区东连街道菜鸟驿站 / 家门口），与短站点重复时不占行
            shipment.address
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals(shipment.pickupLocation, ignoreCase = true) }
                ?.let { place ->
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "放置位置",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(text = place, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            shipment.trackingNumber?.let {
                Spacer(Modifier.height(4.dp))
                Text(text = "单号 $it", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "更新于 " + TimeUtil.relative(now, shipment.lastUpdatedAt),
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(12.dp))
            // 三个按钮等宽等高（SOP §13.1 关键信息不被截断：复制按钮收窄内边距避免折行）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        val code = shipment.pickupCode
                        if (!code.isNullOrBlank()) {
                            copyToClipboard(context, "取件码", code)
                        }
                    },
                    enabled = !shipment.pickupCode.isNullOrBlank(),
                    // 等宽按钮里放不下“图标 + 5 字”，只留文字并收窄内边距，避免末字被裁
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("复制取件码", maxLines = 1)
                }
                OutlinedButton(
                    onClick = { openPlatform(context, shipment.sourcePlatform) },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.width(16.dp),
                    )
                }
                OutlinedButton(
                    onClick = onMarkPickedUp,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Default.Done,
                        contentDescription = null,
                        modifier = Modifier.width(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("已取", maxLines = 1)
                }
            }
        }
    }
}

/** 派送中 / 运输中卡片（SOP §13.2 §13.3）：只显示关键摘要，不堆轨迹原文 */
@Composable
private fun BriefCard(
    shipment: ShipmentEntity,
    now: Long,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = shipment.carrier ?: "快递",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                val location = shipment.pickupLocation
                Text(
                    text = statusLabel(shipment.statusEnum) + (location?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "更新于 " + TimeUtil.relative(now, shipment.lastUpdatedAt),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            StatusChip(shipment.statusEnum, userStatus = shipment.userStatusEnum)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

private const val HOME_LIMIT = 100
