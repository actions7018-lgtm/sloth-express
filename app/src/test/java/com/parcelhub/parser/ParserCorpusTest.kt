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
import com.parcelhub.model.RawNotification
import com.parcelhub.model.ShipmentStatus
import com.parcelhub.testutil.TestRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析语料验收（SOP Phase 3/4 验收线：样本 ≥100 条、成功率 ≥90%）。
 *
 * 语料全部是贴近真实的通知文案，覆盖 12 个来源、9 种物流状态、
 * 取件码 / 柜号 / 运单号 / 承运商四类字段，以及必须被门禁挡掉的无关通知。
 *
 * 判定口径（成功 = 样本表现符合预期）：
 *  - 应识别样本：产出事件 + 置信度可建单 + 置信度 ≥0.70 复核线 + 字段断言全对；
 *  - 应过滤样本：不得产出事件（隐私与降噪要求）。
 */
class ParserCorpusTest {

    private val engine = ParserEngine(TestRules.compiled)

    private fun raw(s: Sample) = RawNotification(
        sourcePackage = s.pkg,
        sourceAppName = null,
        notificationKey = "corpus-${s.pkg}-${s.text.hashCode()}",
        title = s.title,
        text = s.text,
        bigText = null,
        subText = null,
        receivedAt = 1_760_000_000_000L,
        isOngoing = false,
    )

    private fun evaluate(s: Sample): List<String> {
        val result = engine.parse(raw(s))
        val errors = mutableListOf<String>()

        // 通用不变量
        if (result.confidence < 0.0 || result.confidence > 0.99) {
            errors += "置信度越界: ${result.confidence}"
        }

        if (!s.parses) {
            if (result.event != null) errors += "应被过滤却产出事件(${result.event.status})"
            if (result.confidence >= 0.40) errors += "被过滤样本置信度过高: ${result.confidence}"
            return errors
        }

        val event = result.event
        if (event == null) {
            errors += "未产出事件: reason=${result.reason}, conf=${result.confidence}"
            return errors
        }
        if (!ShipmentMatcher.canCreateShipment(result.confidence)) {
            errors += "置信度不足以建单: ${result.confidence}"
        }
        if (result.confidence < 0.70) {
            errors += "置信度低于复核线: ${result.confidence}"
        }
        if (event.eventTime <= 0L) errors += "事件时间非法"
        if (event.rawSummary.isBlank()) errors += "摘要为空"

        s.status?.let { if (event.status != it) errors += "状态=${event.status} 期望=$it" }
        s.code?.let { if (event.pickupCode != it) errors += "取件码=${event.pickupCode} 期望=$it" }
        if (s.noCode && !event.pickupCode.isNullOrBlank()) {
            errors += "不应识别到取件码: ${event.pickupCode}"
        }
        s.tracking?.let {
            if (event.trackingNumber != it) errors += "运单号=${event.trackingNumber} 期望=$it"
            // 隐私（SOP §22.2）：落库摘要不得出现完整运单号
            if (event.rawSummary.contains(it)) errors += "摘要泄露完整运单号"
        }
        s.carrier?.let { if (event.carrier != it) errors += "承运商=${event.carrier} 期望=$it" }

        return errors
    }

    @Test
    fun corpus_meets_sample_volume() {
        assertTrue("语料样本量 ${CORPUS.size} 低于 SOP 验收线 100", CORPUS.size >= 100)
        // 结构自检：避免误把“应识别”写成“应过滤”
        val identifiable = CORPUS.count { it.parses }
        val filtered = CORPUS.size - identifiable
        assertTrue("应过滤样本过少，门禁覆盖不足: $filtered", filtered >= 10)
        assertTrue("应识别样本过少", identifiable >= 80)
    }

