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
import com.parcelhub.data.entity.isActiveInTransit
import com.parcelhub.data.entity.isActiveOutForDelivery
import com.parcelhub.data.entity.isArchived
import com.parcelhub.data.entity.needsPickup
import com.parcelhub.model.ShipmentStatus
import com.parcelhub.model.UserStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表归组口径单测（SOP §4.3 + 用户需求「点已取件后自动归入已签收」）。
 *
 * 覆盖：平台状态与用户状态分离、已取件不再占用待取、已签收分组同时接纳
 * 平台终态与用户已取件。
 */
class ShipmentFilterTest {

    private fun shipment(
        status: ShipmentStatus,
        userStatus: UserStatus = UserStatus.UNPROCESSED,
        pickupCode: String? = null,
        pickupLocation: String? = null,
        address: String? = null,
    ) = ShipmentEntity(
        id = 1L,
        status = status.name,
        userStatus = userStatus.name,
        pickupCode = pickupCode,
        pickupLocation = pickupLocation,
        address = address,
        carrier = "中通快递",
    )

    // ---------- 待取 ----------

    @Test
    fun arrival_with_code_is_pickup_ready() {
        val s = shipment(ShipmentStatus.ARRIVED, pickupCode = "16-4-9626")
        assertTrue(s.needsPickup)
        assertTrue(ShipmentFilter.matches(s, ShipmentFilter.PICKUP))
        assertFalse(ShipmentFilter.matches(s, ShipmentFilter.DONE))
    }

    @Test
    fun picked_up_leaves_pickup_group() {
        val s = shipment(
            ShipmentStatus.ARRIVED,
            userStatus = UserStatus.PICKED_UP,
            pickupCode = "16-4-9626",
        )
        assertFalse("已取件不得再算待取", s.needsPickup)
        assertFalse(ShipmentFilter.matches(s, ShipmentFilter.PICKUP))
    }

    @Test
    fun delivered_with_leftover_code_is_not_pickup_ready() {
        // 平台已签收但历史取件码仍在：不能因为有码就一直挂在待取
        val s = shipment(ShipmentStatus.DELIVERED, pickupCode = "16-4-9626")
        assertFalse(s.needsPickup)
        assertFalse(ShipmentFilter.matches(s, ShipmentFilter.PICKUP))
        assertTrue(ShipmentFilter.matches(s, ShipmentFilter.DONE))
    }

    @Test
    fun door_delivery_leaves_pickup_group() {
        // 家门口已送达 = 不需要去驿站取：不计入待取，也不进桌面 Widget 待办
        val s = shipment(
            ShipmentStatus.ARRIVED,
            pickupCode = "9988",
            pickupLocation = "家门口",
            address = "家门口",
        )
        assertFalse("家门口投递不得算待取", s.needsPickup)
        assertFalse(ShipmentFilter.matches(s, ShipmentFilter.PICKUP))
        assertTrue(ShipmentFilter.matches(s, ShipmentFilter.ALL))
    }

    @Test
    fun station_named_with_door_word_still_needs_pickup() {
        // 「驿站门口」不能被「门口」抢走，仍应算待取
        val s = shipment(
            ShipmentStatus.ARRIVED,
            pickupCode = "1-2-3",
            pickupLocation = "菜鸟驿站",
            address = "余杭区东连街道菜鸟驿站",
        )
        assertTrue(s.needsPickup)
    }

    @Test
    fun dismissed_leaves_pickup_group() {
        val s = shipment(ShipmentStatus.ARRIVED, userStatus = UserStatus.DISMISSED, pickupCode = "2-2-7508")
        assertFalse(s.needsPickup)
        assertFalse(ShipmentFilter.matches(s, ShipmentFilter.PICKUP))
        assertFalse("已忽略不等于已签收", ShipmentFilter.matches(s, ShipmentFilter.DONE))
        assertTrue("已忽略仍应在全部里可见", ShipmentFilter.matches(s, ShipmentFilter.ALL))
    }

    // ---------- 已签收 ----------

    @Test
    fun picked_up_is_grouped_into_delivered() {
        val s = shipment(ShipmentStatus.ARRIVED, userStatus = UserStatus.PICKED_UP, pickupCode = "16-4-9626")
        assertTrue("点已取件后必须归入已签收", s.isArchived)
        assertTrue(ShipmentFilter.matches(s, ShipmentFilter.DONE))
        assertFalse(ShipmentFilter.matches(s, ShipmentFilter.PICKUP))
        assertFalse(ShipmentFilter.matches(s, ShipmentFilter.TRANSIT))
        assertTrue(ShipmentFilter.matches(s, ShipmentFilter.ALL))
    }

