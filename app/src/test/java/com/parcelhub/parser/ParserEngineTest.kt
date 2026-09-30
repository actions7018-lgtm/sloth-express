/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.parser

import com.parcelhub.matcher.ShipmentMatcher
import com.parcelhub.model.EventType
import com.parcelhub.model.RawNotification
import com.parcelhub.model.ShipmentStatus
import com.parcelhub.model.SourceType
import com.parcelhub.testutil.TestRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析引擎端到端单测（SOP §6）：
 * 真实 `rules.json` + 真实通知文本 → 事件 / 置信度 / 原因。
 */
class ParserEngineTest {

    private val engine = ParserEngine(TestRules.compiled)

    private fun raw(
        sourcePackage: String,
        title: String,
        text: String,
        notificationKey: String = "key-${sourcePackage.hashCode()}-${title.hashCode()}",
        receivedAt: Long = 1_760_000_000_000L,
    ) = RawNotification(
        sourcePackage = sourcePackage,
        sourceAppName = null,
        notificationKey = notificationKey,
        title = title,
        text = text,
        bigText = null,
        subText = null,
        receivedAt = receivedAt,
        isOngoing = false,
    )

    // ---------- 到站 + 取件码：核心场景 ----------

    @Test
    fun arrival_with_pickup_code_from_cainiao() {
        val result = engine.parse(
            raw(
                sourcePackage = "com.cainiao.wireless",
                title = "菜鸟驿站",
                text = "您的包裹已到驿站，取件码：16-4-9626，运单号 771234567890",
            ),
        )

        val event = result.event
        assertNotNull("到站通知应当产出事件", event)
        assertEquals("16-4-9626", event?.pickupCode)
        assertEquals(ShipmentStatus.ARRIVED, event?.status)
        assertEquals(EventType.ARRIVED, event?.eventType)
        assertEquals("771234567890", event?.trackingNumber)
        assertEquals(SourceType.PICKUP_STATION, event?.sourceType)
        assertTrue("置信度应足够建单: ${result.confidence}", ShipmentMatcher.canCreateShipment(result.confidence))
        assertNull(result.reason)

        // 隐私：落库摘要不得包含完整运单号（SOP §22.2）
        assertFalse(event!!.rawSummary.contains("771234567890"))
    }

    @Test
    fun locker_delivery_with_code_from_fengchao() {
        val result = engine.parse(
            raw(
                sourcePackage = "com.fcbox.bxlm",
                title = "丰巢",
                text = "您的包裹已入柜，柜号 A12，取件码 2-2-7508",
            ),
        )

        val event = result.event
        assertNotNull(event)
        assertEquals("2-2-7508", event?.pickupCode)
        assertEquals(ShipmentStatus.PICKUP_READY, event?.status)
        assertTrue(result.confidence >= 0.70)
    }

    // ---------- 状态识别 ----------

    @Test
    fun out_for_delivery_with_labeled_tracking() {
        val result = engine.parse(
            raw(
                sourcePackage = "com.sf.activity",
                title = "顺丰速运",
                text = "您的快递派送中，运单号 SF1234567890123",
            ),
        )

        val event = result.event
        assertNotNull(event)
        assertEquals(ShipmentStatus.OUT_FOR_DELIVERY, event?.status)
        assertEquals(EventType.OUT_FOR_DELIVERY, event?.eventType)
        assertEquals("SF1234567890123", event?.trackingNumber)
        assertEquals("顺丰速运", event?.carrier)
        assertNull(result.reason)
    }

    @Test
    fun delivered_status_is_recognized() {
        val result = engine.parse(
            raw(
                sourcePackage = "com.zto.zto",
                title = "中通快递",
                text = "您的快递已签收，运单号 771234567890",
            ),
        )

        val event = result.event
        assertNotNull(event)
        assertEquals(ShipmentStatus.DELIVERED, event?.status)
        assertEquals(EventType.DELIVERED, event?.eventType)
        assertTrue(result.confidence >= 0.70)
    }

    @Test
    fun shipped_status_from_ecommerce() {
        val result = engine.parse(
            raw(
                sourcePackage = "com.taobao.taobao",
                title = "淘宝",
                text = "卖家已发货，包裹将由中通快递配送，运单号 771234567890",
            ),
        )

        val event = result.event
        assertNotNull(event)
        assertEquals(ShipmentStatus.SHIPPED, event?.status)
        assertEquals("中通快递", event?.carrier)
    }

