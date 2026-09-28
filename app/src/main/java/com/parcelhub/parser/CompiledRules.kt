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

import com.parcelhub.util.AppLog

/**
 * 预编译规则集（SOP §7.3：禁止在事件处理中重复 Pattern.compile）。
 *
 * Parser 初始化时一次性编译全部正则，之后长期复用；
 * 编译失败的单条规则被跳过并记录，绝不让坏规则拖垮解析（SOP §29 可靠性）。
 */
class CompiledRules(val rules: RuleSet) {

    data class CompiledStatusRule(
        val rule: StatusRule,
        val literalKeywords: List<String>,
        val regexKeywords: List<Regex>,
    )

    val labeledTracking: Regex? = safeCompile(rules.labeledTrackingPattern, "labeledTracking")

    val carrierPatterns: List<Pair<CarrierRule, Regex>> = rules.carriers.flatMap { carrier ->
        carrier.trackingPatterns.mapNotNull { pattern ->
            safeCompile(pattern, "carrier/${carrier.code}")?.let { carrier to it }
        }
    }

    /**
     * 无别名时用于“按单号形态反查承运商”的规则子集：
     * 过滤掉同时接受纯数字与纯字母的全字符集规则（如“任意 10-15 位字母数字”），
     * 这类规则没有区分度，会把普通运单号误判成某家承运商（SOP §6.4 不得猜测）。
     */
    val distinctiveCarrierPatterns: List<Pair<CarrierRule, Regex>> =
        carrierPatterns.filter { (_, regex) -> isDistinctive(regex) }

    /** 同时命中数字探针与字母探针 → 该规则对任意串都成立，没有区分度 */
    private fun isDistinctive(regex: Regex): Boolean =
        !(regex.matches(DIGIT_PROBE) && regex.matches(LETTER_PROBE))

    val statusRules: List<CompiledStatusRule> = rules.statusRules
        .sortedByDescending { it.priority }
        .map { rule ->
            val (literal, regex) = rule.keywords.partition { !it.contains(".*") && !it.contains('\\') }
            CompiledStatusRule(
                rule = rule,
                literalKeywords = literal,
                regexKeywords = regex.mapNotNull { safeCompile(it, "status/${rule.eventType}") },
            )
        }

    val pickupContext: List<Regex> = rules.pickup.contextPatterns.mapNotNull {
        safeCompile(it, "pickup/context")
    }

    val pickupSegmented: Regex? = safeCompile(rules.pickup.segmentedPattern, "pickup/segmented")
    val pickupAlnum: Regex? = safeCompile(rules.pickup.alnumPattern, "pickup/alnum")
    val pickupPureDigits: Regex? = safeCompile(rules.pickup.pureDigits, "pickup/pureDigits")
    val pickupToken: Regex? = safeCompile(rules.pickup.tokenPattern, "pickup/token")
    val pickupExclude: List<Regex> = rules.pickup.excludePatterns.mapNotNull {
        safeCompile(it, "pickup/exclude")
    }
    val pickupLocker: List<Regex> = rules.pickup.lockerPatterns.mapNotNull {
        safeCompile(it, "pickup/locker")
    }

    val locationPatterns: List<Regex> = rules.locations.patterns.mapNotNull {
        safeCompile(it, "location")
    }
    val destinationPatterns: List<Regex> = rules.locations.destinationPatterns.mapNotNull {
        safeCompile(it, "destination")
    }

    /** 放置位置规则（完整站点地址 / 门口投放），SOP §6.4 只认通知里明确出现的地点 */
    val placePatterns: List<Regex> = rules.locations.placePatterns.mapNotNull {
        safeCompile(it, "location/place")
    }

    /** 地点黑名单：避免把噪声文本当成取件地点 */
    val locationBlacklist: List<String> = rules.locations.blacklist

    /** 监听回调用的极轻量门禁关键词（SOP §7.1 第 4 步） */
    val gateKeywords: List<String> = rules.gateKeywords

    fun sourceFor(packageName: String): SourceRule? =
        rules.sources.firstOrNull { it.packageName == packageName }

    private fun safeCompile(pattern: String, tag: String): Regex? = try {
        Regex(pattern)
    } catch (t: Throwable) {
        AppLog.w("bad rule [$tag]: $pattern")
        null
    }

    private companion object {
        const val DIGIT_PROBE = "1234567890123"
        const val LETTER_PROBE = "ABCDEFGHIJKLM"
    }
}
