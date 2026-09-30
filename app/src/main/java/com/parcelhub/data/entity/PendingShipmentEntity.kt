/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.parcelhub.pending.PendingShipment
import com.parcelhub.pending.PendingShipmentStatus

/**
 * 待补全订单表（SOP §21 ShipmentPending）。
 *
 * 解决 SOP §一 的漏检：「已发货但当时没有单号」不再直接结束流程，
 * 而是落一条 [PendingShipmentStatus.WAITING_TRACKING] 记录，
 * 等后续通知 / 短信补全单号，超过 72 小时转 EXPIRED（SOP §24）。
 */
@Entity(
    tableName = "pending_shipments",
    indices = [
        Index(value = ["status"]),
        Index(value = ["shipped_at"]),
    ],
)
data class PendingShipmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "order_key") val orderKey: String? = null,
    @ColumnInfo(name = "platform") val platform: String? = null,
    /** 同订单的正式包裹；补全到单号时回填它的 tracking_number（防重复建单，SOP 场景 6） */
    @ColumnInfo(name = "shipment_id") val shipmentId: Long? = null,
    @ColumnInfo(name = "shipped_at") val shippedAt: Long = 0L,
    @ColumnInfo(name = "tracking_number") val trackingNumber: String? = null,
    @ColumnInfo(name = "carrier") val carrier: String? = null,
    @ColumnInfo(name = "status") val status: String = PendingShipmentStatus.WAITING_TRACKING.name,
    @ColumnInfo(name = "last_check_at") val lastCheckAt: Long? = null,
    @ColumnInfo(name = "next_check_at") val nextCheckAt: Long? = null,
    @ColumnInfo(name = "expire_at") val expireAt: Long = 0L,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,

    // ---- 来源识别（需求 §二）：这条待补全记录是从哪条采集链路发现的 ----

    /** [com.parcelhub.pending.PendingSourceType] 名；旧数据 / 未识别为 null → UI 显示「未知」 */
    @ColumnInfo(name = "source_type") val sourceType: String? = null,
    /** 来源购物 App 包名（需求 §三）；短信 / 分享等中转来源为 null */
    @ColumnInfo(name = "source_package_name") val sourcePackageName: String? = null,
    /** 来源 App 显示名（如「拼多多」）：系统 applicationLabel，拿不到用 rules.json appName */
    @ColumnInfo(name = "source_app_name") val sourceAppName: String? = null,

    // ---- 详情页展示 + 去重键（需求 §五商品名/商家、§十 orderKey+platform+商品+商家） ----
    // 通知文本里通常没有这两个字段 → 真实链路为 null（详情页显示「未知」），Mock 数据会填。

    @ColumnInfo(name = "product_title") val productTitle: String? = null,
    @ColumnInfo(name = "seller_name") val sellerName: String? = null,
)

/** DB 字符串状态 → 枚举，UI 与仓储统一走这里，避免散落解析逻辑 */
val PendingShipmentEntity.statusEnum: PendingShipmentStatus
    get() = PendingShipmentStatus.from(status)

val PendingShipmentEntity.isWaiting: Boolean
    get() = statusEnum == PendingShipmentStatus.WAITING_TRACKING

fun PendingShipmentEntity.toPending(): PendingShipment = PendingShipment(
    id = id,
    orderKey = orderKey,
    platform = platform,
    shipmentId = shipmentId,
    shippedAt = shippedAt,
    trackingNumber = trackingNumber,
    carrierCode = carrier,
    lastCheckAt = lastCheckAt,
    nextCheckAt = nextCheckAt,
    expireAt = expireAt,
    status = statusEnum,
)
