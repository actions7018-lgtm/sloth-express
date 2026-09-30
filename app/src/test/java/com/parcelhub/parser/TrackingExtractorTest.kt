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

import com.parcelhub.testutil.TestRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运单号 / 承运商提取单测（SOP §6.1）。
 */
class TrackingExtractorTest {

    private val extractor = TrackingExtractor(TestRules.compiled)

    @Test
    fun labeled_tracking_is_extracted_and_uppercased() {
        val result = extractor.extract("您的快递已签收，运单号 sf1234567890123")
        assertEquals("SF1234567890123", result.trackingNumber)
        assertEquals("顺丰速运", result.carrier)
        assertEquals(0.90, result.confidence, 0.001)
    }

    @Test
    fun alias_in_text_wins_over_shape() {
        val result = extractor.extract("中通快递提示：运单号 771234567890")
        assertEquals("771234567890", result.trackingNumber)
        assertEquals("中通快递", result.carrier)
    }

    @Test
    fun no_tracking_returns_null() {
        val result = extractor.extract("您的包裹已到驿站")
        assertNull(result.trackingNumber)
        assertEquals(0.0, result.confidence, 0.0)
    }

    @Test
    fun phone_like_value_is_not_a_tracking_number() {
        val result = extractor.extract("运单号 13812345678")
        assertNull(result.trackingNumber)
    }

    @Test
    fun generic_shape_rule_does_not_hijack_carrier() {
        // “任意 10-15 位字母数字”这类全字符集规则无区分度，必须被排除，
        // 否则任意数字串都会被误判为京东物流。
        assertEquals("中通快递", extractor.detectCarrierByShape("771234567890"))
        assertEquals("顺丰速运", extractor.detectCarrierByShape("SF1234567890123"))
        assertNull(extractor.detectCarrierByShape("A88123"))
    }

    @Test
    fun blank_text_is_safe() {
        val result = extractor.extract("   ")
        assertNull(result.trackingNumber)
        assertNull(result.carrier)
    }

    // ---------- SOP V2.0 §7/§8 列表批量 + §29/§30 紧凑模式 ----------

    @Test
    fun extract_all_collects_multiple_numbers_across_carriers() {
        val results = extractor.extractAll(
            "快递单号 YT0710904037400，另一单快递单号 SF1234567890123",
        )
        val numbers = results.map { it.trackingNumber }
        assertTrue("缺圆通单: $numbers", numbers.contains("YT0710904037400"))
        assertTrue("缺顺丰单: $numbers", numbers.contains("SF1234567890123"))
    }

    @Test
    fun extract_all_dedupes_repeated_numbers() {
        val results = extractor.extractAll(
            "快递单号 YT0710904037400，重复快递单号 YT0710904037400",
        )
        assertEquals(1, results.size)
    }

    @Test
    fun extract_all_bare_numeric_rejected_in_compact_mode() {
        // 紧凑拼接文本里纯数字候选不认（座机号等相邻数字可能被粘成假单号）
        val text = "运单号 771234567890 已发出"
        assertTrue(extractor.extractAll(text, allowBareNumeric = false).isEmpty())
        val allowed = extractor.extractAll(text)
        assertEquals(listOf("771234567890"), allowed.map { it.trackingNumber })
    }

    @Test
    fun extract_all_lettered_candidates_survive_compact_mode() {
        val results = extractor.extractAll(
            "快递单号SF1234567890123已发出",
            allowBareNumeric = false,
        )
        assertEquals(listOf("SF1234567890123"), results.map { it.trackingNumber })
    }

    // ---------- 0.1.10：真机实测的承运商形态漏提取 ----------

    @Test
    fun sto_fifteen_digit_number_via_carrier_alias_is_extracted() {
        // 拼多多物流页「申通快递 : 777449133381845」没有「单号」标签，
        // 只能靠承运商形态提取；旧规则 [78]\d{11,13}（总长 12-14 位）
        // 漏掉 15 位真单号 → 身份闸放行、解析无单号 → 行永远「单号未知」。
        val result = extractor.extract(
            "申通快递 : 777449133381845 复制 订单编号: 260928-434215381420088",
        )
        assertEquals("777449133381845", result.trackingNumber)
        assertEquals("申通快递", result.carrier)
    }
}
