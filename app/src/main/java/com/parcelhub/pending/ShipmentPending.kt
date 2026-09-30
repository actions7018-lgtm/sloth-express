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
 * 待补全订单状态（SOP §21 PendingShipmentStatus）。
 *
 * 与 [com.parcelhub.model.ShipmentStatus] 完全解耦：
 * 「已发货但没有单号」不是一个物流阶段，而是**等待补全**的处理状态，
 * 不能当成 IN_TRANSIT 展示（SOP §20）。
 */
enum class PendingShipmentStatus {
    /** 商家已发货，尚未发现快递单号 */
    WAITING_TRACKING,

    /** 已从通知 / 短信补全到单号（正式包裹见 [PendingShipment.shipmentId]） */
    TRACKING_FOUND,

    /** 超过 72 小时仍无单号（SOP §24） */
    EXPIRED,

    /** 用户或来源侧明确取消 */
    CANCELLED;

    companion object {
        fun from(raw: String?): PendingShipmentStatus =
            entries.firstOrNull { it.name == raw } ?: WAITING_TRACKING
    }
}

/**
 * 待补全订单（SOP §21 ShipmentPending）。
 *
 * 「商家已发货但当时没有单号」不再直接结束流程（SOP §一 根本原因），
 * 而是落成一条待补全记录，等待后续通知 / 短信补全，超时后 [PendingShipmentStatus.EXPIRED]。
 *
 * 来源识别与详情页字段（需求 §二/§五）落在 **实体层**
 * [com.parcelhub.data.entity.PendingShipmentEntity] 的
 * `sourceType / sourcePackageName / sourceAppName / productTitle / sellerName`；
 * 本领域模型保持 SOP 原貌不重复携带（领域层只服务匹配与补偿决策），
 * 其中商品名 / 商家来自 Mock 数据或未来的结构化订单源，通知文本仍不存原文（SOP §42 隐私口径）。
 * [PendingShipment.shipmentId] 为本仓库新增：把待补全记录绑定到同订单的正式包裹，
 * 补全到单号时直接回填，避免同一订单生成两条 Shipment（SOP 场景 6 / 7）。
 */
data class PendingShipment(
    val id: Long = 0L,
    /** 平台订单号 / 订单关联 ID（一级匹配键，SOP §30） */
    val orderKey: String? = null,
    /** 发现「已发货」的来源平台（购物 App 包名；短信等中转来源为 null） */
    val platform: String? = null,
    /** 同订单已创建的正式包裹；补全时回填单号的目标 */
    val shipmentId: Long? = null,
    /** 商家发货时间（事件时间） */
    val shippedAt: Long,
    /** 补全到的单号；未补全为 null */
    val trackingNumber: String? = null,
    val carrierCode: String? = null,
    /** 上次补偿检查时间 */
    val lastCheckAt: Long? = null,
    /** 下次低频补偿检查时间（SOP §24：24h → 48h → 72h） */
    val nextCheckAt: Long? = null,
    /** 过期时间：发货后 72 小时（SOP §24） */
    val expireAt: Long,
    val status: PendingShipmentStatus = PendingShipmentStatus.WAITING_TRACKING,
) {
    /** 是否仍在等待补全（首页只展示这个状态，SOP §25 没有待补全就不安排任务） */
    val isWaiting: Boolean get() = status == PendingShipmentStatus.WAITING_TRACKING
}
