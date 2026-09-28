/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.autoquery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 查询任务状态机单测（参考 SOP §9 任务状态 / §25 自动化状态机 / §27 防死循环 / §31 异常状态）。
 */
class QueryTaskStateMachineTest {

    private fun resolve(current: QueryTaskStatus, incoming: QueryTaskStatus) =
        QueryTaskStateMachine.resolve(current, incoming)

    /** 主链按 SOP §9 顺序推进 */
    @Test
    fun forward_along_chain() {
        assertEquals(QueryTaskStatus.LAUNCHING, resolve(QueryTaskStatus.PENDING, QueryTaskStatus.LAUNCHING))
        assertEquals(
            QueryTaskStatus.CAINIAO_OPENED,
            resolve(QueryTaskStatus.LAUNCHING, QueryTaskStatus.CAINIAO_OPENED),
        )
        assertEquals(
            QueryTaskStatus.WAITING_AUTOMATION,
            resolve(QueryTaskStatus.CAINIAO_OPENED, QueryTaskStatus.WAITING_AUTOMATION),
        )
        assertEquals(
            QueryTaskStatus.INPUT_FOUND,
            resolve(QueryTaskStatus.WAITING_AUTOMATION, QueryTaskStatus.INPUT_FOUND),
        )
        assertEquals(
            QueryTaskStatus.NUMBER_FILLED,
            resolve(QueryTaskStatus.INPUT_FOUND, QueryTaskStatus.NUMBER_FILLED),
        )
        assertEquals(
            QueryTaskStatus.COMPLETED,
            resolve(QueryTaskStatus.NUMBER_FILLED, QueryTaskStatus.COMPLETED),
        )
    }

    /** 跨级前进允许（例如页面事件来得快，CAINIAO_OPENED 直接 INPUT_FOUND） */
    @Test
    fun forward_jump_allowed() {
        assertEquals(
            QueryTaskStatus.INPUT_FOUND,
            resolve(QueryTaskStatus.CAINIAO_OPENED, QueryTaskStatus.INPUT_FOUND),
        )
        assertEquals(
            QueryTaskStatus.COMPLETED,
            resolve(QueryTaskStatus.CAINIAO_OPENED, QueryTaskStatus.COMPLETED),
        )
    }

    /** 主链禁止倒退（防重复触发导致状态回跳，SOP §27） */
    @Test
    fun never_move_backward() {
        val cases = listOf(
            Triple(QueryTaskStatus.LAUNCHING, QueryTaskStatus.PENDING, QueryTaskStatus.LAUNCHING),
            Triple(QueryTaskStatus.INPUT_FOUND, QueryTaskStatus.CAINIAO_OPENED, QueryTaskStatus.INPUT_FOUND),
            Triple(QueryTaskStatus.NUMBER_FILLED, QueryTaskStatus.WAITING_AUTOMATION, QueryTaskStatus.NUMBER_FILLED),
            Triple(QueryTaskStatus.COMPLETED, QueryTaskStatus.NUMBER_FILLED, QueryTaskStatus.COMPLETED),
        )
        for ((current, incoming, expected) in cases) {
            assertEquals("$current <- $incoming", expected, resolve(current, incoming))
            assertFalse(QueryTaskStateMachine.changed(current, resolve(current, incoming)))
            assertFalse(QueryTaskStateMachine.canTransition(current, incoming))
        }
    }

    /** 失败 / 取消可从任意活跃态进入，且立刻停住（SOP §10.2 / §31） */
    @Test
    fun fail_or_cancel_from_any_active_state() {
        val active = listOf(
            QueryTaskStatus.PENDING,
            QueryTaskStatus.LAUNCHING,
            QueryTaskStatus.CAINIAO_OPENED,
            QueryTaskStatus.WAITING_AUTOMATION,
            QueryTaskStatus.INPUT_FOUND,
            QueryTaskStatus.NUMBER_FILLED,
        )
        for (state in active) {
            assertEquals(QueryTaskStatus.FAILED, resolve(state, QueryTaskStatus.FAILED))
            assertEquals(QueryTaskStatus.CANCELLED, resolve(state, QueryTaskStatus.CANCELLED))
            assertTrue(QueryTaskStateMachine.isActive(state))
        }
    }

    /** 终态不被改写：完成 / 失败 / 取消都不会被后续事件复活（防死循环） */
    @Test
    fun terminal_never_overwritten() {
        val terminal = listOf(
            QueryTaskStatus.COMPLETED,
            QueryTaskStatus.FAILED,
            QueryTaskStatus.CANCELLED,
        )
        val all = terminal + listOf(
            QueryTaskStatus.PENDING,
            QueryTaskStatus.LAUNCHING,
            QueryTaskStatus.CAINIAO_OPENED,
            QueryTaskStatus.WAITING_AUTOMATION,
            QueryTaskStatus.INPUT_FOUND,
            QueryTaskStatus.NUMBER_FILLED,
        )
        for (t in terminal) {
            for (incoming in all) {
                assertEquals("$t <- $incoming", t, resolve(t, incoming))
            }
            assertFalse(QueryTaskStateMachine.isActive(t))
            assertTrue(QueryTaskStateMachine.isTerminal(t))
        }
    }

    /** 有效任务判定：只有终态才算结束（SOP §9 同单号只允许一个有效任务） */
    @Test
    fun active_until_terminal() {
        assertTrue(QueryTaskStateMachine.isActive(QueryTaskStatus.PENDING))
        assertTrue(QueryTaskStateMachine.isActive(QueryTaskStatus.NUMBER_FILLED))
        assertFalse(QueryTaskStateMachine.isActive(QueryTaskStatus.COMPLETED))
        assertFalse(QueryTaskStateMachine.isActive(QueryTaskStatus.FAILED))
        assertFalse(QueryTaskStateMachine.isActive(QueryTaskStatus.CANCELLED))
    }

    /** 落库前校验用的 canTransition */
    @Test
    fun can_transition_check() {
        assertTrue(QueryTaskStateMachine.canTransition(QueryTaskStatus.PENDING, QueryTaskStatus.LAUNCHING))
        assertTrue(QueryTaskStateMachine.canTransition(QueryTaskStatus.INPUT_FOUND, QueryTaskStatus.FAILED))
        assertFalse(QueryTaskStateMachine.canTransition(QueryTaskStatus.COMPLETED, QueryTaskStatus.LAUNCHING))
        assertFalse(QueryTaskStateMachine.canTransition(QueryTaskStatus.FAILED, QueryTaskStatus.PENDING))
    }
}
