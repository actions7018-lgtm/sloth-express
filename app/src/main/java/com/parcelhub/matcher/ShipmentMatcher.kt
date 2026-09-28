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

import com.parcelhub.data.entity.ParcelEventEntity
import com.parcelhub.data.entity.ShipmentEntity
import com.parcelhub.model.ShipmentStatus
import kotlin.math.abs

/**
 * Shipment 匹配器（SOP §5）。
 *
 * 一级：carrier + trackingNumber（最高优先级）
 * 二级：平台订单号 / 事件关联 ID
 * 三级：来源 + 时间窗 + 地点 + 摘要指纹（仅无单号场景）
 *
 * 纯逻辑实现：调用方负责从数据库取候选，便于单测与规则调参。
 */
object ShipmentMatcher {

    /** 三级匹配时间窗：±6 小时 */
    const val TIME_WINDOW_MS: Long = 6 * 60 * 60 * 1000L

    /** 低于该置信度不创建正式 Shipment（SOP §6.5） */
    const val MIN_CREATE_CONFIDENCE: Double = 0.40

    /** 入库但标记待确认（SOP §6.5） */
    const val REVIEW_CONFIDENCE: Double = 0.70

    /** 运单号归一化：去空白/连字符/统一大写 */
    fun normalizeTracking(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val normalized = raw.trim()
            .uppercase()
            .replace(Regex("[\\s\\-　]"), "")
        return normalized.takeIf { it.isNotEmpty() }
    }

    fun canCreateShipment(confidence: Double): Boolean = confidence >= MIN_CREATE_CONFIDENCE

    fun needsReview(confidence: Double): Boolean = confidence < REVIEW_CONFIDENCE

    /** 一级匹配：运单号 */
    fun matchByTracking(
        candidate: String?,
        existing: ShipmentEntity?,
    ): ShipmentEntity? {
        val normalized = normalizeTracking(candidate) ?: return null
        val target = existing ?: return null
        return if (normalizeTracking(target.trackingNumber) == normalized) target else null
    }

    /** 二级匹配：平台订单号命中已有事件，返回其包裹 id */
    fun matchByOrderKey(candidates: List<ParcelEventEntity>): Long? =
        candidates.firstOrNull { it.shipmentId > 0L }?.shipmentId

    /**
     * 三级匹配：同来源 + 时间窗 + 地点或指纹一致（SOP §5 三级匹配，仅无单号场景兜底）。
     * 返回命中的 shipmentId；无可靠依据时返回 null（不得猜测，SOP §6.4）。
     *
     * 两侧都有运单号且不一致时直接跳过：单号不同即不同包裹，
     * 即使地点、时间窗、指纹都吻合也不得归并（防止同驿站多件互相串单）。
     */
    fun matchByContext(
        event: ParcelEventEntity,
        candidates: List<ParcelEventEntity>,
    ): Long? {
        val eventTracking = normalizeTracking(event.trackingNumber)
        var bestId: Long? = null
        var bestScore = 0
        for (candidate in candidates) {
            if (candidate.shipmentId <= 0L) continue
            if (abs(candidate.eventTime - event.eventTime) > TIME_WINDOW_MS) continue

            val candidateTracking = normalizeTracking(candidate.trackingNumber)
            if (eventTracking != null &&
                candidateTracking != null &&
                eventTracking != candidateTracking
            ) {
                continue
            }

            val locationMatch = !event.pickupLocation.isNullOrBlank() &&
                event.pickupLocation == candidate.pickupLocation
            val fingerprintMatch = !event.fingerprint.isNullOrBlank() &&
                event.fingerprint == candidate.fingerprint

            if (!locationMatch && !fingerprintMatch) continue

            val score = (if (fingerprintMatch) 2 else 0) + (if (locationMatch) 1 else 0)
            if (score > bestScore) {
                bestScore = score
                bestId = candidate.shipmentId
            }
        }
        return bestId
    }

    /**
     * 合并字段：新事件写入包裹时的取值规则。
     * 新信息优先，但不允许用空值覆盖已有值。
     */
    fun mergePickup(
        currentCode: String?,
        currentLocation: String?,
        currentLocker: String?,
        eventCode: String?,
        eventLocation: String?,
    ): Triple<String?, String?, String?> = Triple(
        eventCode?.takeIf { it.isNotBlank() } ?: currentCode,
        eventLocation?.takeIf { it.isNotBlank() } ?: currentLocation,
        currentLocker,
    )

    /** 承运商与状态展示用：事件状态 → 包裹状态（经状态机） */
    fun resolveStatus(current: ShipmentStatus, incoming: ShipmentStatus): ShipmentStatus =
        StateMachine.resolve(current, incoming)
}
