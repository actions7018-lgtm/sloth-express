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
    /** 最近一次监听服务连接成功时间（检测 §4.2 / §4.3 断开时长判定） */
    val lastConnectedAt: Long = 0L,
    /** 最近一次监听服务断开时间；从未断开为 0 */
    val lastDisconnectedAt: Long = 0L,
    /** 连续监听/解析失败次数：解析成功清零（检测 §4.3/§4.4） */
    val consecutiveErrors: Int = 0,
    /** 最近一次收到短信（任意短信，检测 §5 证据） */
    val lastSmsEventAt: Long = 0L,
    /** 最近一次物流短信入库成功 */
    val lastSmsParsedAt: Long = 0L,
    /** 连续短信处理失败次数（异常，不含正常过滤；检测 §5.3/§5.4） */
    val consecutiveSmsFailures: Int = 0,
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
        val now = clock()
        _snapshot.value = _snapshot.value.copy(
            listenerBound = bound,
            lastConnectedAt = if (bound) now else _snapshot.value.lastConnectedAt,
            lastDisconnectedAt = if (bound) _snapshot.value.lastDisconnectedAt else now,
        )
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
            consecutiveErrors = 0,
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
        val current = _snapshot.value
        _snapshot.value = current.copy(
            lastErrorAt = clock(),
            consecutiveErrors = current.consecutiveErrors + 1,
        )
    }

    fun onDropped() {
        val current = _snapshot.value
        _snapshot.value = current.copy(totalDropped = current.totalDropped + 1)
    }

    /** 收到一条短信（任意内容）；[parsed] 为 true 表示其中的物流短信成功入库（连续失败随之清零） */
    fun onSmsEvent(parsed: Boolean) {
        val now = clock()
        _snapshot.value = _snapshot.value.copy(
            lastSmsEventAt = now,
            lastSmsParsedAt = if (parsed) now else _snapshot.value.lastSmsParsedAt,
            consecutiveSmsFailures = if (parsed) 0 else _snapshot.value.consecutiveSmsFailures,
        )
    }

    /** 短信处理抛异常（检测 §5.4 连续 3 次 → ERROR） */
    fun onSmsFailure() {
        val current = _snapshot.value
        _snapshot.value = current.copy(consecutiveSmsFailures = current.consecutiveSmsFailures + 1)
    }
}
