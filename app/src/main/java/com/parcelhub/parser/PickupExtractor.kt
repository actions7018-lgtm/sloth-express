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
 * 取件码提取器（SOP §6.2 / §6.3）。
 *
 * 识别优先级：明确关键词 → 凭/取/领取上下文 → 结构化格式 → 候选 token → 上下文评分。
 * 必须覆盖：纯数字、字母数字、`16-4-9626`、`2-2-7508`、`A88123`、多取件码、多码簇、柜号。
 * 必须排除：验证码、手机号、金额、时间、日期、订单号、尾号、单号片段、时长数字。
 *
 * 全部正则来自 [CompiledRules]（初始化时预编译，事件处理不再编译，SOP §7.3）。
 */
class PickupExtractor(private val rules: CompiledRules) {

    data class PickupResult(
        val code: String?,
        val codes: List<String>,
        val lockerNumber: String?,
        val confidence: Double,
        val reason: String?,
    )

    private data class Candidate(
        val token: String,
        val index: Int,
        val score: Double,
    )

    fun extract(text: String, trackingNumber: String? = null): PickupResult {
        if (text.isBlank()) {
            return PickupResult(null, emptyList(), null, 0.0, "通知正文为空")
        }

        val candidates = LinkedHashMap<String, Candidate>()

        fun offer(token: String, index: Int, score: Double) {
            val clean = token.trim().trim('，', ',', '。', '.', '：', ':', '、', '/', ')', '）')
            if (clean.length < 4 || clean.length > 16) return
            val existing = candidates[clean]
            if (existing == null || score > existing.score) {
                candidates[clean] = Candidate(clean, index, score)
            }
        }

        // ---- 1) 明确关键词上下文（最高优先级） ----
        // 强关键词（取件码/取货码/取件号…）给高分；弱关键词（取件/领取…）只给基础分
        for (keyword in rules.rules.pickup.positiveKeywords) {
            val strong = keyword.contains('码') || keyword.contains('号') || keyword.contains("凭证")
            val tokenScore = if (strong) 3.2 else 2.0
            val segmentedScore = if (strong) 3.6 else 2.6
            val digitsScore = if (strong) 3.0 else 1.8
            var searchFrom = 0
            while (true) {
                val at = text.indexOf(keyword, searchFrom)
                if (at < 0) break
                searchFrom = at + keyword.length
                val windowEnd = (at + keyword.length + CONTEXT_WINDOW).coerceAtMost(text.length)
                val region = text.substring(at, windowEnd)
                for (match in rules.pickupToken?.findAll(region) ?: emptySequence()) {
                    offer(match.value, at + match.range.first, tokenScore)
                }
                // 关键词紧邻的结构化取件码（取件码:16-4-9626）
                for (match in rules.pickupSegmented?.findAll(region) ?: emptySequence()) {
                    offer(match.value, at + match.range.first, segmentedScore)
                }
                // 关键词后紧跟的纯数字
                for (match in rules.pickupPureDigits?.findAll(region) ?: emptySequence()) {
                    offer(match.value, at + match.range.first, digitsScore)
                }
            }
        }

        // ---- 2) 结构化格式（无关键词时兜底） ----
        for (match in rules.pickupSegmented?.findAll(text) ?: emptySequence()) {
            offer(match.value, match.range.first, 2.0)
        }

        // ---- 3) 候选 token + 上下文评分（仅在还没有强候选时扫描，控制开销） ----
        if (candidates.values.none { it.score >= 2.5 }) {
            for (match in rules.pickupAlnum?.findAll(text) ?: emptySequence()) {
                offer(match.value, match.range.first, 1.6)
            }
            for (match in rules.pickupPureDigits?.findAll(text) ?: emptySequence()) {
                offer(match.value, match.range.first, 1.0)
            }
        }

        // ---- 4) 负向过滤 ----
        val accepted = candidates.values.filter { candidate ->
            !isExcluded(candidate, text, trackingNumber)
        }

        // ---- 5) 打分排序：上下文评分高者胜出 ----
        val ranked = accepted.sortedWith(
            compareByDescending<Candidate> { it.score }
                .thenBy { it.index },
        )

        val best = ranked.firstOrNull { it.score >= ACCEPT_THRESHOLD }
        val locker = extractLocker(text)

        return when {
            best == null -> PickupResult(
                code = null,
                codes = emptyList(),
                lockerNumber = locker,
                confidence = if (locker != null) 0.55 else 0.0,
                reason = if (locker != null) "仅识别到柜号" else "没有找到取件码",
            )

            best.score >= 3.0 -> PickupResult(
                code = best.token,
                codes = ranked.filter { it.score >= ACCEPT_THRESHOLD }.map { it.token }.distinct(),
                lockerNumber = locker,
                confidence = if (ranked.size > 1) 0.92 else 0.95,
                reason = null,
            )

            else -> PickupResult(
                code = best.token,
                codes = ranked.filter { it.score >= ACCEPT_THRESHOLD }.map { it.token }.distinct(),
                lockerNumber = locker,
                confidence = 0.75,
                reason = "取件码来自结构化匹配，建议人工确认",
            )
        }
    }

    /** 柜号 / 格口号（与取件码分开，不参与取件码打分） */
    private fun extractLocker(text: String): String? {
        for (regex in rules.pickupLocker) {
            val match = regex.find(text) ?: continue
            val value = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: continue
            if (!looksLikeNoise(value)) return value
        }
        return null
    }

    /** 负向过滤（SOP §6.3）：任一规则命中即丢弃候选 */
    private fun isExcluded(candidate: Candidate, text: String, trackingNumber: String?): Boolean {
        val token = candidate.token

        // 固定排除形态：手机号 / 时间 / 日期 / 长数字 / 金额
        for (exclude in rules.pickupExclude) {
            if (exclude.containsMatchIn(token)) return true
        }

        // 手机号（含 138-1234-5678 这种带分隔写法）
        val bare = token.replace("-", "").replace(" ", "")
        if (bare.length == 11 && bare.first() == '1' && bare[1] in '3'..'9') return true
        if (bare.length >= 11 && bare.all { it.isDigit() }) return true

        // 与运单号重叠（单号的一部分不能当取件码）
        if (!trackingNumber.isNullOrBlank()) {
            if (trackingNumber.contains(token) || token.contains(trackingNumber)) return true
        }

        // 附近出现负向关键词：验证码 / 验证 / 尾号 / 订单 / 金额 / 时长 …
        val start = (candidate.index - NEAR_WINDOW).coerceAtLeast(0)
        val end = (candidate.index + token.length + 4).coerceAtMost(text.length)
        val region = text.substring(start, end)
        for (keyword in rules.rules.pickup.negativeKeywords) {
            if (region.contains(keyword)) return true
        }
        for (context in rules.rules.pickup.negativeContexts) {
            if (region.contains(context)) return true
        }

        return false
    }

    private fun looksLikeNoise(value: String): Boolean =
        value.length > 8 || value.all { it.isDigit() && it == '0' }

    private companion object {
        /** 关键词后向前看窗口 */
        const val CONTEXT_WINDOW = 24

        /** 负向上下文邻近窗口 */
        const val NEAR_WINDOW = 8

        /** 接受阈值：低于该分数不作为取件码返回 */
        const val ACCEPT_THRESHOLD = 2.0
    }
}
