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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入框定位与页面匹配单测（参考 SOP §16 / §17.3 / §33 先抓节点树）。
 *
 * 其中 [realCainiaoQueryPage] / [realCainiaoHomePage] / [realCainiaoLoginPage]
 * 三组节点是**真机 `uiautomator dump` 抓到的原样数据**（菜鸟 8.11.923），
 * 保证规则不是靠猜出来的。
 */
class CainiaoNodeMatcherTest {

    // ---------- 真机抓取数据（SOP §33） ----------

    /** 查询页唯一输入框：QueryPackageProActivity */
    private val realCainiaoQueryPage = listOf(
        CainiaoNode(
            viewId = "com.cainiao.wireless:id/package_search_layout",
            className = "android.widget.LinearLayout",
        ),
        CainiaoNode(
            viewId = "com.cainiao.wireless:id/package_search_et_content",
            className = "android.widget.EditText",
            text = "搜索快递单号/商品名称/备注",
            editable = true,
            clickable = true,
            enabled = true,
            visible = true,
            focusable = true,
            widthDp = 257,
            parentIds = listOf(
                "com.cainiao.wireless:id/package_search_layout",
                "com.cainiao.wireless:id/action_bar_root",
            ),
        ),
        CainiaoNode(
            viewId = "com.cainiao.wireless:id/package_search_iv_scan",
            className = "android.widget.ImageView",
            contentDesc = "扫一扫，按钮",
            clickable = true,
            widthDp = 20,
        ),
        CainiaoNode(
            viewId = "com.cainiao.wireless:id/package_search_tv_close",
            className = "android.widget.TextView",
            text = "取消",
            clickable = true,
            widthDp = 54,
        ),
    )

    /** 首页搜索条 */
    private val realCainiaoHomePage = listOf(
        CainiaoNode(
            viewId = "com.cainiao.wireless:id/home_action_bar_search_layout",
            className = "android.widget.RelativeLayout",
            clickable = true,
            enabled = true,
            visible = true,
        ),
        CainiaoNode(
            viewId = "com.cainiao.wireless:id/home_action_bar_search_ts_hint",
            className = "android.widget.TextSwitcher",
        ),
        CainiaoNode(
            className = "android.widget.TextView",
            text = "复制单号查包裹进展",
        ),
        CainiaoNode(
            viewId = "com.cainiao.wireless:id/home_action_bar_searchbtn",
            className = "android.widget.TextView",
            text = "搜索",
            clickable = true,
        ),
        CainiaoNode(
            viewId = "com.cainiao.wireless:id/home_action_bar_search_iv_camera",
            className = "android.widget.ImageView",
            contentDesc = "相册，按钮",
            clickable = true,
        ),
    )

    /** 登录页（UserLoginActivity）的两个输入框——绝不能被当成运单号输入框 */
    private val realCainiaoLoginPage = listOf(
        CainiaoNode(
            viewId = "com.cainiao.wireless:id/aliuser_login_mobile_et",
            className = "android.widget.EditText",
            text = "请输入手机号码",
            editable = true,
            clickable = true,
            enabled = true,
            visible = true,
            focusable = true,
            widthDp = 190,
        ),
        CainiaoNode(
            viewId = "com.cainiao.wireless:id/aliuser_register_sms_code_et",
            className = "android.widget.EditText",
            editable = true,
            clickable = true,
            enabled = true,
            visible = true,
            focusable = true,
            widthDp = 330,
        ),
    )

    // ---------- 真机回归 ----------

    @Test
    fun real_query_page_input_is_matched() {
        val picked = CainiaoNodeMatcher.pickInput(realCainiaoQueryPage)
        assertEquals("com.cainiao.wireless:id/package_search_et_content", picked?.viewId)
        assertTrue(CainiaoNodeMatcher.isTargetPage(realCainiaoQueryPage))
    }

    @Test
    fun real_home_page_has_entry_but_no_input() {
        assertNull(CainiaoNodeMatcher.pickInput(realCainiaoHomePage))
        assertFalse(CainiaoNodeMatcher.isTargetPage(realCainiaoHomePage))

        val entry = CainiaoNodeMatcher.pickSearchEntry(realCainiaoHomePage)
        assertEquals("com.cainiao.wireless:id/home_action_bar_search_layout", entry?.viewId)
    }

    /** SOP §2.1「不读取与快递无关的数据」：登录页的手机号/验证码框一律排除 */
    @Test
    fun real_login_page_is_never_treated_as_target_page() {
        assertNull(CainiaoNodeMatcher.pickInput(realCainiaoLoginPage))
        assertFalse(CainiaoNodeMatcher.isTargetPage(realCainiaoLoginPage))
    }

    // ---------- 优先级（SOP §17.3） ----------

    @Test
    fun exact_resource_id_wins() {
        val plain = realCainiaoQueryPage[0].copy(
            className = "android.widget.EditText",
            editable = true,
            focusable = true,
            widthDp = 600,
            text = "快递",
        )
        val exact = realCainiaoQueryPage[1]
        assertTrue(CainiaoNodeMatcher.scoreInput(exact) > CainiaoNodeMatcher.scoreInput(plain))
        assertSame(exact, CainiaoNodeMatcher.pickInput(listOf(plain, exact)))
    }

