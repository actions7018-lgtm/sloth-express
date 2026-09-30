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

import com.parcelhub.data.entity.ParcelEventEntity
import com.parcelhub.data.entity.ShipmentEntity
import com.parcelhub.model.ParcelEvent
import com.parcelhub.model.ShipmentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 匹配算法单测（SOP §5 三级匹配 + §6.5 置信度门槛）。
 */
class ShipmentMatcherTest {

    // ---------- 运单号归一化 ----------

    @Test
    fun normalize_tracking_strips_spaces_and_hyphens() {
        assertEquals("SF1234567890123", ShipmentMatcher.normalizeTracking(" sf-1234 567890123 "))
        assertNull(ShipmentMatcher.normalizeTracking(null))
        assertNull(ShipmentMatcher.normalizeTracking("   "))
        assertNull(ShipmentMatcher.normalizeTracking("---"))
    }

    // ---------- 置信度门槛 ----------

    @Test
    fun confidence_gate() {
        assertFalse(ShipmentMatcher.canCreateShipment(0.10))
        assertFalse(ShipmentMatcher.canCreateShipment(0.39))
        assertTrue(ShipmentMatcher.canCreateShipment(0.40))
        assertTrue(ShipmentMatcher.canCreateShipment(0.95))

        assertTrue(ShipmentMatcher.needsReview(0.50))
        assertFalse(ShipmentMatcher.needsReview(0.70))
    }

    // ---------- 一级匹配 ----------

    @Test
    fun match_by_tracking_only_when_identical() {
        val existing = ShipmentEntity(id = 7L, trackingNumber = "SF-1234567890123")

        val hit = ShipmentMatcher.matchByTracking("sf1234567890123", existing)
        assertNotNull(hit)
        assertEquals(7L, hit?.id)

        assertNull(ShipmentMatcher.matchByTracking("SF9999999999999", existing))
        assertNull(ShipmentMatcher.matchByTracking(null, existing))
        assertNull(ShipmentMatcher.matchByTracking("SF1234567890123", null))
    }

    // ---------- 二级匹配 ----------

    @Test
    fun match_by_order_key_returns_bound_shipment() {
        val bound = event(shipmentId = 12L, orderKey = "ORD20260926001")
        val unbound = event(shipmentId = 0L, orderKey = "ORD20260926001")

        assertEquals(12L, ShipmentMatcher.matchByOrderKey(listOf(unbound, bound)))
        assertNull(ShipmentMatcher.matchByOrderKey(listOf(unbound)))
        assertNull(ShipmentMatcher.matchByOrderKey(emptyList()))
    }

    // ---------- 三级匹配 ----------

    @Test
    fun match_by_context_requires_location_or_fingerprint() {
        val now = System.currentTimeMillis()
        val target = event(shipmentId = 33L, eventTime = now, pickupLocation = "东门驿站")
        val incoming = event(shipmentId = 0L, eventTime = now + 60_000L, pickupLocation = "东门驿站")

        // 地点一致 → 命中
        assertEquals(33L, ShipmentMatcher.matchByContext(incoming, listOf(target)))

        // 地点与指纹都不一致 → 不猜测，返回 null（SOP §6.4）
        val unrelated = event(
            shipmentId = 33L,
            eventTime = now,
            pickupLocation = "西门快递柜",
            fingerprint = "abc",
        )
        assertNull(
            ShipmentMatcher.matchByContext(
                incoming.copy(fingerprint = "xyz"),
                listOf(unrelated),
            ),
        )
    }

    @Test
    fun match_by_context_respects_time_window() {
        val now = System.currentTimeMillis()
        val target = event(
            shipmentId = 9L,
            eventTime = now,
            pickupLocation = "东门驿站",
        )
        val stale = target.copy(eventTime = now - ShipmentMatcher.TIME_WINDOW_MS - 1)
        val incoming = target.copy(shipmentId = 0L, eventTime = now)

        assertNull(ShipmentMatcher.matchByContext(incoming, listOf(stale)))
        assertEquals(9L, ShipmentMatcher.matchByContext(incoming, listOf(target)))
    }

    @Test
    fun match_by_context_prefers_fingerprint_over_location() {
        val now = System.currentTimeMillis()
        val byLocation = event(shipmentId = 1L, eventTime = now, pickupLocation = "东门驿站")
        val byFingerprint = event(
            shipmentId = 2L,
            eventTime = now,
            pickupLocation = "东门驿站",
            fingerprint = "fp-1",
        )
        val incoming = event(
            shipmentId = 0L,
            eventTime = now,
            pickupLocation = "东门驿站",
            fingerprint = "fp-1",
        )

        // 指纹 + 地点双命中（score 3）优先于仅地点命中（score 1）
        assertEquals(2L, ShipmentMatcher.matchByContext(incoming, listOf(byLocation, byFingerprint)))
    }

