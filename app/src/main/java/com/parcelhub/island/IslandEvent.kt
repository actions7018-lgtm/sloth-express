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

import com.parcelhub.model.ShipmentStatus

/**
 * 灵动岛事件（SOP §6）。
 *
 * 只带 `shipmentId`，展示时才去 Repository 取内容——与 SOP §6「事件只带 parcelId」一致，
 * 也避免单号 / 取件码在内存里长期留存（隐私口径：解析与保存都在本机）。
 *
 * 与 `com.parcelhub.model.ParcelEvent` 的区别：
 *  - `model.ParcelEvent` 是**解析层**的原始事件（来源通知 → 解析 → 入库）；
 *  - 本类是**展示层**的状态变化事件（入库结果 → 灵动岛），由 [IslandEventDispatcher] 产生。
 */
sealed class IslandEvent {

    abstract val shipmentId: Long

    abstract val kind: Kind

    /** 事件类型：去重 eventId 的组成部分（SOP §15） */
    enum class Kind {
        /** 新快递到达（SOP §8.1：到站 + 取件码 + 首次发现） */
        ARRIVED,

        /** 进入派送（SOP §8.2：其它状态 → 派送中） */
        DELIVERING,

        /** 用户完成取件（SOP §8.3） */
        PICKED_UP,

        /** 其它值得关注的状态变化（签收 / 退回 / 取消） */
        STATUS_CHANGED,
    }

    /** 新快递已到达 */
    data class Arrived(override val shipmentId: Long) : IslandEvent() {
        override val kind: Kind = Kind.ARRIVED
    }

    /** 快递进入派送 */
    data class Delivering(override val shipmentId: Long) : IslandEvent() {
        override val kind: Kind = Kind.DELIVERING
    }

    /** 用户点「标记已取件」且数据库确实更新成功 */
    data class PickedUp(override val shipmentId: Long) : IslandEvent() {
        override val kind: Kind = Kind.PICKED_UP
    }

    /** 其它状态变化；[status] 用于生成文案 */
    data class StatusChanged(
        override val shipmentId: Long,
        val status: ShipmentStatus,
    ) : IslandEvent() {
        override val kind: Kind = Kind.STATUS_CHANGED
    }
}
