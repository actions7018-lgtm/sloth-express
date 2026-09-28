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

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无障碍服务配置守卫（SOP §5 服务配置 / §16 页面匹配的前提）。
 *
 * 为什么用“读源码 XML”这种测试：真机踩过一次**运行时才暴露、日志里还看不出来**的坑——
 * 配置是从诊断用最小配置改回来的，漏了 `flagReportViewIds`，导致 API 24+ 上
 * `viewIdResourceName` 恒为 null，`CainiaoNodeMatcher` 靠 resource-id 的判定全部失效，
 * 表现为“扫描一直在跑、永远匹配不上、5s 后 FAILED(timeout)”。
 *
 * 那次排障花了整轮真机时间才定位，所以把这条约束钉在单测里：
 * 少了这个属性，`CainiaoNodeMatcherTest.entry_is_missed_when_flag_report_view_ids_is_off`
 * 描述的失效模式就会在真机上重演。
 */
class CainiaoAccessibilityConfigTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    /** 从工作目录向上找这份配置（Gradle 单测的 user.dir 可能是模块目录也可能是仓库根） */
    private fun serviceConfig(): File {
        val rels = listOf(
            "src/main/res/xml/cainiao_accessibility.xml",
            "app/src/main/res/xml/cainiao_accessibility.xml",
        )
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in rels) {
                val candidate = File(dir, rel)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        error("未找到 cainiao_accessibility.xml（工作目录=${System.getProperty("user.dir")}）")
    }

    private fun attribute(name: String): String {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val root = factory.newDocumentBuilder().parse(serviceConfig()).documentElement
        assertTrue(
            "cainiao_accessibility.xml 缺少 android:$name 属性",
            root.hasAttributeNS(androidNs, name),
        )
        return root.getAttributeNS(androidNs, name)
    }

    /**
     * 没有它：所有节点 viewIdResourceName=null → 一切 id 匹配失效（真机 timeout 根因）。
     *
     * 它是 `android:accessibilityFlags` 的枚举值（对应 AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS），
     * 不是独立属性——写成 `android:flagReportViewIds="true"` 会资源链接失败。
     */
    @Test
    fun flag_report_view_ids_is_enabled() {
        val flags = attribute("accessibilityFlags")
        assertTrue(
            "accessibilityFlags=$flags 未包含 flagReportViewIds（缺失会导致所有 resource-id 匹配失效）",
            flags.split('|').map { it.trim() }.contains("flagReportViewIds"),
        )
    }

    /** 没有它：读不到窗口内容，`rootInActiveWindow` 拿不到可遍历的节点树 */
    @Test
    fun can_retrieve_window_content_is_enabled() {
        assertEquals("true", attribute("canRetrieveWindowContent"))
    }

    /** 服务要收 WINDOW_STATE_CHANGED 才知道页面切换（SOP §16 页面指纹） */
    @Test
    fun event_types_cover_window_state_changed() {
        val types = attribute("accessibilityEventTypes")
        assertTrue("accessibilityEventTypes=$types 未覆盖 typeAllMask", types.contains("typeAllMask"))
    }
}
