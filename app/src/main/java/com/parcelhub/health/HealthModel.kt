/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.health

/**
 * 自动检测健康状态（检测 SOP §一）。
 *
 * 核心原则（§二十）：
 *  - 权限正常 ≠ 功能一定正常；
 *  - 没有事件 ≠ 功能故障；
 *  - 功能关闭 ≠ ERROR；系统不提供状态 ≠ ERROR；一次失败 ≠ ERROR。
 */
enum class HealthStatus(
    /** 模块行符号（检测 SOP §十六） */
    val symbol: String,
    /** 模块行状态词 */
    val label: String,
    /** 页面顶部总状态词 */
    val overallLabel: String,
) {
    HEALTHY("✓", "正常", "运行正常"),
    WARNING("⚠", "提示", "存在提示"),
    ERROR("✕", "异常", "自动识别存在严重问题"),
    DISABLED("—", "未开启", "全部未开启"),
    UNKNOWN("?", "无法判断", "暂无法判断");

    companion object {
        /**
         * 模块内状态优先级（检测 SOP §二）：
         * ERROR > WARNING > UNKNOWN > HEALTHY > DISABLED。
         */
        private val PRIORITY = mapOf(
            ERROR to 5,
            WARNING to 4,
            UNKNOWN to 3,
            HEALTHY to 2,
            DISABLED to 1,
        )

        fun highest(a: HealthStatus, b: HealthStatus): HealthStatus =
            if (PRIORITY.getValue(a) >= PRIORITY.getValue(b)) a else b
    }
}

/** 模块分类（检测 SOP §三）：核心出错重点提示；可选模块关闭不拖累整体；展示模块不影响数据 */
enum class HealthCategory {
    CORE,
    OPTIONAL,
    DISPLAY,
}

/** 七大模块（检测 SOP §十四 HealthModule） */
enum class HealthModule(
    val displayName: String,
    val category: HealthCategory,
) {
    NOTIFICATION("通知监听", HealthCategory.OPTIONAL),
    SMS("短信识别", HealthCategory.OPTIONAL),
    ACCESSIBILITY("无障碍识别", HealthCategory.OPTIONAL),
    BACKGROUND("后台运行", HealthCategory.CORE),
    PENDING_SHIPMENT("待补全任务", HealthCategory.CORE),
    WIDGET("Widget", HealthCategory.DISPLAY),
    ISLAND("灵动岛", HealthCategory.DISPLAY);
}

/** 证据行（检测 SOP §十三 HealthEvidence 的 UI 投影）：只放结构化字段，不放内容 */
data class HealthDetail(
    val label: String,
    val value: String,
)

/** 异常详情里的跳转动作（检测 SOP §十七 [去设置]） */
enum class HealthAction(val label: String) {
    OPEN_NOTIFICATION_ACCESS("去开启通知使用权"),
    OPEN_ACCESSIBILITY("去开启无障碍"),
    OPEN_SMS_PERMISSION("去开启短信权限"),
    OPEN_BATTERY_SETTINGS("检查电池优化"),
    OPEN_OVERLAY("去开启悬浮授权"),
}

/** 单个模块的检测结果（检测 SOP §十四 ModuleHealth） */
data class ModuleHealth(
    val module: HealthModule,
    val status: HealthStatus,
    /** 行右侧状态词：默认取 [HealthStatus.label]，个别状态可覆盖（如短信「需要授权」） */
    val label: String = status.label,
    /** 一行短语：状态的自然语言解释，禁止把 WARNING 说成“坏了”（§一 WARNING 定义） */
    val summary: String,
    /** 次要说明行（如「系统限制风险：…」）：风险提示与状态分开展示，不混进状态判定 */
    val subLine: String? = null,
    /** 证据明细（§十三），点击模块展开 */
    val details: List<HealthDetail> = emptyList(),
    /** 需要用户处理时的跳转；正常/无需处理为 null */
    val action: HealthAction? = null,
)

/** 一次自动检测的完整结果（检测 SOP §十五/§十六） */
data class HealthReport(
    val overall: HealthStatus,
    val checkedAt: Long,
    val modules: List<ModuleHealth>,
)
