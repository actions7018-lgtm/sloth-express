/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.widget

import com.parcelhub.data.entity.ShipmentEntity
import com.parcelhub.model.ShipmentStatus
import com.parcelhub.model.UserStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面 Widget 待办投影规则（用户需求：谁能进待办、怎么排序、文案长什么样）。
 *
 * 这些规则是「数据库 / App 页面 / Widget 三者一致」的关键，
 * 因此放在纯 JVM 单测里钉死，而不是靠真机肉眼比对。
 */
class TodoWidgetItemTest {

    private fun shipment(
        id: Long,
        status: ShipmentStatus,
        userStatus: UserStatus = UserStatus.UNPROCESSED,
        pickupCode: String? = null,
        pickupLocation: String? = null,
        address: String? = null,
        tracking: String? = null,
        updatedAt: Long = 0L,
    ) = ShipmentEntity(
        id = id,
        carrier = "菜鸟",
        status = status.name,
        userStatus = userStatus.name,
        pickupCode = pickupCode,
        pickupLocation = pickupLocation,
        address = address,
        trackingNumber = tracking,
        updatedAt = updatedAt,
    )

    @Test
    fun pickup_task_with_code_shows_code_and_station() {
        val items = TodoWidgetItem.from(
            listOf(
                shipment(
                    1L,
                    ShipmentStatus.ARRIVED,
                    pickupCode = "99-9-7515",
                    pickupLocation = "菜鸟驿站",
                    tracking = "YT4482236617",
                ),
            ),
        )
        assertEquals(1, items.size)
        val item = items.first()
        assertEquals("取件码 99-9-7515｜菜鸟驿站", item.title)
        assertTrue("有取件码才显示包裹图标", item.showParcelIcon)
        assertNull("取件任务不必再占一行显示单号", item.trackingNumber)
        assertFalse(item.checked)
    }

    @Test
    fun arrived_without_code_becomes_query_task() {
        val items = TodoWidgetItem.from(
            listOf(
                shipment(
                    2L,
                    ShipmentStatus.ARRIVED,
                    pickupLocation = "菜鸟驿站",
                    tracking = "YT9900112233",
                ),
            ),
        )
        val item = items.single()
        assertEquals("待查询｜菜鸟驿站", item.title)
        assertFalse("待查询项不显示包裹图标", item.showParcelIcon)
        assertEquals("待查询项要给出单号作为查询依据", "YT9900112233", item.trackingNumber)
    }

    @Test
    fun door_delivery_never_enters_todo() {
        val items = TodoWidgetItem.from(
            listOf(
                shipment(
                    3L,
                    ShipmentStatus.ARRIVED,
                    pickupCode = "9988",
                    pickupLocation = "家门口",
                    address = "家门口",
                    tracking = "771122334455",
                ),
            ),
        )
        assertTrue("家门口投递不需要去取件，不得进入待办", items.isEmpty())
    }

    @Test
    fun picked_up_and_dismissed_never_enter_todo() {
        val rows = listOf(
            shipment(4L, ShipmentStatus.ARRIVED, userStatus = UserStatus.PICKED_UP, pickupCode = "1-1-1"),
            shipment(5L, ShipmentStatus.ARRIVED, userStatus = UserStatus.DISMISSED, pickupCode = "2-2-2"),
        )
        assertTrue(TodoWidgetItem.from(rows).isEmpty())
    }

    @Test
    fun in_transit_shipment_is_not_a_todo() {
        val rows = listOf(shipment(6L, ShipmentStatus.OUT_FOR_DELIVERY, tracking = "YT0001"))
        assertTrue(TodoWidgetItem.from(rows).isEmpty())
    }

    @Test
    fun pickup_tasks_come_before_query_tasks() {
        val rows = listOf(
            shipment(10L, ShipmentStatus.ARRIVED, pickupLocation = "菜鸟驿站", tracking = "T-Q1", updatedAt = 900L),
            shipment(
                11L,
                ShipmentStatus.ARRIVED,
                pickupCode = "16-4-9626",
                pickupLocation = "东门代收点",
                updatedAt = 100L,
            ),
        )
        val items = TodoWidgetItem.from(rows)
        assertEquals(listOf("取件码 16-4-9626｜东门代收点", "待查询｜菜鸟驿站"), items.map { it.title })
        assertEquals(listOf(11L, 10L), items.map { it.shipmentId })
    }

