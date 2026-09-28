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
 * 菜鸟自动化配置（参考 SOP §20 Profile / §22 兼容不同菜鸟版本 / §33 先抓节点树）。
 *
 * 用 `data class` 而不用 object：菜鸟改版导致节点变化时，只需新增一个版本实例，
 * 匹配器按版本选用，不用改匹配逻辑（§22）。
 *
 * **DEFAULT 的全部取值来自真机抓取**（`uiautomator dump`，菜鸟 `versionName=8.11.923`），
 * 不是网上整理的资料。SOP §33 要求：禁止未抓节点就硬编码，改版后重新抓取。
 *
 * 真机实测结论：
 *  1. 官方深链不可用——`guoguo://go/…` 目标 Activity 未导出（Permission Denial），
 *     `cainiao://index/my?operation=expressTrace` 无法解析（Activity not found）。
 *     因此只能 `getLaunchIntentForPackage` 开首页，再由无障碍点进查询页。
 *  2. 查询页 `QueryPackageProActivity` **未登录可进入**；但游客态首页的搜索入口会先弹
 *     `UserLoginActivity`（登录页无“跳过”），故不依赖游客态首页。
 *  3. 占位文案是 EditText 的 **text 属性**，不是 hint。
 *  4. 「取包裹」页是 WebView（`cubex_js_*`），无障碍读不到内部，不作为目标页。
 */
data class CainiaoAutomationProfile(
    /** 抓取该配置时的菜鸟版本（§22：多版本并存时按版本选用） */
    val version: String,
    /** 目标包名（SOP §16 只允许操作菜鸟） */
    val packageName: String,
    /** 查询页（真机 `QueryPackageProActivity`）输入框的 resource-id */
    val inputViewId: String,
    /** 输入框占位文案（真机以 text 属性呈现） */
    val inputPlaceholder: String,
    /** 首页搜索条容器（clickable=true） */
    val searchEntryViewId: String,
    /** 首页搜索按钮（text=“搜索”，clickable=true） */
    val searchButtonViewId: String,
    /** 首页搜索条内轮播的引导文案，可作为兜底的文本匹配线索 */
    val searchEntryText: String,
    /** 首页搜索条内相机/相册图标（也在容器内，避免误点） */
    val searchCameraViewId: String,
    /** 查询页 Activity（用于日志核对，不作为唯一判定条件） */
    val targetActivity: String,
    /** 菜鸟首页 Activity */
    val homeActivity: String,
    /** 填入后出现的“搜索快递单号”结果卡片（第一版不点，用户自己点查询，SOP §23） */
    val queryCardViewId: String,
    /**
     * 是否允许自动点击“查询”（SOP §20 / §23 / §37）。
     * 第一版必须为 false：只填单号、不点查询；只有真机验证按钮节点稳定后才考虑打开。
     */
    val enableAutoClickSearch: Boolean = false,
) {
    /** 是否为本配置对应的菜鸟包名 */
    fun isCainiaoPackage(packageName: CharSequence?): Boolean =
        packageName?.toString() == this.packageName

    companion object {
        /** 真机抓取的默认配置：菜鸟 8.11.923 */
        val DEFAULT = CainiaoAutomationProfile(
            version = "8.11.923",
            packageName = "com.cainiao.wireless",
            inputViewId = "com.cainiao.wireless:id/package_search_et_content",
            inputPlaceholder = "搜索快递单号/商品名称/备注",
            searchEntryViewId = "com.cainiao.wireless:id/home_action_bar_search_layout",
            searchButtonViewId = "com.cainiao.wireless:id/home_action_bar_searchbtn",
            searchEntryText = "复制单号查包裹进展",
            searchCameraViewId = "com.cainiao.wireless:id/home_action_bar_search_iv_camera",
            targetActivity = "com.cainiao.wireless.mvp.activities.QueryPackageProActivity",
            homeActivity = "com.cainiao.wireless.homepage.view.activity.HomePageActivity",
            queryCardViewId = "com.cainiao.wireless:id/package_query_import_card_normal_layout",
            enableAutoClickSearch = false,
        )

        /** 按菜鸟版本选用配置；未知版本回落到 DEFAULT（§22） */
        fun forVersion(version: String?, known: List<CainiaoAutomationProfile> = listOf(DEFAULT)): CainiaoAutomationProfile =
            known.firstOrNull { it.version == version } ?: DEFAULT
    }
}