    @Test
    fun corpus_success_rate_reaches_90_percent() {
        val failures = mutableListOf<String>()
        CORPUS.forEachIndexed { index, sample ->
            val errors = evaluate(sample)
            if (errors.isNotEmpty()) {
                failures += "#$index [${sample.pkg}] ${sample.text.take(26)} → ${errors.joinToString("；")}"
            }
        }
        val rate = (CORPUS.size - failures.size).toDouble() / CORPUS.size
        assertTrue(
            "解析成功率 ${"%.1f".format(rate * 100)}% 低于 SOP 验收线 90%：\n" +
                failures.joinToString("\n"),
            rate >= 0.90,
        )
    }

    @Test
    fun corpus_has_no_failing_sample() {
        val failures = mutableListOf<String>()
        CORPUS.forEachIndexed { index, sample ->
            val errors = evaluate(sample)
            if (errors.isNotEmpty()) {
                failures += "#$index [${sample.pkg}] ${sample.text.take(26)} → ${errors.joinToString("；")}"
            }
        }
        assertEquals("存在不达标样本：\n" + failures.joinToString("\n"), emptyList<String>(), failures)
    }

    @Test
    fun whitelist_and_gate_samples_cover_both_paths() {
        val whitelist = CORPUS.filter { TestRules.compiled.sourceFor(it.pkg) != null }.size
        val unknown = CORPUS.size - whitelist
        assertTrue("白名单来源样本过少: $whitelist", whitelist >= 70)
        assertTrue("未知来源样本过少: $unknown", unknown >= 15)
    }

    /** 语料条目：parses=false 表示必须被门禁/无信号过滤掉 */
    private data class Sample(
        val pkg: String,
        val title: String,
        val text: String,
        val parses: Boolean,
        val status: ShipmentStatus? = null,
        val code: String? = null,
        val tracking: String? = null,
        val carrier: String? = null,
        val noCode: Boolean = false,
    )

