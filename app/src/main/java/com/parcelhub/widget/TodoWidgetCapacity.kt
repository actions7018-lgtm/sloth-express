/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.widget

/**
 * Widget「一屏放几条 / 第几页」的纯函数。
 *
 * 为什么需要它：桌面给的高度是**运行时才知道**的（`AppWidgetManager.getAppWidgetOptions`
 * 的 `OPTION_APPWIDGET_MAX_HEIGHT`），而 RemoteViews 没有测量回调 —— 只能拿 dp 数值
 * 自己算。算错的后果真机见过两次：一次是溢出行被 clip 到屏外，一次是按 MIN_HEIGHT 算出
 * 每页 1 条、下面空一大片。
 *
 * 版面账（dp，对应 `res/layout/widget_todo*.xml`，改布局必须同步这里）：
 *   固定开销 FIXED = 上内边距 5 + 下内边距 4 + 标题行 14sp≈19（真机 dump 实测 57px/3x）+ 到列表间距 1 = 29
 *   单条行高 ROW   = 方框 24（内容高）+ 根视图上下内边距合计 5px≈1.67dp = 25.7
 *   → 一屏条数 = (高度 - 29) / 25.7，至少 1 条；本机 184dp → **6 条**
 *   （29 + 6×25.7 = 183.2 ≤ 184，见 TodoWidgetCapacityTest 的硬约束；
 *     翻页字在标题行右上角，不占条目区高度，所以没有单独的 MORE 项；
 *     单号也改成与主文案同行右侧，否则「待查询」条目是两行 34dp，一页就装不下 6 条）
 *
 * 为什么固定开销从 35 压到 29、行高从 24 涨到 25.7：
 * 用户要「取件码上下行间距增大 5px」且**保住一页 6 条**（真机量过：余量只剩 15px），
 * 所以行高 +5px 的代价必须从组件自身上下留白里扣（8/6dp → 5/4dp，间距 2dp → 1dp）。
 * 行高用 25.7 而不是 25.67：公式取整方向要偏保守（宁可少算 0.03dp 也别溢出），
 * 桌面给的真实框高是 191dp、上报的 MAX_HEIGHT 只有 184dp，还留着 7dp 的余量兜底。
 *
 * 方框 36dp → 24dp 是用户按「行间距还是太宽，一页放 6 个」压的：文字只有 19dp，
 * 行高 39dp 时行与行之间空 20dp。**方框再加大会直接让一页从 6 条掉到 5 条**。
 *
 * 高度取 `OPTION_APPWIDGET_MAX_HEIGHT`：MIN_HEIGHT 是「可缩到的最小值」，
 * 按它算会退化成每页 1 条、下面空一大片（真机踩过）。
 *
 * 注意：这里**不能**用 ScrollView 做滚动（RemoteViews 白名单不放它，
 * `apply()` 会抛 Class not allowed to be inflated），超出容量只能翻页，
 * 见 README §3.6。
 */
object TodoWidgetCapacity {

    /** 单条**内容**高（dp）= 方框高度；根视图另有上下内边距（dimens.xml） */
    const val ROW_CONTENT_H_DP = 24

    /** 单条行高（dp，含上下内边距）= 72px + 5px ≈ 25.67dp，这里取保守的 25.7 */
    const val ROW_H_DP = 25.7

    /** 上下内边距 + 标题 + 间距的固定开销（dp） */
    const val FIXED_DP = 29

    /** 底部/标题行翻页字的高度：已挪进标题行，**不占条目区高度**（容量公式不再为它留位） */

    /** 单页条数上限（RemoteViews 体积 / addView 次数护栏） */
    const val MAX_ROWS = 12

    /** 桌面没回报高度时的兜底 = 我们在 todo_widget_info.xml 里声明的 minHeight */
    const val FALLBACK_HEIGHT_DP = 110

    /** 一屏能显示几条（翻页字在标题行、不占高度；高度非法或缺失时退回兜底值） */
    fun rowsFor(heightDp: Int): Int {
        val height = if (heightDp > 0) heightDp else FALLBACK_HEIGHT_DP
        // 行高是小数 dp（25.7）→ 必须用浮点除法再截断，否则 155/24 这类整数除法会算多
        return ((height - FIXED_DP) / ROW_H_DP).toInt().coerceIn(1, MAX_ROWS)
    }

    /** 总页数（至少 1 页） */
    fun pageCount(itemCount: Int, rows: Int): Int {
        if (itemCount <= 0 || rows <= 0) return 1
        return (itemCount + rows - 1) / rows
    }

    /** 把页码夹进合法范围：条目被勾掉 / 删除后页数会变少，不能停在空页上 */
    fun clampPage(page: Int, itemCount: Int, rows: Int): Int {
        val last = pageCount(itemCount, rows) - 1
        return page.coerceIn(0, last)
    }

    /** 指定页的起始下标 */
    fun startOf(page: Int, rows: Int): Int = page * rows
}
