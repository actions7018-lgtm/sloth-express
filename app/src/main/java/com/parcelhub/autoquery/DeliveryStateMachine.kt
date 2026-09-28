/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.autoquery

/**
 * 配送状态机（EXPRESS_SMART_QUERY SOP §7）。
 *
 * 链路（§7）：
 * ```
 * WAITING → IN_TRANSIT → OUT_FOR_DELIVERY
 *                            ├→ DELIVERED_DOOR
 *                            └→ ARRIVED_STATION → PICKED_UP
 * ```
 *
 * 与平台通知状态机（`matcher.StateMachine`，只前进不倒退）不同，本状态机
 * **允许状态修正**：驿站可被再次派送（`ARRIVED_STATION → OUT_FOR_DELIVERY`），
 * 除此之外仍不允许状态倒退，终态不被任何后续状态覆盖。
 *
 * 纯函数实现，无任何 IO，便于 JVM 单测。
 */
object DeliveryStateMachine {

    /** 正常链路顺序，数值越大越新（SOP §7） */
    private val ORDER: Map<DeliveryStatus, Int> = mapOf(
        DeliveryStatus.WAITING to 0,
        DeliveryStatus.IN_TRANSIT to 1,
        DeliveryStatus.OUT_FOR_DELIVERY to 2,
        DeliveryStatus.ARRIVED_STATION to 3,
        DeliveryStatus.DELIVERED_DOOR to 4,
        DeliveryStatus.PICKED_UP to 5,
    )

    /** 终态：已送达（门口）/ 已取件，不被任何后续状态改写（SOP §7 链路末端） */
    private val TERMINAL = setOf(
        DeliveryStatus.DELIVERED_DOOR,
        DeliveryStatus.PICKED_UP,
    )

    fun isTerminal(status: DeliveryStatus): Boolean = status in TERMINAL

    fun rank(status: DeliveryStatus): Int = ORDER[status] ?: -1

    /**
     * 计算收到 [incoming] 后该包裹应处的配送状态。
     *
     * 规则：
     *  1. `incoming` 为 UNKNOWN 不改写（信息不足不降级）；
     *  2. `current` 为 UNKNOWN 直接采纳新状态；
     *  3. 终态（已送达 / 已取件）不被任何后续状态覆盖；
     *  4. 允许状态修正：已到驿站被再次派送（SOP §7「派送中 → 驿站 → 再次派送」）；
     *  5. 其余情况只允许前进（含跨级前进，如运输中直接到站），不允许倒退。
     */
    fun resolve(current: DeliveryStatus, incoming: DeliveryStatus): DeliveryStatus {
        if (incoming == DeliveryStatus.UNKNOWN) return current
        if (current == DeliveryStatus.UNKNOWN) return incoming
        if (isTerminal(current)) return current
        if (isTerminal(incoming)) return incoming

        // 状态修正：驿站 → 再次派送（唯一允许的“回退”，本质是改判，不是倒退）
        if (current == DeliveryStatus.ARRIVED_STATION &&
            incoming == DeliveryStatus.OUT_FOR_DELIVERY
        ) {
            return incoming
        }

        return if (rank(incoming) >= rank(current)) incoming else current
    }

    /** 是否发生状态变化 */
    fun changed(current: DeliveryStatus, resolved: DeliveryStatus): Boolean = current != resolved

    /**
     * 是否需要重新评估自动查询决策（SOP §4：决策随状态变化重算）。
     *
     * @param incoming 新收到的状态（不是 resolve 的结果），内部自行 [resolve] 后再比较
     */
    fun requiresReDecision(current: DeliveryStatus, incoming: DeliveryStatus): Boolean =
        changed(current, resolve(current, incoming))
}
