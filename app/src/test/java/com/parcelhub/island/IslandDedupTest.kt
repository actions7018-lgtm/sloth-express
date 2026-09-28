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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 防重复提醒单测（SOP §15）。
 *
 * 两个硬约束：
 *  1. 同一包裹 + 同一类型 + 同一自然日**只弹一次**；
 *  2. 记录要能跨 App 重启保留（SOP §22 测试 5），这里通过“同一个 store 换一天/换实例”
 *     来模拟重启与跨天。
 */
class IslandDedupTest {

    private val store = InMemorySeenStore()
    private val dedup = IslandDedup(store) { "20260927" }

    private val arrived = IslandEvent.Arrived(7515L)

    @Test
    fun first_event_is_shown() {
        assertTrue(dedup.shouldShow(arrived, now = 1_000L))
    }

    @Test
    fun same_event_same_day_shows_only_once() {
        assertTrue(dedup.shouldShow(arrived, now = 1_000L))
        // 同一状态连续刷新不能再弹（SOP §8.2 / §15）
        assertFalse(dedup.shouldShow(arrived, now = 2_000L))
        assertFalse(dedup.shouldShow(arrived, now = 86_400_000L))
        assertEquals(1, store.size())
    }

    @Test
    fun different_type_or_shipment_is_independent() {
        assertTrue(dedup.shouldShow(arrived, now = 1_000L))
        assertTrue(dedup.shouldShow(IslandEvent.PickedUp(7515L), now = 2_000L))
        assertTrue(dedup.shouldShow(IslandEvent.Arrived(7516L), now = 3_000L))
        assertEquals(3, store.size())
    }

    @Test
    fun event_id_follows_sop_format() {
        assertEquals("7515_ARRIVED_20260927", dedup.eventId(arrived, now = 1_000L))
    }

    @Test
    fun record_survives_process_restart_and_expires_on_new_day() {
        assertTrue(dedup.shouldShow(arrived, now = 1_000L))

        // 模拟 App 重启：新建实例，但底层存储是同一份 → 当天仍然不再弹
        val afterRestart = IslandDedup(store) { "20260927" }
        assertFalse(afterRestart.shouldShow(arrived, now = 2_000L))

        // 模拟跨天：日期桶变了 → 放行，同时旧记录被 rollDay 清掉，存储不随天数增长
        val nextDay = IslandDedup(store) { "20260928" }
        assertTrue(nextDay.shouldShow(arrived, now = 3_000L))
        assertEquals(1, store.size())
    }

    @Test
    fun store_is_cleared_when_day_rolls() {
        assertTrue(dedup.shouldShow(arrived, now = 1_000L))
        assertTrue(dedup.shouldShow(IslandEvent.Delivering(1L), now = 2_000L))
        assertEquals(2, store.size())

        store.rollDay("20260928")
        assertEquals(0, store.size())
        assertFalse(store.hasSeen("7515_ARRIVED_20260927"))
    }
}
