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

import com.parcelhub.util.AppLog

/**
 * 自动查询链路日志（参考 SOP §30 日志规范）。
 *
 * 全链路统一用方括号标签，logcat 里一眼能串起一次自动化：
 * ```text
 * [NOTIFICATION] 收到通知
 * [PARSE]        识别单号：SF123…789
 * [DECISION]     DELIVERED + STATION + NO_PICKUP_CODE
 * [QUERY_TASK]   created
 * [CAINIAO]      launch requested / app detected / search page detected /
 *                input found / set text requested / text verified / completed
 * ```
 *
 * 调用方必须传入已脱敏文本，禁止把通知原文、完整单号传进来（隐私口径）。
 */
object AutoQueryLog {
    fun notification(message: String) = AppLog.d("[NOTIFICATION] $message")
    fun parse(message: String) = AppLog.d("[PARSE] $message")
    fun decision(message: String) = AppLog.d("[DECISION] $message")
    fun task(message: String) = AppLog.i("[QUERY_TASK] $message")
    fun cainiao(message: String) = AppLog.i("[CAINIAO] $message")
    fun warn(message: String, throwable: Throwable? = null) = AppLog.w("[QUERY_TASK] $message", throwable)

    /** 单号脱敏：只保留头 3 + 尾 3，其余打点 */
    fun mask(trackingNumber: String?): String {
        val tn = trackingNumber?.trim().orEmpty()
        if (tn.isEmpty()) return "<empty>"
        if (tn.length <= 6) return tn
        return tn.take(3) + "…" + tn.takeLast(3)
    }
}
