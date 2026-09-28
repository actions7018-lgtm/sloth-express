/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.sms

import com.parcelhub.model.EventType
import com.parcelhub.model.RawNotification
import com.parcelhub.model.ShipmentStatus
import com.parcelhub.parser.ParserEngine
import com.parcelhub.testutil.TestRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 短信识别验收（快递短信 SOP §43「必须至少测试」的六条语料）。
 *
 * 口径：短信走**统一解析层**（SOP §3/§49），所以这里验证的是
 * 「第一层过滤（SOP §9~§11） → ParserEngine 解析」这条完整链路，
 * 而不是另写一套短信专用识别逻辑。
 */
class SmsDetectionTest {

    private val engine = ParserEngine(TestRules.compiled)

    private fun raw(text: String, sender: String = "1069000000000") = RawNotification(
        sourcePackage = SmsContract.SOURCE_PACKAGE,
        sourceAppName = null,
        notificationKey = SmsContract.smsHash(sender, text, NOW),
        title = SmsContract.senderFingerprint(sender),
        text = text,
        bigText = null,
        subText = null,
        receivedAt = NOW,
        isOngoing = false,
    )

    /** 第一层过滤（SOP §9~§11）：先判排除、再判物流词 */
    private fun firstLayer(text: String): String = when {
        SmsContract.excludedReason(text) != null -> "excluded"
        !SmsContract.isLogistics(text) -> "not_logistics"
        else -> "pass"
    }

    // ---------------------------------------------------------------- §43.1 正常派送

    @Test
    fun `正常派送短信 - 识别承运商单号与派送状态`() {
        val text = "您的快件 SF1234567890123 正在派送，请注意查收。"

        assertEquals("第一层过滤应放行", "pass", firstLayer(text))

        val result = engine.parse(raw(text))
        val event = result.event
        assertNotNull("物流短信必须产出事件，实际 reason=${result.reason}", event)
        assertEquals("SF1234567890123", event?.trackingNumber)
        assertEquals("顺丰速运", event?.carrier)
        assertEquals(EventType.OUT_FOR_DELIVERY, event?.eventType)
        assertEquals(ShipmentStatus.OUT_FOR_DELIVERY, event?.status)
        assertTrue("应过 0.70 可信线，实际 ${result.confidence}", result.confidence >= 0.70)
        assertTrue("短信来源必须命中白名单规则", result.confidence > 0.70)
    }

    // ---------------------------------------------------------------- §43.2 驿站

    @Test
    fun `驿站短信 - 到站状态与取件码`() {
        val text = "您的包裹已到达XX驿站，取件码A328。"

        assertEquals("第一层过滤应放行", "pass", firstLayer(text))

        val result = engine.parse(raw(text))
        val event = result.event
        assertNotNull("驿站短信必须产出事件，实际 reason=${result.reason}", event)
        assertEquals(EventType.ARRIVED, event?.eventType)
        assertEquals(ShipmentStatus.ARRIVED, event?.status)
        assertEquals("A328", event?.pickupCode)
        assertTrue("取件码上下文置信度应过线，实际 ${result.confidence}", result.confidence >= 0.70)
    }

    // ---------------------------------------------------------------- §43.3 家门口

    @Test
    fun `家门口投放短信 - 识别为已送达`() {
        val text = "您的包裹已放在家门口。"

        assertEquals("第一层过滤应放行", "pass", firstLayer(text))

        val result = engine.parse(raw(text))
        val event = result.event
        assertNotNull("家门口投放必须产出事件，实际 reason=${result.reason}", event)
        // SOP 的 DELIVERED_HOME 在本工程里就是平台状态 DELIVERED + 放置位置家门口
        assertEquals(ShipmentStatus.DELIVERED, event?.status)
        assertEquals(EventType.DELIVERED, event?.eventType)
        assertEquals("家门口", event?.destination)
    }

    // ---------------------------------------------------------------- §43.4 验证码

    @Test
    fun `验证码短信 - 第一层直接排除`() {
        val text = "您的验证码是583921。"

        assertEquals("验证码必须被排除", "excluded", firstLayer(text))
        assertNotNull(SmsContract.excludedReason(text))
    }

    // ---------------------------------------------------------------- §43.5 银行

    @Test
    fun `银行短信 - 第一层直接排除`() {
        val text = "您的账户收到一笔转账100元。"

        assertEquals("银行短信必须被排除", "excluded", firstLayer(text))
        assertNotNull(SmsContract.excludedReason(text))
    }

    // ---------------------------------------------------------------- §43.6 手机号

    @Test
    fun `只有手机号 - 不是物流短信也不产出事件`() {
        val text = "联系电话：13812345678"

        assertEquals("纯手机号不得判成物流短信", "not_logistics", firstLayer(text))
        assertFalse(SmsContract.isLogistics(text))

        val result = engine.parse(raw(text))
        assertNull("没有快递信号就不该有事件", result.event)
        assertTrue("手机号不得被当成运单号", result.event?.trackingNumber == null)
    }

    // ---------------------------------------------------------------- 白名单与隐私不变量

    @Test
    fun `短信来源已注册在规则白名单里`() {
        assertNotNull(
            "rules.json 必须含短信来源，否则会走通用门禁把正常短信挡掉",
            TestRules.compiled.sourceFor(SmsContract.SOURCE_PACKAGE),
        )
        assertTrue(
            "白名单来源关键词命中一次即可过监听门禁",
            engine.cheapGate(
                SmsContract.SOURCE_PACKAGE,
                "【顺丰速运】您的快递已到达，运单号SF1234567890123",
            ),
        )
    }

    @Test
    fun `发件人指纹不可逆且不会被当成运单号`() {
        val sender = "13812345678"
        val fingerprint = SmsContract.senderFingerprint(sender)

        assertFalse("指纹里不得出现手机号原文", fingerprint.contains(sender))
        assertTrue("指纹必须带分隔符，避免连成一串数字被单号正则命中", fingerprint.contains('-'))
        assertEquals("同一发件人指纹稳定", fingerprint, SmsContract.senderFingerprint(sender))
        assertFalse(
            "不同发件人指纹必须不同",
            fingerprint == SmsContract.senderFingerprint("13800000000"),
        )
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
    }
}
