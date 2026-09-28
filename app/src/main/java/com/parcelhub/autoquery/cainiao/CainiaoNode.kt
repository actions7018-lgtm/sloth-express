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
 * 无障碍节点的纯数据投影。
 *
 * 之所以不是直接持有 `AccessibilityNodeInfo`：匹配规则必须能在 JVM 单测里跑（SOP §33），
 * 而 `AccessibilityNodeInfo` 只能由系统构造。服务侧负责采集，本类只带匹配要用的字段。
 *
 * @property viewId 完整 resource-id，如 `com.cainiao.wireless:id/package_search_et_content`
 * @property widthDp 节点宽度（dp），SOP §17.3 要求输入框宽于 250dp 才考虑
 * @property parentIds 从根到父的 resource-id 链，用于“父级包含 search/express/package”判定
 */
data class CainiaoNode(
    val viewId: String = "",
    val className: String = "",
    val text: String = "",
    val contentDesc: String = "",
    val editable: Boolean = false,
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val password: Boolean = false,
    val widthDp: Int = 0,
    val parentIds: List<String> = emptyList(),
) {
    /** 是否为可编辑的单行/多行文本框 */
    val isEditText: Boolean get() = className == EDITABLE_CLASS || className.endsWith("EditText")

    companion object {
        const val EDITABLE_CLASS = "android.widget.EditText"
    }
}
