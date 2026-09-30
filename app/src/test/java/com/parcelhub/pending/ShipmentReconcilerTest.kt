/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.pending

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 待补全延迟补偿（SOP §23/§24 / 场景 8·9）：
 * 24h → 48h 低频检查，72h 转 EXPIRED；补到单号转 FOUND；没有待补全订单不安排任务。
 */
class ShipmentReconcilerTest {

    private val shippedAt = 1_000_000_000_000L
    private val h24 = 24L * 60 * 60 * 1000
    private val h48 = 48L * 60 * 60 * 1000
    private val h72 = 72L * 60 * 60 * 1000

    private fun pending(
        nextCheckAt: Long? = shippedAt + h24,
        trackingNumber: String? = null,
        status: PendingShipmentStatus = PendingShipmentStatus.WAITING_TRACKING,
    ) = PendingShipment(
        id = 1L,
        platform = "com.taobao",
        shipmentId = 10L,
        shippedAt = shippedAt,
        trackingNumber = trackingNumber,
        nextCheckAt = nextCheckAt,
        expireAt = shippedAt + h72,
        status = status,
    )

    @Test
    fun initial_schedule_is_24h_check_72h_expire() {
        val (nextCheck, expire) = ShipmentReconciler.initial(shippedAt)
        assertEquals(shippedAt + h24, nextCheck)
        assertEquals(shippedAt + h72, expire)
    }

    @Test
    fun before_first_check_keeps() {
        val plan = ShipmentReconciler.decide(pending(), null, shippedAt + h24 - 1)
        assertEquals(ShipmentReconciler.Decision.KEEP, plan.decision)
        assertNull(plan.lastCheckAt)
    }

    @Test
    fun first_check_reschedules_to_48h() {
        val plan = ShipmentReconciler.decide(pending(), null, shippedAt + h24)
        assertEquals(ShipmentReconciler.Decision.RESCHEDULE, plan.decision)
        assertEquals(shippedAt + h48, plan.nextCheckAt)
        assertEquals(shippedAt + h24, plan.lastCheckAt)
    }

    @Test
    fun second_check_reschedules_to_expire() {
        val plan = ShipmentReconciler.decide(
            pending(nextCheckAt = shippedAt + h48),
            null,
            shippedAt + h48,
        )
        assertEquals(ShipmentReconciler.Decision.RESCHEDULE, plan.decision)
        assertEquals(shippedAt + h72, plan.nextCheckAt)
    }

    @Test
    fun beyond_72h_expires() {
        val plan = ShipmentReconciler.decide(pending(), null, shippedAt + h72)
        assertEquals(ShipmentReconciler.Decision.EXPIRE, plan.decision)
        assertEquals(shippedAt + h72, plan.lastCheckAt)
    }

    @Test
    fun pending_with_tracking_is_found() {
        val plan = ShipmentReconciler.decide(pending(trackingNumber = "SF123"), null, shippedAt + 1)
        assertEquals(ShipmentReconciler.Decision.FOUND, plan.decision)
        assertEquals("SF123", plan.trackingNumber)
    }

    @Test
    fun linked_shipment_with_tracking_is_found() {
        // 单号由别的入口回填进正式包裹：补偿检查时也能收口（SOP 场景 2）
        val plan = ShipmentReconciler.decide(pending(), "YT987", shippedAt + h24)
        assertEquals(ShipmentReconciler.Decision.FOUND, plan.decision)
        assertEquals("YT987", plan.trackingNumber)
    }

    @Test
    fun non_waiting_is_never_rescheduled() {
        val plan = ShipmentReconciler.decide(
            pending(status = PendingShipmentStatus.EXPIRED),
            null,
            shippedAt + h72 * 2,
        )
        assertEquals(ShipmentReconciler.Decision.KEEP, plan.decision)
        assertNull(plan.lastCheckAt)
    }
}
