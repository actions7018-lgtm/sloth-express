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
 * 待补全订单的延迟补偿决策（SOP §23 ShipmentReconciliationWorker / §24 补偿时间）。
 *
 * 只做「读表 → 决策」的纯函数部分，落库与唤醒由
 * [com.parcelhub.data.repository.ShipmentRepository.reconcilePending] /
 * ServiceLocator 的低频循环完成。
 *
 * 节奏（SOP §24）：
 *  - 0～24h：主要等待通知 / 短信事件（事件驱动，不轮询）；
 *  - 24h / 48h：各做一次低频检查（发现单号则补全、否则顺延）；
 *  - 超过 72h：EXPIRED（SOP §24 / 场景 9）。
 *
 * 禁止高频轮询（SOP §25）：没有任何 WAITING_TRACKING 时**不安排任何检查任务**。
 */
object ShipmentReconciler {

    /** 发货后第一次低频补偿检查 */
    const val FIRST_CHECK_DELAY_MS: Long = 24L * 60 * 60 * 1000

    /** 第二次低频补偿检查 */
    const val SECOND_CHECK_DELAY_MS: Long = 48L * 60 * 60 * 1000

    /** 过期时间：发货后 72 小时 */
    const val EXPIRE_DELAY_MS: Long = 72L * 60 * 60 * 1000

    enum class Decision {
        /** 未到检查点，保持原样 */
        KEEP,

        /** 到检查点但仍未补全到单号：顺延到下一个检查点 */
        RESCHEDULE,

        /** 补全到单号（待补全记录自身有单号，或绑定的正式包裹已有单号） */
        FOUND,

        /** 超过 72 小时：标记 EXPIRED（SOP 场景 9） */
        EXPIRE,
    }

    data class Plan(
        val decision: Decision,
        /** 需要写回的新 nextCheckAt（其余决策为 null） */
        val nextCheckAt: Long? = null,
        /** 需要写回的 lastCheckAt（KEEP 为 null） */
        val lastCheckAt: Long? = null,
        /** FOUND 时要写回的单号（取自正式包裹） */
        val trackingNumber: String? = null,
    )

    /** 新建待补全记录时的初始计划（SOP §24） */
    fun initial(shippedAt: Long): Pair<Long, Long> {
        val nextCheckAt = shippedAt + FIRST_CHECK_DELAY_MS
        val expireAt = shippedAt + EXPIRE_DELAY_MS
        return nextCheckAt to expireAt
    }

    /**
     * 计算某条待补全记录在 [now] 时刻应执行的动作。
     *
     * @param shipmentTracking 绑定正式包裹的单号（没绑定或未补全为 null）
     */
    fun decide(pending: PendingShipment, shipmentTracking: String?, now: Long): Plan {
        if (!pending.isWaiting) return Plan(Decision.KEEP)

        // 1) 已补到单号 → FOUND（自身记录优先，其次看绑定的正式包裹）
        val found = pending.trackingNumber?.takeIf { it.isNotBlank() }
            ?: shipmentTracking?.takeIf { it.isNotBlank() }
        if (found != null) {
            return Plan(Decision.FOUND, lastCheckAt = now, trackingNumber = found)
        }

        // 2) 超时 → EXPIRE（先判过期，72h 后不再顺延）
        if (now >= pending.expireAt) {
            return Plan(Decision.EXPIRE, lastCheckAt = now)
        }

        // 3) 到检查点 → 顺延：24h 检查点 → 48h，48h 检查点 → expireAt
        val next = pending.nextCheckAt ?: (pending.shippedAt + FIRST_CHECK_DELAY_MS)
        if (now >= next) {
            val second = pending.shippedAt + SECOND_CHECK_DELAY_MS
            val following = if (next < second) second else pending.expireAt
            return Plan(Decision.RESCHEDULE, nextCheckAt = following, lastCheckAt = now)
        }

        // 4) 未到检查点
        return Plan(Decision.KEEP, nextCheckAt = pending.nextCheckAt)
    }
}
