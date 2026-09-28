/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.ui.shipment

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.parcelhub.data.entity.isPickupReady
import com.parcelhub.data.entity.statusEnum
import com.parcelhub.data.entity.userStatusEnum
import com.parcelhub.model.UserStatus
import com.parcelhub.ui.components.DetailRow
import com.parcelhub.ui.components.EmptyState
import com.parcelhub.ui.components.SectionCard
import com.parcelhub.ui.components.StatusChip
import com.parcelhub.ui.components.statusLabel
import com.parcelhub.ui.components.userStatusLabel
import com.parcelhub.util.TimeUtil
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * 包裹详情（SOP §14 P4）。
 *
 * 展示当前状态、取件信息、基础资料与事件时间线；
 * 平台状态与用户状态分开展示（SOP §4.3）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShipmentDetailScreen(shipmentId: Long, onBack: () -> Unit) {
    val repository = App.graph.repository
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val shipment by remember(repository, shipmentId) { repository.observeShipment(shipmentId) }
        .collectAsState(initial = null)
    val events by remember(repository, shipmentId) { repository.observeEvents(shipmentId) }
        .collectAsState(initial = emptyList())

    var showDelete by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val item = shipment

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除该包裹？") },
            text = { Text("相关事件记录会一并删除，此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    scope.launch {
                        repository.deleteShipment(shipmentId)
                        onBack()
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.carrier ?: "包裹详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除")
                    }
                },
            )
        },
    ) { padding ->
        if (item == null) {
            Column(Modifier.padding(padding)) {
                EmptyState(title = "包裹不存在", subtitle = "可能已被删除，返回列表看看")
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 当前状态 + 取件信息
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = statusLabel(item.statusEnum),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    // 必须传 userStatus：否则已取件后角标仍是黄色「待取」（真机反馈缺陷）
                    StatusChip(item.statusEnum, userStatus = item.userStatusEnum)
                }
                if (item.isPickupReady) {
                    Spacer(Modifier.height(8.dp))
                    item.pickupCode?.takeIf { it.isNotBlank() }?.let {
                        DetailRow("取件码", it)
                    }
                    item.pickupLocation?.takeIf { it.isNotBlank() }?.let {
                        DetailRow("取件地址", it)
                    }
                    item.lockerNumber?.takeIf { it.isNotBlank() }?.let {
                        DetailRow("柜号", it)
                    }
                }
                DetailRow("用户状态", userStatusLabel(item.userStatusEnum))
                item.pickedUpAt?.takeIf { it > 0L }?.let {
                    DetailRow("取件时间", TimeUtil.dateText(it))
                }
                DetailRow("更新时间", TimeUtil.dateText(item.lastUpdatedAt))

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                // 数据库真正更新成功才算成功；失败必须如实告诉用户，不假装成功
                                val ok = repository.setUserStatus(shipmentId, UserStatus.PICKED_UP)
                                if (!ok) {
                                    Toast.makeText(context, "标记失败，请重试", Toast.LENGTH_SHORT)
                                        .show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("标记已取件") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val ok = repository.setUserStatus(shipmentId, UserStatus.DISMISSED)
                                if (!ok) {
                                    Toast.makeText(context, "标记失败，请重试", Toast.LENGTH_SHORT)
                                        .show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("忽略") }
                }
            }

            // 基础信息
            SectionCard {
                Text(
                    text = "基础信息",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                DetailRow("运单号", item.trackingNumber ?: "未知")
                DetailRow("快递公司", item.carrier ?: "未知")
                DetailRow("来源平台", item.sourcePlatform ?: "未知")
                DetailRow("平台状态", statusLabel(item.statusEnum))
                DetailRow("用户状态", userStatusLabel(item.userStatusEnum))
                item.address?.takeIf { it.isNotBlank() }?.let { DetailRow("放置位置", it) }
            }

            // 事件时间线
            SectionCard {
                Text(
                    text = "状态时间线",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                if (events.isEmpty()) {
                    Text(
                        text = "暂无事件记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    events.forEach { event ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                        ) {
                            Text(
                                text = TimeUtil.dateText(event.eventTime),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Text(
                                text = event.rawSummary.ifBlank { statusLabel(com.parcelhub.model.ShipmentStatus.from(event.status)) },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            event.pickupCode?.takeIf { it.isNotBlank() }?.let { code ->
                                Text(
                                    text = "取件码 $code",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            event.pickupLocation?.takeIf { it.isNotBlank() }?.let { location ->
                                Text(
                                    text = location,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "数据更新于 ${TimeUtil.dateText(now)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
