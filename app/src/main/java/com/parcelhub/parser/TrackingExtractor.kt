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

/**
 * 运单号 + 快递公司提取（SOP §6.1 第二层通用字段提取器）。
 *
 * 顺序：带标签单号（运单号/单号：xxx） → 承运商关键词 → 承运商单号形态。
 * 正则全部来自 [CompiledRules]，事件处理阶段零编译。
 */
class TrackingExtractor(private val rules: CompiledRules) {

    data class Result(
        val trackingNumber: String?,
        val carrier: String?,
        val confidence: Double,
        val reason: String?,
    )

    fun extract(text: String, carrierHint: String? = null): Result {
        if (text.isBlank()) return Result(null, null, 0.0, "通知正文为空")

        val aliasCarrier = detectCarrierByAlias(text)
        val carrier = carrierHint ?: aliasCarrier

        // 1) 带标签的运单号：优先级最高
        val labeled = rules.labeledTracking?.find(text)?.groupValues?.getOrNull(1)
        if (!labeled.isNullOrBlank() && !isNoise(labeled)) {
            return Result(
                trackingNumber = labeled.trim().uppercase(),
                carrier = aliasCarrier ?: detectCarrierByShape(labeled),
                confidence = 0.90,
                reason = null,
            )
        }

        // 2) 承运商关键词出现时，按该公司运单号形态匹配
        val carrierRule = aliasCarrier?.let { name ->
            rules.rules.carriers.firstOrNull { it.name == name }
        }
        if (carrierRule != null) {
            for ((rule, regex) in rules.carrierPatterns) {
                if (rule.code != carrierRule.code) continue
                val match = regex.find(text) ?: continue
                val value = match.value
                if (isNoise(value)) continue
                return Result(
                    trackingNumber = value.trim().uppercase(),
                    carrier = aliasCarrier,
                    confidence = 0.80,
                    reason = null,
                )
            }
        }

        // 3) 既没有标签、也没有承运商提示：只认“带字母公司代号”的单号形态（SOP §17）。
        //    短信里常见 `您的快件 SF1234567890123 正在派送` 这种写法（快递短信 SOP §43.1）。
        //    纯数字形态是多家承运商共用的，猜不出就不猜（SOP §6.4 不得猜测）——
        //    所以这里要求匹配结果里必须带字母（SF… / YT… / EMS… 本身就是区分度）。
        if (carrier == null) {
            for ((rule, regex) in rules.distinctiveCarrierPatterns) {
                val match = regex.find(text) ?: continue
                val value = match.value
                if (isNoise(value)) continue
                if (value.none { it.isLetter() }) continue
                return Result(
                    trackingNumber = value.trim().uppercase(),
                    carrier = rule.name,
                    confidence = 0.75,
                    reason = null,
                )
            }
        }

        return Result(null, carrier, 0.0, "没有找到运单号")
    }

    /**
     * 全部可识别单号（SOP V2.0 §7/§8 列表批量扫描）：
     * 同一页出现多个订单的单号时逐个返回（按归一化单号去重，保序）。
     *
     * 与 [extract] 的差别：
     *  - 逐级收集**全部**命中而不是首个（多单混排页一单一条）；
     *  - 有承运商提示时也继续扫「带字母代号」形态（SF/YT… 跨公司单号，
     *    字母前缀本身就是区分度，SOP V2.0 §9「不依赖关键词」）；
     *  - [allowBareNumeric]=false 只用于 §29/§30 紧凑拼接兜底文本：
     *    压缩空白会把相邻行数字粘在一起（座机 0769-33555666 等），
     *    纯数字候选在此模式下不认，只认带字母代号或带标签命中的单号。
     */
    fun extractAll(
        text: String,
        carrierHint: String? = null,
        allowBareNumeric: Boolean = true,
    ): List<Result> {
        if (text.isBlank()) return emptyList()

        val aliasCarrier = detectCarrierByAlias(text)
        val carrier = carrierHint ?: aliasCarrier
        val out = LinkedHashMap<String, Result>()

        fun put(value: String?, carrierName: String?, confidence: Double) {
            if (value.isNullOrBlank()) return
            val trimmed = value.trim()
            if (isNoise(trimmed)) return
            if (!allowBareNumeric && trimmed.none { it.isLetter() }) return
            val key = trimmed.uppercase()
            out.putIfAbsent(
                key,
                Result(trackingNumber = key, carrier = carrierName, confidence = confidence, reason = null),
            )
        }

        // 1) 带标签的运单号：优先级最高
        rules.labeledTracking?.findAll(text)?.forEach { m ->
            val value = m.groupValues.getOrNull(1)
            put(value, aliasCarrier ?: detectCarrierByShape(value.orEmpty()), 0.90)
        }

        // 2) 承运商关键词出现时，按该公司运单号形态匹配
        val carrierRule = aliasCarrier?.let { name ->
            rules.rules.carriers.firstOrNull { it.name == name }
        }
        if (carrierRule != null) {
            for ((rule, regex) in rules.carrierPatterns) {
                if (rule.code != carrierRule.code) continue
                regex.findAll(text).forEach { m -> put(m.value, aliasCarrier, 0.80) }
            }
        }

        // 3) 带字母公司代号的单号形态：多单混排时跨公司收集（必须带字母）
        for ((rule, regex) in rules.distinctiveCarrierPatterns) {
            regex.findAll(text).forEach { m ->
                val value = m.value.trim()
                if (value.none { it.isLetter() }) return@forEach
                put(value, rule.name, 0.75)
            }
        }

        return out.values.toList()
    }

    /** 文本中出现的承运商别名 → 承运商名 */
    fun detectCarrierByAlias(text: String): String? {
        var best: String? = null
        var bestLength = 0
        for (carrier in rules.rules.carriers) {
            for (alias in carrier.aliases) {
                if (alias.length > bestLength && text.contains(alias)) {
                    best = carrier.name
                    bestLength = alias.length
                }
            }
        }
        return best
    }

    /** 单号形态反查承运商：只用有区分度的规则，猜不出就不猜（SOP §6.4） */
    fun detectCarrierByShape(tracking: String): String? {
        val value = tracking.trim().uppercase()
        for ((carrier, regex) in rules.distinctiveCarrierPatterns) {
            if (regex.matches(value)) return carrier.name
        }
        return null
    }

    /** 排除手机号 / 日期 / 时间 / 过长数字等非单号形态 */
    private fun isNoise(value: String): Boolean {
        val bare = value.replace("-", "").replace(" ", "")
        if (bare.length < 8) return true
        if (bare.length >= 24) return true
        if (bare.length == 11 && bare.first() == '1' && bare[1] in '3'..'9') return true
        if (PHONE_IN_TOKEN.containsMatchIn(value)) return true
        if (DATE_IN_TOKEN.containsMatchIn(value)) return true
        if (TIME_IN_TOKEN.containsMatchIn(value)) return true
        return false
    }

    private companion object {
        val PHONE_IN_TOKEN = Regex("1[3-9]\\d{9}")
        val DATE_IN_TOKEN = Regex("\\b\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\b")
        val TIME_IN_TOKEN = Regex("\\b\\d{1,2}[:：]\\d{2}\\b")
    }
}
