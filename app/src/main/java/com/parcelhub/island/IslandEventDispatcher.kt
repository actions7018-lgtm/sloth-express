/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.island

import com.parcelhub.data.repository.IngestOutcome
import com.parcelhub.model.ShipmentStatus
import com.parcelhub.model.UserStatus

/**
 * 灵动岛事件分发器（SOP §3 的 `ParcelEventDispatcher`，SOP §6 事件模型 / §8 触发规则）。
 *
 * 职责边界：
 *  - **只做“状态变化 → 灵动岛事件”的纯函数映射**，不碰 UI、不碰数据库、不去重；
 *  - 去重由 [IslandDedup] 负责（SOP §15），展示由 [IslandManager] 负责；
 *  - 三个入口对应三条触发链：
 *    1. 解析入库 → [fromIngest]（SOP §8.1 到达 / §8.2 派送 / 状态变化）
 *    2. 用户点「标记已取件」→ [fromUserStatus]（SOP §8.3，必须是**数据库更新成功**之后）
 *
 * 映射规则刻意保守：只有“确实值得打断用户”的状态才产出事件，
 * 运输中这类高频状态不产出（SOP §2.2：不要高频轮询 / 反复提醒）。
 */
class IslandEventDispatcher(

    /** 日期桶格式：eventId 的时间桶部分（SOP §15 示例 `7515_ARRIVED_20260927`） */
    private val dayOf: (Long) -> String = ::islandDayBucket,
) {

    /**
     * 解析入库结果 → 灵动岛事件（SOP §8.1 / §8.2 / 状态变化）。
     *
     * @return null = 这个结果不值得弹灵动岛（调用方继续走普通提醒链路）
     */
    fun fromIngest(outcome: IngestOutcome): IslandEvent? {
        // 重复通知 / 低置信度丢弃：不产生任何展示（SOP §15 防重复）
        if (outcome.kind == IngestOutcome.Kind.DUPLICATE ||
            outcome.kind == IngestOutcome.Kind.IGNORED ||
            outcome.shipmentId <= 0L
        ) {
            return null
        }

        // §8.1 新快递到达：到站/可取件 + 有取件码 + 首次发现
        // 「首次发现」= 状态刚进入到站态，或已到站但取件码/地点刚发生变化
        val atStation = outcome.status == ShipmentStatus.ARRIVED ||
            outcome.status == ShipmentStatus.PICKUP_READY
        val hasCode = !outcome.pickupCode.isNullOrEmpty()
        if (atStation && hasCode &&
            (outcome.previousStatus !in ARRIVAL_STATUSES || outcome.pickupChanged)
        ) {
            return IslandEvent.Arrived(outcome.shipmentId)
        }

        // §8.2 快件进入派送：从其它状态变成派送中（statusChanged 已保证“不是原地刷新”）
        if (outcome.statusChanged && outcome.status == ShipmentStatus.OUT_FOR_DELIVERY) {
            return IslandEvent.Delivering(outcome.shipmentId)
        }

        // 其它值得告知的终态：签收 / 退回 / 取消（一次性，不会反复触发）。
        // 运输中、发货不产出事件——保持现状：由“运输中提醒”开关决定是否提醒。
        if (outcome.statusChanged && outcome.status in SIGNIFICANT_STATUSES) {
            return IslandEvent.StatusChanged(outcome.shipmentId, outcome.status)
        }

        return null
    }

    /**
     * 用户状态变化 → 灵动岛事件（SOP §8.3）。
     *
     * **必须传入数据库真实更新结果**：`updated=false`（UPDATE 影响 0 行）时一律返回 null，
     * 保证“UI 显示成功但库里没变”这种假成功不会触发 `✓ 取件完成`（SOP §9 / §19）。
     */
    fun fromUserStatus(
        shipmentId: Long,
        status: UserStatus,
        updated: Boolean,
    ): IslandEvent? {
        if (!updated || shipmentId <= 0L) return null
        return if (status == UserStatus.PICKED_UP) {
            IslandEvent.PickedUp(shipmentId)
        } else {
            null
        }
    }

    /** 事件去重键：`parcelId_eventType_日期桶`（SOP §15） */
    fun eventId(event: IslandEvent, now: Long): String =
        "${event.shipmentId}_${event.kind.name}_${dayOf(now)}"

    private companion object {
        /** 已经处于到站态（用于判断“首次发现”） */
        val ARRIVAL_STATUSES = setOf(
            ShipmentStatus.ARRIVED,
            ShipmentStatus.PICKUP_READY,
        )

        /** 值得作为事件告知用户的终态 */
        val SIGNIFICANT_STATUSES = setOf(
            ShipmentStatus.DELIVERED,
            ShipmentStatus.RETURNED,
            ShipmentStatus.CANCELLED,
        )
    }
}
