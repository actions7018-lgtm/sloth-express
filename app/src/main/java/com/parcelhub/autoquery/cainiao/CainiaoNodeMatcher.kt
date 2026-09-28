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

/**
 * 菜鸟页面节点匹配（参考 SOP §16 页面匹配策略 / §17 输入框定位优先级）。
 *
 * 纯函数、无 Android 依赖，可在 JVM 单测覆盖（§33：先抓节点再写规则）。
 *
 * 定位优先级按 SOP §17.3 自上而下，命中即得高分（[profile] 决定各档的具体取值）：
 *  1. resource-id 精确等于目标输入框 id
 *  2. class 为 EditText 且 viewId 同时含 `search` 与 (`express`/`mail`/`package`)
 *  3. class 为 EditText 且文案含 单号/运单/快递/粘贴/搜索
 *  4. focusable 且正在获得焦点的 EditText
 *  5. focusable 且宽于 250dp、父级含 search/express/package 的 EditText
 *  6. 普通 focusable EditText（兜底）
 *
 * 恒定排除（SOP §17.3 末两条 + §2.1 不读无关数据）：
 *  密码框、登录/验证码/手机号输入框、被禁用或不可见的节点。
 *
 * 纯函数、无 Android 依赖，可在 JVM 单测覆盖。
 */
object CainiaoNodeMatcher {

    /** 分档权重：只保证相对大小，不参与业务计算 */
    private const val SCORE_EXACT_ID = 10_000
    private const val SCORE_ID_HINT = 5_000
    private const val SCORE_TEXT_HINT = 3_000
    private const val SCORE_FOCUSED = 1_500
    private const val SCORE_WIDTH_PARENT = 800
    private const val SCORE_FALLBACK = 300

    /** SOP §17.3：输入框宽度需大于 250dp */
    const val MIN_INPUT_WIDTH_DP = 250

    private val ID_KEYWORDS = listOf("search", "express", "mail", "package", "track", "waybill")
    private val TEXT_KEYWORDS = listOf("单号", "运单", "快递", "粘贴", "搜索", "查包裹", "tracking", "number")

    /** 绝不填入的输入框（登录态相关，SOP §2.2 不模拟登录、不读无关数据） */
    private val FORBIDDEN_ID_KEYWORDS =
        listOf("login", "password", "passwd", "sms", "verify", "code", "mobile", "phone", "account")

    /**
     * 该节点是否可以被当作“运单号输入框”。
     *
     * 恒定排除：密码框、非可编辑文本、被禁用/隐藏、登录与验证码相关输入框。
     */
    fun isEligibleInput(node: CainiaoNode): Boolean {
        if (!node.isEditText) return false
        if (node.password) return false
        if (!node.editable) return false
        if (!node.enabled || !node.visible) return false
        val id = node.viewId.lowercase()
        if (FORBIDDEN_ID_KEYWORDS.any { id.contains(it) }) return false
        return true
    }

    /** 该节点作为运单号输入框的分值，0 表示不可用 */
    fun scoreInput(
        node: CainiaoNode,
        profile: CainiaoAutomationProfile = CainiaoAutomationProfile.DEFAULT,
    ): Int {
        if (!isEligibleInput(node)) return 0

        if (node.viewId == profile.inputViewId) return SCORE_EXACT_ID

        // viewId 同时命中 “search” 与 “express/mail/package/waybill/track” 之一时更可信
        val lowerId = node.viewId.lowercase()
        val idSaysSearch = lowerId.contains("search") &&
            (lowerId.contains("express") || lowerId.contains("mail") ||
                lowerId.contains("package") || lowerId.contains("waybill") ||
                lowerId.contains("track"))
        if (idSaysSearch) return SCORE_ID_HINT

        val haystack = (node.text + "|" + node.contentDesc).lowercase()
        if (TEXT_KEYWORDS.any { haystack.contains(it) }) return SCORE_TEXT_HINT

        if (node.focusable && node.focused) return SCORE_FOCUSED

        if (node.focusable && node.widthDp > MIN_INPUT_WIDTH_DP && parentSaysSearch(node)) {
            return SCORE_WIDTH_PARENT
        }

        return SCORE_FALLBACK
    }

    /** 在候选节点中选出分值最高者；平手取列表中靠前的一个 */
    fun pickInput(
        nodes: List<CainiaoNode>,
        profile: CainiaoAutomationProfile = CainiaoAutomationProfile.DEFAULT,
    ): CainiaoNode? =
        nodes
            .map { it to scoreInput(it, profile) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first

    /**
     * 识别“当前是否为目标查询页”：只要存在可用的运单号输入框即可。
     *
     * 不依赖 Activity 名（SOP §16：以页面节点为准，包名只是第一道门）。
     */
    fun isTargetPage(
        nodes: List<CainiaoNode>,
        profile: CainiaoAutomationProfile = CainiaoAutomationProfile.DEFAULT,
    ): Boolean = pickInput(nodes, profile) != null

    /** 该节点是否为“进入查询页”的搜索入口 */
    fun isSearchEntry(
        node: CainiaoNode,
        profile: CainiaoAutomationProfile = CainiaoAutomationProfile.DEFAULT,
    ): Boolean {
        if (!node.visible || !node.enabled) return false
        if (node.viewId == profile.searchEntryViewId ||
            node.viewId == profile.searchButtonViewId
        ) {
            return node.clickable
        }
        // 兜底：轮播文案节点本身不可点，但父级可点
        if (node.text == profile.searchEntryText && node.clickable) return true
        return false
    }

    /** 在候选节点中挑出首页搜索入口（优先精确 id，其次文本兜底） */
    fun pickSearchEntry(
        nodes: List<CainiaoNode>,
        profile: CainiaoAutomationProfile = CainiaoAutomationProfile.DEFAULT,
    ): CainiaoNode? {
        val exact = nodes.firstOrNull {
            it.viewId == profile.searchEntryViewId && it.clickable
        }
        if (exact != null) return exact
        val button = nodes.firstOrNull {
            it.viewId == profile.searchButtonViewId && it.clickable
        }
        if (button != null) return button
        return nodes.firstOrNull { isSearchEntry(it, profile) && it.clickable }
    }

    /** 父链是否包含搜索/运单相关容器（SOP §17.3 第 5 条） */
    private fun parentSaysSearch(node: CainiaoNode): Boolean {
        val parents = node.parentIds.joinToString("|").lowercase()
        return ID_KEYWORDS.any { parents.contains(it) }
    }
}
