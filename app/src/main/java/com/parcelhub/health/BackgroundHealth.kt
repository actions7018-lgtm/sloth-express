/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.health

/**
 * 后台运行证据（检测 SOP §7.1 检查项目 / §十三 HealthEvidence）。
 *
 * 只记时间戳与计数，进程内存态：进程被杀后证据归零，
 * 检测端据此走 §7.6 的 UNKNOWN（不许把“没有证据”当 ERROR）。
 * 写入方是 ServiceLocator 的待补全补偿循环；读取方是 [HealthCollector]。
 */
object BackgroundHealth {

    @Volatile
    var lastAttemptAt: Long = 0L
        private set

    @Volatile
    var lastSuccessAt: Long = 0L
        private set

    @Volatile
    var consecutiveFailures: Int = 0
        private set

    /** 一次补偿执行成功（含“无任务可做”的空转对账） */
    fun recordSuccess(now: Long) {
        lastAttemptAt = now
        lastSuccessAt = now
        consecutiveFailures = 0
    }

    /** 一次补偿执行失败（数据库异常等，§7.4 连续 3 次 → ERROR） */
    fun recordFailure(now: Long) {
        lastAttemptAt = now
        consecutiveFailures++
    }
}