    @Test
    fun placeholder_text_beats_generic_edittext() {
        val withHint = CainiaoNode(
            className = "android.widget.EditText",
            text = "粘贴运单号查询",
            editable = true,
            focusable = true,
            widthDp = 300,
        )
        val generic = CainiaoNode(
            className = "android.widget.EditText",
            text = "",
            editable = true,
            focusable = true,
            widthDp = 300,
        )
        assertTrue(CainiaoNodeMatcher.scoreInput(withHint) > CainiaoNodeMatcher.scoreInput(generic))
    }

    @Test
    fun wide_input_with_search_parent_scores_above_plain() {
        val wide = CainiaoNode(
            className = "android.widget.EditText",
            editable = true,
            focusable = true,
            widthDp = 400,
            parentIds = listOf("com.foo:id/package_search_layout"),
        )
        val plain = CainiaoNode(
            className = "android.widget.EditText",
            editable = true,
            focusable = true,
            widthDp = 120,
        )
        assertTrue(CainiaoNodeMatcher.scoreInput(wide) > CainiaoNodeMatcher.scoreInput(plain))
    }

    // ---------- 恒定排除（SOP §17.3 末两条） ----------

    @Test
    fun password_box_is_excluded() {
        val pwd = realCainiaoQueryPage[1].copy(viewId = "com.foo:id/search_pwd", password = true)
        assertEquals(0, CainiaoNodeMatcher.scoreInput(pwd))
        assertNull(CainiaoNodeMatcher.pickInput(listOf(pwd)))
    }

    @Test
    fun hidden_or_disabled_is_excluded() {
        val hidden = realCainiaoQueryPage[1].copy(visible = false)
        val disabled = realCainiaoQueryPage[1].copy(enabled = false)
        assertEquals(0, CainiaoNodeMatcher.scoreInput(hidden))
        assertEquals(0, CainiaoNodeMatcher.scoreInput(disabled))
    }

    @Test
    fun non_editable_and_non_edittext_are_excluded() {
        val notEditable = realCainiaoQueryPage[1].copy(editable = false)
        val textView = realCainiaoQueryPage[1].copy(className = "android.widget.TextView")
        assertEquals(0, CainiaoNodeMatcher.scoreInput(notEditable))
        assertEquals(0, CainiaoNodeMatcher.scoreInput(textView))
    }

    @Test
    fun login_and_code_fields_are_excluded_even_with_search_parent() {
        val mobile = CainiaoNode(
            viewId = "com.foo:id/login_search_mobile",
            className = "android.widget.EditText",
            editable = true,
            focusable = true,
            widthDp = 600,
            parentIds = listOf("com.foo:id/search_layout"),
        )
        assertEquals(0, CainiaoNodeMatcher.scoreInput(mobile))
    }

    @Test
    fun empty_candidate_list_yields_null() {
        assertNull(CainiaoNodeMatcher.pickInput(emptyList()))
        assertNull(CainiaoNodeMatcher.pickSearchEntry(emptyList()))
        assertFalse(CainiaoNodeMatcher.isTargetPage(emptyList()))
    }

    // ---------- 搜索入口 ----------

    @Test
    fun search_entry_requires_clickable() {
        val notClickable = realCainiaoHomePage[0].copy(clickable = false)
        val entry = CainiaoNodeMatcher.pickSearchEntry(realCainiaoHomePage)
        assertEquals("com.cainiao.wireless:id/home_action_bar_search_layout", entry?.viewId)

        // 精确 id 不可点时，回落到“搜索”按钮
        val fallback = CainiaoNodeMatcher.pickSearchEntry(
            realCainiaoHomePage.filterNot { it.viewId == "com.cainiao.wireless:id/home_action_bar_search_layout" } +
                notClickable,
        )
        assertEquals("com.cainiao.wireless:id/home_action_bar_searchbtn", fallback?.viewId)
    }

    @Test
    fun placeholder_text_node_is_clickable_entry_fallback() {
        val textNode = CainiaoNode(
            className = "android.widget.TextView",
            text = "复制单号查包裹进展",
            clickable = true,
        )
        assertTrue(CainiaoNodeMatcher.isSearchEntry(textNode))
        assertFalse(
            CainiaoNodeMatcher.isSearchEntry(
                textNode.copy(className = "android.widget.TextView", text = "今日好物推荐"),
            ),
        )
    }

    /**
     * 回归钉子（真机 2026-09-27 FAILED(timeout) 的根因）：
     * 无障碍服务配置没开 `flagReportViewIds` 时，API 24+ 上所有节点的 `viewIdResourceName`
     * 都是 null（落到模型里是空串），此时首页真实节点必须**一个都匹配不上**——
     * 表现就是“扫描一直在跑、永远 miss、5s 后超时、还不打任何日志”。
     *
     * 文案兜底也救不回来：真机的轮播文案挂在 TextSwitcher 的 TextView 上、自身不可点。
     * 配置侧守卫见 [CainiaoAccessibilityConfigTest]。
     */
    @Test
    fun entry_is_missed_when_flag_report_view_ids_is_off() {
        val blind = realCainiaoHomePage.map { it.copy(viewId = "") }

        assertNull(CainiaoNodeMatcher.pickSearchEntry(blind))
        assertNull(CainiaoNodeMatcher.pickInput(blind))
        assertFalse(CainiaoNodeMatcher.isTargetPage(blind))

        // 同一批节点只要 viewId 回来就能立刻命中（证明差异只在 viewId）
        assertEquals(
            "com.cainiao.wireless:id/home_action_bar_search_layout",
            CainiaoNodeMatcher.pickSearchEntry(realCainiaoHomePage)?.viewId,
        )
    }
}
