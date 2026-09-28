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

/**
 * 一条（或合并后的多段）短信（SOP §41 建议的形状）。
 *
 * 生命周期极短：接收 → 过滤 → 入队 → 解析落库后即被丢弃，
 * 不做长期缓存，也不写进任何持久化结构——**只有去重哈希会被记账**（SOP §38/§42）。
 *
 * @property sender 发件人（服务号 / 手机号），只在内存里参与过滤与哈希，不落库
 * @property body   短信正文，解析层读完即弃；落库的只有脱敏摘要与提取出的字段
 * @property subscriptionId 双卡场景的卡槽（SOP §41）；本机 ROM 未提供稳定取值时为 null
 */
data class SmsEnvelope(
    val sender: String,
    val body: String,
    val timestampMs: Long,
    val subscriptionId: Int? = null,
) {
    /** 去重哈希：`sha256(sender|body|timestamp)`（SOP §42） */
    val hash: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        SmsContract.smsHash(sender, body, timestampMs)
    }
}
