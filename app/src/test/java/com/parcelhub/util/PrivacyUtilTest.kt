/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脱敏单测（SOP §22.2）：
 * 落库摘要里不得出现完整运单号 / 手机号 / 详细地址。
 */
class PrivacyUtilTest {

    @Test
    fun pure_digit_tracking_is_masked() {
        val summary = PrivacyUtil.summarize("您的包裹已签收，运单号 771234567890")
        assertFalse(summary.contains("771234567890"))
        assertTrue(summary.contains("7712****90"))
    }

    @Test
    fun letter_prefixed_tracking_is_masked() {
        for (tracking in listOf("YT4482236617", "EA123456789CN", "SF1234567890123", "JD00123456789")) {
            val summary = PrivacyUtil.summarize("运单号 $tracking 已更新")
            assertFalse("$tracking 泄露到摘要: $summary", summary.contains(tracking))
        }
    }

    @Test
    fun phone_number_is_masked() {
        val summary = PrivacyUtil.summarize("快递员 13812345678 将联系您")
        assertFalse(summary.contains("13812345678"))
        assertTrue(summary.contains("138****5678"))
    }

    @Test
    fun pickup_code_keeps_readable_in_summary() {
        // 取件码是展示必需字段，不应被单号规则误伤
        val summary = PrivacyUtil.summarize("您的包裹已到驿站，取件码 16-4-9626")
        assertTrue(summary.contains("16-4-9626"))
        val alnum = PrivacyUtil.summarize("您的包裹已入柜，取件码 A88123")
        assertTrue(alnum.contains("A88123"))
    }

    @Test
    fun blank_input_stays_blank() {
        assertTrue(PrivacyUtil.summarize("").isEmpty())
        assertTrue(PrivacyUtil.summarize("   ").isEmpty())
    }

    @Test
    fun summary_is_length_limited() {
        val summary = PrivacyUtil.summarize("快递".repeat(200))
        assertTrue(summary.length <= 81)
    }
}
