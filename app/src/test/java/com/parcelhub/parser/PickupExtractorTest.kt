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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取件码提取单测（SOP §6.2 / §6.3）。
 *
 * 必须覆盖：纯数字、字母数字、`16-4-9626`、`2-2-7508`、`A88123`、多取件码、柜号；
 * 必须排除：验证码、手机号、金额、时间、日期、订单号、尾号、单号片段。
 */
class PickupExtractorTest {

    private val extractor = PickupExtractor(TestRules.compiled)

    // ---------- 正向：结构化取件码 ----------

    @Test
    fun segmented_code_with_keyword() {
        val result = extractor.extract("您的快递已到驿站，取件码：16-4-9626，请及时取件")
        assertEquals("16-4-9626", result.code)
        assertTrue(result.confidence >= 0.70)
        assertNull(result.reason)
    }

    @Test
    fun another_segmented_code() {
        val result = extractor.extract("【菜鸟驿站】您的包裹已到，取件码 2-2-7508")
        assertEquals("2-2-7508", result.code)
    }

    @Test
    fun alphanumeric_code() {
        val result = extractor.extract("您的取件码 A88123，请到驿站出示")
        assertEquals("A88123", result.code)
        assertTrue(result.confidence >= 0.70)
    }

    @Test
    fun code_without_colon() {
        val result = extractor.extract("取货码 8866")
        assertEquals("8866", result.code)
    }

    @Test
    fun multiple_codes_all_reported_and_first_is_best() {
        val result = extractor.extract("取件码 11-22-3344，备用取件码 55-66-7788")
        assertEquals("11-22-3344", result.code)
        assertTrue(result.codes.contains("11-22-3344"))
        assertTrue(result.codes.contains("55-66-7788"))
    }

    @Test
    fun pure_digit_code_with_positive_keyword() {
        val result = extractor.extract("您的包裹已到，凭码 6688 到前台领取")
        assertEquals("6688", result.code)
    }

    // ---------- 负向：验证码 / 手机号 / 金额 / 尾号 ----------

    @Test
    fun verification_code_is_rejected() {
        val result = extractor.extract("您的短信验证码 881234，请勿泄露给他人")
        assertNull(result.code)
        assertEquals(0.0, result.confidence, 0.0)
        assertEquals("没有找到取件码", result.reason)
    }

    @Test
    fun verification_code_does_not_block_real_pickup_code() {
        val result = extractor.extract("您的短信验证码是 881234，取件码 16-4-9626")
        assertEquals("16-4-9626", result.code)
        assertFalse(result.codes.contains("881234"))
    }

    @Test
    fun phone_number_is_rejected() {
        val result = extractor.extract("取件码 13812345678")
        assertNull(result.code)
    }

    @Test
    fun amount_is_rejected() {
        val result = extractor.extract("取件码 1234 元")
        assertNull(result.code)
    }

    @Test
    fun order_tail_number_is_rejected() {
        val result = extractor.extract("取件码 1234，订单尾号 5678")
        assertNull(result.code)
    }

    @Test
    fun tracking_fragment_is_rejected_when_overlapping() {
        val result = extractor.extract("取件码 16-4-9626", trackingNumber = "16-4-9626")
        assertNull(result.code)
    }

    @Test
    fun empty_text_returns_no_code() {
        val result = extractor.extract("   ")
        assertNull(result.code)
        assertTrue(result.codes.isEmpty())
        assertNull(result.lockerNumber)
    }

    // ---------- 柜号 ----------

    @Test
    fun locker_number_is_extracted_separately() {
        val result = extractor.extract("您的包裹已入柜，柜号 A12")
        assertNull(result.code)
        assertEquals("A12", result.lockerNumber)
        assertEquals(0.55, result.confidence, 0.001)
        assertEquals("仅识别到柜号", result.reason)
    }

    @Test
    fun locker_and_code_coexist() {
        val result = extractor.extract("您的包裹已入柜，柜号 A12，取件码 16-4-9626")
        assertEquals("16-4-9626", result.code)
        assertEquals("A12", result.lockerNumber)
    }
}