    @Test
    fun platform_terminal_states_are_grouped_into_delivered() {
        for (status in listOf(ShipmentStatus.DELIVERED, ShipmentStatus.RETURNED, ShipmentStatus.CANCELLED)) {
            val s = shipment(status)
            assertTrue("$status 应归入已签收", s.isArchived)
            assertTrue(ShipmentFilter.matches(s, ShipmentFilter.DONE))
        }
    }

    @Test
    fun unprocessed_arrival_is_not_delivered() {
        val s = shipment(ShipmentStatus.ARRIVED, pickupCode = "16-4-9626")
        assertFalse(s.isArchived)
        assertFalse(ShipmentFilter.matches(s, ShipmentFilter.DONE))
    }

    // ---------- 运输中 ----------

    @Test
    fun transit_statuses_are_grouped_into_transit() {
        for (status in listOf(ShipmentStatus.SHIPPED, ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY)) {
            val s = shipment(status)
            assertTrue("$status 应归入运输中", ShipmentFilter.matches(s, ShipmentFilter.TRANSIT))
            assertFalse(ShipmentFilter.matches(s, ShipmentFilter.DONE))
        }
    }

    @Test
    fun picked_up_does_not_stay_in_transit() {
        val s = shipment(ShipmentStatus.IN_TRANSIT, userStatus = UserStatus.PICKED_UP)
        assertFalse(ShipmentFilter.matches(s, ShipmentFilter.TRANSIT))
        assertTrue(ShipmentFilter.matches(s, ShipmentFilter.DONE))
    }

    // ---------- 首页分组口径（待取 / 派送中 / 运输中） ----------

    @Test
    fun out_for_delivery_shows_in_home_group() {
        val s = shipment(ShipmentStatus.OUT_FOR_DELIVERY)
        assertTrue("未处理的派送中必须在首页派送中分组", s.isActiveOutForDelivery)
        assertFalse(s.isActiveInTransit)
    }

    @Test
    fun picked_up_out_for_delivery_leaves_home_groups() {
        // 用户需求：派送中的快递点「已取件」后即结束，
        // 不再显示在首页派送中，而是出现在快递页的「已签收」
        val s = shipment(ShipmentStatus.OUT_FOR_DELIVERY, userStatus = UserStatus.PICKED_UP)
        assertFalse("已取件不得继续挂在首页派送中", s.isActiveOutForDelivery)
        assertFalse("已取件不得归入首页运输中", s.isActiveInTransit)
        assertTrue(ShipmentFilter.matches(s, ShipmentFilter.DONE))
        assertFalse(ShipmentFilter.matches(s, ShipmentFilter.TRANSIT))
    }

    @Test
    fun picked_up_in_transit_leaves_home_groups() {
        val s = shipment(ShipmentStatus.IN_TRANSIT, userStatus = UserStatus.PICKED_UP)
        assertFalse("已取件不得归入首页运输中", s.isActiveInTransit)
        assertFalse(s.isActiveOutForDelivery)
        assertTrue(ShipmentFilter.matches(s, ShipmentFilter.DONE))
    }

    // ---------- 通用口径 ----------

    @Test
    fun every_shipment_is_visible_under_all() {
        val samples = listOf(
            shipment(ShipmentStatus.UNKNOWN),
            shipment(ShipmentStatus.ARRIVED, pickupCode = "16-4-9626"),
            shipment(ShipmentStatus.DELIVERED),
            shipment(ShipmentStatus.IN_TRANSIT, userStatus = UserStatus.PICKED_UP),
        )
        samples.forEach {
            assertTrue("全部筛选必须包含所有包裹", ShipmentFilter.matches(it, ShipmentFilter.ALL))
        }
    }

    @Test
    fun filters_are_mutually_consistent() {
        val s = shipment(ShipmentStatus.ARRIVED, pickupCode = "16-4-9626")
        // 同一条未处理的待取件：只能同时命中 全部 + 待取
        val hits = ShipmentFilter.OPTIONS.filter { ShipmentFilter.matches(s, it) }
        assertEquals(setOf(ShipmentFilter.ALL, ShipmentFilter.PICKUP), hits.toSet())
    }

    @Test
    fun options_order_matches_ui() {
        assertEquals(listOf("全部", "待取", "运输中", "已签收"), ShipmentFilter.OPTIONS)
    }

    @Test
    fun filter_keeps_input_order() {
        val a = shipment(ShipmentStatus.DELIVERED)
        val b = shipment(ShipmentStatus.ARRIVED, pickupCode = "1-1-1")
        val c = shipment(ShipmentStatus.IN_TRANSIT)
        val input = listOf(a, b, c)
        assertEquals(listOf(c), ShipmentFilter.filter(input, ShipmentFilter.TRANSIT))
        assertEquals(listOf(a), ShipmentFilter.filter(input, ShipmentFilter.DONE))
        assertEquals(input, ShipmentFilter.filter(input, ShipmentFilter.ALL))
    }
}
