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

import java.util.Locale

/** 时间工具（SOP §9.2 保留策略与 UI 相对时间展示）。 */
object TimeUtil {

    const val SECOND = 1000L
    const val MINUTE = 60 * SECOND
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR

    /** 相对时间：刚刚 / n 分钟前 / n 小时前 / 昨天 / 具体日期 */
    fun relative(now: Long, then: Long): String {
        if (then <= 0L) return "未知"
        val diff = now - then
        return when {
            diff < MINUTE -> "刚刚"
            diff < HOUR -> "${diff / MINUTE} 分钟前"
            diff < DAY && isSameDay(now, then) -> "${diff / HOUR} 小时前"
            diff < 2 * DAY -> "昨天"
            diff < 7 * DAY -> "${diff / DAY} 天前"
            else -> dateText(then)
        }
    }

    /** 事件时间归一化窗口：用于“同事件重复通知”去重（SOP §10.3） */
    fun timeWindowBucket(eventTime: Long, windowMs: Long = 5 * MINUTE): Long =
        if (eventTime <= 0L) 0L else eventTime / windowMs

    private fun isSameDay(a: Long, b: Long): Boolean {
        // 简化实现：以自然日边界（本地时区）比较
        val calA = java.util.Calendar.getInstance().apply { timeInMillis = a }
        val calB = java.util.Calendar.getInstance().apply { timeInMillis = b }
        return calA.get(java.util.Calendar.YEAR) == calB.get(java.util.Calendar.YEAR) &&
            calA.get(java.util.Calendar.DAY_OF_YEAR) == calB.get(java.util.Calendar.DAY_OF_YEAR)
    }

    fun dateText(time: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = time }
        return String.format(
            Locale.CHINA,
            "%04d-%02d-%02d %02d:%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
        )
    }
}
