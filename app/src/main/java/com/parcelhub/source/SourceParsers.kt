/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.source

import com.parcelhub.model.SourceType

/**
 * 平台来源适配器（SOP §17）：注册表模式。
 *
 * 新增平台只允许在这里注册，不得修改核心状态机 / 合并算法。
 * 解析器只负责“平台特有字段”，通用字段（单号/状态/取件码/地点）
 * 一律交给 parser 层的通用提取器。
 */
interface SourceParser {
    val sourceType: SourceType

    /** 二级匹配用的平台订单号 / 订单关联 ID（SOP §5.2） */
    fun orderKey(text: String): String?

    /** 三级匹配指纹：标准化标题（SOP §5.3，不保存通知全文） */
    fun fingerprint(title: String, body: String): String

    /** 承运商提示（平台已知承运商时） */
    fun carrierHint(text: String): String? = null
}

/** 各平台共用的基础实现 */
open class BaseSourceParser(
    override val sourceType: SourceType = SourceType.OTHER,
    private val orderPatterns: List<Regex> = DEFAULT_ORDER_PATTERNS,
) : SourceParser {

    override fun orderKey(text: String): String? {
        if (text.isBlank()) return null
        for (pattern in orderPatterns) {
            val match = pattern.find(text) ?: continue
            val value = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (value.length in 6..32) return value
        }
        return null
    }

    override fun fingerprint(title: String, body: String): String {
        val source = title.ifBlank { body.lineSequence().firstOrNull().orEmpty() }
        val normalized = source
            .lowercase()
            .replace(Regex("[\\s\\p{Punct}\\p{S}]+"), "")
        return normalized.take(60)
    }

    companion object {
        private val DEFAULT_ORDER_PATTERNS = listOf(
            Regex("订单号\\s*[：:=为是]?\\s*([0-9A-Za-z]{6,32})"),
            Regex("订单编号\\s*[：:=]?\\s*([0-9A-Za-z]{6,32})"),
        )
    }
}

/** 淘宝 / 天猫 */
class TaobaoParser : BaseSourceParser(
    sourceType = SourceType.ECOMMERCE,
    orderPatterns = listOf(
        Regex("订单(?:号|编号)\\s*[：:=为是]?\\s*(\\d{10,24})"),
        Regex("淘宝订单\\s*[：:=]?\\s*(\\d{10,24})"),
    ),
)

/** 京东 */
class JdParser : BaseSourceParser(
    sourceType = SourceType.ECOMMERCE,
    orderPatterns = listOf(
        Regex("订单(?:号|编号)\\s*[：:=为是]?\\s*(\\d{10,20})"),
        Regex("京东订单\\s*[：:=]?\\s*(\\d{10,20})"),
    ),
)

/** 拼多多 */
class PddParser : BaseSourceParser(
    sourceType = SourceType.ECOMMERCE,
    orderPatterns = listOf(
        // 真机实测（0.1.8，拼多多订单详情页）：编号是「前缀-长号」连字符格式
        // 260928-434215381420088——旧的纯 \d 在连字符处断开（前缀 6 位 < 10 不达标），
        // orderKey 恒空 → 详情页只剩裸「订单编号」词、入库被身份闸丢弃、
        // 二级匹配（orderKey）整级失效。连字符格式优先，纯数字格式兜底。
        Regex("订单(?:号|编号)\\s*[：:=为是]?\\s*(\\d{4,12}-\\d{6,24})"),
        Regex("订单(?:号|编号)\\s*[：:=为是]?\\s*(\\d{10,24})"),
    ),
)

/** 抖音电商 */
class DouyinParser : BaseSourceParser(
    sourceType = SourceType.ECOMMERCE,
    orderPatterns = listOf(
        Regex("订单(?:号|编号)?\\s*[：:=]?\\s*(\\d{10,24})"),
    ),
)

/** 菜鸟 / 菜鸟裹裹 */
class CainiaoParser : BaseSourceParser(
    sourceType = SourceType.PICKUP_STATION,
    orderPatterns = listOf(
        Regex("包裹码\\s*[：:=]?\\s*([0-9A-Za-z]{6,24})"),
        Regex("订单(?:号|编号)\\s*[：:=]?\\s*([0-9A-Za-z]{6,32})"),
    ),
)

/** 丰巢 */
class FengchaoParser : BaseSourceParser(
    sourceType = SourceType.LOCKER,
    orderPatterns = listOf(
        Regex("取件(?:码|号)\\s*[：:=]?\\s*([A-Za-z0-9\\-]{4,16})"),
        Regex("订单(?:号|编号)\\s*[：:=]?\\s*([0-9A-Za-z]{6,32})"),
    ),
)

/** 承运商通用实现：强制承运商提示 */
open class CarrierSourceParser(
    sourceType: SourceType = SourceType.LOGISTICS,
    private val carrier: String,
    orderPatterns: List<Regex> = listOf(
        Regex("订单(?:号|编号)\\s*[：:=]?\\s*([0-9A-Za-z]{6,32})"),
    ),
) : BaseSourceParser(sourceType, orderPatterns) {

    override fun carrierHint(text: String): String? = carrier
}

class SfParser : CarrierSourceParser(carrier = "顺丰速运")
class ZtoParser : CarrierSourceParser(carrier = "中通快递")
class YtoParser : CarrierSourceParser(carrier = "圆通速递")
class YundaParser : CarrierSourceParser(carrier = "韵达速递")
class StoParser : CarrierSourceParser(carrier = "申通快递")
class JtParser : CarrierSourceParser(carrier = "极兔速递")

/** 兜底来源：未知 App 也走同一套通用字段提取 */
class GenericParser : BaseSourceParser(sourceType = SourceType.OTHER)

/**
 * 来源注册表：packageName → 解析器。
 * 解析器是无状态单例，可安全复用（不持有 Context）。
 */
object SourceRegistry {

    private val parsers: Map<String, SourceParser> = mapOf(
        "com.taobao.taobao" to TaobaoParser(),
        "com.tmall.android" to TaobaoParser(),
        "com.jingdong.app.mall" to JdParser(),
        "com.xunmeng.pinduoduo" to PddParser(),
        "com.ss.android.ugc.aweme" to DouyinParser(),
        "com.ss.android.ugc.aweme.lite" to DouyinParser(),
        "com.cainiao.wireless" to CainiaoParser(),
        "com.fcbox.bxlm" to FengchaoParser(),
        "com.sf.activity" to SfParser(),
        "com.zto.zto" to ZtoParser(),
        "com.yto.express" to YtoParser(),
        "com.yunda.android" to YundaParser(),
        "com.sto.android" to StoParser(),
        "com.jtexpress.android" to JtParser(),
        "com.chinaPost.activity" to CarrierSourceParser(carrier = "EMS"),
    )

    private val generic = GenericParser()

    fun parserFor(packageName: String): SourceParser = parsers[packageName] ?: generic

    fun isRegistered(packageName: String): Boolean = packageName in parsers
}
