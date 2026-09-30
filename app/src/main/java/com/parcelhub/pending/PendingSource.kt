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
 * 待补全订单的「来源识别」（需求 §二 sourceType / §三 来源识别）。
 *
 * 与 [com.parcelhub.model.SourceType]（事件分类：ECOMMERCE/LOGISTICS/…）是两回事：
 * 这里记录的是**这条待补全记录是从哪条采集链路发现的**：
 *
 *  - [ECOMMERCE]   ：来源是购物 App（rules.json `sourceType=ECOMMERCE`），如拼多多、淘宝；
 *  - [NOTIFICATION]：来源是通知监听，但不是购物 App（如快递公司通知）；
 *  - [ACCESSIBILITY]：由无障碍服务读取页面文本产生（需求 §八）；
 *  - [SCREEN_OCR]  ：由截图 OCR 产生（模型保留；项目当前没有 OCR 链路）；
 *  - [SMS]         ：短信中转来源（`com.parcelhub.sms`）；
 *  - [USER_INPUT]  ：用户分享 / 手动导入（`com.parcelhub.share`）。
 *
 * 纯函数、无 IO、无 Android 依赖，可被 JVM 单测直接覆盖。
 */
enum class PendingSourceType {
    ECOMMERCE,
    NOTIFICATION,
    ACCESSIBILITY,
    SCREEN_OCR,
    SMS,
    USER_INPUT;

    companion object {
        /** 未知通道名一律降级为 NOTIFICATION（通知监听是默认采集链路） */
        fun from(raw: String?): PendingSourceType =
            entries.firstOrNull { it.name == raw } ?: NOTIFICATION
    }
}

/** 一条待补全记录解析出的来源三元组（需求 §二：type + packageName + appName） */
data class PendingSourceInfo(
    val type: PendingSourceType,
    /** 来源购物 App 包名；短信 / 分享等中转来源为 null */
    val packageName: String?,
    /** 来源 App 显示名（如「拼多多」）；拿不到为 null，UI 显示「未知」 */
    val appName: String?,
)

/**
 * 来源解析器（需求 §三）：把「事件从哪来」折算成 [PendingSourceInfo]。
 *
 * 判定顺序（与需求 §三 一致）：
 *  1. 中转来源伪包名：`com.parcelhub.sms` → SMS、`com.parcelhub.share` → USER_INPUT；
 *  2. 显式采集通道（[com.parcelhub.model.RawNotification.channel]，
 *     如无障碍读取 ACCESSIBILITY）优先于包名推断；
 *  3. rules.json 里 `sourceType=ECOMMERCE` 的购物 App → ECOMMERCE（需求 §三-1
 *     「已有购物 App 来源」，如拼多多通知 → ECOMMERCE + 包名 + 应用名）；
 *  4. 其余由通知监听发现 → NOTIFICATION（需求 §三-2）。
 *
 * 应用名取值优先级：系统 PackageManager 应用标签（需求 §三-2 的 applicationLabel）
 * → rules.json 的 appName 兜底 → null。
 *
 * 纯函数：所有外部信息（规则类型、规则应用名、系统标签）都由调用方注入，
 * 便于 JVM 单测覆盖每一条分支（PendingSourceTest）。
 */
object PendingSourceResolver {

    /** 与 [PendingMatcher.RELAY_SOURCES] 保持一致的中转来源（漂移由单测约束） */
    const val SMS_SOURCE = "com.parcelhub.sms"
    const val SHARE_SOURCE = "com.parcelhub.share"

    /**
     * @param sourcePackage 事件来源包名（通知监听 = 应用包名；短信 / 分享 = 伪包名）
     * @param channel 显式采集通道（[PendingSourceType.name]）；null = 按包名推断
     * @param ruleType rules.json 该包的 sourceType（如 ECOMMERCE）；未知来源为 null
     * @param ruleAppName rules.json 该包的 appName 兜底显示名
     * @param appLabel PackageManager.applicationLabel（系统应用名）
     */
    fun resolve(
        sourcePackage: String,
        channel: String?,
        ruleType: String?,
        ruleAppName: String?,
        appLabel: String?,
    ): PendingSourceInfo {
        val type = when {
            sourcePackage == SMS_SOURCE -> PendingSourceType.SMS
            sourcePackage == SHARE_SOURCE -> PendingSourceType.USER_INPUT
            !channel.isNullOrBlank() -> PendingSourceType.from(channel)
            ruleType == "ECOMMERCE" -> PendingSourceType.ECOMMERCE
            else -> PendingSourceType.NOTIFICATION
        }

        // 中转来源不携带购物 App 信息（需求 §三-5：SMS 默认只保存 SMS）
        val relay = type == PendingSourceType.SMS || type == PendingSourceType.USER_INPUT
        return PendingSourceInfo(
            type = type,
            packageName = if (relay) {
                null
            } else {
                sourcePackage.takeIf { it.isNotBlank() }
            },
            appName = if (relay) {
                null
            } else {
                appLabel?.takeIf { it.isNotBlank() } ?: ruleAppName?.takeIf { it.isNotBlank() }
            },
        )
    }
}

/**
 * 来源显示名（需求 §十一 来源显示）：待补全卡片与详情页共用的取值顺序。
 * `sourceAppName` → 类型兜底（短信 / 手动分享）→ 「未知」（需求：来源未知也不能隐藏字段）。
 */
fun pendingSourceLabel(
    sourceAppName: String?,
    sourceType: String?,
): String =
    sourceAppName?.takeIf { it.isNotBlank() }
        ?: when (PendingSourceType.from(sourceType)) {
            PendingSourceType.SMS -> "短信"
            PendingSourceType.USER_INPUT -> "手动分享"
            else -> "未知"
        }
