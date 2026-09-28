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

/**
 * 事件表（SOP §10.1 parcel_events）。
 *
 * 在文档字段基础上补充两列匹配辅助字段：
 *  - dedup_key   ：来源 + 通知 key + 事件类型，防止通知重复更新产生重复事件（SOP §10.3）
 *  - order_key   ：平台订单号，二级匹配用（SOP §5.2）
 *  - fingerprint ：三级匹配指纹（SOP §5.3）
 * 这三列不改变核心实体语义，仅服务于合并算法。
 */
@Entity(
    tableName = "parcel_events",
    indices = [
        Index(value = ["shipment_id"]),
        Index(value = ["notification_key"]),
        Index(value = ["event_time"]),
        Index(value = ["dedup_key"], unique = true),
        Index(value = ["order_key"]),
        Index(value = ["source_package", "event_time"]),
    ],
)
data class ParcelEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "shipment_id") val shipmentId: Long = 0L,
    @ColumnInfo(name = "source_type") val sourceType: String = "OTHER",
    @ColumnInfo(name = "source_package") val sourcePackage: String = "",
    @ColumnInfo(name = "notification_key") val notificationKey: String = "",
    @ColumnInfo(name = "event_type") val eventType: String = "UNKNOWN",
    @ColumnInfo(name = "status") val status: String = "UNKNOWN",
    @ColumnInfo(name = "tracking_number") val trackingNumber: String? = null,
    @ColumnInfo(name = "carrier") val carrier: String? = null,
    @ColumnInfo(name = "pickup_code") val pickupCode: String? = null,
    @ColumnInfo(name = "pickup_location") val pickupLocation: String? = null,
    @ColumnInfo(name = "destination") val destination: String? = null,
    @ColumnInfo(name = "order_key") val orderKey: String? = null,
    @ColumnInfo(name = "fingerprint") val fingerprint: String? = null,
    @ColumnInfo(name = "dedup_key") val dedupKey: String = "",
    @ColumnInfo(name = "raw_summary") val rawSummary: String = "",
    @ColumnInfo(name = "confidence") val confidence: Double = 0.0,
    @ColumnInfo(name = "event_time") val eventTime: Long = 0L,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0L,
)
