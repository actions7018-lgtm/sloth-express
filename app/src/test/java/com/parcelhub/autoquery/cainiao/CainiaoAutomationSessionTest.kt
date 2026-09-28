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

import com.parcelhub.autoquery.QueryTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无障碍自动化会话单测（参考 SOP §25 状态机 / §26 Task 绑定 / §27 有限重试与防死循环 / §31 异常状态）。
 */
class CainiaoAutomationSessionTest {

    private val startAt = 1_700_000_000_000L
    private val tracking = "SF1234567890123"

    private fun session(
        maxAttempts: Int = CainiaoAutomationSession.MAX_ATTEMPTS,
        timeoutMs: Long = CainiaoAutomationSession.PAGE_TIMEOUT_MS,
        onStatus: ((String?, QueryTaskStatus) -> Unit)? = null,
    ) = CainiaoAutomationSession(maxAttempts, timeoutMs, onStatus)

    /** 正常链路：开始 → 找到框 → 填入 → 校验通过 */
    @Test
    fun happy_path_reaches_number_filled() {
        val s = session()
        assertTrue(s.start(tracking, startAt))
        assertEquals(QueryTaskStatus.WAITING_AUTOMATION, s.status)
        assertTrue(s.isAutomating)

        assertTrue(s.onInputFound())
        assertEquals(QueryTaskStatus.INPUT_FOUND, s.status)

        assertTrue(s.onFillAttempt(startAt + 500))
        assertEquals(1, s.attempts)

        assertTrue(s.onVerified(tracking))
        assertEquals(QueryTaskStatus.NUMBER_FILLED, s.status)
        assertFalse(s.isAutomating)
    }

    /** SOP §18.4：回读与绑定单号不一致不算成功 */
    @Test
    fun verify_rejects_mismatched_text() {
        val s = session()
        s.start(tracking, startAt)
        s.onInputFound()
        s.onFillAttempt(startAt)

        assertFalse(s.onVerified(""))
        assertFalse(s.onVerified(null))
        assertFalse(s.onVerified("SF123456789012"))
        assertEquals(QueryTaskStatus.INPUT_FOUND, s.status)

        assertTrue(s.onVerified(tracking))
        assertEquals(QueryTaskStatus.NUMBER_FILLED, s.status)
    }

    /** 单号两侧空白可容忍（UI 可能带空格），内部内容必须一致 */
    @Test
    fun verify_trims_actual_value() {
        val s = session()
        s.start(tracking, startAt)
        s.onInputFound()
        assertTrue(s.onVerified("  $tracking  "))
        assertEquals(QueryTaskStatus.NUMBER_FILLED, s.status)
    }

    /** SOP §27：确定性失败只重试到上限，然后停住 */
    @Test
    fun retry_exhaustion_fails_session() {
        val s = session(maxAttempts = 2)
        s.start(tracking, startAt)
        s.onInputFound()

        assertTrue(s.onFillAttempt(startAt))
        assertTrue(s.onFillFailed("set_text_false"))   // 第 1 次失败，还可重试

        assertTrue(s.onFillAttempt(startAt + 100))
        assertFalse(s.onFillFailed("no_change"))       // 第 2 次失败，用尽 → FAILED

        assertEquals(QueryTaskStatus.FAILED, s.status)
        assertEquals(2, s.attempts)
        assertEquals("no_change", s.lastFailure)
        assertFalse(s.isAutomating)
    }

    /** 失败次数由 onFillAttempt 单独计，onFillFailed 不重复计数 */
    @Test
    fun attempts_are_counted_once_per_fill_attempt() {
        val s = session(maxAttempts = 3)
        s.start(tracking, startAt)
        s.onInputFound()
        s.onFillAttempt(startAt)
        s.onFillFailed("x")
        assertEquals(1, s.attempts)
        s.onFillAttempt(startAt)
        assertEquals(2, s.attempts)
    }

    /** 未开始 / 已结束的会话不接受填入 */
    @Test
    fun fill_attempt_only_allowed_while_automating() {
        val s = session()
        assertFalse(s.onFillAttempt(startAt))

        s.start(tracking, startAt)
        assertTrue(s.onFillAttempt(startAt))
    }

    /** SOP §27/§28：超时即失败，不保活、不轮询（上限 5 秒） */
    @Test
    fun timeout_fails_session() {
        assertEquals(5_000L, CainiaoAutomationSession.PAGE_TIMEOUT_MS)
        val s = session(timeoutMs = 5_000L)
        s.start(tracking, startAt)

        assertFalse(s.onTimeout(startAt + 1_000L))    // 还没超时
        assertEquals(QueryTaskStatus.WAITING_AUTOMATION, s.status)

        assertTrue(s.onTimeout(startAt + 5_001L))     // 超时 → FAILED
        assertEquals(QueryTaskStatus.FAILED, s.status)
        assertEquals("timeout", s.lastFailure)
    }

    /** 超时判定发生在填入尝试上：已超时则不再发起填入 */
    @Test
    fun fill_attempt_refused_after_timeout() {
        val s = session(timeoutMs = 100L)
        s.start(tracking, startAt)
        s.onInputFound()
        assertFalse(s.onFillAttempt(startAt + 500L))
        assertEquals(QueryTaskStatus.FAILED, s.status)
    }

