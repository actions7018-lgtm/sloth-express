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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 平台状态机单测（SOP §11 / 验收 §28.12：旧通知不得覆盖新状态）。
 */
class StateMachineTest {

    @Test
    fun forward_along_chain() {
        assertEquals(
            ShipmentStatus.IN_TRANSIT,
            StateMachine.resolve(ShipmentStatus.SHIPPED, ShipmentStatus.IN_TRANSIT),
        )
        assertEquals(
            ShipmentStatus.ARRIVED,
            StateMachine.resolve(ShipmentStatus.IN_TRANSIT, ShipmentStatus.ARRIVED),
        )
        assertEquals(
            ShipmentStatus.PICKUP_READY,
            StateMachine.resolve(ShipmentStatus.ARRIVED, ShipmentStatus.PICKUP_READY),
        )
        assertEquals(
            ShipmentStatus.DELIVERED,
            StateMachine.resolve(ShipmentStatus.PICKUP_READY, ShipmentStatus.DELIVERED),
        )
    }

    @Test
    fun never_move_backward() {
        val cases = listOf(
            Triple(ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY),
            Triple(ShipmentStatus.PICKUP_READY, ShipmentStatus.SHIPPED, ShipmentStatus.PICKUP_READY),
            Triple(ShipmentStatus.ARRIVED, ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.ARRIVED),
            Triple(ShipmentStatus.DELIVERED, ShipmentStatus.ARRIVED, ShipmentStatus.DELIVERED),
        )
        for ((current, incoming, expected) in cases) {
            assertEquals("$current <- $incoming", expected, StateMachine.resolve(current, incoming))
            assertFalse(StateMachine.changed(current, StateMachine.resolve(current, incoming)))
        }
    }

    @Test
    fun terminal_state_never_overwritten() {
        for (terminal in listOf(
            ShipmentStatus.DELIVERED,
            ShipmentStatus.RETURNED,
            ShipmentStatus.CANCELLED,
        )) {
            for (incoming in ShipmentStatus.entries) {
                assertEquals(
                    "终态 $terminal 不应被 $incoming 覆盖",
                    terminal,
                    StateMachine.resolve(terminal, incoming),
                )
            }
        }
    }

    @Test
    fun unfinished_can_become_returned_or_cancelled() {
        assertEquals(
            ShipmentStatus.RETURNED,
            StateMachine.resolve(ShipmentStatus.PICKUP_READY, ShipmentStatus.RETURNED),
        )
        assertEquals(
            ShipmentStatus.CANCELLED,
            StateMachine.resolve(ShipmentStatus.IN_TRANSIT, ShipmentStatus.CANCELLED),
        )
    }

    @Test
    fun unknown_keeps_current() {
        assertEquals(
            ShipmentStatus.SHIPPED,
            StateMachine.resolve(ShipmentStatus.SHIPPED, ShipmentStatus.UNKNOWN),
        )
        assertEquals(
            ShipmentStatus.SHIPPED,
            StateMachine.resolve(ShipmentStatus.UNKNOWN, ShipmentStatus.SHIPPED),
        )
        assertEquals(
            ShipmentStatus.UNKNOWN,
            StateMachine.resolve(ShipmentStatus.UNKNOWN, ShipmentStatus.UNKNOWN),
        )
    }

    @Test
    fun same_status_is_stable() {
        for (status in ShipmentStatus.entries) {
            assertEquals(status, StateMachine.resolve(status, status))
            assertFalse(StateMachine.changed(status, status))
        }
    }

    @Test
    fun strong_reminder_only_for_arrival_or_pickup() {
        assertTrue(StateMachine.isStrongReminder(ShipmentStatus.ARRIVED))
        assertTrue(StateMachine.isStrongReminder(ShipmentStatus.PICKUP_READY))
        assertFalse(StateMachine.isStrongReminder(ShipmentStatus.IN_TRANSIT))
        assertFalse(StateMachine.isStrongReminder(ShipmentStatus.DELIVERED))
    }

    /** 全状态组合不变量：结果不倒退、终态不被改写。 */
    @Test
    fun invariants_over_all_pairs() {
        for (current in ShipmentStatus.entries) {
            for (incoming in ShipmentStatus.entries) {
                val resolved = StateMachine.resolve(current, incoming)

                if (StateMachine.isTerminal(current)) {
                    assertEquals("终态被改写: $current <- $incoming", current, resolved)
                }

                if (!StateMachine.isTerminal(current) &&
                    !StateMachine.isTerminal(incoming) &&
                    incoming != ShipmentStatus.UNKNOWN
                ) {
                    assertTrue(
                        "状态倒退: $current <- $incoming -> $resolved",
                        StateMachine.rank(resolved) >= StateMachine.rank(current),
                    )
                }

                // 幂等：结果再合并同一条 incoming 不变化
                assertEquals(resolved, StateMachine.resolve(resolved, incoming))
            }
        }
    }
}
