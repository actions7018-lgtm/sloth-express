/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.pending

/**
 * 「去对应 App 查看」的补全焦点（需求 §八 与 Accessibility 联动）。
 *
 * 用户在待补全详情点开来源购物 App 后，这里记录要监控的
 * `sourcePackageName`；无障碍服务在 [matches] 命中时才读取该 App 的页面文本，
 * 尝试从订单 / 物流页识别快递单号并回补 ShipmentPending。
 *
 *  - **有时限**：焦点只存活 [TTL_MS]（10 分钟），到点自动失效，不做常驻监控；
 *  - **单焦点**：同一时刻只监控一个来源 App（详情页再次点击会覆盖）；
 *  - **无 Android 依赖**：纯内存对象，可被 JVM 单测覆盖。
 */
object PendingFocus {

    /** 焦点存活时间：打开来源 App 后 10 分钟内有效 */
    const val TTL_MS: Long = 10 * 60_000L

    data class Focus(
        val packageName: String,
        val pendingId: Long,
        val deadlineAt: Long,
    )

    @Volatile
    private var focus: Focus? = null

    /** 用户点击「去对应 App 查看」时建立焦点（[now] = 当前时间戳 ms） */
    fun set(packageName: String, pendingId: Long, now: Long) {
        focus = Focus(packageName, pendingId, now + TTL_MS)
    }

    fun clear() {
        focus = null
    }

    /** 当前焦点（过期自动清空）；仅供诊断 / 测试读取 */
    fun current(now: Long): Focus? {
        val f = focus ?: return null
        if (now > f.deadlineAt) {
            focus = null
            return null
        }
        return f
    }

    /**
     * 无障碍事件包名是否命中焦点（需求 §八：`packageName == sourcePackageName`）。
     * 过期焦点顺手清掉，之后一律返回 false。
     */
    fun matches(packageName: String?, now: Long): Boolean {
        val f = current(now) ?: return false
        return packageName != null && packageName == f.packageName
    }
}
