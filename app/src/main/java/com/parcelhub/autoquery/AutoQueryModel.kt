/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.autoquery

/**
 * 快递智能识别与自动查询（模块 `EXPRESS_SMART_QUERY`）核心数据与枚举。
 *
 * - §2 核心数据 → [AutoQueryInput]
 * - §3 枚举 → [PlacementType] / [DeliveryStatus] / [DeliveryMode] / [AutoQueryDecisionType]
 *
 * 本文件只放纯数据模型，不依赖 Android 框架，便于 JVM 单元测试。
 */

/** 放置位置类型（SOP §3）：驿站 / 快递柜 / 家门口 / 办公地点 / 未知 */
enum class PlacementType {
    STATION,
    LOCKER,
    DOOR,
    OFFICE,
    UNKNOWN,
}

/**
 * 配送状态（SOP §3）。
 * 状态链路见 SOP §7：WAITING → IN_TRANSIT → OUT_FOR_DELIVERY → {DELIVERED_DOOR, ARRIVED_STATION → PICKED_UP}。
 */
enum class DeliveryStatus {
    WAITING,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    ARRIVED_STATION,
    DELIVERED_DOOR,
    PICKED_UP,
    UNKNOWN,
}

/** 配送方式（SOP §3）：包裹将被 / 已被放到哪里 */
enum class DeliveryMode {
    STATION,
    LOCKER,
    DOOR,
    OFFICE,
    UNKNOWN,
}

/** 自动查询决策结果（SOP §3） */
enum class AutoQueryDecisionType {
    /** 需要查询（若命中冷却则由引擎改判为 [COOLDOWN]） */
    QUERY,

    /** 自动查询冷却中，不重复查询（SOP §9） */
    COOLDOWN,

    /** 不需要查询（SOP §5 的“不查询”各场景） */
    NOT_REQUIRED,

    /** 自动查询开关关闭（SOP §5） */
    DISABLED,
}

/**
 * 自动查询决策输入（SOP §2 核心数据）。
 *
 * 决策引擎只读取其中与判定相关的字段（单号 / 取件码 / 三处位置 / 配送状态 /
 * 配送方式 / 上次自动查询时间 / 开关），其余字段属于该功能的数据契约，
 * 随输入一并传入，供后续查询链路（SOP §8）与诊断复用。
 *
 * @param autoQueryEnabled 自动查询开关（SOP §4 第 1 优先级）
 * @param preferredPlace 用户默认放置位置（SOP §6：优先级最低，不得覆盖平台位置）
 * @param sourcePlace 平台通知里显示的位置（SOP §6：最高优先级）
 * @param actualPlace 物流里显示的实际位置（SOP §6：次优先级）
 * @param lastAutoQueryAt 上次自动查询时间戳，0 表示从未查询过
 * @param now 决策时刻（毫秒），冷却判断的基准
 * @param manual 是否用户主动查询：不受自动查询冷却限制（SOP §9 / §11.6）
 * @param cooldownMillis 自动查询冷却时长，默认 10 分钟（SOP §9）
 */
data class AutoQueryInput(
    val autoQueryEnabled: Boolean = true,
    val trackingNumber: String? = null,
    val courier: String? = null,
    val pickupCode: String? = null,
    val preferredPlace: String? = null,
    val sourcePlace: String? = null,
    val actualPlace: String? = null,
    val deliveryStatus: DeliveryStatus = DeliveryStatus.UNKNOWN,
    val deliveryMode: DeliveryMode = DeliveryMode.UNKNOWN,
    val latestDescription: String? = null,
    val latestLogisticsTime: Long = 0L,
    val lastAutoQueryAt: Long = 0L,
    val lastQueryResult: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val now: Long = 0L,
    val manual: Boolean = false,
    val cooldownMillis: Long = AutoQueryDecisionEngine.COOLDOWN_DEFAULT_MS,
)
