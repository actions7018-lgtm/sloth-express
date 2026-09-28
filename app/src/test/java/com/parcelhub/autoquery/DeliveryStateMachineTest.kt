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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配送状态机单测（EXPRESS_SMART_QUERY SOP §7）。
 *
 * 重点：链路前进、状态不倒退、允许「派送中 → 驿站 → 再次派送」的状态修正、终态不被覆盖。
 */
class DeliveryStateMachineTest {

    private fun resolve(current: DeliveryStatus, incoming: DeliveryStatus) =
        DeliveryStateMachine.resolve(current, incoming)

    /** SOP §7 主链路可一路前进 */
    @Test
    fun forward_along_chain() {
        assertEquals(
            DeliveryStatus.IN_TRANSIT,
            resolve(DeliveryStatus.WAITING, DeliveryStatus.IN_TRANSIT),
        )
        assertEquals(
            DeliveryStatus.OUT_FOR_DELIVERY,
            resolve(DeliveryStatus.IN_TRANSIT, DeliveryStatus.OUT_FOR_DELIVERY),
        )
        assertEquals(
            DeliveryStatus.ARRIVED_STATION,
            resolve(DeliveryStatus.OUT_FOR_DELIVERY, DeliveryStatus.ARRIVED_STATION),
        )
        assertEquals(
            DeliveryStatus.DELIVERED_DOOR,
            resolve(DeliveryStatus.OUT_FOR_DELIVERY, DeliveryStatus.DELIVERED_DOOR),
        )
        assertEquals(
            DeliveryStatus.PICKED_UP,
            resolve(DeliveryStatus.ARRIVED_STATION, DeliveryStatus.PICKED_UP),
        )
    }

    /** 允许跨级前进：运输中直接到站、派送中直接签收 */
    @Test
    fun forward_jump_allowed() {
        assertEquals(
            DeliveryStatus.ARRIVED_STATION,
            resolve(DeliveryStatus.IN_TRANSIT, DeliveryStatus.ARRIVED_STATION),
        )
        assertEquals(
            DeliveryStatus.DELIVERED_DOOR,
            resolve(DeliveryStatus.IN_TRANSIT, DeliveryStatus.DELIVERED_DOOR),
        )
    }

    /** 状态不倒退（SOP §7：除状态修正外不允许回退） */
    @Test
    fun never_move_backward() {
        val cases = listOf(
            Triple(DeliveryStatus.IN_TRANSIT, DeliveryStatus.WAITING, DeliveryStatus.IN_TRANSIT),
            Triple(DeliveryStatus.OUT_FOR_DELIVERY, DeliveryStatus.IN_TRANSIT, DeliveryStatus.OUT_FOR_DELIVERY),
            Triple(DeliveryStatus.ARRIVED_STATION, DeliveryStatus.WAITING, DeliveryStatus.ARRIVED_STATION),
            Triple(DeliveryStatus.ARRIVED_STATION, DeliveryStatus.IN_TRANSIT, DeliveryStatus.ARRIVED_STATION),
            Triple(DeliveryStatus.OUT_FOR_DELIVERY, DeliveryStatus.WAITING, DeliveryStatus.OUT_FOR_DELIVERY),
        )
        for ((current, incoming, expected) in cases) {
            assertEquals("$current <- $incoming", expected, resolve(current, incoming))
            assertFalse(DeliveryStateMachine.changed(current, resolve(current, incoming)))
        }
    }

    /** SOP §7 唯一允许的状态修正：派送中 → 驿站 → 再次派送 */
    @Test
    fun correction_arrived_station_back_to_out_for_delivery() {
        assertEquals(
            DeliveryStatus.ARRIVED_STATION,
            resolve(DeliveryStatus.OUT_FOR_DELIVERY, DeliveryStatus.ARRIVED_STATION),
        )
        assertEquals(
            DeliveryStatus.OUT_FOR_DELIVERY,
            resolve(DeliveryStatus.ARRIVED_STATION, DeliveryStatus.OUT_FOR_DELIVERY),
        )
        assertTrue(DeliveryStateMachine.changed(DeliveryStatus.ARRIVED_STATION, DeliveryStatus.OUT_FOR_DELIVERY))
    }

    /** 终态不被任何后续状态改写 */
    @Test
    fun terminal_never_overwritten() {
        val incoming = listOf(
            DeliveryStatus.WAITING,
            DeliveryStatus.IN_TRANSIT,
            DeliveryStatus.OUT_FOR_DELIVERY,
            DeliveryStatus.ARRIVED_STATION,
            DeliveryStatus.DELIVERED_DOOR,
            DeliveryStatus.PICKED_UP,
        )
        for (terminal in listOf(DeliveryStatus.DELIVERED_DOOR, DeliveryStatus.PICKED_UP)) {
            for (next in incoming) {
                assertEquals("$terminal <- $next", terminal, resolve(terminal, next))
            }
        }
    }

    /** UNKNOWN 语义：新状态未知不改写，原状态未知则采纳新状态 */
    @Test
    fun unknown_handling() {
        assertEquals(
            DeliveryStatus.IN_TRANSIT,
            resolve(DeliveryStatus.IN_TRANSIT, DeliveryStatus.UNKNOWN),
        )
        assertEquals(
            DeliveryStatus.ARRIVED_STATION,
            resolve(DeliveryStatus.UNKNOWN, DeliveryStatus.ARRIVED_STATION),
        )
        assertEquals(
            DeliveryStatus.UNKNOWN,
            resolve(DeliveryStatus.UNKNOWN, DeliveryStatus.UNKNOWN),
        )
    }

    /** 非终态可被终态覆盖（已到站 → 已签收 / 已取件） */
    @Test
    fun terminal_from_non_terminal() {
        assertEquals(
            DeliveryStatus.DELIVERED_DOOR,
            resolve(DeliveryStatus.ARRIVED_STATION, DeliveryStatus.DELIVERED_DOOR),
        )
        assertEquals(
            DeliveryStatus.PICKED_UP,
            resolve(DeliveryStatus.OUT_FOR_DELIVERY, DeliveryStatus.PICKED_UP),
        )
    }

    /** 状态变化 → 需要重新评估自动查询决策（SOP §4） */
    @Test
    fun re_decision_only_on_change() {
        assertTrue(
            DeliveryStateMachine.requiresReDecision(
                DeliveryStatus.OUT_FOR_DELIVERY,
                DeliveryStatus.ARRIVED_STATION,
            ),
        )
        assertFalse(
            DeliveryStateMachine.requiresReDecision(
                DeliveryStatus.ARRIVED_STATION,
                DeliveryStatus.ARRIVED_STATION,
            ),
        )
        assertFalse(
            DeliveryStateMachine.requiresReDecision(
                DeliveryStatus.IN_TRANSIT,
                DeliveryStatus.WAITING,
            ),
        )
    }
}
