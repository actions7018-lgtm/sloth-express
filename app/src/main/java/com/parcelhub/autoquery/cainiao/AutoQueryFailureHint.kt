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

/**
 * 自动化失败原因 → 用户语言（参考 SOP §31 / T07 / T08）。
 *
 * 技术原因（`timeout` / `set_text_false` / `verify_mismatch` …）只进日志与数据库，
 * 发给用户的通知里只出现下面这些说法，不带技术名词。
 *
 * 纯逻辑、无 Android 依赖，可在 JVM 单测覆盖。
 */
object AutoQueryFailureHint {

    /**
     * 技术原因 → 用户可读的一句话。
     *
     * @param reason 会话记录的 `lastFailure`，可能为 null（未知失败）
     */
    fun hint(reason: String?): String = when {
        reason == null -> "自动填单号中断"
        reason.startsWith("timeout") -> "菜鸟页面打开超时"
        reason.startsWith("action_loop") -> "菜鸟页面没有反应"
        reason.startsWith("verify_mismatch") -> "填入后校验不一致"
        reason == "set_text_false" || reason == "retry_exhausted" -> "单号填入失败"
        else -> "自动填单号中断"
    }
}
