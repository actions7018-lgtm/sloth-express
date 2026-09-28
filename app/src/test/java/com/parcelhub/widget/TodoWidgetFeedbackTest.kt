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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Widget 勾选反馈动画的帧表。
 *
 * 帧表算错的后果在真机上很难看清（「没淡出就没了」「停在半透明不动」「收尾跳一下」），
 * 所以把 pop、递减性、上滑进度、帧距全部钉死在单测里。
 */
class TodoWidgetFeedbackTest {

    private fun assertNear(message: String, expected: Float, actual: Float) =
        assertTrue("$message（expected=$expected actual=$actual）", abs(expected - actual) < 0.001f)

    @Test
    fun first_frame_starts_the_check_pop_shrunk_and_fully_opaque() {
        val first = TodoWidgetFeedback.frameOf(TodoWidgetFeedback.PHASE_CHECK)
        // pop 的起手 = 方框内边距更大（图标更小），弹回 3dp；白名单不放 setScaleX，只能改内边距
        assertTrue(
            "第 0 帧方框内边距要比静止值大（图标缩下去才有 pop）",
            first.checkPadDp > TodoWidgetFeedback.REST_CHECK_PAD_DP,
        )
        assertNear("打勾时整行不能透明", 1f, first.alpha)
        assertNear("进入段不该提前上滑", 0f, first.shift)
    }

    @Test
    fun pop_overshoots_then_settles_exactly_at_rest_padding() {
        val enter = (0 until TodoWidgetFeedback.ENTER_FRAMES).map {
            TodoWidgetFeedback.frameOf(it).checkPadDp
        }
        // 内边距冲到静止值以下 = 图标冲过头再落回（回弹感），否则就是干巴巴的放大
        assertTrue(
            "pop 必须冲过静止内边距（回弹感）",
            enter.minOrNull()!! < TodoWidgetFeedback.REST_CHECK_PAD_DP,
        )
        assertNear(
            "进入段最后一帧必须精确回到静止内边距，否则方框会永久偏大",
            TodoWidgetFeedback.REST_CHECK_PAD_DP,
            enter.last(),
        )
        assertTrue(
            "缩小幅度要看得出来（图标至少小 1dp）",
            enter.first() > TodoWidgetFeedback.REST_CHECK_PAD_DP + 1f,
        )
    }

    @Test
    fun enter_segment_never_fades_or_shifts() {
        for (phase in 0 until TodoWidgetFeedback.ENTER_FRAMES) {
            val frame = TodoWidgetFeedback.frameOf(phase)
            assertNear("打勾阶段不能透明（phase=$phase）", 1f, frame.alpha)
            assertNear("打勾阶段不能上滑（phase=$phase）", 0f, frame.shift)
        }
    }

    @Test
    fun exit_alpha_strictly_decreases_to_zero() {
        var prev = Float.MAX_VALUE
        for (phase in TodoWidgetFeedback.ENTER_FRAMES..TodoWidgetFeedback.LAST_PHASE) {
            val alpha = TodoWidgetFeedback.frameOf(phase).alpha
            assertTrue("第 $phase 帧必须比上一帧更透明", alpha < prev)
            prev = alpha
        }
        // 末帧透明到 0：此时下面的条目已滑到位，移除时视觉上是「原地不动」
        assertNear("最后一帧应完全透明（预滑已到位，收尾才不跳）", 0f, prev)
    }

    @Test
    fun rows_below_slide_exactly_one_row_height_by_the_last_frame() {
        var prev = -1f
        for (phase in TodoWidgetFeedback.ENTER_FRAMES..TodoWidgetFeedback.LAST_PHASE) {
            val shift = TodoWidgetFeedback.frameOf(phase).shift
            assertTrue("上滑进度必须不减（phase=$phase）", shift >= prev)
            prev = shift
        }
        assertNear("末帧必须正好滑满一行，否则移除瞬间会跳一下", 1f, prev)
        // 淡出与上滑同源（同一个 standard 曲线），淡完的瞬间也正好滑完
        assertNear(
            "淡出进度与上滑进度必须同步",
            1f - TodoWidgetFeedback.frameOf(TodoWidgetFeedback.LAST_PHASE).alpha,
            prev,
        )
    }

    @Test
    fun untouched_rows_get_the_identity_frame() {
        // 普通条目若被误判成动画条目，方框会被改内边距 —— 必须是恒等帧
        assertTrue("没被勾过的条目必须是恒等帧", TodoWidgetFeedback.frameFor(null).isIdentity)
        assertFalse(
            "被勾过的条目即使是第 0 帧也不是恒等帧（要 pop）",
            TodoWidgetFeedback.frameFor(TodoWidgetFeedback.PHASE_CHECK).isIdentity,
        )
        assertTrue("非恒等帧才应该产生动画 setter", TodoWidgetFeedback.frameOf(TodoWidgetFeedback.LAST_PHASE).isIdentity.not())
    }

    @Test
    fun out_of_range_phase_clamps_instead_of_crashing() {
        assertNear(
            "负帧号按第 0 帧处理",
            TodoWidgetFeedback.frameOf(TodoWidgetFeedback.PHASE_CHECK).checkPadDp,
            TodoWidgetFeedback.frameOf(-5).checkPadDp,
        )
        assertNear(
            "超大帧号按末帧处理",
            TodoWidgetFeedback.frameOf(TodoWidgetFeedback.LAST_PHASE).alpha,
            TodoWidgetFeedback.frameOf(99).alpha,
        )
    }

    @Test
    fun frame_pacing_is_smooth_but_will_not_jank_the_launcher() {
        // ≤40ms 才算「密到看不出跳」（33ms ≈30fps）；上限之外还要防有人改小到 16ms 以下
        assertTrue(
            "帧距 ${TodoWidgetFeedback.STEP_MS}ms 太大，会一格一格跳",
            TodoWidgetFeedback.STEP_MS <= 40L,
        )
        assertTrue("帧距太密会把桌面拖卡", TodoWidgetFeedback.STEP_MS >= 16L)
        assertTrue("退出段至少要有 8 帧才够丝滑", TodoWidgetFeedback.EXIT_FRAMES >= 8)
    }

    @Test
    fun animation_is_long_enough_to_see_but_snappy() {
        val total = TodoWidgetFeedback.totalMs()
        assertTrue("动画太短就等于直接消失（${total}ms）", total >= 400L)
        assertTrue("动画太长用户会觉得卡（${total}ms）", total <= 1_000L)
        assertEquals(
            "帧数 = 进入 + 退出 - 1（重叠那一帧）",
            TodoWidgetFeedback.ENTER_FRAMES + TodoWidgetFeedback.EXIT_FRAMES - 1,
            TodoWidgetFeedback.LAST_PHASE,
        )
    }
}
