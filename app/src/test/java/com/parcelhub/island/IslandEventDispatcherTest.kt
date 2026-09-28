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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 灵动岛事件分发单测（SOP §6 事件模型 / §8 触发规则 / §15 防重复的前置条件）。
 *
 * 覆盖“该弹的弹、不该弹的绝不弹”——灵动岛是**短时打断**，
 * 误弹比漏弹更伤体验（SOP §8.2：同一状态连续刷新不应反复弹出）。
 */
class IslandEventDispatcherTest {

    private val dispatcher = IslandEventDispatcher { "20260927" }

    private fun outcome(
        kind: IngestOutcome.Kind = IngestOutcome.Kind.UPDATED,
        shipmentId: Long = 7L,
        previous: ShipmentStatus = ShipmentStatus.IN_TRANSIT,
        status: ShipmentStatus = ShipmentStatus.IN_TRANSIT,
        statusChanged: Boolean = previous != status,
        pickupCode: String? = null,
        pickupLocation: String? = null,
        pickupChanged: Boolean = false,
        confidence: Double = 0.9,
    ) = IngestOutcome(
        kind = kind,
        shipmentId = shipmentId,
        previousStatus = previous,
        status = status,
        statusChanged = statusChanged,
        pickupCode = pickupCode,
        pickupLocation = pickupLocation,
        pickupChanged = pickupChanged,
        confidence = confidence,
    )

    // ---------- SOP §8.1 新快递到达 ----------

    @Test
    fun arrival_with_pickup_code_fires_arrived() {
        val event = dispatcher.fromIngest(
            outcome(
                previous = ShipmentStatus.IN_TRANSIT,
                status = ShipmentStatus.ARRIVED,
                pickupCode = "99-9-7515",
                pickupLocation = "菜鸟驿站",
                pickupChanged = true,
            ),
        )
        assertEquals(IslandEvent.Arrived(7L), event)
        assertEquals(IslandEvent.Kind.ARRIVED, event!!.kind)
    }

    @Test
    fun arrival_without_pickup_code_is_silent() {
        // SOP §8.1 三个条件缺一不可：没有取件码就不算“新快递已到达”
        assertNull(
            dispatcher.fromIngest(
                outcome(
                    previous = ShipmentStatus.IN_TRANSIT,
                    status = ShipmentStatus.ARRIVED,
                    pickupChanged = false,
                ),
            ),
        )
    }

    @Test
    fun new_shipment_already_arrived_fires_arrived() {
        // 首次入库直接是到站 + 取件码（previous = UNKNOWN，不属于到站态）
        val event = dispatcher.fromIngest(
            outcome(
                kind = IngestOutcome.Kind.NEW_SHIPMENT,
                previous = ShipmentStatus.UNKNOWN,
                status = ShipmentStatus.PICKUP_READY,
                pickupCode = "10-1-1001",
                pickupChanged = true,
            ),
        )
        assertEquals(IslandEvent.Arrived(7L), event)
    }

    @Test
    fun repeated_arrival_state_does_not_refire() {
        // 已经在到站态、取件码也没变 → 不能再次弹（SOP §8.2）
        assertNull(
            dispatcher.fromIngest(
                outcome(
                    previous = ShipmentStatus.ARRIVED,
                    status = ShipmentStatus.ARRIVED,
                    pickupCode = "99-9-7515",
                    pickupChanged = false,
                ),
            ),
        )
    }

    // ---------- SOP §8.2 进入派送 ----------

    @Test
    fun transition_into_delivering_fires_delivering() {
        val event = dispatcher.fromIngest(
            outcome(previous = ShipmentStatus.IN_TRANSIT, status = ShipmentStatus.OUT_FOR_DELIVERY),
        )
        assertEquals(IslandEvent.Delivering(7L), event)
    }

    @Test
    fun delivering_status_refresh_is_silent() {
        // 已经是派送中、原地刷新 → 不重复弹
        assertNull(
            dispatcher.fromIngest(
                outcome(previous = ShipmentStatus.OUT_FOR_DELIVERY, status = ShipmentStatus.OUT_FOR_DELIVERY),
            ),
        )
    }

    @Test
    fun transit_change_is_silent() {
        // 运输中是高频状态：不产出灵动岛事件（SOP §2.2 不做高频轮询提醒）
        assertNull(
            dispatcher.fromIngest(
                outcome(previous = ShipmentStatus.SHIPPED, status = ShipmentStatus.IN_TRANSIT),
            ),
        )
    }

    @Test
    fun delivered_fires_status_changed() {
        val event = dispatcher.fromIngest(
            outcome(previous = ShipmentStatus.OUT_FOR_DELIVERY, status = ShipmentStatus.DELIVERED),
        )
        assertEquals(IslandEvent.StatusChanged(7L, ShipmentStatus.DELIVERED), event)
    }

    // ---------- 无效入库结果 ----------

    @Test
    fun duplicate_and_ignored_outcomes_are_silent() {
        assertNull(dispatcher.fromIngest(outcome(kind = IngestOutcome.Kind.DUPLICATE)))
        assertNull(dispatcher.fromIngest(outcome(kind = IngestOutcome.Kind.IGNORED)))
        assertNull(dispatcher.fromIngest(outcome(shipmentId = 0L)))
    }

    // ---------- SOP §8.3 标记已取件（必须是写库成功） ----------

    @Test
    fun picked_up_fires_only_when_database_updated() {
        assertEquals(
            IslandEvent.PickedUp(7L),
            dispatcher.fromUserStatus(7L, UserStatus.PICKED_UP, updated = true),
        )
        // UPDATE 影响 0 行 → 绝不给“✓ 取件完成”（SOP §9 / §19）
        assertNull(dispatcher.fromUserStatus(7L, UserStatus.PICKED_UP, updated = false))
        assertNull(dispatcher.fromUserStatus(0L, UserStatus.PICKED_UP, updated = true))
        assertNull(dispatcher.fromUserStatus(7L, UserStatus.DISMISSED, updated = true))
        assertNull(dispatcher.fromUserStatus(7L, UserStatus.UNPROCESSED, updated = true))
    }

    // ---------- SOP §15 eventId ----------

    @Test
    fun event_id_matches_sop_example() {
        // SOP §15 示例：7515_ARRIVED_20260927
        assertEquals(
            "7515_ARRIVED_20260927",
            dispatcher.eventId(IslandEvent.Arrived(7515L), now = 0L),
        )
        assertEquals(
            "7515_PICKED_UP_20260927",
            dispatcher.eventId(IslandEvent.PickedUp(7515L), now = 0L),
        )
    }

    @Test
    fun different_kinds_have_distinct_event_ids() {
        val arrived = dispatcher.eventId(IslandEvent.Arrived(7L), 0L)
        val pickedUp = dispatcher.eventId(IslandEvent.PickedUp(7L), 0L)
        assertTrue(arrived != pickedUp)
    }
}
