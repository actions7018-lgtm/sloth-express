/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.ocr

/**
 * OCR 截图兜底触发闸（SOP V2.0 §19/§20）：纯逻辑、无 IO、JVM 单测可覆盖。
 *
 * 触发条件（[OcrController] 在调用 [allow] 前逐项检查）：
 *  1. OCR 开关开（设置页「OCR 截图兜底」）；
 *  2. 当前页面是订单 / 物流页（含「订单」字段文本）；
 *  3. 页面文本里没有单号形态（无障碍已尽力、仍读不到）；
 *  4. 该来源有待补全单，或处于手动识别焦点窗口（用户明确要求读这一页）。
 *
 * 节奏（§20）：
 *  - 同一页面 30 秒内不重复截图；
 *  - 连续识别失败 → 指数退避 30s → 2min → 10min（封顶，换页 / 手动焦点重置）；
 *  - 成功取到单号 → 待补全清零 → 条件 4 不再满足，自然停（“同一订单成功后永久停止”）。
 */
class OcrGate(
    private val backoffMs: LongArray = DEFAULT_BACKOFF_MS,
) {

    private var lastAttemptAt: Long = 0L
    private var failures: Int = 0

    /** 当前冷却时长：失败 0 次用基础 30s，之后按退避表逐级拉长（封顶末位） */
    val cooldownMs: Long
        get() = backoffMs.getOrElse(failures.coerceAtMost(backoffMs.lastIndex)) { backoffMs.last() }

    fun allow(now: Long): Boolean = now - lastAttemptAt >= cooldownMs

    /** 截图发起即记账（无论成败），拉下一次的冷却起点 */
    fun markAttempt(now: Long) {
        lastAttemptAt = now
    }

    /** OCR 文本为空 / 截图失败 / 仍没取到单号 → 退避升级 */
    fun markFailure() {
        failures++
    }

    /** 取到单号：失败计数清零（后续由待补全清空自然停） */
    fun markSuccess() {
        failures = 0
    }

    /** 换页 / 新的手动焦点：重新给足立即尝试的额度 */
    fun resetWindow() {
        lastAttemptAt = 0L
        failures = 0
    }

    val failureCount: Int get() = failures

    companion object {
        /** [0]=基础页面冷却（成功后同页再触发的最小间隔），之后为连续失败退避 */
        val DEFAULT_BACKOFF_MS = longArrayOf(30_000L, 120_000L, 600_000L)
    }
}