    /** 终态不可回退（防死循环，SOP §27） */
    @Test
    fun terminal_state_not_overwritten() {
        val s = session()
        s.start(tracking, startAt)
        s.onInputFound()
        s.onFillAttempt(startAt)
        s.onFillAttempt(startAt)
        s.onFillFailed("give_up")
        assertEquals(QueryTaskStatus.FAILED, s.status)

        assertFalse(s.onInputFound())
        assertFalse(s.onVerified(tracking))
        assertEquals(QueryTaskStatus.FAILED, s.status)
    }

    /** 同一时刻只允许一个在飞的会话 */
    @Test
    fun second_start_rejected_while_automating() {
        val s = session()
        assertTrue(s.start(tracking, startAt))
        assertFalse(s.start("YT987654321", startAt + 10))
        assertEquals(tracking, s.trackingNumber)
    }

    /** 会话结束后可重新开始（SOP §31 服务恢复 / 新任务） */
    @Test
    fun reset_allows_new_session() {
        val s = session()
        s.start(tracking, startAt)
        s.finish(QueryTaskStatus.CANCELLED)
        assertEquals(QueryTaskStatus.CANCELLED, s.status)

        s.reset()
        assertEquals(QueryTaskStatus.PENDING, s.status)
        assertEquals("", s.trackingNumber)
        assertTrue(s.start("YT123", startAt + 100))
        assertEquals("YT123", s.trackingNumber)
    }

    /** 填完之后替换为新单号也算新会话 */
    @Test
    fun completed_session_can_be_replaced() {
        val s = session()
        s.start(tracking, startAt)
        s.onInputFound()
        s.onVerified(tracking)
        assertEquals(QueryTaskStatus.NUMBER_FILLED, s.status)

        assertTrue(s.start("YT111", startAt + 500))
        assertEquals("YT111", s.trackingNumber)
        assertEquals(QueryTaskStatus.WAITING_AUTOMATION, s.status)
    }

    /** 空单号不启动 */
    @Test
    fun blank_tracking_rejected() {
        val s = session()
        assertFalse(s.start(null, startAt))
        assertFalse(s.start("", startAt))
        assertFalse(s.start("   ", startAt))
        assertEquals(QueryTaskStatus.PENDING, s.status)
        assertFalse(s.isAutomating)
    }

    /** 状态回调携带单号，供任务侧落库（SOP §29/§30） */
    @Test
    fun status_callback_reports_tracking_and_status() {
        val events = mutableListOf<Pair<String?, QueryTaskStatus>>()
        val s = session(onStatus = { tn, st -> events += tn to st })
        s.start(tracking, startAt)
        s.onInputFound()
        s.onFillAttempt(startAt)
        s.onVerified(tracking)

        assertEquals(
            listOf(
                tracking to QueryTaskStatus.WAITING_AUTOMATION,
                tracking to QueryTaskStatus.INPUT_FOUND,
                tracking to QueryTaskStatus.NUMBER_FILLED,
            ),
            events,
        )
    }

    /** 服务重连时若尚未开始则不动作；已经开始则重挂（SOP §31） */
    @Test
    fun service_connected_reattaches_only_started_session() {
        val s = session()
        s.onServiceConnected(startAt)
        assertEquals(QueryTaskStatus.PENDING, s.status)

        s.start(tracking, startAt)
        assertEquals(QueryTaskStatus.WAITING_AUTOMATION, s.status)
        s.onServiceConnected(startAt + 10)
        assertEquals(QueryTaskStatus.WAITING_AUTOMATION, s.status)
    }

    /** SOP §27：同任务 + 同页面 + 同动作短时间重复 → 禁止再次执行 */
    @Test
    fun repeated_action_on_same_page_is_blocked() {
        val s = session()
        s.start(tracking, startAt)
        val page = "com.cainiao.wireless.homepage.view.activity.HomePageActivity"

        assertTrue(s.noteAction("click_search_entry", page, startAt))
        // 3 秒内同动作同页面重复 → 禁止
        assertFalse(s.noteAction("click_search_entry", page, startAt + 1_000L))
        assertEquals("action_loop:click_search_entry", s.lastFailure)
        // 会话本身还在跑（只是该动作被禁），等超时收口
        assertEquals(QueryTaskStatus.WAITING_AUTOMATION, s.status)

        // 间隔够久 → 允许
        assertTrue(s.noteAction("click_search_entry", page, startAt + 3_000L))
        // 换页面 → 允许
        assertTrue(
            s.noteAction(
                "click_search_entry",
                "com.cainiao.wireless.mvp.activities.QueryPackageProActivity",
                startAt + 3_100L,
            ),
        )
        // 换动作 → 允许
        assertTrue(s.noteAction("fill_input", page, startAt + 3_200L))
    }

    /** 未开始的会话不记录动作 */
    @Test
    fun note_action_rejected_when_idle() {
        val s = session()
        assertFalse(s.noteAction("click_search_entry", "page", startAt))
        s.start(tracking, startAt)
        s.finish(QueryTaskStatus.CANCELLED)
        assertFalse(s.noteAction("click_search_entry", "page", startAt + 10))
    }

    /** 新会话清空动作指纹（SOP §31 恢复后不沿用旧指纹） */
    @Test
    fun new_session_clears_action_fingerprint() {
        val s = session()
        s.start(tracking, startAt)
        assertTrue(s.noteAction("click_search_entry", "page", startAt))
        assertFalse(s.noteAction("click_search_entry", "page", startAt + 500L))

        s.reset()
        s.start("YT999", startAt + 1_000L)
        assertTrue(s.noteAction("click_search_entry", "page", startAt + 1_000L))
    }
}
