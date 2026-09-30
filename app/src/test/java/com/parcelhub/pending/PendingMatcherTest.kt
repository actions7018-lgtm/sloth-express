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

import com.parcelhub.ServiceLocator
import com.parcelhub.sms.SmsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待补全匹配（SOP §30 / 场景 1·2·6·7）：
 * 昨天已发货无单号，今天带单号的事件要能落回同一条待补全订单，
 * 且任何歧义都不得强行绑定。
 */
class PendingMatcherTest {

    private val day1 = 1_000_000_000_000L
    private val hour = 60 * 60 * 1000L

    private fun pending(
        id: Long = 1L,
        orderKey: String? = null,
        platform: String? = "com.taobao",
        shippedAt: Long = day1,
        status: PendingShipmentStatus = PendingShipmentStatus.WAITING_TRACKING,
        shipmentId: Long? = 10L,
    ) = PendingShipment(
        id = id,
        orderKey = orderKey,
        platform = platform,
        shipmentId = shipmentId,
        shippedAt = shippedAt,
        expireAt = shippedAt + ShipmentReconciler.EXPIRE_DELAY_MS,
        status = status,
    )

    // ---------- 1) orderKey 优先 ----------

    @Test
    fun orderKey_match_wins() {
        val a = pending(id = 1, orderKey = "ORDER-1", platform = "com.taobao")
        val b = pending(id = 2, orderKey = "ORDER-2", platform = "com.taobao")
        val hit = PendingMatcher.match(
            candidates = listOf(a, b),
            orderKey = "ORDER-2",
            platform = "com.taobao",
            at = day1 + hour,
        )
        assertEquals(2L, hit?.pending?.id)
        assertEquals(PendingMatcher.Reason.ORDER_KEY, hit?.reason)
    }

    // ---------- 2) 平台唯一命中（SOP 场景 2：购物 App 通知补全） ----------

    @Test
    fun platform_unique_match() {
        val hit = PendingMatcher.match(
            candidates = listOf(pending()),
            orderKey = null,
            platform = "com.taobao",
            at = day1 + 6 * hour,
        )
        assertEquals(1L, hit?.pending?.id)
        assertEquals(PendingMatcher.Reason.PLATFORM, hit?.reason)
    }

    @Test
    fun same_platform_ambiguous_is_rejected() {
        val a = pending(id = 1)
        val b = pending(id = 2, shippedAt = day1 + hour)
        val hit = PendingMatcher.match(
            candidates = listOf(a, b),
            orderKey = null,
            platform = "com.taobao",
            at = day1 + 2 * hour,
        )
        assertNull(hit)
    }

    @Test
    fun cross_platform_event_never_binds() {
        // 拼多多的单号事件不能落到淘宝的待补全订单（SOP §30 不能强行绑定）
        val hit = PendingMatcher.match(
            candidates = listOf(pending(platform = "com.taobao")),
            orderKey = null,
            platform = "com.xunmeng.pinduoduo",
            at = day1 + hour,
        )
        assertNull(hit)
    }

    @Test
    fun platform_event_binds_unique_unknown_platform_pending() {
        // 「已发货」是短信发现的（平台未知），后续购物 App 通知来单号：唯一候选可认
        val hit = PendingMatcher.match(
            candidates = listOf(pending(platform = null)),
            orderKey = null,
            platform = "com.taobao",
            at = day1 + hour,
        )
        assertEquals(1L, hit?.pending?.id)
        assertEquals(PendingMatcher.Reason.PLATFORM, hit?.reason)
    }

    // ---------- 3) 中转来源：窗口内唯一（SOP 场景 1：短信补全） ----------

    @Test
    fun relay_single_waiting_matches() {
        val hit = PendingMatcher.match(
            candidates = listOf(pending()),
            orderKey = null,
            platform = null, // 短信 / 分享
            at = day1 + 30 * hour,
        )
        assertEquals(1L, hit?.pending?.id)
        assertEquals(PendingMatcher.Reason.SINGLE_IN_WINDOW, hit?.reason)
    }

    @Test
    fun relay_multiple_waiting_is_ambiguous() {
        val hit = PendingMatcher.match(
            candidates = listOf(pending(id = 1), pending(id = 2, shippedAt = day1 + 2 * hour)),
            orderKey = null,
            platform = null,
            at = day1 + 4 * hour,
        )
        assertNull(hit)
    }

    // ---------- 窗口与状态过滤 ----------

    @Test
    fun event_beyond_72h_window_does_not_match() {
        val hit = PendingMatcher.match(
            candidates = listOf(pending()),
            orderKey = null,
            platform = null,
            at = day1 + ShipmentReconciler.EXPIRE_DELAY_MS + hour,
        )
        assertNull(hit)
    }

    @Test
    fun non_waiting_records_are_ignored() {
        val found = pending(status = PendingShipmentStatus.TRACKING_FOUND)
        val expired = pending(id = 2, status = PendingShipmentStatus.EXPIRED)
        val hit = PendingMatcher.match(
            candidates = listOf(found, expired),
            orderKey = null,
            platform = null,
            at = day1 + hour,
        )
        assertNull(hit)
    }

    // ---------- 防漂移：中转来源常量 ----------

    @Test
    fun relay_sources_match_real_constants() {
        assertTrue(SmsContract.SOURCE_PACKAGE in PendingMatcher.RELAY_SOURCES)
        assertTrue(ServiceLocator.SHARE_SOURCE in PendingMatcher.RELAY_SOURCES)
        assertEquals(null, PendingMatcher.sourcePlatform(SmsContract.SOURCE_PACKAGE))
        assertEquals("com.taobao", PendingMatcher.sourcePlatform("com.taobao"))
    }
}
