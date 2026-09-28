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
import com.parcelhub.autoquery.QueryTaskStateMachine

/**
 * 无障碍自动填单号的会话状态（参考 SOP §25 自动化状态机 / §27 有限重试 / §30 Task 绑定）。
 *
 * ```text
 * PENDING → WAITING_AUTOMATION → INPUT_FOUND → NUMBER_FILLED
 *     任意活跃态 → FAILED（重试用尽 / 超时 / 未找到输入框）
 * ```
 *
 * 约束：
 *  - 一个会话只绑定一个单号（[trackingNumber]），中途不换号，防并发填错（SOP §26）；
 *  - 重试只针对确定性失败（找不到输入框、SET_TEXT 未生效），次数上限 [maxAttempts]；
 *  - 超时（[timeoutMs]，SOP §28 最多 5 秒）即失败，不保活、不轮询、不无限重试（SOP §27 / §39.4）；
 *  - 同任务 + 同页面 + 同动作在短时间内重复 → 禁止再次执行（SOP §27，见 [noteAction]）；
 *  - 状态流转全部走 [QueryTaskStateMachine]，终态不被改写。
 *
 * 纯逻辑、无 Android 依赖，可在 JVM 单测覆盖。
 */
class CainiaoAutomationSession(
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val timeoutMs: Long = PAGE_TIMEOUT_MS,
    private val onStatus: ((trackingNumber: String?, status: QueryTaskStatus) -> Unit)? = null,
) {

    private val lock = Any()

    /** 当前绑定的单号；空串表示未绑定 */
    var trackingNumber: String = ""
        private set

    var status: QueryTaskStatus = QueryTaskStatus.PENDING
        private set

    /** 已发起的填入次数（含失败的） */
    var attempts: Int = 0
        private set

    var lastFailure: String? = null
        private set

    private var startedAt: Long = 0L

    /** 最近一次动作指纹（SOP §27）：动作名 + 页面指纹 + 时间 */
    private var lastAction: String? = null
    private var lastActionPage: String? = null
    private var lastActionAt: Long = 0L

    /** 是否正在自动化（需要消费无障碍事件） */
    val isAutomating: Boolean
        get() = synchronized(lock) {
            status == QueryTaskStatus.WAITING_AUTOMATION ||
                status == QueryTaskStatus.INPUT_FOUND
        }

    /** 是否仍有活动会话（含尚未填完） */
    val isActive: Boolean
        get() = synchronized(lock) { QueryTaskStateMachine.isActive(status) }

    /**
     * 开始一次自动化。
     *
     * @return false 表示单号无效、或已有活动会话（同一时刻只允许一个有效任务，SOP §10.1）
     */
    fun start(tracking: String?, now: Long): Boolean = synchronized(lock) {
        val tn = tracking?.trim().orEmpty()
        if (tn.isEmpty()) return false
        // 同一时刻只允许一个在飞的会话；已结束（填完/失败/取消）的可被新会话替换
        if (isAutomatingInternal()) return false
        trackingNumber = tn
        attempts = 0
        lastFailure = null
        startedAt = now
        lastAction = null
        lastActionPage = null
        lastActionAt = 0L
        // 新会话是状态重置，不走 resolve()：已结束的旧会话要能被新单号替换
        val previous = status
        status = QueryTaskStatus.WAITING_AUTOMATION
        if (previous != status) onStatus?.invoke(trackingNumber, status)
        true
    }

    /** 无障碍服务连上后重挂（服务重连恢复，SOP §31） */
    fun onServiceConnected(now: Long) = synchronized(lock) {
        if (status == QueryTaskStatus.PENDING && trackingNumber.isNotEmpty()) {
            startedAt = now
            advance(QueryTaskStatus.WAITING_AUTOMATION)
        }
    }

    /** 找到输入框（SOP §17 输入框定位成功） */
    fun onInputFound(): Boolean = synchronized(lock) {
        if (!isAutomatingInternal()) return false
        advance(QueryTaskStatus.INPUT_FOUND)
        true
    }

    /**
     * 发起一次填入。
     *
     * @return false 表示当前状态不应填入，或重试次数已用尽（会话转 [QueryTaskStatus.FAILED]）
     */
    fun onFillAttempt(now: Long): Boolean = synchronized(lock) {
        if (!isAutomatingInternal()) return false
        if (timeoutExceeded(now)) {
            // 超时即失败（SOP §27）：不保活、不无限等待
            lastFailure = "timeout"
            advance(QueryTaskStatus.FAILED)
            return false
        }
        attempts++
        if (attempts > maxAttempts) {
            lastFailure = "retry_exhausted"
            advance(QueryTaskStatus.FAILED)
            return false
        }
        true
    }

    /**
     * 校验填入结果（SOP §18.4 验证填充结果）。
     *
     * 只有回读文本与绑定单号完全一致才算成功；否则由调用方决定是否重试。
     */
    fun onVerified(actual: String?): Boolean = synchronized(lock) {
        if (!isAutomatingInternal()) return false
        val expected = trackingNumber
        if (expected.isEmpty()) return false
        val value = actual?.trim().orEmpty()
        if (value != expected) return false
        advance(QueryTaskStatus.NUMBER_FILLED)
        true
    }

    /**
     * 填入失败（SET_TEXT 返回 false 或回读不符）。
     *
     * 次数已由 [onFillAttempt] 计入，这里只记录原因并在用尽重试时终止会话。
     * @return false 表示重试已用尽，会话已转 [QueryTaskStatus.FAILED]
     */
    fun onFillFailed(reason: String): Boolean = synchronized(lock) {
        if (!isAutomatingInternal()) return false
        lastFailure = reason
        if (attempts >= maxAttempts) {
            advance(QueryTaskStatus.FAILED)
            return false
        }
        true
    }

    /** 超时未找到目标页 / 未填入 → 失败（不保活，SOP §27） */
    fun onTimeout(now: Long): Boolean = synchronized(lock) {
        if (!isAutomatingInternal()) return false
        if (!timeoutExceeded(now)) return false
        lastFailure = "timeout"
        advance(QueryTaskStatus.FAILED)
        true
    }

    /** 外部收尾（完成 / 取消 / 失败） */
    fun finish(target: QueryTaskStatus): Boolean = synchronized(lock) {
        val resolved = QueryTaskStateMachine.resolve(status, target)
        val changed = resolved != status
        status = resolved
        if (changed) onStatus?.invoke(trackingNumber, resolved)
        changed
    }

    /**
     * 记录一次页面动作并判定是否允许执行（SOP §27 防死循环）。
     *
     * 同一会话内「同动作 + 同页面指纹」在 [MIN_ACTION_INTERVAL_MS] 内重复出现
     * （例如反复点同一个搜索入口但页面一直没变），说明自动化卡住了，
     * 返回 false，调用方必须停止该动作、等待超时收口，不得换个方式硬点。
     *
     * @param action 动作名，如 `"click_search_entry"`
     * @param page 页面指纹，如前台 Activity 名；拿不到时传空串（按“同页”处理，更保守）
     * @return true 允许执行并已记录；false 禁止再次执行
     */
    fun noteAction(action: String, page: String, now: Long): Boolean = synchronized(lock) {
        if (!isAutomatingInternal()) return false
        if (action == lastAction && page == lastActionPage && now - lastActionAt < MIN_ACTION_INTERVAL_MS) {
            lastFailure = "action_loop:$action"
            return false
        }
        lastAction = action
        lastActionPage = page
        lastActionAt = now
        true
    }

    /** 结束后清空绑定，允许下一次会话 */
    fun reset() = synchronized(lock) {
        trackingNumber = ""
        attempts = 0
        lastFailure = null
        startedAt = 0L
        status = QueryTaskStatus.PENDING
    }

    private fun isAutomatingInternal(): Boolean =
        status == QueryTaskStatus.WAITING_AUTOMATION ||
            status == QueryTaskStatus.INPUT_FOUND

    private fun timeoutExceeded(now: Long): Boolean =
        startedAt > 0L && now - startedAt > timeoutMs

    private fun advance(target: QueryTaskStatus) {
        val resolved = QueryTaskStateMachine.resolve(status, target)
        if (resolved == status) return
        status = resolved
        onStatus?.invoke(trackingNumber, resolved)
    }

    companion object {
        /** SOP §18.4：只针对确定性失败重试，最多 2 次 */
        const val MAX_ATTEMPTS: Int = 2

        /**
         * 从开始到填入的总时长上限（SOP §28：输入框找不到时等待下一次事件，**最多 5 秒**）。
         * 超过即失败，不做无限等待（SOP §27）。
         */
        const val PAGE_TIMEOUT_MS: Long = 5_000L

        /**
         * SOP §27：同任务 + 同页面 + 同动作的最小间隔。
         * 短于此间隔的重复动作视为死循环前兆，直接禁止。
         */
        const val MIN_ACTION_INTERVAL_MS: Long = 3_000L
    }
}
