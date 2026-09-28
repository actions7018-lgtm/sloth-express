/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.matcher

import com.parcelhub.model.ShipmentStatus

/**
 * 平台状态机（SOP §11）。
 *
 * 只允许“合理方向升级”，旧通知不得覆盖新状态（SOP §28.12）。
 * 纯函数实现，无任何 IO，便于 JVM 单测。
 */
object StateMachine {

    /** 正常链路顺序，数值越大越新 */
    private val ORDER: Map<ShipmentStatus, Int> = mapOf(
        ShipmentStatus.UNKNOWN to 0,
        ShipmentStatus.SHIPPED to 1,
        ShipmentStatus.IN_TRANSIT to 2,
        ShipmentStatus.OUT_FOR_DELIVERY to 3,
        ShipmentStatus.ARRIVED to 4,
        ShipmentStatus.PICKUP_READY to 5,
        ShipmentStatus.DELIVERED to 6,
    )

    private val TERMINAL =
        setOf(ShipmentStatus.DELIVERED, ShipmentStatus.RETURNED, ShipmentStatus.CANCELLED)

    fun isTerminal(status: ShipmentStatus): Boolean = status in TERMINAL

    fun rank(status: ShipmentStatus): Int = ORDER[status] ?: -1

    /**
     * 计算收到 [incoming] 后包裹应处的状态。
     *
     * 规则：
     *  1. 未完成状态可被 RETURNED / CANCELLED 覆盖；
     *  2. 终态（已签收/退回/取消）之间不互相覆盖；
     *  3. 同链路上只允许前进（升级），不允许倒退；
     *  4. 相同状态原样返回。
     */
    fun resolve(current: ShipmentStatus, incoming: ShipmentStatus): ShipmentStatus {
        if (incoming == ShipmentStatus.UNKNOWN) return current
        if (current == ShipmentStatus.UNKNOWN) return incoming

        // 终态永远不被任何后续通知改写（含退回/取消，它们不参与链路排序）
        if (isTerminal(current)) return current

        // 未完成状态 → 退回 / 取消
        if (isTerminal(incoming)) return incoming

        // 非终态之间：只前进
        return if (rank(incoming) > rank(current)) incoming else current
    }

    /** 是否发生状态变化 */
    fun changed(current: ShipmentStatus, resolved: ShipmentStatus): Boolean = current != resolved

    /** 是否是“用户需要立刻知道”的状态（到站 / 有取件码），用于强提醒 */
    fun isStrongReminder(status: ShipmentStatus): Boolean =
        status == ShipmentStatus.ARRIVED || status == ShipmentStatus.PICKUP_READY
}
