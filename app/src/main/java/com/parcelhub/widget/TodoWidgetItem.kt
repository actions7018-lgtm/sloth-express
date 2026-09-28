/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.widget

import com.parcelhub.data.entity.ShipmentEntity
import com.parcelhub.data.entity.needsPickup

/**
 * 桌面 Widget 的一条待办（纯数据投影）。
 *
 * 之所以不做成 RemoteViews 直接消费的对象：投影规则（谁进待办、怎么排序、文案长什么样）
 * 必须能在 JVM 单测里跑，而 RemoteViews 只能由系统构造。
 */
data class TodoWidgetItem(
    val shipmentId: Long,
    /** 主文案，形如 `取件码 99-9-7515｜菜鸟驿站` 或 `待查询｜菜鸟驿站` */
    val title: String,
    /** 副文案（单号），仅「待查询」项需要，其余为 null 表示不显示 */
    val trackingNumber: String?,
    /** 是否显示包裹图标：只有真正的取件任务（有取件码）才显示 */
    val showParcelIcon: Boolean,
    /** 勾选反馈态：刚完成、正在展示「已完成」的那一拍 */
    val checked: Boolean = false,
) {
    companion object {

        /**
         * 从数据库行投影出 Widget 待办列表。
         *
         * 规则（对应用户需求）：
         *  - 只取 [needsPickup]（已到站/入柜/有取件码，且未取件、未忽略、**不是家门口投递**）；
         *  - 有取件码的排前面，无取件码的（待查询）排后面，组内按更新时间倒序；
         *  - [feedbackIds] 是「刚点完勾、已经写库完成」的包裹：它们此时已不满足 needsPickup，
         *    但要在列表里多留一拍显示勾选态，给用户完成反馈。
         *
         * **动画要在原地做**：写库会把 `updated_at` 顶到最新，而勾选项已不满足 needsPickup，
         * 直接参与排序/插队都会跳到列表最前 → 整页先重排一次再谈淡出（真机踩过）。
         * 所以调用方在**写库前**先记下它当时的位置，经 [anchors] 传回来原样插回去；
         * 没有锚点（`-1`/缺失）才退回「排最前」的兜底行为。
         * 注意锚点是**写库前**列表里的下标：写库后它被移出，插回同一个下标正好复原原顺序。
         */
        fun from(
            shipments: List<ShipmentEntity>,
            feedbackIds: Set<Long> = emptySet(),
            anchors: Map<Long, Int> = emptyMap(),
        ): List<TodoWidgetItem> {
            val todos = shipments.filter { it.needsPickup }
            val todoIds = todos.map { it.id }.toSet()

            val feedback = shipments.filter { it.id in feedbackIds && it.id !in todoIds }
                .map { it.toItem(checked = true) }

            val ordered = todos.sortedWith(
                compareByDescending<ShipmentEntity> { !it.pickupCode.isNullOrBlank() }
                    .thenByDescending { it.updatedAt },
            ).map { it.toItem(checked = false) }

            if (feedback.isEmpty()) return ordered

            // 逐个插回锚点位置（同时只会有 1 条在做动画，多条时后插的按当时的下标算，够用）
            val result = ordered.toMutableList()
            feedback.forEach { item ->
                val at = anchors[item.shipmentId]
                if (at == null || at < 0) {
                    result.add(0, item)
                } else {
                    result.add(at.coerceIn(0, result.size), item)
                }
            }
            return result
        }
    }
}

private fun ShipmentEntity.toItem(checked: Boolean): TodoWidgetItem {
    val place = pickupLocation?.takeIf { it.isNotBlank() } ?: "未知地点"
    val hasCode = !pickupCode.isNullOrBlank()
    return TodoWidgetItem(
        shipmentId = id,
        title = if (hasCode) "取件码 $pickupCode｜$place" else "待查询｜$place",
        // 有取件码时列表已经很明确，不必再占一行；待查询时单号才是用户去查的依据
        trackingNumber = if (hasCode) null else trackingNumber?.takeIf { it.isNotBlank() },
        showParcelIcon = hasCode,
        checked = checked,
    )
}
