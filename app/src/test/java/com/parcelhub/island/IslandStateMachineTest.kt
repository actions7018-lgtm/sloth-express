/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 灵动岛状态机单测（SOP §7 状态流程 / §18 动画时长）。
 *
 * 全部用注入的 `now` 驱动，不依赖真实时间与 Handler，所以边界（到点前一毫秒、
 * 到点那一毫秒）都能确定性覆盖。
 */
class IslandStateMachineTest {

    private val timings = IslandStateMachine.Timings.DEFAULT
    private val machine = IslandStateMachine(timings)

    private fun content(title: String = "📦 新快递已到达") = IslandContent(
        shipmentId = 7L,
        title = title,
        line1 = "取件码 99-9-7515",
        line2 = "菜鸟驿站",
        tone = IslandTone.ARRIVAL,
    )

    private fun compact() = IslandContent(
        shipmentId = 0L,
        title = "📦 3 个待取",
        tone = IslandTone.ARRIVAL,
    )

    // ---------- 时长口径（SOP §18） ----------

    @Test
    fun expand_delay_is_within_200_to_300ms() {
        assertTrue(
            "展开 ${timings.expandDelayMs}ms 应落在 SOP §18 的 200~300ms",
            timings.expandDelayMs in 200L..300L,
        )
        assertTrue(
            "收起 ${timings.compactHoldMs}ms 的收尾应在合理区间",
            timings.compactHoldMs in 200L..1_500L,
        )
    }

    @Test
    fun success_state_lasts_one_to_two_seconds() {
        // SOP §7.4 / §8.3：成功态约 1～2 秒后自动收起
        assertTrue(
            "成功态 ${timings.successHoldMs}ms 应落在 1000~2000ms",
            timings.successHoldMs in 1_000L..2_000L,
        )
    }

    // ---------- HIDDEN → COMPACT → EXPANDED ----------

    @Test
    fun idle_machine_stays_hidden() {
        assertFalse(machine.tick(10_000L))
        assertEquals(IslandState.HIDDEN, machine.state)
        assertNull(machine.content)
    }

    @Test
    fun event_from_hidden_goes_compact_then_expanded() {
        assertTrue(machine.onEvent(IslandEvent.Arrived(7L), content(), compact(), now = 0L))

        assertEquals(IslandState.COMPACT, machine.state)
        assertEquals("📦 3 个待取", machine.content?.title)
        assertEquals(timings.expandDelayMs, machine.advanceAt)

        // 展开前一毫秒还没动
        assertFalse(machine.tick(timings.expandDelayMs - 1))
        assertEquals(IslandState.COMPACT, machine.state)

        // 到点进入 EXPANDED，展示的是事件内容而不是胶囊内容
        assertTrue(machine.tick(timings.expandDelayMs))
        assertEquals(IslandState.EXPANDED, machine.state)
        assertEquals("📦 新快递已到达", machine.content?.title)
        assertEquals(timings.expandDelayMs + timings.expandedHoldMs, machine.advanceAt)
    }

    @Test
    fun expanded_collapses_to_compact_then_hidden() {
        machine.onEvent(IslandEvent.Arrived(7L), content(), compact(), now = 0L)
        machine.tick(timings.expandDelayMs)
        assertEquals(IslandState.EXPANDED, machine.state)

        // 展示结束 → 收起回胶囊（SOP §18 顺序）
        machine.tick(timings.expandDelayMs + timings.expandedHoldMs)
        assertEquals(IslandState.COMPACT, machine.state)
        assertEquals("📦 3 个待取", machine.content?.title)

        // 胶囊收尾 → 隐藏，内容清空
        machine.tick(timings.expandDelayMs + timings.expandedHoldMs + timings.compactHoldMs)
        assertEquals(IslandState.HIDDEN, machine.state)
        assertNull(machine.content)
        assertNull(machine.compactContent)
    }

    // ---------- SOP §7.3 SUCCESS ----------

    @Test
    fun picked_up_runs_through_success_then_hides() {
        machine.onEvent(IslandEvent.PickedUp(7L), content("✓ 取件完成"), compact(), now = 0L)
        assertEquals(IslandState.COMPACT, machine.state)

        machine.tick(timings.expandDelayMs)
        assertEquals(IslandState.SUCCESS, machine.state)
        assertEquals("✓ 取件完成", machine.content?.title)

        machine.tick(timings.expandDelayMs + timings.successHoldMs)
        assertEquals(IslandState.COMPACT, machine.state)

        machine.tick(timings.expandDelayMs + timings.successHoldMs + timings.compactHoldMs)
        assertEquals(IslandState.HIDDEN, machine.state)
    }

    // ---------- 展示中来新事件 ----------

    @Test
    fun new_event_while_showing_swaps_content_immediately() {
        machine.onEvent(IslandEvent.Arrived(7L), content(), compact(), now = 0L)
        machine.tick(timings.expandDelayMs)
        assertEquals(IslandState.EXPANDED, machine.state)

        val delivering = IslandContent(
            shipmentId = 7L,
            title = "🚚 圆通速递",
            line1 = "正在派送",
            tone = IslandTone.LOGISTICS,
        )
        assertTrue(machine.onEvent(IslandEvent.Delivering(7L), delivering, compact(), now = 1_000L))
        assertEquals(IslandState.EXPANDED, machine.state)
        assertEquals("🚚 圆通速递", machine.content?.title)
        // 重新计一次展示时长
        assertEquals(1_000L + timings.expandedHoldMs, machine.advanceAt)
    }

    // ---------- 关闭 ----------

    @Test
    fun hide_resets_immediately() {
        machine.onEvent(IslandEvent.Arrived(7L), content(), compact(), now = 0L)
        assertTrue(machine.hide())
        assertEquals(IslandState.HIDDEN, machine.state)
        assertEquals(0L, machine.advanceAt)
        assertFalse(machine.hide())
        assertFalse(machine.tick(Long.MAX_VALUE))
    }

    @Test
    fun custom_timings_drive_transitions() {
        // 用极短时长验证“到点即推进”，确认时间只由注入的 now 决定
        val fast = IslandStateMachine(
            IslandStateMachine.Timings(
                expandDelayMs = 1L,
                expandedHoldMs = 2L,
                successHoldMs = 3L,
                compactHoldMs = 4L,
            ),
        )
        fast.onEvent(IslandEvent.Arrived(7L), content(), compact(), now = 100L)
        assertFalse(fast.tick(100L))
        assertTrue(fast.tick(101L))
        assertEquals(IslandState.EXPANDED, fast.state)
        assertTrue(fast.tick(103L))
        assertEquals(IslandState.COMPACT, fast.state)
        assertTrue(fast.tick(107L))
        assertEquals(IslandState.HIDDEN, fast.state)
    }
}