    // ---------- 门禁：把无关通知挡在解析之外 ----------

    @Test
    fun unrelated_notification_is_gated_out() {
        val result = engine.parse(
            raw(
                sourcePackage = "com.some.weather",
                title = "天气预报",
                text = "明天天气晴，记得带伞",
            ),
        )

        assertNull(result.event)
        assertEquals(0.0, result.confidence, 0.0)
        assertEquals("非快递相关通知", result.reason)
    }

    @Test
    fun low_signal_notification_is_kept_below_create_threshold() {
        val result = engine.parse(
            raw(
                sourcePackage = "com.some.app",
                title = "服务通知",
                text = "快递服务升级，物流时效调整",
            ),
        )

        // 通过了关键词门禁，但没有单号 / 状态 / 取件码
        assertNull(result.event)
        assertEquals(0.30, result.confidence, 0.001)
        assertEquals("没有识别到快递信号", result.reason)
        assertFalse("低置信不得创建包裹", ShipmentMatcher.canCreateShipment(result.confidence))
    }

    @Test
    fun empty_body_returns_reason() {
        val result = engine.parse(
            raw(sourcePackage = "com.cainiao.wireless", title = "", text = ""),
        )
        assertNull(result.event)
        assertEquals("通知正文为空", result.reason)
    }

    // ---------- 轻量门禁（监听回调用） ----------

    @Test
    fun cheap_gate_passes_whitelist_source_with_single_keyword() {
        assertTrue(engine.cheapGate("com.cainiao.wireless", "你的包裹有更新"))
    }

    @Test
    fun cheap_gate_requires_two_keywords_for_unknown_source() {
        assertFalse(engine.cheapGate("com.unknown.app", "明天天气晴"))
        assertTrue(engine.cheapGate("com.unknown.app", "快递柜使用须知"))
        assertTrue(engine.cheapGate("com.unknown.app", "明天有雨，快递柜"))
    }

    @Test
    fun cheap_gate_accepts_strong_keyword_alone() {
        assertTrue(engine.cheapGate("com.unknown.app", "取件码已更新"))
        assertTrue(engine.cheapGate("com.unknown.app", "您的运单号已揽收"))
    }

    // ---------- 样本批处理不变量 ----------

    @Test
    fun all_samples_produce_well_formed_results() {
        val samples = SAMPLES.map { (pkg, title, text) -> raw(pkg, title, text) }

        for ((index, sample) in samples.withIndex()) {
            val result = engine.parse(sample)
            assertTrue("样本 #$index 置信度越界: ${result.confidence}", result.confidence in 0.0..0.99)
            val event = result.event
            if (event != null) {
                assertTrue(
                    "样本 #$index 有事件但置信度不足以建单: ${result.confidence}",
                    ShipmentMatcher.canCreateShipment(result.confidence),
                )
                assertTrue("样本 #$index 事件时间非法", event.eventTime > 0L)
                assertTrue("样本 #$index 摘要为空", event.rawSummary.isNotBlank())
            } else {
                assertTrue(
                    "样本 #$index 无事件却给出高置信: ${result.confidence}",
                    result.confidence < 0.40,
                )
                assertNotNull("样本 #$index 缺少失败原因", result.reason)
            }
        }
    }

    // ---------- SOP V2.0 §29/§30 紧凑文本兜底 + §7/§8 列表批量多单号 ----------

    @Test
    fun split_tracking_number_recovered_via_compact_text() {
        // 无障碍节点按行拼接把单号截断成 SF123 / 4567890123：原文读不到，
        // 压缩空白后必须能提出来（带字母候选，纯数字紧凑拼接不认）。
        val result = engine.parse(
            raw(
                sourcePackage = "com.xunmeng.pinduoduo",
                title = "拼多多",
                text = "快递单号\nSF123\n4567890123",
            ),
        )
        val event = result.event
        assertNotNull("紧凑兜底应产出事件", event)
        assertEquals("SF1234567890123", event?.trackingNumber)
        assertTrue(
            "置信度应足以建单: ${result.confidence}",
            ShipmentMatcher.canCreateShipment(result.confidence),
        )
    }

