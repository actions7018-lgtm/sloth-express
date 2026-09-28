/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.util

/**
 * 隐私与脱敏工具（SOP §22）。
 *
 * 只处理与快递有关的字段；任何外显/导出的文本都必须经过这里。
 * 纯 Kotlin 实现，可在 JVM 单元测试中直接验证。
 */
object PrivacyUtil {

    private val PHONE_REGEX = Regex("(?<!\\d)(1[3-9]\\d)(\\d{4})(\\d{4})(?!\\d)")
    private val LONG_DIGITS_REGEX = Regex("(?<!\\d)\\d{10,}(?!\\d)")

    /** 承运商形态的单号（SF…/YT…/EA…CN 等字母+数字组合），纯数字规则覆盖不到 */
    private val ALNUM_TRACKING_REGEX =
        Regex("(?<![A-Za-z0-9])[A-Za-z]{1,4}\\d{6,}[A-Za-z]{0,4}(?![A-Za-z0-9])")

    private val ADDRESS_REGEX = Regex("[\\u4e00-\\u9fa5A-Za-z0-9]*(?:省|市|区|县|路|街|号|栋|单元|室)")

    /** 手机号遮罩：13812345678 → 138****5678 */
    fun maskPhone(input: String): String =
        PHONE_REGEX.replace(input) { m -> "${m.groupValues[1]}****${m.groupValues[3]}" }

    /** 长数字（订单号/单号）遮罩：保留前 4 后 2 */
    fun maskLongNumber(input: String): String =
        LONG_DIGITS_REGEX.replace(input) { m ->
            val v = m.value
            "${v.take(4)}****${v.takeLast(2)}"
        }

    /** 字母前缀单号遮罩：YT4482236617 → YT44****17（SOP §22.2 摘要不得含完整运单号） */
    fun maskTrackingLike(input: String): String =
        ALNUM_TRACKING_REGEX.replace(input) { m ->
            val v = m.value
            if (v.length <= 6) v else "${v.take(4)}****${v.takeLast(2)}"
        }

    /** 地址遮罩：保留首段与末字，中间打码 */
    fun maskAddress(input: String): String =
        ADDRESS_REGEX.replace(input) { m ->
            val v = m.value
            if (v.length <= 6) v else v.take(4) + "****" + v.takeLast(2)
        }

    /**
     * 生成脱敏摘要：手机号 / 长数字 / 详细地址统一处理，并限制长度。
     * 用于 parcel_events.raw_summary，替代“保存完整通知原文”。
     */
    fun summarize(raw: String, maxLength: Int = 80): String {
        if (raw.isBlank()) return ""
        var s = raw.replace('\n', ' ').trim()
        s = maskPhone(s)
        s = maskAddress(s)
        s = maskTrackingLike(s)
        s = maskLongNumber(s)
        s = s.replace(Regex("\\s+"), " ")
        return if (s.length <= maxLength) s else s.take(maxLength) + "…"
    }

    /**
     * 诊断样本（SOP §19.1）：只保留来源/时间/结果/原因，不含正文。
     */
    fun diagnosticSample(
        sourcePackage: String,
        timeText: String,
        result: String,
        reason: String,
    ): String = "来源：$sourcePackage\n时间：$timeText\n识别结果：$result\n原因：$reason"
}
