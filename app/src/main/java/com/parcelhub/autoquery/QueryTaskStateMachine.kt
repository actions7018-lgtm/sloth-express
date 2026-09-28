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

/**
 * 查询任务状态机（参考 SOP §9 任务状态 / §25 自动化状态机 / §27 防死循环）。
 *
 * 约束与平台状态机一致：只前进不倒退，终态不被改写；
 * 差别在于 [QueryTaskStatus.FAILED] 与 [QueryTaskStatus.CANCELLED] 允许从任意活跃态进入——
 * 失败必须能立刻停住，不能因为“状态倒退禁令”卡在半路。
 *
 * 纯函数、无 IO。
 */
object QueryTaskStateMachine {

    /** 正常主链顺序，数值越大越接近完成 */
    private val ORDER: Map<QueryTaskStatus, Int> = mapOf(
        QueryTaskStatus.PENDING to 0,
        QueryTaskStatus.LAUNCHING to 1,
        QueryTaskStatus.CAINIAO_OPENED to 2,
        QueryTaskStatus.WAITING_AUTOMATION to 3,
        QueryTaskStatus.INPUT_FOUND to 4,
        QueryTaskStatus.NUMBER_FILLED to 5,
        QueryTaskStatus.COMPLETED to 6,
    )

    /** 终态：完成 / 失败 / 取消，不被任何后续状态改写 */
    private val TERMINAL = setOf(
        QueryTaskStatus.COMPLETED,
        QueryTaskStatus.FAILED,
        QueryTaskStatus.CANCELLED,
    )

    /** 任何活跃态都能进入的“立即停止”状态（SOP §10.2 / §31） */
    private val IMMEDIATE = setOf(
        QueryTaskStatus.FAILED,
        QueryTaskStatus.CANCELLED,
    )

    fun isTerminal(status: QueryTaskStatus): Boolean = status in TERMINAL

    /** 是否仍是有效任务（SOP §9：同单号只允许一个有效任务） */
    fun isActive(status: QueryTaskStatus): Boolean = !isTerminal(status)

    fun rank(status: QueryTaskStatus): Int = ORDER[status] ?: -1

    /**
     * 计算收到 [incoming] 后任务应处的状态。
     *
     * 规则：
     *  1. 终态不被改写（防死循环、防重复触发，SOP §27）；
     *  2. [QueryTaskStatus.FAILED] / [QueryTaskStatus.CANCELLED] 可从任意活跃态进入；
     *  3. 主链只允许前进（含跨级前进，如 CAINIAO_OPENED 直接 INPUT_FOUND）；
     *  4. 相同状态原样返回，禁止倒退。
     */
    fun resolve(current: QueryTaskStatus, incoming: QueryTaskStatus): QueryTaskStatus {
        if (isTerminal(current)) return current
        if (incoming in IMMEDIATE) return incoming
        return if (rank(incoming) >= rank(current)) incoming else current
    }

    /** 是否发生状态变化 */
    fun changed(current: QueryTaskStatus, resolved: QueryTaskStatus): Boolean = current != resolved

    /** 是否为允许的转移（用于落库前校验） */
    fun canTransition(from: QueryTaskStatus, to: QueryTaskStatus): Boolean =
        resolve(from, to) == to
}
