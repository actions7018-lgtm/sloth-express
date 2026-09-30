/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.pending

/**
 * 待补全订单 ↔ 新到单号事件的匹配（SOP §30 匹配优先级）。
 *
 * 纯函数、无 IO，可被 JVM 单测直接覆盖。
 *
 * 优先级（SOP §30）：
 *  1. orderKey 完全一致；
 *  2. 平台一致且落在发货后 72h 窗口内（唯一命中才算，多个同平台订单视为歧义不绑）；
 *  3. 中转来源（短信 / 分享，不携带平台信息）且窗口内**只有一个**待补全订单——
 *     这就是 SOP 场景 1「昨天已发货，今天短信出现单号」的通路。
 *
 * 保守原则：任何一步出现歧义就返回 null，宁可保持待补全，也不能把单号绑错订单
 * （SOP §30「不能仅靠商品名称强行绑定」）。
 */
object PendingMatcher {

    /** 补全匹配窗口：发货后 72 小时内（与 [ShipmentReconciler.EXPIRE_DELAY_MS] 一致） */
    const val MATCH_WINDOW_MS: Long = ShipmentReconciler.EXPIRE_DELAY_MS

    /** 匹配命中的层级（诊断 / 日志用） */
    enum class Reason {
        /** orderKey 一致（最高优先级） */
        ORDER_KEY,

        /** 同平台唯一命中 */
        PLATFORM,

        /** 中转来源（短信等）+ 窗口内唯一待补全订单 */
        SINGLE_IN_WINDOW,
    }

    data class Result(
        val pending: PendingShipment,
        val reason: Reason,
    )

    /**
     * @param candidates 当前所有待补全订单（含非 WAITING 状态，由本函数过滤）
     * @param orderKey 新事件的订单号（可为 null）
     * @param platform 新事件的来源平台包名；短信 / 分享等中转来源必须传 null（见 [sourcePlatform]）
     * @param at 新事件时间
     */
    fun match(
        candidates: List<PendingShipment>,
        orderKey: String?,
        platform: String?,
        at: Long,
    ): Result? {
        val waiting = candidates.filter {
            it.isWaiting &&
                at >= it.shippedAt - CLOCK_SKEW_MS &&
                at <= it.shippedAt + MATCH_WINDOW_MS
        }
        if (waiting.isEmpty()) return null

        // 1) orderKey
        if (!orderKey.isNullOrBlank()) {
            waiting.filter { it.orderKey == orderKey }
                .maxByOrNull { it.shippedAt }
                ?.let { return Result(it, Reason.ORDER_KEY) }
        }

        // 2/3) 平台
        if (platform != null) {
            // 携带平台信息的来源只认同平台或「平台未知」（中转来源发现）的记录，
            // 绝不跨平台兜底（防误绑，SOP §30）
            val exact = waiting.filter { it.platform == platform }
            if (exact.size == 1) return Result(exact.single(), Reason.PLATFORM)
            if (exact.size > 1) return null // 同平台多单歧义，宁可保持待补全
            val unknown = waiting.filter { it.platform == null }
            return if (unknown.size == 1) {
                Result(unknown.single(), Reason.PLATFORM)
            } else {
                null
            }
        }

        // 中转来源（短信 / 分享）：窗口内唯一才敢认
        return waiting.singleOrNull()?.let { Result(it, Reason.SINGLE_IN_WINDOW) }
    }

    /**
     * 把来源包名折算成匹配用的「平台」：
     * 中转来源不携带购物平台信息 → null（降级为窗口内唯一匹配）。
     */
    fun sourcePlatform(sourcePackage: String): String? =
        if (sourcePackage in RELAY_SOURCES) null else sourcePackage

    /**
     * 中转来源：拿不到购物平台信息的入口。
     * 与 [com.parcelhub.sms.SmsContract.SOURCE_PACKAGE] / ServiceLocator.SHARE_SOURCE
     * 保持一致（PendingMatcherTest 有防漂移断言）。
     */
    val RELAY_SOURCES: Set<String> = setOf(
        "com.parcelhub.sms",
        "com.parcelhub.share",
    )

    /** 允许的时钟偏差：事件时间略早于发货时间不判负 */
    private const val CLOCK_SKEW_MS: Long = 60 * 60 * 1000L
}
