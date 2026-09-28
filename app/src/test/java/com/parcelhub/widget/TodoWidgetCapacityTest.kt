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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Widget 版面容量：桌面给的高度 → 每屏几条 / 第几页。
 * 算错的代价是「还有 N 项」整行被裁到屏外（真机踩过），所以这些边界都钉死。
 */
class TodoWidgetCapacityTest {

    // ---------- 每屏条数 ----------

    @Test
    fun rows_on_this_device_height_is_six() {
        // 真机（HONOR 9X / 华为桌面 4x2 格）：MAX_HEIGHT=184dp、实测框高 191dp
        // 用户需求：行高含 +5px 行间距 = 25.7dp → (184-29)/25.7 = 6.03 → 6
        assertEquals(6, TodoWidgetCapacity.rowsFor(184))
        assertEquals(6, TodoWidgetCapacity.rowsFor(191))
    }

    @Test
    fun rows_at_declared_min_height_still_shows_rows() {
        // todo_widget_info.xml 声明 minHeight=110dp：(110-29)/25.7 = 3.15 → 3 条，不能是 0 或负数
        assertEquals(3, TodoWidgetCapacity.rowsFor(110))
    }

    @Test
    fun rows_never_zero_or_negative_when_height_is_tiny() {
        // 比一屏还矮时退回「至少 1 条」（溢出会被 clip，但不能一条都不显示）
        assertEquals(1, TodoWidgetCapacity.rowsFor(50))
        assertEquals(1, TodoWidgetCapacity.rowsFor(1))
    }

    @Test
    fun rows_falls_back_to_declared_min_height_when_launcher_reports_nothing() {
        // getAppWidgetOptions 可能给空 Bundle（部分桌面 / 首次回填前）→ 退回声明的 minHeight
        assertEquals(3, TodoWidgetCapacity.rowsFor(0))
        assertEquals(3, TodoWidgetCapacity.rowsFor(-1))
        assertEquals(
            TodoWidgetCapacity.rowsFor(TodoWidgetCapacity.FALLBACK_HEIGHT_DP),
            TodoWidgetCapacity.rowsFor(0),
        )
    }

    @Test
    fun rows_grow_with_height_and_are_capped() {
        assertEquals(10, TodoWidgetCapacity.rowsFor(300))  // (300-29)/25.7 = 10.54 → 10 条
        assertEquals(TodoWidgetCapacity.MAX_ROWS, TodoWidgetCapacity.rowsFor(100_000))
    }

    @Test
    fun six_rows_always_fit_in_the_declared_height() {
        // 硬约束：一屏 6 条的总高度（固定开销 + 6 行）必须 ≤ 桌面给的高度，
        // 否则第 6 条会被 clip 到屏外（真机踩过）。改方框/内边距前先跑这条。
        val needed = TodoWidgetCapacity.FIXED_DP + 6 * TodoWidgetCapacity.ROW_H_DP
        assertEquals(true, needed <= 184)
    }

    @Test
    fun row_height_leaves_room_for_the_5px_line_spacing() {
        // 用户要求「取件码上下行间距 +5px」且**保住一页 6 条**：
        // 行高 = 方框 24dp + 上下内边距（dimens.xml：0.83dp+1dp → 3x 下 2px+3px = 5px）
        // 容量公式用的 25.7dp 必须 ≥ 真实行高 25.67dp，否则会把第 6 条算出去
        assertEquals(24, TodoWidgetCapacity.ROW_CONTENT_H_DP)
        assertEquals(true, TodoWidgetCapacity.ROW_H_DP >= 24f + 5f / 3f)
        // 同时不能虚高：虚高会白白丢掉一屏的条数
        assertEquals(true, TodoWidgetCapacity.ROW_H_DP <= 26f)
    }

    // ---------- 分页 ----------

    @Test
    fun page_count_covers_all_items() {
        assertEquals(3, TodoWidgetCapacity.pageCount(7, 3))   // 3+3+1
        assertEquals(1, TodoWidgetCapacity.pageCount(3, 3))   // 正好一屏 → 不出翻页行
        assertEquals(1, TodoWidgetCapacity.pageCount(1, 3))
        assertEquals(1, TodoWidgetCapacity.pageCount(0, 3))   // 空态
        assertEquals(1, TodoWidgetCapacity.pageCount(-5, 3))
    }

    @Test
    fun clamp_page_keeps_us_on_a_non_empty_page() {
        // 上一屏有 7 条、勾到只剩 3 条 → 原来停在第 2 页会看到空白
        assertEquals(0, TodoWidgetCapacity.clampPage(9, 3, 3))
        assertEquals(2, TodoWidgetCapacity.clampPage(9, 7, 3))
        assertEquals(0, TodoWidgetCapacity.clampPage(-1, 7, 3))
        assertEquals(0, TodoWidgetCapacity.clampPage(5, 0, 3)) // 空态永远第 0 页
    }

    @Test
    fun start_offset_is_page_times_rows() {
        assertEquals(0, TodoWidgetCapacity.startOf(0, 3))
        assertEquals(6, TodoWidgetCapacity.startOf(2, 3))
    }
}
