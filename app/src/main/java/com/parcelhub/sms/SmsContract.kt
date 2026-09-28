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

import java.security.MessageDigest

/**
 * 短信来源契约（快递短信 SOP §3 / §9~§11 / §42）。
 *
 * 纯 Kotlin、零 Android 依赖：关键词表、排除表、去重哈希都能直接在 JVM 单测里验证。
 *
 * 三条核心口径（SOP §1 / §49）：
 *  1. 短信只是一个**数据来源**，不建第二套快递系统——识别结果统一进现有 `Parcel` 数据层；
 *  2. 第一层过滤先判断“是否可能属于物流短信”，**绝不因为看到数字就判定快递**（SOP §9）；
 *  3. 非物流短信（验证码 / 银行 / 支付…）在这一步直接丢掉，
 *     不进数据库、不进 Widget、不进灵动岛（SOP §40）。
 */
object SmsContract {

    /**
     * 短信来源的伪包名。
     *
     * 它同时注册在 `rules.json` 的 `sources` 白名单与 `source_apps` 表里，
     * 因此短信和其它来源共用同一套开关（`SourceSettings` + 来源管理页），
     * **不为短信单开第二份状态**；`source_platform` / `parcel_events.source_package`
     * 也用它标记“这条快递来自短信”（SOP §22 的 `source` 字段）。
     */
    const val SOURCE_PACKAGE = "com.parcelhub.sms"

    /**
     * 物流关键词（SOP §10 的词表，外加同义写法：快件 / 单号 / 代收 / 到站 / 取货 / 到柜）。
     *
     * 只做“可能属于物流短信”的粗筛，真正的字段提取交给统一解析层
     * （运单号 / 状态 / 取件码 / 地点都在 `ParserEngine` 里，SOP §3 的复用原则）。
     */
    val LOGISTICS_KEYWORDS = listOf(
        "快递", "快件", "物流", "包裹", "运单", "单号",
        "派送", "派件", "配送", "送达", "签收",
        "驿站", "代收", "自提", "取件", "取件码", "取货", "提货码",
        "丰巢", "入柜", "到站", "快递员", "派送员", "物流更新",
    )

    /**
     * 排除关键词（SOP §11 / §40）。
     *
     * 命中即 `isLogistics = false`，无论正文里有没有物流关键词。
     * 例：`【XX银行】您的验证码为 583921` → 直接丢弃。
     */
    val EXCLUDED_KEYWORDS = listOf(
        "验证码", "动态码", "登录", "支付", "身份验证", "安全验证",
        "银行卡", "信用卡", "账户", "余额", "账单", "密码",
        "贷款", "还款", "转账", "充值", "扣款",
    )

    /** 命中的排除关键词（SOP §11）；null = 不是被排除的类型 */
    fun excludedReason(body: String): String? =
        EXCLUDED_KEYWORDS.firstOrNull { body.contains(it) }

    /** 是否可能属于物流短信（SOP §9/§10） */
    fun isLogistics(body: String): Boolean =
        LOGISTICS_KEYWORDS.any { body.contains(it) }

    /** sha256 十六进制（SOP §42 的 smsHash 底座），纯 JVM 可测 */
    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 短信去重哈希（SOP §42）：`sha256(sender + body + timestamp)`。
     *
     * 只存哈希、不存原文与发件人（SOP §38/§39），跨重启仍然稳定，
     * 因此可以直接当 `notificationKey` 用：同一条短信重复投递 → 同一个 dedupKey → 事件层自动去重。
     */
    fun smsHash(sender: String, body: String, timestampMs: Long): String =
        sha256Hex("$sender|$body|$timestampMs")

    /**
     * 发件人指纹：用作 `RawNotification.title` → `fingerprint`（SOP §5.3 标准化标题）。
     *
     * 必须满足两点：
     *  - **不可逆**，绝不把手机号 / 短信号码落库（SOP §38 只存必要字段）；
     *  - 带分隔符，避免 16 进制串被运单号正则当成单号
     *    （每段 4 位，任何数字连续段都不会达到 8 位单号的长度下限）。
     */
    fun senderFingerprint(sender: String): String =
        sha256Hex(sender).take(16).chunked(4).joinToString("-")
}
