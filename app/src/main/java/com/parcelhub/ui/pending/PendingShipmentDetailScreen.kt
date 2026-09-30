/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.ui.pending

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.parcelhub.App
import com.parcelhub.data.entity.PendingShipmentEntity
import com.parcelhub.data.entity.statusEnum
import com.parcelhub.pending.PendingFocus
import com.parcelhub.pending.PendingShipmentStatus
import com.parcelhub.pending.pendingSourceLabel
import com.parcelhub.ui.components.DetailRow
import com.parcelhub.ui.components.EmptyState
import com.parcelhub.ui.components.SectionCard
import com.parcelhub.util.TimeUtil
import kotlinx.coroutines.flow.flowOf

/**
 * 待补全详情（需求 §五 PendingShipmentDetail）。
 *
 * 首页「待补全」卡片点击进入（需求 §四），展示：
 * 商品名称 / 商家 / 订单平台 / 来源 App / 订单状态 / 发货时间 / 当前是否有单号 /
 * 下一次自动检查时间 / 预计停止补全时间，以及 [去对应 App 查看] 按钮。
 *
 * 点击按钮（需求 §六/§八/§九）：
 *  - 还没有单号 → 记录补全焦点（[PendingFocus]，10 分钟），无障碍服务在这期间
 *    监控该来源 App 的订单 / 物流页文本尝试补全；随后拉起对应 App；
 *  - 已有单号 → 不再进入补全（§九「物流信息已获取」），只打开 App 查看；
 *  - 未安装 / 拉起失败 → Toast「对应 App 已卸载或无法打开」，不崩溃（§六-3）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingShipmentDetailScreen(pendingId: Long, onBack: () -> Unit) {
    val repository = App.graph.repository
    val context = LocalContext.current

    val pending by remember(repository, pendingId) {
        repository.observePendingById(pendingId)
    }.collectAsState(initial = null)
    val item = pending

    // 关联正式包裹：补全后的单号可能挂在它身上（需求 §九 已有单号就不再补全）
    val shipmentId = item?.shipmentId?.takeIf { it > 0L }
    val shipment by remember(repository, shipmentId) {
        if (shipmentId != null) {
            repository.observeShipment(shipmentId)
        } else {
            flowOf(null)
        }
    }.collectAsState(initial = null)

    val tracking = item?.trackingNumber ?: shipment?.trackingNumber
    val hasTracking = !tracking.isNullOrBlank()

    // 订单平台显示名：来源管理里的应用名（Mock 平台名本身就是中文，原样展示）
    val sources by remember(repository) { repository.observeSources() }
        .collectAsState(initial = emptyList())
    val sourceNames = remember(sources) { sources.associate { it.packageName to it.appName } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("待补全详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (item == null) {
            Column(Modifier.padding(padding)) {
                EmptyState(title = "待补全订单不存在", subtitle = "可能已被补全或删除，返回首页看看")
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
            // 补全状态（需求 §九）：已有单号 → 「物流信息已获取」；否则 → 「等待物流信息」
            SectionCard {
                Text(
                    text = if (hasTracking) "物流信息已获取" else "等待物流信息",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (hasTracking) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (hasTracking) {
                    DetailRow("快递单号", tracking.orEmpty())
                }
            }

            // 订单信息（需求 §五字段全集）
            SectionCard {
                Text(
                    text = "订单信息",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                DetailRow("商品名称", item.productTitle ?: "未知")
                DetailRow("商家", item.sellerName ?: "未知")
                DetailRow(
                    "订单平台",
                    sourceNames[item.platform] ?: item.platform ?: "未知",
                )
                DetailRow("来源 App", pendingSourceLabel(item.sourceAppName, item.sourceType))
                DetailRow("订单状态", pendingStatusLabel(item.statusEnum))
                DetailRow("发货时间", TimeUtil.dateText(item.shippedAt))
                DetailRow(
                    "当前是否有单号",
                    if (hasTracking) "有（$tracking）" else "暂无单号",
                )
                DetailRow(
                    "下一次自动检查",
                    item.nextCheckAt?.takeIf { it > 0L }?.let { TimeUtil.dateText(it) } ?: "暂无计划",
                )
                DetailRow("预计停止补全", TimeUtil.dateText(item.expireAt))
            }

            // 去对应 App 查看（需求 §六）：中转来源没有来源 App → 不展示按钮
            item.sourcePackageName?.let { pkg ->
                val appName = item.sourceAppName
                OutlinedButton(
                    onClick = {
                        // §九：已有单号不再进入补全流程，只打开 App 查看
                        if (!hasTracking) {
                            PendingFocus.set(pkg, item.id, System.currentTimeMillis())
                        }
                        openSourceApp(context, pkg)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (!appName.isNullOrBlank()) {
                            "去${appName}查看"
                        } else {
                            "去对应 App 查看"
                        },
                    )
                }
            }
        }
    }
}

/** 待补全订单状态 → 展示文案（需求 §五「订单状态」） */
private fun pendingStatusLabel(status: PendingShipmentStatus): String = when (status) {
    PendingShipmentStatus.WAITING_TRACKING -> "等待物流单号"
    PendingShipmentStatus.TRACKING_FOUND -> "已补全单号"
    PendingShipmentStatus.EXPIRED -> "已过期（超 72 小时未补全）"
    PendingShipmentStatus.CANCELLED -> "已取消"
}

/**
 * 打开来源购物 App（需求 §六）。
 *
 * 优先级：项目当前没有各平台「订单 / 物流页」官方 Deep Link 映射（§六-1 无可选项），
 * 直接走 §六-2：`PackageManager.getLaunchIntentForPackage` 拉起 App 首页；
 * 未安装或拉起失败 → §六-3 Toast「对应 App 已卸载或无法打开」，不崩溃。
 */
private fun openSourceApp(context: Context, packageName: String): Boolean {
    val intent = try {
        context.packageManager.getLaunchIntentForPackage(packageName)
    } catch (t: Throwable) {
        null
    }
    if (intent == null) {
        Toast.makeText(context, "对应 App 已卸载或无法打开", Toast.LENGTH_SHORT).show()
        return false
    }
    return try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        Toast.makeText(context, "对应 App 已卸载或无法打开", Toast.LENGTH_SHORT).show()
        false
    }
}
