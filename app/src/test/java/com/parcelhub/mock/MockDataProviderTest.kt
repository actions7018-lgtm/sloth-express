/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.mock

import com.parcelhub.data.entity.isActiveInTransit
import com.parcelhub.data.entity.isArchived
import com.parcelhub.data.entity.needsPickup
import com.parcelhub.data.entity.statusEnum
import com.parcelhub.data.entity.userStatusEnum
import com.parcelhub.model.ShipmentStatus
import com.parcelhub.model.UserStatus
import com.parcelhub.pending.PendingShipmentStatus
import com.parcelhub.pending.PendingSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mock 数据规格断言（自动注入规格 §1/§5/§6/§7/§9）：
 * 50 条标准数据的分布、生命周期不变量、单号/订单键唯一、落库状态映射。
 */
class MockDataProviderTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_700_000_000_000L

    private val all = MockParcelDataProvider.parcels(now)
    private val standard = all.filter { it.id <= MockParcelDataProvider.SHIPMENT_ID_BASE + 50 }
    private val sceneE = all.first { it.id == MockParcelDataProvider.SHIPMENT_ID_BASE + 51 }

    // ---------- §1 分布 ----------

    @Test
    fun standard_count_is_50_plus_one_edge_row() {
        assertEquals(50, standard.size)
        assertEquals(1, all.size - standard.size) // 场景 E 边界行不计入 50（§11）
    }

    @Test
    fun status_distribution_matches_spec() {
        assertEquals(8, standard.count { it.scenario.startsWith("刚下单") })
        assertEquals(5, standard.count { it.scenario.startsWith("商家已接单") })
        assertEquals(
            5,
            standard.count { it.status == MockParcelStatus.ORDER_SHIPPED_WAITING_TRACKING },
        )
        assertEquals(10, standard.count { it.status == MockParcelStatus.IN_TRANSIT })
        assertEquals(7, standard.count { it.status == MockParcelStatus.DELIVERING })
        assertEquals(7, standard.count { it.status == MockParcelStatus.ARRIVED_STATION })
        assertEquals(4, standard.count { it.status == MockParcelStatus.DELIVERED_HOME })
        assertEquals(4, standard.count { it.status == MockParcelStatus.SIGNED })
    }

    // ---------- §9.1/9.2 无单号状态必须没有单号 ----------

    @Test
    fun created_rows_have_no_tracking_carrier_or_pickup_code() {
        val created = standard.filter { it.status == MockParcelStatus.CREATED }
        assertEquals(13, created.size)
        created.forEach {
            assertNull(it.trackingNumber)
            assertNull(it.carrierCode)
            assertNull(it.pickupCode)
        }
    }

    @Test
    fun waiting_tracking_rows_have_no_tracking_and_produce_pending() {
        val waiting = standard.filter { it.isWaitingTracking }
        waiting.forEach {
            assertNull(it.trackingNumber)
            assertNull(it.carrierCode)
            val pending = MockParcelDataProvider.toPendingEntity(it)
            assertNotNull(pending)
            assertEquals(PendingShipmentStatus.WAITING_TRACKING.name, pending!!.status)
            assertEquals(it.id, pending.shipmentId)
            assertEquals(it.shippedAt!! + 72 * hour, pending.expireAt)
        }
        // 固定待补全主键：900014 → 910014，重复注入不撞行
        assertEquals(910_014L, MockParcelDataProvider.pendingIdFor(900_014L))
    }

    // ---------- §5 昨天已发货无单号 ≥5 条 ----------

    @Test
    fun at_least_five_shipped_yesterday_without_tracking() {
        val yesterday = standard.count {
            it.isWaitingTracking &&
                it.shippedAt != null &&
                it.shippedAt in (now - 48 * hour)..(now - 24 * hour)
        }
        assertTrue("expected ≥5, got $yesterday", yesterday >= 5)
    }

    // ---------- §6 场景 E：>72h → EXPIRED 判定时间已到 ----------

    @Test
    fun scene_e_edge_row_is_expired_on_sight() {
        assertTrue(sceneE.isWaitingTracking)
        assertTrue(sceneE.shippedAt!! < now - 72 * hour)
        val pending = MockParcelDataProvider.toPendingEntity(sceneE)
        assertNotNull(pending)
        assertTrue("expireAt must be in the past", pending!!.expireAt < now)
    }

    // ---------- §9.3~9.7 有单号状态 / 取件码不变量 ----------

    @Test
    fun tracked_statuses_all_have_tracking_numbers() {
        val tracked = standard.filter {
            it.status in listOf(
                MockParcelStatus.IN_TRANSIT,
                MockParcelStatus.DELIVERING,
                MockParcelStatus.ARRIVED_STATION,
                MockParcelStatus.DELIVERED_HOME,
                MockParcelStatus.SIGNED,
            )
        }
        assertEquals(32, tracked.size)
        tracked.forEach {
            assertNotNull(it.trackingNumber)
            assertNotNull(it.carrierCode)
        }
    }

    @Test
    fun arrived_station_maps_to_pickup_group_with_code() {
        val arrived = standard.filter { it.status == MockParcelStatus.ARRIVED_STATION }
        arrived.forEach { p ->
            val entity = MockParcelDataProvider.toShipmentEntity(p)
            assertNotNull(entity.pickupCode)
            assertEquals(ShipmentStatus.ARRIVED, entity.statusEnum)
            assertTrue("ARRIVED_STATION must be in 待取 group", entity.needsPickup)
        }
    }

    @Test
    fun delivered_home_has_no_code_and_needs_no_pickup() {
        val homes = standard.filter { it.status == MockParcelStatus.DELIVERED_HOME }
        homes.forEach { p ->
            val entity = MockParcelDataProvider.toShipmentEntity(p)
            assertNull(entity.pickupCode)
            assertEquals(ShipmentStatus.DELIVERED, entity.statusEnum)
            assertFalse(entity.needsPickup)
        }
    }

    @Test
    fun signed_is_picked_up_and_not_in_pickup_group() {
        val signed = standard.filter { it.status == MockParcelStatus.SIGNED }
        signed.forEach { p ->
            val entity = MockParcelDataProvider.toShipmentEntity(p)
            assertEquals(UserStatus.PICKED_UP, entity.userStatusEnum)
            assertNotNull(entity.pickedUpAt)
            assertFalse("SIGNED must not show as 待取", entity.needsPickup)
            assertTrue(entity.isArchived)
        }
    }

    // ---------- §9.8 单号唯一且是虚构测试单号 ----------

    @Test
    fun tracking_numbers_are_unique_and_clearly_mock() {
        val tracking = all.mapNotNull { it.trackingNumber }
        assertEquals(tracking.size, tracking.toSet().size)
        assertTrue(tracking.all { it.startsWith("MOCK") })
        assertFalse("虚构单号不应是纯数字（避免与真实单号形态混淆）", tracking.any { it.all(Char::isDigit) })
    }

    @Test
    fun order_keys_and_ids_are_unique() {
        assertEquals(51, all.map { it.orderKey }.toSet().size)
        assertEquals(51, all.map { it.id }.toSet().size)
        assertTrue(all.all { it.orderKey.startsWith("MOCK-") })
    }

    // ---------- §2/§3/§4 平台、快递、商品分布 ----------

    @Test
    fun platforms_are_supported_and_balanced() {
        val supported = setOf("淘宝", "天猫", "京东", "拼多多", "抖音")
        assertTrue(all.all { it.platform in supported })
        val maxShare = all.groupingBy { it.platform }.eachCount().values.max()
        assertTrue("单平台占比过高：$maxShare/51", maxShare <= 15)
    }

    @Test
    fun carriers_are_from_the_spec_carrier_list() {
        val carriers = setOf(
            "顺丰速运", "中通快递", "圆通速递", "申通快递", "韵达速递",
            "极兔速递", "EMS", "京东物流", "德邦物流",
        )
        assertTrue(all.filter { it.carrierCode != null }.all { it.carrierCode in carriers })
        // 无单号状态不该带快递公司（§1.1/1.2/1.3 carrierCode=null）
        standard.filter {
            it.status == MockParcelStatus.CREATED || it.isWaitingTracking
        }.forEach { assertNull(it.carrierCode) }
    }

    @Test
    fun product_titles_all_distinct_with_at_least_25_bases() {
        assertEquals(51, all.map { it.productTitle }.toSet().size)
        val bases = all.map { it.productTitle.substringBefore('（') }.toSet()
        assertTrue("商品基名应 ≥25：${bases.size}", bases.size >= 25)
        assertTrue(all.all { it.sellerName.contains("模拟") })
    }

    // ---------- §7/§8 字段与来源 ----------

    @Test
    fun sources_all_mock_prefixed_and_cover_all_six_kinds() {
        assertTrue(all.all { it.source.startsWith("MOCK") })
        assertEquals(
            setOf(
                "MOCK_ORDER", "MOCK_SMS", "MOCK_NOTIFICATION",
                "MOCK_ACCESSIBILITY", "MOCK_OCR", "MOCK_API",
            ),
            all.map { it.source }.toSet(),
        )
    }

    @Test
    fun confidence_in_range_and_times_are_ordered() {
        all.forEach {
            assertTrue(it.confidence in 0.9..1.0)
            assertTrue("orderTime ≤ firstDetectedAt", it.orderTime <= it.firstDetectedAt)
            assertTrue(it.lastUpdatedAt <= now)
            if (it.shippedAt != null) assertTrue(it.orderTime <= it.shippedAt)
        }
    }

    // ---------- §9 落库状态映射（规格状态 → 现有 ShipmentStatus） ----------

    @Test
    fun spec_status_maps_to_existing_shipment_status() {
        fun statusOf(s: MockParcelStatus) =
            MockParcelDataProvider.toShipmentEntity(standard.first { it.status == s }).statusEnum

        assertEquals(ShipmentStatus.UNKNOWN, statusOf(MockParcelStatus.CREATED))
        assertEquals(ShipmentStatus.SHIPPED, statusOf(MockParcelStatus.ORDER_SHIPPED_WAITING_TRACKING))
        assertEquals(ShipmentStatus.IN_TRANSIT, statusOf(MockParcelStatus.IN_TRANSIT))
        assertEquals(ShipmentStatus.OUT_FOR_DELIVERY, statusOf(MockParcelStatus.DELIVERING))
        assertEquals(ShipmentStatus.ARRIVED, statusOf(MockParcelStatus.ARRIVED_STATION))
        assertEquals(ShipmentStatus.DELIVERED, statusOf(MockParcelStatus.DELIVERED_HOME))
        assertEquals(ShipmentStatus.DELIVERED, statusOf(MockParcelStatus.SIGNED))
    }

    @Test
    fun transit_rows_land_in_home_active_group() {
        standard.filter { it.status == MockParcelStatus.IN_TRANSIT }.forEach {
            val entity = MockParcelDataProvider.toShipmentEntity(it)
            assertTrue("IN_TRANSIT 必须进首页运输中分组", entity.isActiveInTransit)
        }
    }

    @Test
    fun mock_rows_use_fixed_ids_in_reserved_range() {
        assertTrue(all.all { it.id in 900_001L..900_051L })
    }

    // ---------- 需求 §二/§四/§五：待补全行的来源识别与详情字段 ----------

    @Test
    fun pending_rows_carry_source_triple_and_order_fields() {
        val waiting = all.filter { it.isWaitingTracking }
        waiting.forEach { p ->
            val pending = MockParcelDataProvider.toPendingEntity(p)
            assertNotNull(pending)
            // 来源三元组（需求 §二）：类型按 MOCK_* 来源映射，应用名 = 平台中文名
            val expectedType = when (p.source) {
                "MOCK_SMS" -> PendingSourceType.SMS
                "MOCK_NOTIFICATION" -> PendingSourceType.NOTIFICATION
                "MOCK_ACCESSIBILITY" -> PendingSourceType.ACCESSIBILITY
                "MOCK_OCR" -> PendingSourceType.SCREEN_OCR
                else -> PendingSourceType.ECOMMERCE
            }
            assertEquals(expectedType.name, pending!!.sourceType)
            assertEquals(p.platform, pending.sourceAppName)
            assertNotNull("来源包名齐备才能 §六 去对应 App 查看", pending.sourcePackageName)
            // 详情页字段（需求 §五）+ 去重键（需求 §十）
            assertEquals(p.productTitle, pending.productTitle)
            assertEquals(p.sellerName, pending.sellerName)
            assertEquals(p.orderKey, pending.orderKey)
            assertEquals(p.platform, pending.platform)
        }
    }

    @Test
    fun pending_source_package_matches_rules_json_package_names() {
        val expected = mapOf(
            "淘宝" to "com.taobao.taobao",
            "天猫" to "com.tmall.android",
            "京东" to "com.jingdong.app.mall",
            "拼多多" to "com.xunmeng.pinduoduo",
            "抖音" to "com.ss.android.ugc.aweme",
        )
        all.filter { it.isWaitingTracking }.forEach { p ->
            val pending = MockParcelDataProvider.toPendingEntity(p)!!
            assertEquals(expected[p.platform], pending.sourcePackageName)
        }
    }

    @Test
    fun scene_13_1_has_pinduoduo_pending_row_with_source_app_name() {
        // 需求 §十三-1：拼多多已发货无单号 → 待补全 → 显示「来源：拼多多」
        val pdd = all.first { it.isWaitingTracking && it.platform == "拼多多" }
        val pending = MockParcelDataProvider.toPendingEntity(pdd)!!
        assertEquals("拼多多", pending.sourceAppName)
        assertEquals(PendingSourceType.ECOMMERCE.name, pending.sourceType)
    }
}
