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

import com.parcelhub.data.entity.ShipmentEntity
import com.parcelhub.data.entity.isArchived
import com.parcelhub.data.entity.needsPickup
import com.parcelhub.data.entity.statusEnum
import com.parcelhub.data.entity.userStatusEnum
import com.parcelhub.model.ShipmentStatus
import com.parcelhub.model.UserStatus

/**
 * 「全部包裹」列表的筛选与归组口径（SOP §14 P3）。
 *
 * 关键约定（SOP §4.3 平台状态与用户状态分离）：
 *  - 用户点「已取件」只改用户状态，平台状态保持原样；
 *  - 但归组时「已取件」必须并入 **已签收**，并且从 **待取 / 运输中** 中移出。
 *
 * 抽成纯函数对象是为了让归组规则可以被 JVM 单测直接覆盖（不依赖 Compose）。
 */
object ShipmentFilter {

    const val ALL = "全部"
    const val PICKUP = "待取"
    const val TRANSIT = "运输中"
    const val DONE = "已签收"

    /** 筛选条顺序（与 UI 展示一致） */
    val OPTIONS: List<String> = listOf(ALL, PICKUP, TRANSIT, DONE)

    private val TRANSIT_STATUSES = setOf(
        ShipmentStatus.SHIPPED,
        ShipmentStatus.IN_TRANSIT,
        ShipmentStatus.OUT_FOR_DELIVERY,
    )

    /** 某个包裹在给定筛选下是否可见 */
    fun matches(shipment: ShipmentEntity, filter: String): Boolean = when (filter) {
        PICKUP -> shipment.needsPickup
        TRANSIT -> shipment.statusEnum in TRANSIT_STATUSES &&
            shipment.userStatusEnum != UserStatus.PICKED_UP
        DONE -> shipment.isArchived
        else -> true
    }

    /** 某个筛选下的包裹列表（保持输入顺序） */
    fun filter(shipments: List<ShipmentEntity>, filter: String): List<ShipmentEntity> =
        shipments.filter { matches(it, filter) }
}
