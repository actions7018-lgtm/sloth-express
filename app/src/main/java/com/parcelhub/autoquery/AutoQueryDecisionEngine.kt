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
 * 自动查询决策引擎（EXPRESS_SMART_QUERY SOP §4 决策优先级 / §5 关键规则 / §6 信息来源 / §9 冷却）。
 *
 * 纯函数、无 IO、无网络：只回答「现在要不要查」。
 * 查询链路（菜鸟 → 对应物流小程序 → 网页兜底，SOP §8）由后续的查询服务负责，本类不碰网络。
 *
 * 决策优先级（SOP §4，自上而下短路）：
 * ```
 * 自动查询开关 → 有效物流单号 → 家门口已送达 → 已取件 → 已有取件码
 * → 驿站/快递柜无取件码 → 运输中 → 派送中未形成最终结果 → 只有单号 → 状态未知/信息不足 → 冷却判断
 * ```
 */
object AutoQueryDecisionEngine {

    /** 自动查询默认冷却：10 分钟（SOP §9） */
    const val COOLDOWN_DEFAULT_MS: Long = 10 * 60 * 1000L

    /**
     * 信息来源优先级（SOP §6）：`sourcePlace > actualPlace > preferredPlace`。
     *
     * 取第一个非空来源再分类；[AutoQueryInput.preferredPlace]（用户默认放置位置）只在
     * 平台两处位置都为空时才生效，永远不会覆盖平台实际显示的位置（SOP §11.4）。
     */
    fun resolvePlacement(input: AutoQueryInput): PlacementType {
        val place = input.sourcePlace.takeUnless { it.isNullOrBlank() }
            ?: input.actualPlace.takeUnless { it.isNullOrBlank() }
            ?: input.preferredPlace
        return PlacementClassifier.classify(place)
    }

    /**
     * 生效位置：先用三处位置文本判定（SOP §6），取不到时才回落到已存的
     * [AutoQueryInput.deliveryMode]（SOP §2），保证新证据优先于历史归类。
     */
    fun effectivePlacement(input: AutoQueryInput): PlacementType {
        val fromPlace = resolvePlacement(input)
        if (fromPlace != PlacementType.UNKNOWN) return fromPlace
        return when (input.deliveryMode) {
            DeliveryMode.STATION -> PlacementType.STATION
            DeliveryMode.LOCKER -> PlacementType.LOCKER
            DeliveryMode.DOOR -> PlacementType.DOOR
            DeliveryMode.OFFICE -> PlacementType.OFFICE
            DeliveryMode.UNKNOWN -> PlacementType.UNKNOWN
        }
    }

    /** 是否处于自动查询冷却（SOP §9）：同一单号 10 分钟内不重复自动查询 */
    fun isCoolingDown(input: AutoQueryInput): Boolean {
        if (input.manual) return false          // 用户主动查询不受冷却限制（SOP §11.6）
        if (input.lastAutoQueryAt <= 0L) return false   // 从未查询过
        // 时间倒挂（改系统时间）时同样按冷却处理，宁可少查一次
        return (input.now - input.lastAutoQueryAt) < input.cooldownMillis
    }

    /**
     * 决策入口（SOP §4）。
     *
     * @return [AutoQueryDecisionType.QUERY] 需查询；命中冷却时改判为
     * [AutoQueryDecisionType.COOLDOWN]；其余为 [AutoQueryDecisionType.NOT_REQUIRED] /
     * [AutoQueryDecisionType.DISABLED]
     */
    fun decide(input: AutoQueryInput): AutoQueryDecisionType {
        val base = decideWithoutCooldown(input)
        // 冷却判断放在整条优先级链的最后（SOP §4）
        if (base == AutoQueryDecisionType.QUERY && isCoolingDown(input)) {
            return AutoQueryDecisionType.COOLDOWN
        }
        return base
    }

    /** 不含冷却判断的优先级链（SOP §4 的第 1~10 步） */
    private fun decideWithoutCooldown(input: AutoQueryInput): AutoQueryDecisionType {
        val notRequired = AutoQueryDecisionType.NOT_REQUIRED
        val query = AutoQueryDecisionType.QUERY

        // 1) 自动查询开关
        if (!input.autoQueryEnabled) return AutoQueryDecisionType.DISABLED

        // 2) 有效物流单号：无单号 → 不查询（SOP §5）
        if (input.trackingNumber.isNullOrBlank()) return notRequired

        // 3) 家门口已送达；含「已送达但地点未知」（SOP §1 / §5 / TC-001、TC-002、TC-010）
        if (input.deliveryStatus == DeliveryStatus.DELIVERED_DOOR) return notRequired

        // 4) 已取件（SOP §5 / TC-014）
        if (input.deliveryStatus == DeliveryStatus.PICKED_UP) return notRequired

        // 5) 已有取件码 → 不自动查询（SOP §1 / §5 / TC-004、TC-013）
        if (!input.pickupCode.isNullOrBlank()) return notRequired

        val placement = effectivePlacement(input)

        // 6) 驿站 / 快递柜 + 无取件码，或状态已是「已到站」（SOP §5 / TC-003、TC-005）
        if (placement == PlacementType.STATION ||
            placement == PlacementType.LOCKER ||
            input.deliveryStatus == DeliveryStatus.ARRIVED_STATION
        ) {
            return query
        }

        // 7) 运输中（SOP §5 / TC-007）
        if (input.deliveryStatus == DeliveryStatus.IN_TRANSIT) return query

        // 8) 派送中且未形成最终配送结果（SOP §5 / TC-008、TC-009）
        if (input.deliveryStatus == DeliveryStatus.OUT_FOR_DELIVERY) return query

        // 9) 只有单号：无地点、无状态、无取件码 → 兜底查询（SOP §1 / TC-006）
        if (placement == PlacementType.UNKNOWN &&
            (input.deliveryStatus == DeliveryStatus.WAITING ||
                input.deliveryStatus == DeliveryStatus.UNKNOWN)
        ) {
            return query
        }

        // 10) 状态未知 / 信息不足 → 不查询（SOP §4）
        return notRequired
    }
}