    @Test
    fun multiple_trackings_yield_one_event_per_number_without_context_spread() {
        // 一页两单：逐单成事件；订单号 / 取件码等归属不明字段不得摊派（§6.4）
        val result = engine.parse(
            raw(
                sourcePackage = "com.xunmeng.pinduoduo",
                title = "拼多多",
                text = "订单编号：2609284852390998\n快递单号 YT0710904037400\n快递单号 SF1234567890123",
            ),
        )
        val trackings = result.events.mapNotNull { it.trackingNumber }.toSet()
        assertEquals("应当一单一条", 2, result.events.size)
        assertTrue("缺圆通单", "YT0710904037400" in trackings)
        assertTrue("缺顺丰单", "SF1234567890123" in trackings)
        result.events.forEach { e ->
            assertNull("批量事件不应摊派订单号", e.orderKey)
            assertNull("批量事件不应摊派取件码", e.pickupCode)
            assertNull("批量事件不应摊派地址", e.destination)
        }
        assertEquals("event 兼容字段应指向第一条", result.events.first(), result.event)
    }

    @Test
    fun single_tracking_keeps_full_context_attribution() {
        // 单号唯一：订单号 / 地址照常归属（多单路径的反向回归）
        val result = engine.parse(
            raw(
                sourcePackage = "com.xunmeng.pinduoduo",
                title = "拼多多",
                text = "订单编号：2609284852390998\n快递单号 YT0710904037400\n收货地址：浙江省杭州市余杭区仓益绿苑56栋",
            ),
        )
        val event = result.event
        assertNotNull("单事件应产出", event)
        assertEquals(1, result.events.size)
        assertEquals("YT0710904037400", event?.trackingNumber)
        assertEquals("2609284852390998", event?.orderKey)
        assertNotNull("单号唯一时地址应归属", event?.destination)
    }

    @Test
    fun pdd_dashed_order_number_is_extracted_as_order_key() {
        // 真机实测：拼多多订单编号是「前缀-长号」带连字符格式
        // （如 260928-434215381420088），旧正则 \d{10,24} 段在连字符处断开导致
        // orderKey 恒空——详情页只剩裸「订单编号」词、入库被身份闸丢弃。
        val result = engine.parse(
            raw(
                sourcePackage = "com.xunmeng.pinduoduo",
                title = "拼多多",
                text = "订单编号: 260928-434215381420088\n快递单号 777449133381845\n收货地址: 广东省惠州市博罗县园洲镇永昌路22号",
            ),
        )
        val event = result.event
        assertNotNull("带连字符订单页应产出事件", event)
        assertEquals("260928-434215381420088", event?.orderKey)
        assertEquals("777449133381845", event?.trackingNumber)
    }

    companion object {
        /** 一批贴近真实的通知样本（覆盖各来源与各状态） */
        private val SAMPLES = listOf(
            Triple("com.cainiao.wireless", "菜鸟", "您的包裹已到驿站，取件码：16-4-9626，运单号 771234567890"),
            Triple("com.cainiao.wireless", "菜鸟", "包裹已到达，取件码 2-2-7508，请凭码取件"),
            Triple("com.fcbox.bxlm", "丰巢", "您的包裹已入柜，柜号 A12，取件码 A88123"),
            Triple("com.sf.activity", "顺丰速运", "您的快递派送中，运单号 SF1234567890123"),
            Triple("com.zto.zto", "中通快递", "您的快递已签收，运单号 771234567890"),
            Triple("com.yto.express", "圆通速递", "包裹运输中，已到达上海转运中心"),
            Triple("com.taobao.taobao", "淘宝", "卖家已发货，运单号 771234567890"),
            Triple("com.jingdong.app.mall", "京东", "您的包裹已到站，取件码 11-22-3344"),
            Triple("com.xunmeng.pinduoduo", "拼多多", "包裹派送中，快递员正在为您派送"),
            Triple("com.sto.android", "申通快递", "您的包裹已退回，运单号 771234567890"),
            Triple("com.eg.android.AlipayGphone", "支付宝", "取件码已更新：16-4-9626，东门驿站"),
            Triple("com.chinaPost.activity", "中国邮政", "您的邮件已签收，运单号 EA123456789CN"),
            Triple("com.unknown.app", "通知", "快递服务升级，物流时效调整"),
            Triple("com.unknown.app", "天气", "明天天气晴，记得带伞"),
            Triple("com.unknown.app", "银行", "您的验证码 881234 已发送"),
            Triple("com.cainiao.wireless", "菜鸟", "运单号 771234567890 已揽收，注意查收"),
        )
    }
}
