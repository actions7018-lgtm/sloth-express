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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.parcelhub.App
import com.parcelhub.data.entity.ShipmentEntity
import com.parcelhub.data.entity.statusEnum
import com.parcelhub.data.entity.userStatusEnum
import com.parcelhub.ui.components.EmptyState
import com.parcelhub.ui.components.StatusChip
import com.parcelhub.ui.components.statusLabel
import com.parcelhub.util.TimeUtil

/**
 * 全部包裹列表（SOP §14 P3）。
 *
 * 只读 Room Flow，增量刷新；底部导航结构不在此处决定。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShipmentListScreen(onOpenDetail: (Long) -> Unit) {
    val repository = App.graph.repository
    val shipments by remember(repository) { repository.observeAll() }
        .collectAsState(initial = emptyList())

    var filter by remember { mutableStateOf(ShipmentFilter.ALL) }
    val filtered = ShipmentFilter.filter(shipments, filter)

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("全部包裹") })
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ShipmentFilter.OPTIONS.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option) },
                    )
                }            }

            if (filtered.isEmpty()) {
                EmptyState(title = "暂无包裹", subtitle = "收到快递通知后会自动出现在这里")
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filtered, key = { it.id }) { shipment ->
                        ShipmentRow(shipment) { onOpenDetail(shipment.id) }
                    }
                }
            }
        }
    }
}

/** 单行包裹条目 */
@Composable
private fun ShipmentRow(shipment: ShipmentEntity, onClick: () -> Unit) {
    val now = System.currentTimeMillis()
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = shipment.carrier ?: "未知快递",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = statusLabel(shipment.statusEnum),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                shipment.pickupCode?.takeIf { it.isNotBlank() }?.let { code ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "取件码 $code",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                shipment.pickupLocation?.takeIf { it.isNotBlank() }?.let { location ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "取件地址 $location",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append("单号 ")
                        append(shipment.trackingNumber ?: "未知")
                        append(" · 更新于 ")
                        append(TimeUtil.relative(now, shipment.lastUpdatedAt))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Box(Modifier.padding(start = 8.dp)) {
                StatusChip(shipment.statusEnum, userStatus = shipment.userStatusEnum)
            }
        }
    }
}

