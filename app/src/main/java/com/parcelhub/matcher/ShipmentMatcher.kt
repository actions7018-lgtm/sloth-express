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
import com.parcelhub.model.ParcelEvent
import com.parcelhub.model.ShipmentStatus
import com.parcelhub.pending.PendingSourceType
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
     * 平台订单号都有且不一致时同样跳过：购物 App 多单混排页面的指纹
     * （页面首行公共框架）高度雷同，靠订单号才能区分不同订单。
     */
    fun matchByContext(
        event: ParcelEventEntity,
        candidates: List<ParcelEventEntity>,
    ): Long? {
        val eventTracking = normalizeTracking(event.trackingNumber)
        val eventOrderKey = event.orderKey?.takeIf { it.isNotBlank() }
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

            val candidateOrderKey = candidate.orderKey?.takeIf { it.isNotBlank() }
            if (eventOrderKey != null &&
                candidateOrderKey != null &&
                eventOrderKey != candidateOrderKey
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
     * 行级单号守卫（SOP §6.4）：二级 / 三级命中已有行之后复核。
     * 行内已有单号且与新事件单号冲突 → 这行不是同一个包裹，宁可另起一行。
     * （防雪球：多单混排页面先把事件引进错误的行，之后订单号链 /
     *  指纹兜底又让后续事件沿错误的行继续滚下去——“3 单只识别出 1 单”。）
     * 任一侧没有单号 → 放行：先到先建的无号行仍可被后到的单号补全。
     */
    fun trackingRowUsable(eventTracking: String?, rowTracking: String?): Boolean {
        val incoming = normalizeTracking(eventTracking) ?: return true
        val existing = normalizeTracking(rowTracking) ?: return true
        return incoming == existing
    }

    /**
     * 「读页面」通道（ACCESSIBILITY 节点文本 / SCREEN_OCR 截图文本）：
     * 身份闸与身份键去重只对这类通道生效——它们都是同一页面的重复读取，
     * 通知 / 短信仍走原通知键去重。
     */
    fun isPageChannel(channel: String?): Boolean =
        channel == PendingSourceType.ACCESSIBILITY.name ||
            channel == PendingSourceType.SCREEN_OCR.name

    /**
     * 无障碍 / OCR「无身份文本」识别闸（SOP §6.4 + V2.0 §19）：
     * 订单列表页这类多单混排文本既无单号也无订单号，只凭状态文字
     * 既建不出正确的包裹（会造“认出来了但没单号”的垃圾行），
     * 又会作为单号为空的桥接候选把后续事件并进同一行。
     * 详情页（有订单号或有单号）与短信 / 通知的「已发货无单号」待补全流程不受影响。
     */
    fun isIdentitylessA11yEvent(channel: String?, tracking: String?, orderKey: String?): Boolean =
        isPageChannel(channel) &&
            normalizeTracking(tracking) == null &&
            orderKey.isNullOrBlank()

    /**
     * 去重命中时的字段补写：同键二次读取（无障碍进页即读，页面渐进加载时
     * 先出状态行、后出单号 / 收货地址）——事件行不重复追加（保持去重），
     * 只把行里还空着的关键字段补上；已有值一律不覆盖，状态不在此路径变更
     * （状态推进仍走正常事件，提醒不丢）。返回 null = 没有可补的，无需写库。
     */
    fun refillOnDedupHit(
        row: ShipmentEntity,
        event: ParcelEvent,
        tracking: String?,
        now: Long,
    ): ShipmentEntity? {
        var changed = false
        fun <T> keep(current: T?, incoming: T?): T? {
            if (current != null) return current
            if (incoming == null) return null
            changed = true
            return incoming
        }

        val trackingNumber = keep(row.trackingNumber, tracking)
        val carrier = keep(row.carrier, event.carrier)
        val address = keep(row.address, event.destination)
        val pickupCode = keep(row.pickupCode, event.pickupCode)
        val pickupLocation = keep(row.pickupLocation, event.pickupLocation)
        if (!changed) return null
        return row.copy(
            trackingNumber = trackingNumber,
            carrier = carrier,
            address = address,
            pickupCode = pickupCode,
            pickupLocation = pickupLocation,
            lastUpdatedAt = now,
            updatedAt = now,
        )
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
