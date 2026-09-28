/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.autoquery.cainiao

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Profile 与 DeepLink 契约单测（参考 SOP §12 / §20 / §22 / §23）。
 */
class CainiaoAutomationProfileTest {

    /** 第一版铁律：不自动点查询（SOP §20 / §23） */
    @Test
    fun default_profile_never_auto_clicks_search() {
        assertFalse(CainiaoAutomationProfile.DEFAULT.enableAutoClickSearch)
    }

    /** 默认配置是真机抓取的 8.11.923（SOP §33） */
    @Test
    fun default_profile_matches_captured_device() {
        val p = CainiaoAutomationProfile.DEFAULT
        assertEquals("8.11.923", p.version)
        assertEquals("com.cainiao.wireless", p.packageName)
        assertEquals("com.cainiao.wireless:id/package_search_et_content", p.inputViewId)
        assertEquals("搜索快递单号/商品名称/备注", p.inputPlaceholder)
        assertTrue(p.isCainiaoPackage("com.cainiao.wireless"))
        assertFalse(p.isCainiaoPackage("com.taobao.taobao"))
        assertFalse(p.isCainiaoPackage(null))
    }

    /** SOP §22：按版本选用；未知版本回落 DEFAULT */
    @Test
    fun for_version_selects_known_or_falls_back() {
        val newer = CainiaoAutomationProfile.DEFAULT.copy(
            version = "9.0.0",
            inputViewId = "com.cainiao.wireless:id/future_search_box",
        )
        val known = listOf(CainiaoAutomationProfile.DEFAULT, newer)

        assertEquals(newer, CainiaoAutomationProfile.forVersion("9.0.0", known))
        assertEquals(
            CainiaoAutomationProfile.DEFAULT,
            CainiaoAutomationProfile.forVersion("8.11.923", known),
        )
        assertEquals(CainiaoAutomationProfile.DEFAULT, CainiaoAutomationProfile.forVersion("7.0.0", known))
        assertEquals(CainiaoAutomationProfile.DEFAULT, CainiaoAutomationProfile.forVersion(null, known))
    }

    /** SOP §12：默认 provider 未经验证，一律返回 null */
    @Test
    fun none_deep_link_provider_returns_null() {
        assertNull(CainiaoDeepLinkProvider.NONE.buildTrackingLink("SF1234567890123", null))
        assertNull(CainiaoDeepLinkProvider.NONE.buildTrackingLink("", "shentong"))
    }

    /** SOP §12 契约：未经验证一律返回 null；接口可被调用、可扩展 */
    @Test
    fun custom_provider_contract() {
        var calledWith: String? = null
        val custom = object : CainiaoDeepLinkProvider {
            override fun buildTrackingLink(trackingNumber: String, carrier: String?): Uri? {
                calledWith = trackingNumber
                return null // 未经真机验证 → 必须返回 null，不得猜测构造
            }
        }
        assertNull(custom.buildTrackingLink("SF1", null))
        assertEquals("SF1", calledWith)
        // 真实 Uri 只能在真机上验证（JVM 单测里 android.net.Uri 是空 stub），不叠加断言
    }

    /** SOP §20：换版本只换 Profile，匹配逻辑不动——不同配置各行其是 */
    @Test
    fun matcher_honors_given_profile() {
        val custom = CainiaoAutomationProfile.DEFAULT.copy(inputViewId = "com.foo:id/box")
        val node = CainiaoNode(
            viewId = "com.foo:id/box",
            className = "android.widget.EditText",
            editable = true,
        )
        // 默认配置下该节点只是普通兜底分
        val defaultScore = CainiaoNodeMatcher.scoreInput(node)
        // 自定义配置下它是精确 id，拿最高分
        val customScore = CainiaoNodeMatcher.scoreInput(node, custom)
        assertTrue(customScore > defaultScore)
        assertEquals("com.foo:id/box", CainiaoNodeMatcher.pickInput(listOf(node), custom)?.viewId)
    }
}
