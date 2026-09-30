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
 * 地址 / 取件地点解析（SOP §6.4）。
 *
 * 优先：已到 / 到达 / 至 → 地点 → 取 / 领取。
 * 无法精确获取时返回 null（pickupLocation = UNKNOWN），**不得猜测地址**。
 */
class AddressExtractor(private val rules: CompiledRules) {

    /** 取件地点（短站点名）：只保留站点本身，如“余杭区东连街道菜鸟驿站”→“菜鸟驿站” */
    fun extractLocation(text: String): String? =
        extractLocationRaw(text)?.let { shortStation(it) }

    /** 取件地点原文（保留行政区 / 道路前缀），用于补全“放置位置” */
    fun extractLocationRaw(text: String): String? {
        if (text.isBlank()) return null
        for (pattern in rules.locationPatterns) {
            for (match in pattern.findAll(text)) {
                val name = match.groupValues.getOrNull(1)
                    ?.trim()
                    ?.trim('的', '。', '，', ',', ' ')
                    ?: continue
                // 规则第一组是站点名，第二组（若有）是站点后缀：拼成完整站点名展示
                val suffix = match.groupValues.getOrNull(2)?.trim().orEmpty()
                val candidate = name + suffix
                if (isValidLocationName(name) && candidate.length <= 24) return candidate
            }
        }
        return null
    }

    /**
     * 放置位置（SOP §6.4：不得猜测地址）——只在通知明确给出时提取：
     * - 完整站点地址：余杭区东连街道菜鸟快递驿站
     * - 门口投放：家门口 / 前台 / 传达室 …
     * 多个候选时取信息量最大的一条。
     */
    fun extractPlace(text: String): String? {
        if (text.isBlank()) return null
        var best: String? = null
        for (pattern in rules.placePatterns) {
            for (match in pattern.findAll(text)) {
                val candidate = match.groupValues.getOrNull(1)
                    ?.trim()
                    ?.trim('的', '。', '，', ',', ' ')
                    ?: continue
                if (!isValidPlace(candidate)) continue
                if (best == null || candidate.length > best.length) best = candidate
            }
        }
        return best
    }

    /** 去掉行政区 / 道路前缀：取件地址只显示站点，前缀留给“放置位置”行 */
    fun shortStation(full: String): String {
        var cut = -1
        for (marker in ADMIN_PREFIX.findAll(full)) cut = marker.range.last + 1
        if (cut <= 0) return full
        val rest = full.substring(cut)
        // 裁完只剩“驿站”这类纯后缀时，保留原文，避免信息被裁没
        if (rest.length < 2 || rest in BARE_SUFFIX) return full
        if (rest.none { it.code in 0x4E00..0x9FA5 }) return full
        return rest
    }

    /** 放置位置候选校验（黑名单 + 噪声词按包含匹配） */
    private fun isValidPlace(place: String): Boolean {
        if (place.length < 2 || place.length > 40) return false
        if (place in BARE_SUFFIX) return false
        if (place.none { it.code in 0x4E00..0x9FA5 }) return false
        for (blocked in rules.locationBlacklist) {
            if (place.contains(blocked)) return false
        }
        for (keyword in rules.rules.pickup.negativeKeywords) {
            if (place.contains(keyword)) return false
        }
        return true
    }

    /** 收货地址（用于包裹详情展示，仅在通知明确给出时提取） */
    fun extractDestination(text: String): String? {
        if (text.isBlank()) return null
        for (pattern in rules.destinationPatterns) {
            val match = pattern.find(text) ?: continue
            val candidate = match.groupValues.getOrNull(1)?.trim() ?: continue
            if (candidate.length in 4..60) return candidate
        }
        return null
    }

    /** 校验站点名部分（不含后缀） */
    private fun isValidLocationName(name: String): Boolean {
        if (name.length < 2 || name.length > 20) return false
        // 噪声词 / 非站点短语（黑名单按包含匹配）
        for (blocked in rules.locationBlacklist) {
            if (name.contains(blocked)) return false
        }
        // 承运商品牌不是地点：拼多多路线卡「预计 5 小时内到达 圆通快递 : YT07…」
        // 会被地点规则 2（“到达 + 2~16 汉字”）截成品牌名（SOP §6.4 不得猜测）
        for (carrier in rules.rules.carriers) {
            if (name == carrier.name || carrier.aliases.any { name.contains(it) }) return false
        }
        // 纯地点后缀（如“驿站”“快递柜”）不构成有效站点名
        if (name in STATION_ONLY) return false
        // 必须包含中文
        if (name.none { it.code in 0x4E00..0x9FA5 }) return false
        // 不能是验证码 / 单号等噪声
        for (keyword in rules.rules.pickup.negativeKeywords) {
            if (name.contains(keyword)) return false
        }
        return true
    }

    private companion object {
        val STATION_ONLY = setOf(
            "驿站", "快递柜", "自提柜", "代收点", "快递超市", "服务站", "菜鸟驿站", "丰巢",
        )

        /** 裁剪后不允许只剩这些纯站点后缀 */
        val BARE_SUFFIX = setOf(
            "驿站", "快递柜", "自提柜", "代收点", "快递超市", "服务站",
        )

        /** 行政区 / 道路前缀（“放置位置”保留、取件地址裁掉的部分） */
        val ADMIN_PREFIX = Regex("(?:区|街道|镇|路|大道|街|巷|弄|小区|花园|新村)")
    }
}
