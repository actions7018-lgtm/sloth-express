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
import com.parcelhub.model.ShipmentStatus

/**
 * 物流状态识别（SOP §6.1 第二层）。
 *
 * 规则按 priority 从高到低匹配，先命中的生效；
 * 关键词支持字面量与正则（含 `.*` 的条目在初始化时预编译）。
 */
class StatusExtractor(private val rules: CompiledRules) {

    data class Result(
        val eventType: EventType,
        val status: ShipmentStatus,
        val reason: String?,
    )

    fun extract(text: String): Result {
        if (text.isBlank()) {
            return Result(EventType.UNKNOWN, ShipmentStatus.UNKNOWN, "通知正文为空")
        }
        for (rule in rules.statusRules) {
            val hit = rule.literalKeywords.any { text.contains(it) } ||
                rule.regexKeywords.any { it.containsMatchIn(text) }
            if (hit) {
                return Result(
                    eventType = EventType.from(rule.rule.eventType),
                    status = ShipmentStatus.from(rule.rule.status),
                    reason = null,
                )
            }
        }
        return Result(EventType.UNKNOWN, ShipmentStatus.UNKNOWN, "没有匹配到物流状态关键词")
    }
}
