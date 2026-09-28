/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.matcher

import com.parcelhub.model.EventType
import com.parcelhub.model.ShipmentStatus

/**
 * 去重引擎（SOP §10.3 / §12.2）。
 *
 * 两层去重：
 *  1. 事件层：来源 + 通知 key + 事件类型 → 防止通知重复 post 产生重复事件；
 *  2. 提醒层：同 Shipment 相同状态 + 相同取件码 + 相同地点在窗口内只提醒一次（§12.1）。
 */
object DedupEngine {

    /** 事件去重键（唯一索引 dedup_key） */
    fun eventDedupKey(sourcePackage: String, notificationKey: String, eventType: EventType): String =
        "$sourcePackage|$notificationKey|${eventType.name}"

    /** 同包裹语义键：承运商 + 运单号 + 事件类型 + 时间窗（防止通知更新造成的重复事件） */
    fun semanticKey(
        carrier: String?,
        trackingNumber: String?,
        eventType: EventType,
        eventTime: Long,
        windowMs: Long = 5 * 60_000L,
    ): String {
        val bucket = if (eventTime <= 0L) 0L else eventTime / windowMs
        return "${carrier.orEmpty().uppercase()}|${trackingNumber.orEmpty()}|${eventType.name}|$bucket"
    }

    /**
     * 提醒去重：同一 Shipment 在 [windowMs] 内出现“相同状态 + 相同取件码 + 相同地点”，
     * 只允许通知一次。
     */
    fun shouldRemind(
        previous: ReminderSignature?,
        current: ReminderSignature,
        now: Long,
        windowMs: Long = 30 * 60_000L,
    ): Boolean {
        if (previous == null) return true
        if (previous != current) return true
        return now - previous.firstSeenAt >= windowMs
    }

    /**
     * 提醒指纹：状态 + 取件码 + 地点。
     * [firstSeenAt] 记录该指纹最近一次提醒时间。
     */
    data class ReminderSignature(
        val status: ShipmentStatus,
        val pickupCode: String?,
        val pickupLocation: String?,
        val firstSeenAt: Long,
    )
}
