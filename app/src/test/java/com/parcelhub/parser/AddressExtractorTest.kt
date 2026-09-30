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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地址 / 取件地点提取单测（SOP §6.4：不得猜测地址）。
 */
class AddressExtractorTest {

    private val extractor = AddressExtractor(TestRules.compiled)

    @Test
    fun pickup_location_extracted_from_label() {
        assertEquals("南门驿站", extractor.extractLocation("取件地点：南门驿站"))
        assertEquals("西门快递柜", extractor.extractLocation("取件点：西门快递柜"))
    }

    @Test
    fun generic_phrase_is_not_a_location() {
        // “您的包裹已到驿站”里没有真实站点名，不得把它当取件地点
        assertNull(extractor.extractLocation("您的包裹已到驿站，请凭码取件"))
    }

    @Test
    fun carrier_brand_is_not_a_location() {
        // 拼多多路线卡「预计 5 小时内到达 圆通快递 : YT07…」——
        // 地点规则 2（“到达 + 2~16 汉字”）会截到承运商品牌名，必须滤掉（SOP §6.4）
        assertNull(extractor.extractLocation("已走 81%，预计 5 小时内到达 圆通快递"))
        assertNull(extractor.extractLocation("您的快件已到达 申通快递 网点"))
        assertNull(extractor.extractLocation("预计送达 中通快递"))
        // 真实站点不受影响
        assertEquals("南门驿站", extractor.extractLocation("取件地点：南门驿站"))
    }

    @Test
    fun missing_location_returns_null_not_guess() {
        assertNull(extractor.extractLocation("今天天气不错"))
        assertNull(extractor.extractLocation(""))
        assertNull(extractor.extractLocation("   "))
    }

    @Test
    fun destination_extracted_when_explicitly_given() {
        val destination = extractor.extractDestination("收货地址：北京市海淀区中关村大街1号")
        assertNotNull(destination)
        assertTrue(destination!!.contains("北京市"))
    }

    @Test
    fun destination_absent_returns_null() {
        assertNull(extractor.extractDestination("您的包裹派送中"))
    }

    // ---- 取件地址（短站点） vs 放置位置（完整投放点）----

    @Test
    fun location_keeps_station_short_and_place_keeps_full() {
        // 取件地址只留站点，行政区前缀交给“放置位置”行展示
        assertEquals(
            "菜鸟驿站",
            extractor.extractLocation("余杭区东连街道菜鸟驿站已收到您的包裹，取件码：12-34"),
        )
        assertEquals(
            "余杭区东连街道菜鸟驿站",
            extractor.extractLocationRaw("余杭区东连街道菜鸟驿站已收到您的包裹，取件码：12-34"),
        )
        assertEquals(
            "余杭区东连街道菜鸟驿站",
            extractor.extractPlace("余杭区东连街道菜鸟驿站已收到您的包裹，取件码：12-34"),
        )
    }

    @Test
    fun short_station_never_trims_away_the_only_information() {
        // 没有行政区前缀时原样返回
        assertEquals("东门代收点", extractor.extractLocation("包裹已到东门代收点"))
        // 裁完只剩“驿站”这种纯后缀时保留原文，避免信息被裁没
        assertEquals("中关村大街驿站", extractor.extractLocation("包裹已到中关村大街驿站"))
    }

    @Test
    fun place_door_delivery_extracted_when_explicit() {
        assertEquals("家门口", extractor.extractPlace("您的快递已放在家门口，运单号771234567890"))
        assertEquals("传达室", extractor.extractPlace("快递已放于传达室，请及时领取"))
    }

    @Test
    fun place_absent_returns_null_not_guess() {
        // 没有行政区前缀、也没有门口投放 → 不猜
        assertNull(extractor.extractPlace("菜鸟驿站已收到您的包裹，取件码：16-4-9626"))
        assertNull(extractor.extractPlace("今天天气不错"))
        assertNull(extractor.extractPlace(""))
    }
}
