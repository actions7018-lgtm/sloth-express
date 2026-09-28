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
import com.parcelhub.autoquery.PlacementClassifier
import com.parcelhub.autoquery.PlacementType
import com.parcelhub.model.ShipmentStatus
import com.parcelhub.model.UserStatus

/**
 * 包裹表（SOP §10.1 shipments）。
 * 字段与需求文档保持一致，不擅自增删核心实体字段。
 */
@Entity(
    tableName = "shipments",
    indices = [
        Index(value = ["tracking_number"]),
        Index(value = ["status"]),
        Index(value = ["updated_at"]),
    ],
)
data class ShipmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "tracking_number") val trackingNumber: String? = null,
    @ColumnInfo(name = "carrier") val carrier: String? = null,
    @ColumnInfo(name = "source_platform") val sourcePlatform: String? = null,
    @ColumnInfo(name = "status") val status: String = ShipmentStatus.UNKNOWN.name,
    @ColumnInfo(name = "user_status") val userStatus: String = UserStatus.UNPROCESSED.name,
    /** 用户点「已取件」的时间（v3 新增）；未取件为 null。用于已签收时间展示与去重 */
    @ColumnInfo(name = "picked_up_at") val pickedUpAt: Long? = null,
    @ColumnInfo(name = "pickup_code") val pickupCode: String? = null,
    @ColumnInfo(name = "pickup_location") val pickupLocation: String? = null,
    @ColumnInfo(name = "locker_number") val lockerNumber: String? = null,
    @ColumnInfo(name = "address") val address: String? = null,
    @ColumnInfo(name = "latest_event_id") val latestEventId: Long? = null,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long = 0L,
    @ColumnInfo(name = "last_updated_at") val lastUpdatedAt: Long = 0L,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
)

/** 状态字段在数据库中是字符串，UI 统一通过扩展属性取枚举，避免散落的解析逻辑 */
val ShipmentEntity.statusEnum: ShipmentStatus
    get() = ShipmentStatus.from(status)

val ShipmentEntity.userStatusEnum: UserStatus
    get() = UserStatus.from(userStatus)

/** 是否处于“待取”（到站 / 入柜 / 有取件码），首页优先展示（SOP §13.1） */
val ShipmentEntity.isPickupReady: Boolean
    get() = statusEnum == ShipmentStatus.ARRIVED ||
        statusEnum == ShipmentStatus.PICKUP_READY ||
        !pickupCode.isNullOrEmpty()

val ShipmentEntity.isOutForDelivery: Boolean
    get() = statusEnum == ShipmentStatus.OUT_FOR_DELIVERY

val ShipmentEntity.isFinished: Boolean
    get() = statusEnum == ShipmentStatus.DELIVERED ||
        statusEnum == ShipmentStatus.RETURNED ||
        statusEnum == ShipmentStatus.CANCELLED

/**
 * 是否投放到「家门口 / 门口」。
 *
 * 这类快递已经送到手上，**不需要用户再去驿站取件**，因此不计入待取待办。
 * 判定优先用放置位置 address（完整落点，如「家门口」），回落到取件地址 pickupLocation。
 * 注意「驿站门口」会被 [PlacementClassifier] 判成驿站（先判站再判门），不会误伤。
 */
val ShipmentEntity.isDeliveredToDoor: Boolean
    get() = PlacementClassifier.classify(
        address?.takeIf { it.isNotBlank() } ?: pickupLocation,
    ) == PlacementType.DOOR

/**
 * 是否应出现在「待取」分组（SOP §4.3）：
 * 平台已签收、用户已取件、用户已忽略、已放在家门口的都不再置顶。
 *
 * 首页待取分组、列表页「待取」筛选、桌面 Widget 待办三处共用这一条口径，
 * 保证数据库 / App 页面 / Widget 三者的“待办”一致（用户需求第 17 条）。
 */
val ShipmentEntity.needsPickup: Boolean
    get() = isPickupReady &&
        !isFinished &&
        !isDeliveredToDoor &&
        userStatusEnum != UserStatus.PICKED_UP &&
        userStatusEnum != UserStatus.DISMISSED

/** 是否属于“已签收”归组：平台终态，或用户已手动取件（SOP §4.3 平台/用户状态分离） */
val ShipmentEntity.isArchived: Boolean
    get() = isFinished || userStatusEnum == UserStatus.PICKED_UP

/**
 * 首页「派送中」分组口径。
 *
 * 必须排除用户已标记取件的包裹：标记已取件后该包裹即归入「已签收」，
 * 不能继续挂在派送中（与列表页「运输中」筛选口径一致，SOP §4.3）。
 */
val ShipmentEntity.isActiveOutForDelivery: Boolean
    get() = isOutForDelivery && userStatusEnum != UserStatus.PICKED_UP

/**
 * 首页「运输中」分组口径：非待取、非派送中、非终态，且用户未标记已取件。
 */
val ShipmentEntity.isActiveInTransit: Boolean
    get() = !isPickupReady &&
        !isOutForDelivery &&
        !isFinished &&
        userStatusEnum != UserStatus.PICKED_UP
