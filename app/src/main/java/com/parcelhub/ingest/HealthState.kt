/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.ingest

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 采集健康快照（SOP §8.5 / §19），仅保存时间戳与计数，不保存通知内容 */
data class HealthSnapshot(
    val listenerBound: Boolean = false,
    val notificationAccess: Boolean = false,
    val lastEventAt: Long = 0L,
    val lastParsedAt: Long = 0L,
    val lastErrorAt: Long = 0L,
    val totalEvents: Long = 0L,
    val totalParsed: Long = 0L,
    val totalSkipped: Long = 0L,
    val totalDropped: Long = 0L,
) {
    /** 是否长期收不到事件（用于“可能受到后台限制”提示，不主动骚扰用户） */
    fun isStalled(now: Long, thresholdMs: Long = 30 * 60_000L): Boolean =
        listenerBound && lastEventAt > 0L && now - lastEventAt > thresholdMs
}

/**
 * 健康状态持有者：单向数据流，监听回调与后台队列都只做一次赋值（无锁无 IO）。
 */
class HealthState(private val clock: () -> Long = System::currentTimeMillis) {

    private val _snapshot = MutableStateFlow(HealthSnapshot())
    val snapshot: StateFlow<HealthSnapshot> = _snapshot.asStateFlow()

    fun onListenerChanged(bound: Boolean) {
        _snapshot.value = _snapshot.value.copy(listenerBound = bound)
    }

    fun onNotificationAccess(enabled: Boolean) {
        _snapshot.value = _snapshot.value.copy(notificationAccess = enabled)
    }

    fun onEvent() {
        val now = clock()
        _snapshot.value = _snapshot.value.copy(
            lastEventAt = now,
            totalEvents = _snapshot.value.totalEvents + 1,
        )
    }

    fun onParsed() {
        val now = clock()
        val current = _snapshot.value
        _snapshot.value = current.copy(
            lastParsedAt = now,
            totalParsed = current.totalParsed + 1,
        )
    }

    fun onSkipped() {
        val current = _snapshot.value
        _snapshot.value = current.copy(
            lastParsedAt = clock(),
            totalSkipped = current.totalSkipped + 1,
        )
    }

    fun onError() {
        _snapshot.value = _snapshot.value.copy(lastErrorAt = clock())
    }

    fun onDropped() {
        val current = _snapshot.value
        _snapshot.value = current.copy(totalDropped = current.totalDropped + 1)
    }
}
