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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 承运商规则（rules.json → carriers） */
data class CarrierRule(
    val code: String,
    val name: String,
    val aliases: List<String>,
    val trackingPatterns: List<String>,
)

/** 物流状态规则（rules.json → statusRules） */
data class StatusRule(
    val eventType: String,
    val status: String,
    val priority: Int,
    val keywords: List<String>,
)

/** 来源 App 规则（rules.json → sources） */
data class SourceRule(
    val packageName: String,
    val appName: String,
    val sourceType: String,
    val keywords: List<String>,
    val priority: Int,
    val enabled: Boolean,
)

/** 取件码提取规则（rules.json → pickup） */
data class PickupRules(
    val positiveKeywords: List<String>,
    val contextPatterns: List<String>,
    val negativeKeywords: List<String>,
    val negativeContexts: List<String>,
    val segmentedPattern: String,
    val alnumPattern: String,
    val pureDigits: String,
    val tokenPattern: String,
    val excludePatterns: List<String>,
    val lockerPatterns: List<String>,
)

/** 地点解析规则（rules.json → locations） */
data class LocationRules(
    val patterns: List<String>,
    val destinationPatterns: List<String>,
    val placePatterns: List<String>,
    val blacklist: List<String>,
)

/** 解析规则集合（SOP §18 V0.1：assets/rules.json 单一数据源） */
data class RuleSet(
    val version: Int,
    val gateKeywords: List<String>,
    val sources: List<SourceRule>,
    val carriers: List<CarrierRule>,
    val labeledTrackingPattern: String,
    val statusRules: List<StatusRule>,
    val pickup: PickupRules,
    val locations: LocationRules,
)

object RuleJsonParser {

    fun parse(jsonText: String): RuleSet {
        val root = Json.parseToJsonElement(jsonText).jsonObject

        val pickup = root.obj("pickup")
        val locations = root.obj("locations")

        return RuleSet(
            version = root.int("version", 1),
            gateKeywords = root.strList("gateKeywords"),
            sources = root.arr("sources").map { it.jsonObject.toSourceRule() },
            carriers = root.arr("carriers").map { it.jsonObject.toCarrierRule() },
            labeledTrackingPattern = root.str(
                "labeledTrackingPattern",
                "(?:运单号|快递单号|物流单号|单号)\\s*[：:]?\\s*([A-Za-z0-9]{8,24})",
            ),
            statusRules = root.arr("statusRules").map { it.jsonObject.toStatusRule() },
            pickup = PickupRules(
                positiveKeywords = pickup.strList("positiveKeywords"),
                contextPatterns = pickup.strList("contextPatterns"),
                negativeKeywords = pickup.strList("negativeKeywords"),
                negativeContexts = pickup.strList("negativeContexts"),
                segmentedPattern = pickup.str("segmentedPattern", "\\b\\d{1,3}-\\d{1,3}-\\d{1,4}\\b"),
                alnumPattern = pickup.str("alnumPattern", "\\b[A-Za-z]{1,3}\\d{4,10}\\b"),
                pureDigits = pickup.str("pureDigits", "\\b\\d{4,8}\\b"),
                tokenPattern = pickup.str("tokenPattern", "[A-Za-z0-9][A-Za-z0-9\\-]{2,15}"),
                excludePatterns = pickup.strList("excludePatterns"),
                lockerPatterns = pickup.strList("lockerPatterns"),
            ),
            locations = LocationRules(
                patterns = locations.strList("patterns"),
                destinationPatterns = locations.strList("destinationPatterns"),
                // 缺失时为空列表：旧规则文件也能安全加载（SOP §19）
                placePatterns = locations.strList("placePatterns"),
                blacklist = locations.strList("blacklist"),
            ),
        )
    }

    private fun JsonObject.toSourceRule(): SourceRule = SourceRule(
        packageName = str("packageName"),
        appName = str("appName", str("packageName")),
        sourceType = str("sourceType", "OTHER"),
        keywords = strList("keywords"),
        priority = int("priority", 0),
        enabled = bool("enabled", true),
    )

    private fun JsonObject.toCarrierRule(): CarrierRule = CarrierRule(
        code = str("code"),
        name = str("name"),
        aliases = strList("aliases"),
        trackingPatterns = strList("trackingPatterns"),
    )

    private fun JsonObject.toStatusRule(): StatusRule = StatusRule(
        eventType = str("eventType"),
        status = str("status"),
        priority = int("priority", 0),
        keywords = strList("keywords"),
    )

    // --- 小工具：缺失字段一律走安全默认值，规则损坏不能导致崩溃（SOP §19 可靠性） ---

    private fun JsonObject.obj(key: String): JsonObject =
        (this[key] as? JsonObject) ?: JsonObject(emptyMap())

    private fun JsonObject.arr(key: String): JsonArray =
        (this[key] as? JsonArray) ?: JsonArray(emptyList())

    private fun JsonObject.str(key: String, default: String = ""): String {
        val primitive = this[key]?.jsonPrimitive ?: return default
        return primitive.contentOrNull ?: default
    }

    private fun JsonObject.int(key: String, default: Int): Int {
        val primitive = this[key]?.jsonPrimitive ?: return default
        return primitive.contentOrNull?.toDoubleOrNull()?.toInt() ?: default
    }

    private fun JsonObject.bool(key: String, default: Boolean): Boolean {
        val primitive = this[key]?.jsonPrimitive ?: return default
        return primitive.booleanOrNull ?: default
    }

    private fun JsonObject.strList(key: String): List<String> =
        arr(key).mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
}
