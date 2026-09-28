/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.parser

import com.parcelhub.model.EventType
import com.parcelhub.model.ParcelEvent
import com.parcelhub.model.ParseResult
import com.parcelhub.model.RawNotification
import com.parcelhub.model.ShipmentStatus
import com.parcelhub.model.SourceType
import com.parcelhub.source.SourceRegistry
import com.parcelhub.util.PrivacyUtil

/**
 * 解析引擎（SOP §6）：双层架构的汇合点。
 *
 * 第一层：来源解析器（谁发的通知）→ 订单号 / 指纹 / 承运商提示；
 * 第二层：通用字段提取器 → 运单号 / 状态 / 取件码 / 地点。
 *
 * 全部正则在 [CompiledRules] 初始化时预编译，事件处理阶段不做 Pattern.compile。
 * 所有自动识别结果都带 confidence（SOP §6.5）。
 */
class ParserEngine(private val rules: CompiledRules) {

    private val pickupExtractor = PickupExtractor(rules)
    private val trackingExtractor = TrackingExtractor(rules)
    private val statusExtractor = StatusExtractor(rules)
    private val addressExtractor = AddressExtractor(rules)

    fun parse(raw: RawNotification): ParseResult {
        val body = raw.body
        if (body.isBlank()) return ParseResult(null, 0.0, "通知正文为空")

        val sourceRule = rules.sourceFor(raw.sourcePackage)
        val sourceParser = SourceRegistry.parserFor(raw.sourcePackage)

        // 未知来源必须通过通用强门禁，避免把无关通知带进解析（SOP §7.1 第 4 步）
        if (sourceRule == null && !passesGenericGate(body)) {
            return ParseResult(null, 0.0, "非快递相关通知")
        }

        // ---- 第二层：通用字段提取 ----
        val tracking = trackingExtractor.extract(body, sourceParser.carrierHint(body))
        val pickup = pickupExtractor.extract(body, tracking.trackingNumber)
        val status = statusExtractor.extract(body)
        val locationFull = addressExtractor.extractLocationRaw(body)
        val location = locationFull?.let { addressExtractor.shortStation(it) }
        // 放置位置：优先“完整投放点”（家门口 / 行政区+站点），其次是显式收货地址，
        // 最后用取件地点原文兜底（原文比短站点多出行政区信息时不丢）
        val destination = addressExtractor.extractPlace(body)
            ?: addressExtractor.extractDestination(body)
            ?: locationFull?.takeIf { it != location }

        val hasStatus = status.eventType != EventType.UNKNOWN
        val hasPickupCode = !pickup.code.isNullOrBlank()
        val hasLocker = !pickup.lockerNumber.isNullOrBlank()
        val hasTracking = tracking.trackingNumber != null

        if (!hasStatus && !hasPickupCode && !hasLocker && !hasTracking) {
            return ParseResult(null, 0.30, "没有识别到快递信号")
        }

        // ---- 事件类型 / 状态 ----
        val eventType = when {
            hasStatus -> status.eventType
            hasPickupCode -> EventType.PICKUP_CODE_AVAILABLE
            else -> EventType.UNKNOWN
        }
        val shipmentStatus = when {
            status.status != ShipmentStatus.UNKNOWN -> status.status
            hasPickupCode || hasLocker -> ShipmentStatus.PICKUP_READY
            else -> ShipmentStatus.UNKNOWN
        }

        // ---- 置信度（SOP §6.5） ----
        var confidence = 0.0
        if (hasStatus) confidence = maxOf(confidence, 0.70)
        if (hasPickupCode) confidence = maxOf(confidence, pickup.confidence)
        if (hasLocker) confidence = maxOf(confidence, 0.55)
        if (hasTracking) confidence = maxOf(confidence, 0.60)
        if (sourceRule != null) confidence += 0.10
        if (hasStatus && hasTracking) confidence += 0.05
        confidence = confidence.coerceAtMost(0.99)

        val reason = when {
            confidence >= 0.70 -> null
            hasPickupCode -> "取件码来自弱上下文，建议确认"
            hasStatus -> "来源不在白名单，标记待确认"
            else -> "信号较弱，标记待确认"
        }

        val event = ParcelEvent(
            sourceType = SourceType.from(sourceRule?.sourceType ?: sourceParser.sourceType.name),
            sourcePackage = raw.sourcePackage,
            notificationKey = raw.notificationKey,
            eventType = eventType,
            status = shipmentStatus,
            trackingNumber = tracking.trackingNumber,
            carrier = tracking.carrier,
            pickupCode = pickup.code,
            pickupLocation = location,
            destination = destination,
            orderKey = sourceParser.orderKey(body),
            fingerprint = sourceParser.fingerprint(raw.title, body),
            rawSummary = PrivacyUtil.summarize(body),
            confidence = confidence,
            eventTime = raw.receivedAt,
            createdAt = System.currentTimeMillis(),
        )

        return ParseResult(event = event, confidence = confidence, reason = reason)
    }

    /** 未知来源的通用门禁：强关键词直接放行，弱关键词需累计 2 个以上 */
    private fun passesGenericGate(body: String): Boolean {
        for (strong in STRONG_GATE) {
            if (body.contains(strong)) return true
        }
        var hits = 0
        for (keyword in rules.gateKeywords) {
            if (body.contains(keyword)) {
                hits++
                if (hits >= 2) return true
            }
        }
        return false
    }

    /** 监听回调用的极轻量门禁（同 [passesGenericGate]，但只看关键词命中数） */
    fun cheapGate(packageName: String, body: String): Boolean {
        if (rules.sourceFor(packageName) != null) {
            // 白名单来源：关键词命中一次即可，避免漏掉新文案
            if (rules.gateKeywords.any { body.contains(it) }) return true
        }
        return passesGenericGate(body)
    }

    private companion object {
        val STRONG_GATE = listOf(
            "取件码", "取货码", "运单号", "快递单号", "已到驿站", "已入柜", "请取件", "取件通知",
        )
    }
}