    private companion object {
        private const val CAINIAO = "com.cainiao.wireless"
        private const val FENGCHAO = "com.fcbox.bxlm"
        private const val SF = "com.sf.activity"
        private const val ZTO = "com.zto.zto"
        private const val YTO = "com.yto.express"
        private const val YUNDA = "com.yunda.android"
        private const val STO = "com.sto.android"
        private const val JT = "com.jtexpress.android"
        private const val POST = "com.chinaPost.activity"
        private const val TAOBAO = "com.taobao.taobao"
        private const val TMALL = "com.tmall.android"
        private const val JD = "com.jingdong.app.mall"
        private const val PDD = "com.xunmeng.pinduoduo"
        private const val DOUYIN = "com.ss.android.ugc.aweme"
        private const val ALIPAY = "com.eg.android.AlipayGphone"
        private const val UNKNOWN = "com.some.unknown.app"

        val CORPUS: List<Sample> = buildList {
            // ---------- 菜鸟驿站：到站 / 取件码 ----------
            add(Sample(CAINIAO, "菜鸟", "您的包裹已到驿站，取件码：16-4-9626，运单号 771234567890",
                parses = true, status = ShipmentStatus.ARRIVED, code = "16-4-9626", tracking = "771234567890"))
            add(Sample(CAINIAO, "菜鸟", "包裹已到达，取件码 2-2-7508，请凭码取件",
                parses = true, status = ShipmentStatus.PICKUP_READY, code = "2-2-7508"))
            add(Sample(CAINIAO, "菜鸟", "您的包裹已到驿站，取件码：A88123，请及时领取",
                parses = true, status = ShipmentStatus.ARRIVED, code = "A88123"))
            add(Sample(CAINIAO, "菜鸟", "驿站已收到您的包裹，运单号 771234567890",
                parses = true, status = ShipmentStatus.ARRIVED, tracking = "771234567890", noCode = true))
            add(Sample(CAINIAO, "菜鸟", "您的包裹已到代收点，取件码 3-5-8842",
                parses = true, status = ShipmentStatus.ARRIVED, code = "3-5-8842"))
            add(Sample(CAINIAO, "菜鸟裹裹", "取件码已更新：16-4-9626",
                parses = true, status = ShipmentStatus.PICKUP_READY, code = "16-4-9626"))
            add(Sample(CAINIAO, "菜鸟", "您有1个包裹已到达驿站，请凭取件码 16-4-9626 领取",
                parses = true, status = ShipmentStatus.PICKUP_READY, code = "16-4-9626"))
            add(Sample(CAINIAO, "菜鸟", "包裹运输中，已到达上海转运中心",
                parses = true, status = ShipmentStatus.IN_TRANSIT, noCode = true))
            add(Sample(CAINIAO, "菜鸟", "您的包裹派送中，快递员将为您送达",
                parses = true, status = ShipmentStatus.OUT_FOR_DELIVERY, noCode = true))
            add(Sample(CAINIAO, "菜鸟", "您的包裹已签收，运单号 771234567890",
                parses = true, status = ShipmentStatus.DELIVERED, tracking = "771234567890"))
            add(Sample(CAINIAO, "菜鸟", "您的包裹已退回，运单号 771234567890",
                parses = true, status = ShipmentStatus.RETURNED, tracking = "771234567890"))
            add(Sample(CAINIAO, "菜鸟", "订单已取消，包裹将不再派送",
                parses = true, status = ShipmentStatus.CANCELLED, noCode = true))
            add(Sample(CAINIAO, "菜鸟", "菜鸟提醒：您的包裹已到站，取件码 8-2-1177",
                parses = true, status = ShipmentStatus.ARRIVED, code = "8-2-1177"))

            // ---------- 丰巢：入柜 / 取件码 ----------
            add(Sample(FENGCHAO, "丰巢", "您的包裹已入柜，柜号 A12，取件码 2-2-7508",
                parses = true, status = ShipmentStatus.PICKUP_READY, code = "2-2-7508"))
            add(Sample(FENGCHAO, "丰巢", "您的快递已存入丰巢柜，取件码 A88123",
                parses = true, status = ShipmentStatus.PICKUP_READY, code = "A88123"))
            add(Sample(FENGCHAO, "丰巢", "包裹已投柜，取货码 5-1-3390",
                parses = true, status = ShipmentStatus.PICKUP_READY, code = "5-1-3390"))
            add(Sample(FENGCHAO, "丰巢智能柜", "您的包裹已到站，取件码 16-4-9626",
                parses = true, status = ShipmentStatus.ARRIVED, code = "16-4-9626"))
            add(Sample(FENGCHAO, "丰巢", "包裹已放入柜中，格口 12，取件码 3-4-2211",
                parses = true, status = ShipmentStatus.PICKUP_READY, code = "3-4-2211"))
            add(Sample(FENGCHAO, "丰巢", "您的包裹派送中，运单号 SF1234567890123",
                parses = true, status = ShipmentStatus.OUT_FOR_DELIVERY,
                tracking = "SF1234567890123", carrier = "顺丰速运"))
            add(Sample(FENGCHAO, "丰巢", "您的包裹已签收，感谢使用丰巢",
                parses = true, status = ShipmentStatus.DELIVERED, noCode = true))
            add(Sample(FENGCHAO, "丰巢", "运单号 4482236617285 已揽收，请耐心等待",
                parses = true, status = ShipmentStatus.SHIPPED, tracking = "4482236617285", noCode = true))
            add(Sample(FENGCHAO, "丰巢", "您的包裹已到站，请凭码取件",
                parses = true, status = ShipmentStatus.PICKUP_READY, noCode = true))

            // ---------- 顺丰 ----------
            add(Sample(SF, "顺丰速运", "顺丰速运：您的快递派送中，运单号 SF1234567890123",
                parses = true, status = ShipmentStatus.OUT_FOR_DELIVERY,
                tracking = "SF1234567890123", carrier = "顺丰速运"))
            add(Sample(SF, "顺丰速运", "顺丰速运：您的快递已签收，运单号 SF1234567890123",
                parses = true, status = ShipmentStatus.DELIVERED,
                tracking = "SF1234567890123", carrier = "顺丰速运"))
            add(Sample(SF, "顺丰速运", "您的顺丰包裹运输中，已到达杭州转运中心",
                parses = true, status = ShipmentStatus.IN_TRANSIT, carrier = "顺丰速运", noCode = true))
            add(Sample(SF, "顺丰速运", "顺丰速运：您的快递已揽收，运单号 SF1234567890123",
                parses = true, status = ShipmentStatus.SHIPPED,
                tracking = "SF1234567890123", carrier = "顺丰速运"))
            add(Sample(SF, "顺丰速运", "您的快递已到达上海分拨中心",
                parses = true, status = ShipmentStatus.IN_TRANSIT, noCode = true))
            add(Sample(SF, "顺丰速运", "顺丰：包裹已退回，运单号 SF1234567890123",
                parses = true, status = ShipmentStatus.RETURNED,
                tracking = "SF1234567890123", carrier = "顺丰速运"))
            add(Sample(SF, "顺丰速运", "顺丰速运提醒：订单已取消",
                parses = true, status = ShipmentStatus.CANCELLED, noCode = true))
            add(Sample(SF, "顺丰速运", "顺丰速运：您的快递即将派送，运单号 SF1234567890123",
                parses = true, tracking = "SF1234567890123", carrier = "顺丰速运"))

            // ---------- 中通 ----------
            add(Sample(ZTO, "中通快递", "中通快递：您的快递已签收，运单号 771234567890",
                parses = true, status = ShipmentStatus.DELIVERED,
                tracking = "771234567890", carrier = "中通快递"))
            add(Sample(ZTO, "中通快递", "中通快递：您的快递派送中，运单号 771234567890",
                parses = true, status = ShipmentStatus.OUT_FOR_DELIVERY,
                tracking = "771234567890", carrier = "中通快递"))
            add(Sample(ZTO, "中通快递", "中通快递：包裹运输中，已离开上海转运中心",
                parses = true, status = ShipmentStatus.IN_TRANSIT, carrier = "中通快递", noCode = true))
            add(Sample(ZTO, "中通快递", "中通快递：您的包裹已到站，取件码 16-4-9626",
                parses = true, status = ShipmentStatus.ARRIVED, code = "16-4-9626"))
            add(Sample(ZTO, "中通快递", "中通快递：您的快递已揽收，运单号 771234567890",
                parses = true, status = ShipmentStatus.SHIPPED, tracking = "771234567890", carrier = "中通快递"))
            add(Sample(ZTO, "中通快递", "中通快递：包裹已退回，运单号 771234567890",
                parses = true, status = ShipmentStatus.RETURNED, tracking = "771234567890", carrier = "中通快递"))
            add(Sample(ZTO, "中通快递", "中通快递：运单号 771234567890 状态更新：运输中",
                parses = true, status = ShipmentStatus.IN_TRANSIT, tracking = "771234567890", carrier = "中通快递"))
            add(Sample(ZTO, "中通快递", "中通快递：您的快递已放入丰巢柜，取件码 5-1-3390",
                parses = true, status = ShipmentStatus.PICKUP_READY, code = "5-1-3390"))

            // ---------- 圆通 ----------
            add(Sample(YTO, "圆通速递", "圆通速递：您的快递已签收，运单号 YT4482236617",
                parses = true, status = ShipmentStatus.DELIVERED,
                tracking = "YT4482236617", carrier = "圆通速递"))
            add(Sample(YTO, "圆通速递", "圆通速递：包裹派送中，快递员正在为您派送",
                parses = true, status = ShipmentStatus.OUT_FOR_DELIVERY, carrier = "圆通速递", noCode = true))
            add(Sample(YTO, "圆通速递", "圆通速递：包裹运输中，已到达广州转运中心",
                parses = true, status = ShipmentStatus.IN_TRANSIT, carrier = "圆通速递", noCode = true))
            add(Sample(YTO, "圆通速递", "圆通速递：您的包裹已到驿站，取件码 16-4-9626",
                parses = true, status = ShipmentStatus.ARRIVED, code = "16-4-9626"))
            add(Sample(YTO, "圆通速递", "圆通速递：卖家已发货，运单号 YT4482236617",
                parses = true, status = ShipmentStatus.SHIPPED,
                tracking = "YT4482236617", carrier = "圆通速递"))
            add(Sample(YTO, "圆通速递", "圆通速递：包裹已退回，运单号 YT4482236617",
                parses = true, status = ShipmentStatus.RETURNED,
                tracking = "YT4482236617", carrier = "圆通速递"))
            add(Sample(YTO, "圆通速递", "圆通速递：运单号 YT4482236617 已揽收",
                parses = true, status = ShipmentStatus.SHIPPED, tracking = "YT4482236617", carrier = "圆通速递"))

            // ---------- 韵达 ----------
            add(Sample(YUNDA, "韵达速递", "韵达速递：您的快递已签收，运单号 3344556677889",
                parses = true, status = ShipmentStatus.DELIVERED,
                tracking = "3344556677889", carrier = "韵达速递"))
            add(Sample(YUNDA, "韵达速递", "韵达速递：包裹运输中，已到达武汉转运中心",
                parses = true, status = ShipmentStatus.IN_TRANSIT, carrier = "韵达速递", noCode = true))
            add(Sample(YUNDA, "韵达速递", "韵达速递：您的快递派送中",
                parses = true, status = ShipmentStatus.OUT_FOR_DELIVERY, carrier = "韵达速递", noCode = true))
            add(Sample(YUNDA, "韵达速递", "韵达速递：包裹已退回，运单号 3344556677889",
                parses = true, status = ShipmentStatus.RETURNED,
                tracking = "3344556677889", carrier = "韵达速递"))
            add(Sample(YUNDA, "韵达速递", "韵达速递：您的包裹已到站，取件码 2-2-7508",
                parses = true, status = ShipmentStatus.ARRIVED, code = "2-2-7508"))
            add(Sample(YUNDA, "韵达速递", "韵达速递：包裹已发出，运单号 3344556677889",
                parses = true, status = ShipmentStatus.SHIPPED,
                tracking = "3344556677889", carrier = "韵达速递"))

            // ---------- 申通 ----------
            add(Sample(STO, "申通快递", "申通快递：您的快递已签收，运单号 771234567890",
                parses = true, status = ShipmentStatus.DELIVERED, tracking = "771234567890"))
            add(Sample(STO, "申通快递", "申通快递：包裹派送中",
                parses = true, status = ShipmentStatus.OUT_FOR_DELIVERY, carrier = "申通快递", noCode = true))
            add(Sample(STO, "申通快递", "申通快递：包裹运输中，干线运输进行中",
                parses = true, status = ShipmentStatus.IN_TRANSIT, carrier = "申通快递", noCode = true))
            add(Sample(STO, "申通快递", "申通快递：您的包裹已入柜，柜号 B07，取件码 9-9-3311",
                parses = true, status = ShipmentStatus.PICKUP_READY, code = "9-9-3311"))
            add(Sample(STO, "申通快递", "申通快递：包裹已退回",
                parses = true, status = ShipmentStatus.RETURNED, carrier = "申通快递", noCode = true))
            add(Sample(STO, "申通快递", "申通快递：已揽收，运单号 771234567890",
                parses = true, status = ShipmentStatus.SHIPPED, tracking = "771234567890"))

            // ---------- 极兔 ----------
            add(Sample(JT, "极兔速递", "极兔速递：您的快递已签收，运单号 998877665544",
                parses = true, status = ShipmentStatus.DELIVERED,
                tracking = "998877665544", carrier = "极兔速递"))
            add(Sample(JT, "极兔速递", "极兔速递：包裹运输中，已到达郑州转运中心",
                parses = true, status = ShipmentStatus.IN_TRANSIT, carrier = "极兔速递", noCode = true))
            add(Sample(JT, "极兔速递", "极兔速递：您的快递派送中",
                parses = true, status = ShipmentStatus.OUT_FOR_DELIVERY, carrier = "极兔速递", noCode = true))
            add(Sample(JT, "极兔速递", "极兔速递：包裹已退回，运单号 998877665544",
                parses = true, status = ShipmentStatus.RETURNED,
                tracking = "998877665544", carrier = "极兔速递"))
            add(Sample(JT, "极兔速递", "极兔速递：包裹已发出",
                parses = true, status = ShipmentStatus.SHIPPED, carrier = "极兔速递", noCode = true))

            // ---------- 中国邮政 / EMS ----------
            add(Sample(POST, "中国邮政", "中国邮政：您的邮件已签收，运单号 EA123456789CN",
                parses = true, status = ShipmentStatus.DELIVERED,
                tracking = "EA123456789CN", carrier = "EMS"))
            add(Sample(POST, "中国邮政", "中国邮政：包裹运输中，已到达北京中心",
                parses = true, status = ShipmentStatus.IN_TRANSIT, carrier = "EMS", noCode = true))
            add(Sample(POST, "EMS", "EMS：您的快递派送中，运单号 EA123456789CN",
                parses = true, status = ShipmentStatus.OUT_FOR_DELIVERY,
                tracking = "EA123456789CN", carrier = "EMS"))
            add(Sample(POST, "中国邮政", "中国邮政：邮件已退回",
                parses = true, status = ShipmentStatus.RETURNED, carrier = "EMS", noCode = true))
            add(Sample(POST, "中国邮政", "中国邮政：您的邮件已到达投递局",
                parses = true, status = ShipmentStatus.IN_TRANSIT, carrier = "EMS", noCode = true))

            // ---------- 淘宝 / 天猫 ----------
            add(Sample(TAOBAO, "淘宝", "卖家已发货，包裹将由中通快递配送，运单号 771234567890",
                parses = true, status = ShipmentStatus.SHIPPED,
                tracking = "771234567890", carrier = "中通快递"))
            add(Sample(TAOBAO, "淘宝", "您的包裹物流更新：已到达杭州转运中心",
                parses = true, status = ShipmentStatus.IN_TRANSIT, noCode = true))
            add(Sample(TAOBAO, "淘宝", "包裹派送中，快递员正在为您派送",
                parses = true, status = ShipmentStatus.OUT_FOR_DELIVERY, noCode = true))
            add(Sample(TAOBAO, "淘宝", "您的包裹已到驿站，取件码 16-4-9626",
                parses = true, status = ShipmentStatus.ARRIVED, code = "16-4-9626"))
            add(Sample(TAOBAO, "淘宝", "卖家已发货，运单号 4482236617285",
                parses = true, status = ShipmentStatus.SHIPPED, tracking = "4482236617285", noCode = true))
            add(Sample(TAOBAO, "淘宝", "您的包裹已签收，运单号 771234567890",
                parses = true, status = ShipmentStatus.DELIVERED, tracking = "771234567890"))
            add(Sample(TAOBAO, "淘宝", "包裹已退回，运单号 771234567890",
                parses = true, status = ShipmentStatus.RETURNED, tracking = "771234567890"))
            add(Sample(TMALL, "天猫", "您的包裹已到驿站，取件码 16-4-9626",
                parses = true, status = ShipmentStatus.ARRIVED, code = "16-4-9626"))
            add(Sample(TMALL, "天猫", "卖家已发货，运单号 771234567890",
                parses = true, status = ShipmentStatus.SHIPPED, tracking = "771234567890"))
            add(Sample(TMALL, "天猫", "您的包裹已签收，运单号 771234567890",
                parses = true, status = ShipmentStatus.DELIVERED, tracking = "771234567890"))

            // ---------- 京东 ----------
            add(Sample(JD, "京东物流", "京东物流：您的包裹已签收，运单号 JD00123456789",
                parses = true, status = ShipmentStatus.DELIVERED,
                tracking = "JD00123456789", carrier = "京东物流"))
            add(Sample(JD, "京东物流", "京东物流：包裹派送中，运单号 JD00123456789",
                parses = true, status = ShipmentStatus.OUT_FOR_DELIVERY,
                tracking = "JD00123456789", carrier = "京东物流"))
            add(Sample(JD, "京东物流", "京东物流：包裹运输中，已到达苏州分拨中心",
                parses = true, status = ShipmentStatus.IN_TRANSIT, carrier = "京东物流", noCode = true))
            add(Sample(JD, "京东", "京东：您的包裹已到站，取件码 11-22-3344",
                parses = true, status = ShipmentStatus.ARRIVED, code = "11-22-3344"))
            add(Sample(JD, "京东物流", "京东物流：包裹已发出，运单号 JD00123456789",
                parses = true, status = ShipmentStatus.SHIPPED,
                tracking = "JD00123456789", carrier = "京东物流"))
            add(Sample(JD, "京东物流", "京东物流：包裹已退回",
                parses = true, status = ShipmentStatus.RETURNED, carrier = "京东物流", noCode = true))

            // ---------- 拼多多 / 抖音 ----------
            add(Sample(PDD, "拼多多", "包裹派送中，快递员正在为您派送",
                parses = true, status = ShipmentStatus.OUT_FOR_DELIVERY, noCode = true))
            add(Sample(PDD, "拼多多", "您的包裹已到驿站，取件码 16-4-9626",
                parses = true, status = ShipmentStatus.ARRIVED, code = "16-4-9626"))
            add(Sample(PDD, "拼多多", "包裹运输中，已到达南京转运中心",
                parses = true, status = ShipmentStatus.IN_TRANSIT, noCode = true))
            add(Sample(PDD, "拼多多", "卖家已发货，运单号 771234567890",
                parses = true, status = ShipmentStatus.SHIPPED, tracking = "771234567890"))
            add(Sample(PDD, "拼多多", "您的包裹已签收，运单号 771234567890",
                parses = true, status = ShipmentStatus.DELIVERED, tracking = "771234567890"))
            add(Sample(DOUYIN, "抖音", "您的包裹已到驿站，取件码 2-2-7508",
                parses = true, status = ShipmentStatus.ARRIVED, code = "2-2-7508"))
            add(Sample(DOUYIN, "抖音", "包裹派送中，请保持电话畅通",
                parses = true, status = ShipmentStatus.OUT_FOR_DELIVERY, noCode = true))
            add(Sample(DOUYIN, "抖音", "包裹运输中，已到达长沙转运中心",
                parses = true, status = ShipmentStatus.IN_TRANSIT, noCode = true))
            add(Sample(DOUYIN, "抖音", "您的包裹已签收，运单号 771234567890",
                parses = true, status = ShipmentStatus.DELIVERED, tracking = "771234567890"))

            // ---------- 支付宝：服务通知形态 ----------
            add(Sample(ALIPAY, "支付宝", "取件码已更新：16-4-9626，东门驿站",
                parses = true, status = ShipmentStatus.PICKUP_READY, code = "16-4-9626"))
            add(Sample(ALIPAY, "支付宝", "您的包裹已到驿站，取件码 16-4-9626",
                parses = true, status = ShipmentStatus.ARRIVED, code = "16-4-9626"))
            add(Sample(ALIPAY, "支付宝", "包裹已到达，取件码 2-2-7508，请凭码取件",
                parses = true, status = ShipmentStatus.PICKUP_READY, code = "2-2-7508"))
            add(Sample(ALIPAY, "支付宝", "驿站已收到您的包裹，运单号 771234567890",
                parses = true, status = ShipmentStatus.ARRIVED, tracking = "771234567890"))
            add(Sample(ALIPAY, "支付宝", "快递到了，取件码 8-2-1177",
                parses = true, status = ShipmentStatus.PICKUP_READY, code = "8-2-1177"))

            // ---------- 未知来源：通过通用门禁 ----------
            add(Sample(UNKNOWN, "服务通知", "取件码已更新：16-4-9626",
                parses = true, status = ShipmentStatus.PICKUP_READY, code = "16-4-9626"))
            add(Sample(UNKNOWN, "服务通知", "您的运单号 771234567890 已揽收",
                parses = true, status = ShipmentStatus.SHIPPED, tracking = "771234567890"))
            add(Sample(UNKNOWN, "驿站通知", "驿站通知：包裹已到达，取件码 16-4-9626",
                parses = true, status = ShipmentStatus.ARRIVED, code = "16-4-9626"))
            add(Sample(UNKNOWN, "物流助手", "您的快递已签收",
                parses = true, status = ShipmentStatus.DELIVERED, noCode = true))
            add(Sample(UNKNOWN, "快递柜", "快递柜使用须知：取件码已更新",
                parses = true, status = ShipmentStatus.PICKUP_READY, noCode = true))

            // ---------- 必须被挡掉的无关通知 ----------
            add(Sample(UNKNOWN, "天气预报", "明天天气晴，记得带伞", parses = false))
            add(Sample(UNKNOWN, "银行通知", "您的验证码 881234 已发送", parses = false))
            add(Sample(UNKNOWN, "日程提醒", "明天上午10点开会", parses = false))
            add(Sample(UNKNOWN, "微信", "在吗，晚上一起吃饭", parses = false))
            add(Sample(UNKNOWN, "应用商店", "新版本已发布，点击更新", parses = false))
            add(Sample(UNKNOWN, "会员中心", "您的会员将于本月底到期", parses = false))
            add(Sample(CAINIAO, "菜鸟", "您的包裹有更新", parses = false))
            add(Sample(CAINIAO, "菜鸟", "菜鸟裹裹已启动后台服务", parses = false))
            add(Sample(FENGCHAO, "丰巢", "丰巢优惠活动进行中", parses = false))
            add(Sample(SF, "顺丰速运", "顺丰会员积分到账提醒", parses = false))
            add(Sample(ZTO, "中通快递", "中通会员中心", parses = false))
            add(Sample(UNKNOWN, "通知", "快递服务升级，物流时效调整", parses = false))
            add(Sample(UNKNOWN, "通知", "快递柜使用须知", parses = false))
            add(Sample(DOUYIN, "抖音", "你关注的主播开播了", parses = false))
            add(Sample(TAOBAO, "淘宝", "店铺上新提醒", parses = false))
            add(Sample(PDD, "拼多多", "签到领金币活动", parses = false))
            add(Sample(UNKNOWN, "手机管家", "照片备份已完成", parses = false))
            add(Sample(UNKNOWN, "安全中心", "登录成功，如非本人操作请忽略", parses = false))
        }
    }

    @Test
    fun engine_rejects_empty_body() {
        val result = engine.parse(
            RawNotification(
                sourcePackage = CAINIAO,
                sourceAppName = null,
                notificationKey = "empty",
                title = "",
                text = "",
                bigText = null,
                subText = null,
                receivedAt = 1L,
                isOngoing = false,
            ),
        )
        assertEquals(null, result.event)
        assertEquals(0.0, result.confidence, 0.0)
        assertNotNull(result.reason)
        assertTrue(CORPUS.size >= 100)
    }
}