    @Test
    fun same_group_sorted_by_updated_at_desc() {
        val rows = listOf(
            shipment(20L, ShipmentStatus.ARRIVED, pickupCode = "A-1", pickupLocation = "驿站甲", updatedAt = 100L),
            shipment(21L, ShipmentStatus.ARRIVED, pickupCode = "B-2", pickupLocation = "驿站乙", updatedAt = 300L),
        )
        assertEquals(listOf(21L, 20L), TodoWidgetItem.from(rows).map { it.shipmentId })
    }

    @Test
    fun feedback_item_is_kept_one_beat_as_checked() {
        // 用户刚在 Widget 上点了勾：数据库里已经是 PICKED_UP，但要多留一拍显示勾选反馈
        val rows = listOf(
            shipment(
                30L,
                ShipmentStatus.OUT_FOR_DELIVERY,
                userStatus = UserStatus.PICKED_UP,
                pickupCode = "9-9-9",
                pickupLocation = "菜鸟驿站",
            ),
            shipment(31L, ShipmentStatus.ARRIVED, pickupCode = "8-8-8", pickupLocation = "菜鸟驿站"),
        )
        val items = TodoWidgetItem.from(rows, feedbackIds = setOf(30L))
        assertEquals(2, items.size)
        assertEquals("刚完成的要排在最前面", 30L, items.first().shipmentId)
        assertTrue("反馈项显示为已勾选", items.first().checked)
        assertFalse(items[1].checked)
    }

    @Test
    fun feedback_item_goes_back_to_its_original_position_when_anchored() {
        // 写库前记下的位置：30 的 updated_at 最小 → 勾选前本来就是第 3 条（下标 2），
        // 勾选后必须**原地**留在那里，否则整页先重排一次再谈淡出，
        // 「消失动画」就变成「先跳到最前面」（真机踩过）
        val rows = listOf(
            shipment(30L, ShipmentStatus.ARRIVED, userStatus = UserStatus.PICKED_UP, pickupCode = "9-9-9", pickupLocation = "甲", updatedAt = 600L),
            shipment(31L, ShipmentStatus.ARRIVED, pickupCode = "8-8-8", pickupLocation = "乙", updatedAt = 800L),
            shipment(32L, ShipmentStatus.ARRIVED, pickupCode = "7-7-7", pickupLocation = "丙", updatedAt = 700L),
        )
        val items = TodoWidgetItem.from(rows, feedbackIds = setOf(30L), anchors = mapOf(30L to 2))
        assertEquals(listOf(31L, 32L, 30L), items.map { it.shipmentId })
        assertTrue("锚点位置那条仍是勾选态", items[2].checked)
        assertFalse(items[0].checked)
    }

    @Test
    fun feedback_anchor_is_clamped_and_missing_anchor_falls_back_to_front() {
        val rows = listOf(
            shipment(40L, ShipmentStatus.ARRIVED, userStatus = UserStatus.PICKED_UP, pickupCode = "9-9-9", pickupLocation = "甲"),
            shipment(41L, ShipmentStatus.ARRIVED, pickupCode = "8-8-8", pickupLocation = "乙"),
        )
        // 越界锚点（数据在这期间变过）→ 夹到末尾，不能抛 IndexOutOfBounds
        assertEquals(
            40L,
            TodoWidgetItem.from(rows, feedbackIds = setOf(40L), anchors = mapOf(40L to 99))
                .last().shipmentId,
        )
        // 没有锚点 → 退回「排最前」的兜底
        assertEquals(
            40L,
            TodoWidgetItem.from(rows, feedbackIds = setOf(40L)).first().shipmentId,
        )
    }

    @Test
    fun feedback_of_unknown_id_is_ignored() {
        val rows = listOf(shipment(40L, ShipmentStatus.ARRIVED, pickupCode = "7-7-7", pickupLocation = "驿站"))
        val items = TodoWidgetItem.from(rows, feedbackIds = setOf(999L))
        assertEquals(1, items.size)
        assertFalse(items.first().checked)
    }
}
