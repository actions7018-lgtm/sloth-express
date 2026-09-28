/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.ui

import com.parcelhub.model.ShipmentStatus
import com.parcelhub.model.UserStatus
import com.parcelhub.ui.components.ChipStyle
import com.parcelhub.ui.components.chipLabelOf
import com.parcelhub.ui.components.chipStyleOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 状态角标口径：**用户状态优先于平台状态**（SOP §4.3）。
 *
 * 真机缺陷：详情页调用 `StatusChip` 时没传 `userStatus`，已取件后角标仍是黄色「待取」。
 */
class StatusChipTest {

    // ---- 真机缺陷回归：已取件必须盖过平台「待取」 ----

    @Test
    fun picked_up_arrived_shipment_shows_picked_up_chip() {
        assertEquals(ChipStyle.USER_DONE, chipStyleOf(ShipmentStatus.ARRIVED, UserStatus.PICKED_UP))
        assertEquals("已取件", chipLabelOf(ShipmentStatus.ARRIVED, UserStatus.PICKED_UP))
    }

    @Test
    fun picked_up_pickup_ready_shipment_shows_picked_up_chip() {
        assertEquals(
            ChipStyle.USER_DONE,
            chipStyleOf(ShipmentStatus.PICKUP_READY, UserStatus.PICKED_UP),
        )
        assertEquals("已取件", chipLabelOf(ShipmentStatus.PICKUP_READY, UserStatus.PICKED_UP))
    }

    @Test
    fun dismissed_shipment_shows_dismissed_chip() {
        assertEquals(ChipStyle.USER_DONE, chipStyleOf(ShipmentStatus.ARRIVED, UserStatus.DISMISSED))
        assertEquals("已忽略", chipLabelOf(ShipmentStatus.ARRIVED, UserStatus.DISMISSED))
    }

    // ---- 未操作时仍按平台状态显示（默认口径不回退） ----

    @Test
    fun unprocessed_arrived_shipment_keeps_pickup_chip() {
        assertEquals(ChipStyle.PICKUP, chipStyleOf(ShipmentStatus.ARRIVED, UserStatus.UNPROCESSED))
        assertEquals("待取", chipLabelOf(ShipmentStatus.ARRIVED, UserStatus.UNPROCESSED))
    }

    @Test
    fun marked_read_does_not_change_chip() {
        assertEquals(ChipStyle.PICKUP, chipStyleOf(ShipmentStatus.ARRIVED, UserStatus.MARKED_READ))
        assertEquals("待取", chipLabelOf(ShipmentStatus.ARRIVED, UserStatus.MARKED_READ))
    }

    @Test
    fun out_for_delivery_shows_dispatch_chip() {
        assertEquals(
            ChipStyle.OUT_FOR_DELIVERY,
            chipStyleOf(ShipmentStatus.OUT_FOR_DELIVERY, UserStatus.UNPROCESSED),
        )
        assertEquals("派送中", chipLabelOf(ShipmentStatus.OUT_FOR_DELIVERY, UserStatus.UNPROCESSED))
    }

    @Test
    fun in_transit_shows_platform_label() {
        assertEquals(ChipStyle.PLAIN, chipStyleOf(ShipmentStatus.IN_TRANSIT, UserStatus.UNPROCESSED))
        assertEquals("运输中", chipLabelOf(ShipmentStatus.IN_TRANSIT, UserStatus.UNPROCESSED))
    }

    @Test
    fun delivered_shows_delivered_chip() {
        assertEquals(ChipStyle.DELIVERED, chipStyleOf(ShipmentStatus.DELIVERED, UserStatus.UNPROCESSED))
        assertEquals("已签收", chipLabelOf(ShipmentStatus.DELIVERED, UserStatus.UNPROCESSED))
    }

    @Test
    fun delivered_and_picked_up_still_shows_picked_up() {
        // 已签收分组里不能再挂「待取」，同理也不该被平台态盖掉用户已取件
        assertEquals(ChipStyle.USER_DONE, chipStyleOf(ShipmentStatus.DELIVERED, UserStatus.PICKED_UP))
        assertEquals("已取件", chipLabelOf(ShipmentStatus.DELIVERED, UserStatus.PICKED_UP))
    }

    @Test
    fun returned_and_cancelled_use_error_style() {
        assertEquals(ChipStyle.ERROR, chipStyleOf(ShipmentStatus.RETURNED, UserStatus.UNPROCESSED))
        assertEquals("已退回", chipLabelOf(ShipmentStatus.RETURNED, UserStatus.UNPROCESSED))
        assertEquals(ChipStyle.ERROR, chipStyleOf(ShipmentStatus.CANCELLED, UserStatus.UNPROCESSED))
        assertEquals("已取消", chipLabelOf(ShipmentStatus.CANCELLED, UserStatus.UNPROCESSED))
    }

    @Test
    fun unknown_status_uses_plain_style() {
        assertEquals(ChipStyle.PLAIN, chipStyleOf(ShipmentStatus.UNKNOWN, UserStatus.UNPROCESSED))
        assertEquals("已发现", chipLabelOf(ShipmentStatus.UNKNOWN, UserStatus.UNPROCESSED))
    }
}
