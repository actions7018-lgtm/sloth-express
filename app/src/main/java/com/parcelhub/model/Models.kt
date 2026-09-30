/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.model

/**
 * 统一事件模型（SOP §4）。
 *
 * 链路：RawNotification → ParcelEvent → Shipment。
 * 本文件只放纯数据模型，不依赖 Android 框架，便于 JVM 单元测试。
 */

/** 事件来源分类（SOP §4.2 sourceType） */
enum class SourceType {
    ECOMMERCE,
    LOGISTICS,
    COURIER,
    PICKUP_STATION,
    LOCKER,
    OTHER;

    companion object {
        fun from(raw: String?): SourceType =
            entries.firstOrNull { it.name == raw } ?: OTHER
    }
}

/** 快递事件类型（SOP §4.2 eventType） */
enum class EventType {
    SHIPPED,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    ARRIVED,
    LOCKER_STORED,
    PICKUP_CODE_AVAILABLE,
    DELIVERED,
    RETURNED,
    CANCELLED,
    UNKNOWN;

    companion object {
        fun from(raw: String?): EventType =
            entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

/** 平台状态机状态（SOP §11） */
enum class ShipmentStatus {
    UNKNOWN,
    SHIPPED,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    ARRIVED,
    PICKUP_READY,
    DELIVERED,
    RETURNED,
    CANCELLED;

    companion object {
        fun from(raw: String?): ShipmentStatus =
            entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

/** 用户处理状态（与平台状态解耦，SOP §4.3） */
enum class UserStatus {
    UNPROCESSED,
    MARKED_READ,
    PICKED_UP,
    DISMISSED;

    companion object {
        fun from(raw: String?): UserStatus =
            entries.firstOrNull { it.name == raw } ?: UNPROCESSED
    }
}

/**
 * 原始通知（SOP §4.1）。
 * 生命周期极短：过滤 → 入队 → 解析 → 落库后即被丢弃，不做长期缓存（SOP §9.1）。
 */
data class RawNotification(
    val sourcePackage: String,
    val sourceAppName: String?,
    val notificationKey: String,
    val title: String,
    val text: String,
    val bigText: String?,
    val subText: String?,
    val receivedAt: Long,
    val isOngoing: Boolean,
    /**
     * 采集通道（[com.parcelhub.pending.PendingSourceType.name]，如 ACCESSIBILITY）。
     * null = 按来源包名推断（通知监听 → NOTIFICATION、`com.parcelhub.sms` → SMS …），
     * 只有「包名无法表达的通道」才需要显式携带（需求 §三-3 无障碍读取）。
     */
    val channel: String? = null,
) {
    /** 标题与正文的轻量拼接结果，解析层唯一读取的文本入口。 */
    val body: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        buildString {
            if (title.isNotEmpty()) append(title).append('\n')
            if (text.isNotEmpty()) append(text)
            if (!bigText.isNullOrEmpty() && bigText != text) append('\n').append(bigText)
            if (!subText.isNullOrEmpty()) append('\n').append(subText)
        }
    }
}

/**
 * 解析后的快递事件（SOP §4.2）。
 * 尚未绑定 shipmentId；由 ShipmentMatcher 匹配成功后写入。
 */
data class ParcelEvent(
    val shipmentId: Long = 0L,
    val sourceType: SourceType = SourceType.OTHER,
    val sourcePackage: String,
    val notificationKey: String,
    val eventType: EventType = EventType.UNKNOWN,
    val status: ShipmentStatus = ShipmentStatus.UNKNOWN,
    val trackingNumber: String? = null,
    val carrier: String? = null,
    val pickupCode: String? = null,
    val pickupLocation: String? = null,
    val destination: String? = null,
    /** 平台订单号 / 订单关联 ID，用于二级匹配 */
    val orderKey: String? = null,
    /** 三级匹配指纹：normalizedTitle 等，不保存通知全文 */
    val fingerprint: String? = null,
    /** 脱敏摘要（不保存完整通知正文，SOP §22.2） */
    val rawSummary: String = "",
    val confidence: Double = 0.0,
    val eventTime: Long = 0L,
    val createdAt: Long = 0L,
    /** 采集通道（同 [RawNotification.channel]）：解析时原样透传给待补全来源识别 */
    val channel: String? = null,
) {
    /** 合并/去重使用的稳定键：来源 + 通知 key */
    val dedupKey: String get() = "$sourcePackage|$notificationKey"
}

/**
 * 解析结果：事件 + 置信度 + 失败原因（用于诊断页脱敏样本，SOP §19.1）。
 *
 * [events]（SOP V2.0 §7/§8 列表批量）：一页识别出多个订单的单号时逐单成事件；
 * 单事件场景恒为 `[event]`，旧调用方只看 [event] 不受影响。
 */
data class ParseResult(
    val event: ParcelEvent?,
    val confidence: Double = 0.0,
    val reason: String? = null,
    val events: List<ParcelEvent> = listOfNotNull(event),
)

/** 包裹实体（SOP §4.3，产品核心实体） */
data class Shipment(
    val id: Long = 0L,
    val trackingNumber: String? = null,
    val carrier: String? = null,
    val sourcePlatform: String? = null,
    val status: ShipmentStatus = ShipmentStatus.UNKNOWN,
    val userStatus: UserStatus = UserStatus.UNPROCESSED,
    val pickupCode: String? = null,
    val pickupLocation: String? = null,
    val lockerNumber: String? = null,
    val address: String? = null,
    val latestEventId: Long? = null,
    val firstSeenAt: Long = 0L,
    val lastUpdatedAt: Long = 0L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 是否处于“待取”（到站 / 入柜 / 有取件码），首页优先展示 */
    val isPickupReady: Boolean
        get() = status == ShipmentStatus.ARRIVED ||
            status == ShipmentStatus.PICKUP_READY ||
            !pickupCode.isNullOrEmpty()

    val isOutForDelivery: Boolean get() = status == ShipmentStatus.OUT_FOR_DELIVERY

    val isFinished: Boolean
        get() = status == ShipmentStatus.DELIVERED ||
            status == ShipmentStatus.RETURNED ||
            status == ShipmentStatus.CANCELLED
}