    @Test
    fun match_by_context_never_cross_merges_different_tracking_numbers() {
        val now = System.currentTimeMillis()

        // 同地点、同时间窗，但单号不同 → 不同包裹，绝不归并（SOP §6.4 不得猜测）
        val otherParcel = event(
            shipmentId = 44L,
            eventTime = now,
            pickupLocation = "菜鸟驿站",
            trackingNumber = "888877776666",
        )
        val incoming = event(
            shipmentId = 0L,
            eventTime = now + 60_000L,
            pickupLocation = "菜鸟驿站",
            trackingNumber = "YT1234567890123",
        )
        assertNull(ShipmentMatcher.matchByContext(incoming, listOf(otherParcel)))

        // 单号一致（忽略大小写）→ 仍然可以归并
        val sameParcel = otherParcel.copy(
            shipmentId = 66L,
            trackingNumber = "yt1234567890123",
        )
        assertEquals(66L, ShipmentMatcher.matchByContext(incoming, listOf(sameParcel)))

        // 一侧无单号 → 保持原有兜底能力（无单号通知仍可按地点归并）
        val noTracking = event(
            shipmentId = 55L,
            eventTime = now,
            pickupLocation = "菜鸟驿站",
        )
        assertEquals(55L, ShipmentMatcher.matchByContext(incoming, listOf(noTracking)))
    }

    // ---------- 取件字段合并 ----------

    @Test
    fun merge_pickup_prefers_new_but_never_clears_with_blank() {
        // 新值优先
        val merged = ShipmentMatcher.mergePickup(
            currentCode = "11-22-3344",
            currentLocation = "东门驿站",
            currentLocker = "A12",
            eventCode = "16-4-9626",
            eventLocation = "西门驿站",
        )
        assertEquals("16-4-9626", merged.first)
        assertEquals("西门驿站", merged.second)
        assertEquals("A12", merged.third)

        // 空值不得覆盖已有值（SOP §11.2）
        val kept = ShipmentMatcher.mergePickup(
            currentCode = "11-22-3344",
            currentLocation = "东门驿站",
            currentLocker = "A12",
            eventCode = null,
            eventLocation = "  ",
        )
        assertEquals("11-22-3344", kept.first)
        assertEquals("东门驿站", kept.second)
        assertEquals("A12", kept.third)
    }

    // ---------- 订单号区分（拼多多多单混排页面，防 3 单并 1 单） ----------

    @Test
    fun match_by_context_skips_when_both_order_keys_differ() {
        val now = System.currentTimeMillis()

        // 指纹（页面公共框架）雷同、时间窗也命中，但平台订单号不同 → 不同订单，绝不归并
        val otherOrder = event(
            shipmentId = 71L,
            eventTime = now,
            orderKey = "260928-434215381420088",
            fingerprint = "我的订单",
        )
        val incoming = event(
            shipmentId = 0L,
            eventTime = now + 60_000L,
            orderKey = "260928-485239099820088",
            fingerprint = "我的订单",
        )
        assertNull(ShipmentMatcher.matchByContext(incoming, listOf(otherOrder)))

        // 订单号一致 → 照常按指纹归并
        val sameOrder = otherOrder.copy(orderKey = "260928-485239099820088")
        assertEquals(71L, ShipmentMatcher.matchByContext(incoming, listOf(sameOrder)))

        // 一侧没有订单号 → 保留兜底能力（无订单号的通知仍可按指纹/地点归并）
        val noKey = otherOrder.copy(orderKey = null)
        assertEquals(71L, ShipmentMatcher.matchByContext(incoming.copy(orderKey = null), listOf(noKey)))
    }

    // ---------- 行级单号守卫（二级/三级命中后复核） ----------

    @Test
    fun tracking_row_guard_blocks_conflicting_rows() {
        // 两侧都有单号且不同 → 拒绝（别单的行）
        assertFalse(ShipmentMatcher.trackingRowUsable("777449133381845", "YT0710902997641"))
        // 任一侧没有单号 → 放行（无号行可被后到的单号补全）
        assertTrue(ShipmentMatcher.trackingRowUsable("YT0710902997641", null))
        assertTrue(ShipmentMatcher.trackingRowUsable(null, "YT0710902997641"))
        // 单号一致（归一化后）→ 放行
        assertTrue(ShipmentMatcher.trackingRowUsable("yt0710902997641", "YT0710902997641"))
    }

