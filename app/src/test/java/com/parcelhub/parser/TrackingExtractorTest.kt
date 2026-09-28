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
}
