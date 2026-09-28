/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.sms

import com.parcelhub.model.RawNotification

/**
 * 短信入队器：短信链路里“接收 → 过滤 → 分发”的**纯内存**那一段（SOP §8 / §49）。
 *
 * 只做判断与构造，不碰数据库、不联网、不打印正文；
 * 真正的解析、合并、落库全部交给既有的 `IngestPipeline`（SOP §3：短信只是一个数据来源）。
 *
 * 顺序（SOP §9~§11 / §42）：
 * ```
 * 开关关闭? → 空正文? → 排除关键词? → 没有物流关键词? → 重复短信? → 入队
 * ```
 * 排除表在物流关键词**之前**判断，保证 `【XX银行】您的验证码为 583921`
 * 这种即使混进物流词也绝不入队（SOP §11）。
 */
class SmsIngestor(
    private val seen: SmsSeenStore,
    private val enqueue: (RawNotification) -> Boolean,
) {

    /**
     * @param enabled 来源管理里“短信”开关的真实状态（由调用方从数据库读出，
     *  Receiver 走 `goAsync()` 取，避免冷进程读到过期的内存镜像）
     * @return 脱敏的处理结果，只含计数与原因标签，**不含正文**
     */
    fun ingest(envelopes: List<SmsEnvelope>, enabled: Boolean = true): SmsIngestOutcome {
        if (!enabled) return SmsIngestOutcome(reason = "disabled")
        if (envelopes.isEmpty()) return SmsIngestOutcome(reason = "empty")

        var enqueued = 0
        var skipped = 0
        var lastReason: String? = null

        for (envelope in envelopes) {
            val body = envelope.body
            if (body.isBlank()) {
                skipped++; lastReason = "empty"; continue
            }
            if (SmsContract.excludedReason(body) != null) {
                skipped++; lastReason = "excluded"; continue
            }
            if (!SmsContract.isLogistics(body)) {
                skipped++; lastReason = "not_logistics"; continue
            }
            val hash = envelope.hash
            if (seen.isSeen(hash)) {
                skipped++; lastReason = "duplicate"; continue
            }

            val ok = enqueue(envelope.toRawNotification(hash))
            if (ok) {
                // 入队成功才记账：队列满被丢弃时下次仍有机会重新进来
                seen.mark(hash)
                enqueued++
            } else {
                skipped++; lastReason = "queue_full"
            }
        }

        return SmsIngestOutcome(enqueued = enqueued, skipped = skipped, reason = lastReason)
    }
}

/** 单批短信的处理结果（给诊断日志用，字段全部脱敏） */
data class SmsIngestOutcome(
    val enqueued: Int = 0,
    val skipped: Int = 0,
    /** 最后一条被跳过的原因：disabled / empty / excluded / not_logistics / duplicate / queue_full */
    val reason: String? = null,
)

/**
 * 短信 → 统一原始通知。
 *
 * - `notificationKey` = 短信哈希（SOP §42）：事件层 `dedupKey = 来源|key|类型` 天然去重；
 * - `title` = **发件人指纹**而不是发件人本身：解析层的 `fingerprint` 会把它落库，
 *   放手机号进去就等于把 PII 写进 `parcel_events`（SOP §38 不在必要字段里）；
 * - 正文只在内存里流转，落库时由 `ParserEngine` 产出脱敏摘要与提取字段。
 */
private fun SmsEnvelope.toRawNotification(hash: String): RawNotification = RawNotification(
    sourcePackage = SmsContract.SOURCE_PACKAGE,
    sourceAppName = null,
    notificationKey = hash,
    title = SmsContract.senderFingerprint(sender),
    text = body,
    bigText = null,
    subText = null,
    receivedAt = timestampMs,
    isOngoing = false,
)