    // ---------- 无障碍无身份文本闸（订单列表页不建垃圾行） ----------

    @Test
    fun identityless_a11y_text_is_dropped() {
        // 列表页：无单号 + 无订单号 → 丢弃
        assertTrue(ShipmentMatcher.isIdentitylessA11yEvent("ACCESSIBILITY", null, null))
        assertTrue(ShipmentMatcher.isIdentitylessA11yEvent("ACCESSIBILITY", "  ", " "))
        // 有单号或有订单号（详情页）→ 放行
        assertFalse(ShipmentMatcher.isIdentitylessA11yEvent("ACCESSIBILITY", "YT0710902997641", null))
        assertFalse(
            ShipmentMatcher.isIdentitylessA11yEvent("ACCESSIBILITY", null, "260928-485239099820088"),
        )
        // 短信 / 通知的「已发货无单号」待补全流程不受影响
        assertFalse(ShipmentMatcher.isIdentitylessA11yEvent("SMS", null, null))
        assertFalse(ShipmentMatcher.isIdentitylessA11yEvent(null, null, null))
    }

    // ---------- OCR 截图文本与无障碍同口径（SOP V2.0 §19） ----------

    @Test
    fun identityless_ocr_text_is_dropped_like_a11y() {
        assertTrue(ShipmentMatcher.isIdentitylessA11yEvent("SCREEN_OCR", null, null))
        assertFalse(ShipmentMatcher.isIdentitylessA11yEvent("SCREEN_OCR", "YT0710904037400", null))
        // 页面通道口径：身份键去重只对「读页面」通道生效
        assertTrue(ShipmentMatcher.isPageChannel("ACCESSIBILITY"))
        assertTrue(ShipmentMatcher.isPageChannel("SCREEN_OCR"))
        assertFalse(ShipmentMatcher.isPageChannel("SMS"))
        assertFalse(ShipmentMatcher.isPageChannel("NOTIFICATION"))
        assertFalse(ShipmentMatcher.isPageChannel(null))
    }

    // ---------- 去重命中的字段补写（页面渐进加载：后到的单号/地址） ----------

    @Test
    fun refill_on_dedup_hit_completes_empty_fields_without_touching_status() {
        val arrived = ParcelEvent(
            sourcePackage = "com.xunmeng.pinduoduo",
            notificationKey = "a11y|pdd|0",
            status = ShipmentStatus.ARRIVED,
            trackingNumber = "yt0710902997641",
            carrier = "圆通速递",
            destination = "浙江省杭州市余杭区仓益绿苑56栋",
            pickupCode = "11-22-3344",
        )
        val row = ShipmentEntity(id = 5L, status = ShipmentStatus.IN_TRANSIT.name)

        val refilled = ShipmentMatcher.refillOnDedupHit(row, arrived, "YT0710902997641", 123L)
        assertNotNull(refilled)
        assertEquals("YT0710902997641", refilled?.trackingNumber)
        assertEquals("圆通速递", refilled?.carrier)
        assertEquals("浙江省杭州市余杭区仓益绿苑56栋", refilled?.address)
        assertEquals("11-22-3344", refilled?.pickupCode)
        // 状态不在此路径变更（状态推进仍走正常事件，提醒不丢）
        assertEquals(ShipmentStatus.IN_TRANSIT.name, refilled?.status)
        assertEquals(123L, refilled?.lastUpdatedAt)

        // 已有值不覆盖 + 没有新空缺 → 不写库（返回 null）
        val again = ShipmentMatcher.refillOnDedupHit(refilled!!, arrived, "777449133381845", 456L)
        assertNull(again)
    }

    private fun event(
        shipmentId: Long,
        orderKey: String? = null,
        pickupLocation: String? = null,
        fingerprint: String? = null,
        eventTime: Long = 0L,
        trackingNumber: String? = null,
    ) = ParcelEventEntity(
        shipmentId = shipmentId,
        sourcePackage = "com.cainiao.wireless",
        notificationKey = "key-$shipmentId-$eventTime-$pickupLocation-$fingerprint",
        orderKey = orderKey,
        pickupLocation = pickupLocation,
        fingerprint = fingerprint,
        trackingNumber = trackingNumber,
        eventTime = eventTime,
        dedupKey = "dk-$shipmentId-$eventTime-$pickupLocation-$fingerprint",
    )
}
