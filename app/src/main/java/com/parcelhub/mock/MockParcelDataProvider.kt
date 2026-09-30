/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.mock

import com.parcelhub.data.entity.PendingShipmentEntity
import com.parcelhub.data.entity.ShipmentEntity
import com.parcelhub.model.ShipmentStatus
import com.parcelhub.model.UserStatus
import com.parcelhub.pending.PendingShipmentStatus
import com.parcelhub.pending.PendingSourceType

/**
 * 50 条标准 Mock 快递数据 + 1 条 72h 边界数据（纯函数、无 IO、无 Android 依赖）。
 *
 * 用途（开发阶段测试首页 / 列表 / 运行检测 / Widget / 灵动岛 / 状态机 / 待补全 / 自动查询）：
 *  1. 覆盖完整物流生命周期：刚下单 → 待发货 → 已发货等单号 → 运输中 → 派送中 →
 *     驿站待取 → 家门口送达 → 已取件签收；
 *  2. 全部内容为虚构（商品 / 商家 / 单号 / 取件码 / 地址均为 MOCK 造词，无真实手机号与地址）；
 *  3. `source` 全部以 `MOCK` 开头，落库后可按 `LIKE 'MOCK%'` 一键识别与清空。
 *
 * 与正式数据模型的关系（不改数据库结构）：
 *  - 规格状态名（[MockParcelStatus]，如 CREATED / DELIVERING）只存在于本数据层，
 *    落库时经 [toShipmentEntity] 映射到现有 [ShipmentStatus] 枚举
 *    （CREATED→UNKNOWN、ORDER_SHIPPED_WAITING_TRACKING→SHIPPED、DELIVERING→OUT_FOR_DELIVERY、
 *    ARRIVED_STATION→ARRIVED、DELIVERED_HOME/SIGNED→DELIVERED），
 *    这样首页 / Widget / 灵动岛 / 状态机拿到的是**它们本来就认识的状态**，业务逻辑零改动；
 *  - 规格里的 productTitle / sellerName / platform / orderTime / confidence 等
 *    落库范围：platform 与 productTitle/sellerName 随待补全订单进 pending_shipments
 *    （需求 §五详情页展示 + §十去重键），其余只在数据层。
 */
enum class MockParcelStatus {
    /** 刚下单 / 商家已接单（无单号） */
    CREATED,

    /** 商家已发货、等待物流单号（进 ShipmentPending） */
    ORDER_SHIPPED_WAITING_TRACKING,

    IN_TRANSIT,

    DELIVERING,

    ARRIVED_STATION,

    DELIVERED_HOME,

    SIGNED,
}

/** 一条 Mock 订单/快递（规格字段全集；落库映射见 [MockParcelDataProvider.toShipmentEntity]） */
data class MockParcel(
    /** 固定主键：900001…（重复注入不产生重复行，清空按行号/来源删除） */
    val id: Long,
    /** 固定订单键：MOCK-O-0001…（防重复 + 待补全表按它识别 Mock 行） */
    val orderKey: String,
    val platform: String,
    val productTitle: String,
    val sellerName: String,
    val status: MockParcelStatus,
    val trackingNumber: String?,
    val carrierCode: String?,
    val pickupCode: String?,
    val locationText: String?,
    val orderTime: Long,
    val shippedAt: Long?,
    val firstDetectedAt: Long,
    val lastUpdatedAt: Long,
    /** MOCK_ORDER / MOCK_SMS / MOCK_NOTIFICATION / MOCK_ACCESSIBILITY / MOCK_OCR / MOCK_API */
    val source: String,
    val confidence: Double,
    /** 场景批注（A~E / 生命周期分组）：只用于报告与单测，不落库 */
    val scenario: String,
) {
    val isWaitingTracking: Boolean
        get() = status == MockParcelStatus.ORDER_SHIPPED_WAITING_TRACKING
}

object MockParcelDataProvider {

    const val SHIPMENT_ID_BASE = 900_000L
    const val PENDING_ID_BASE = 910_000L

    /** 标准 50 条（场景 E 边界行不计入，规格 §11） */
    const val STANDARD_COUNT = 50

    private const val HOUR = 60 * 60 * 1000L
    private const val DAY = 24 * HOUR

    /** 平台：只用项目 rules.json 已支持的电商来源，轮转分配避免单平台占比过高 */
    private val PLATFORMS = listOf("淘宝", "天猫", "京东", "拼多多", "抖音")

    /**
     * Mock 平台中文名 → rules.json 真实包名（需求 §四「来源：拼多多 >」/ §六 去对应 App 查看）。
     * 只用于 Mock 待补全行的 `source_package_name`，方便真机点开详情直接拉起对应 App。
     */
    private val PLATFORM_PACKAGES = mapOf(
        "淘宝" to "com.taobao.taobao",
        "天猫" to "com.tmall.android",
        "京东" to "com.jingdong.app.mall",
        "拼多多" to "com.xunmeng.pinduoduo",
        "抖音" to "com.ss.android.ugc.aweme",
    )

    /** 快递公司（规格 §三；德邦不在 rules 载体里，carrier 为自由文本照常展示） */
    private val CARRIERS = listOf(
        "顺丰速运", "中通快递", "圆通速递", "申通快递", "韵达速递",
        "极兔速递", "EMS", "京东物流", "德邦物流",
    )

    /** 商品基名 25 个（规格 §四），两两配色变体 → 50 个互不重复的商品标题 */
    private val PRODUCTS = listOf(
        "无线键盘", "鼠标垫", "手机支架", "数据线", "充电头",
        "运动水杯", "保温杯", "耳机", "蓝牙音箱", "台灯",
        "衣架", "收纳盒", "电脑支架", "机械键盘", "充电宝",
        "洗脸巾", "雨伞", "双肩包", "运动毛巾", "拖鞋",
        "零食大礼包", "挂耳咖啡", "猫砂", "宠物玩具", "宠物用品",
    )
    private val COLORS = listOf("深空灰", "米白")

    /**
     * 生成完整数据集：[now] 为基准时间（时间层次相对它回推，单测可传固定值）。
     *
     * 分布（规格 §一，标准 50 条）：
     * 刚下单 8 / 商家已接单 5 / 已发货等单号 5 / 运输中 10 /
     * 派送中 7 / 驿站待取 7 / 家门口送达 4 / 已取件签收 4；
     * 外加 1 条场景 E 边界行（已发货 >72h 仍无单号 → 测 EXPIRED），不计入 50。
     */
    fun parcels(now: Long): List<MockParcel> {
        val out = ArrayList<MockParcel>(STANDARD_COUNT + 1)
        var trackSeq = 0
        var pickupSeq = 0

        fun title(): String {
            val i = out.size
            return "${PRODUCTS[i / 2 % PRODUCTS.size]}（${COLORS[i % COLORS.size]}）"
        }

        fun platform(): String = PLATFORMS[out.size % PLATFORMS.size]

        fun seller(): String = "${platform()}·模拟旗舰店"

        fun confidence(): Double = 0.90 + (out.size % 10) * 0.01

        fun tracking(): String {
            trackSeq++
            return "MOCK" + trackSeq.toString().padStart(8, '0')
        }

        fun pickup(): String {
            pickupSeq++
            return (900_000 + pickupSeq).toString() // 6 位虚构取件码 900001…
        }

        fun add(
            status: MockParcelStatus,
            orderTime: Long,
            shippedAt: Long?,
            firstDetectedAt: Long,
            lastUpdatedAt: Long,
            source: String,
            scenario: String,
            trackingNumber: String? = null,
            carrierCode: String? = null,
            pickupCode: String? = null,
            locationText: String? = null,
        ): MockParcel {
            val seq = out.size + 1
            val parcel = MockParcel(
                id = SHIPMENT_ID_BASE + seq,
                orderKey = "MOCK-O-" + seq.toString().padStart(4, '0'),
                platform = platform(),
                productTitle = title(),
                sellerName = seller(),
                status = status,
                trackingNumber = trackingNumber,
                carrierCode = carrierCode,
                pickupCode = pickupCode,
                locationText = locationText,
                orderTime = orderTime,
                shippedAt = shippedAt,
                firstDetectedAt = firstDetectedAt,
                lastUpdatedAt = lastUpdatedAt,
                source = source,
                confidence = confidence(),
                scenario = scenario,
            )
            out += parcel
            return parcel
        }

        // ---- 1. 刚下单 / 待发货 ×8（今天 1~6 小时内，无单号，scenario §一.1） ----
        repeat(8) { k ->
            val orderTime = now - (1L + k % 6) * HOUR
            add(
                status = MockParcelStatus.CREATED,
                orderTime = orderTime,
                shippedAt = null,
                firstDetectedAt = orderTime,
                lastUpdatedAt = orderTime,
                source = "MOCK_ORDER",
                scenario = "刚下单：订单已提交，等待商家发货",
                locationText = "未发货",
            )
        }

        // ---- 2. 商家已接单 / 待发货 ×5（3 今天 + 2 昨天，无单号，scenario §一.2） ----
        repeat(5) { k ->
            val orderTime = if (k < 3) now - (7L + k) * HOUR else now - (26L + (k - 3) * 4) * HOUR
            add(
                status = MockParcelStatus.CREATED,
                orderTime = orderTime,
                shippedAt = null,
                firstDetectedAt = orderTime,
                lastUpdatedAt = orderTime,
                source = "MOCK_ORDER",
                scenario = "商家已接单：商家正在准备商品",
                locationText = "未发货",
            )
        }

        // ---- 3. 已发货 / 等待物流单号 ×5（全部昨天发货，全部进 ShipmentPending，scenario §一.3 + §5） ----
        // 场景 A~D 对应规格 §六；第 5 条为额外的“昨天已发货”样本（满足 §5 ≥5 条）
        val waitingSpec = listOf(
            Triple(25L, "MOCK_ORDER", "场景A：昨天已发货，无单号，等待补全"),
            Triple(26L, "MOCK_SMS", "场景B：昨天已发货，今天应可由 SMS 补全，仍无单号"),
            Triple(28L, "MOCK_NOTIFICATION", "场景C：昨天已发货，今天应可由通知补全，仍无单号"),
            Triple(30L, "MOCK_ACCESSIBILITY", "场景D：昨天已发货，应可由无障碍/OCR 补全，仍无单号"),
            Triple(32L, "MOCK_OCR", "昨天已发货，无单号，等待补全（第 5 条样本）"),
        )
        waitingSpec.forEach { (hoursAgo, source, scenario) ->
            val shippedAt = now - hoursAgo * HOUR
            add(
                status = MockParcelStatus.ORDER_SHIPPED_WAITING_TRACKING,
                orderTime = shippedAt - 2 * HOUR,
                shippedAt = shippedAt,
                firstDetectedAt = shippedAt,
                lastUpdatedAt = shippedAt,
                source = source,
                scenario = scenario,
            )
        }

        // ---- 4. 运输中 ×10（发货 1~5 天前，有单号） ----
        val transitSources = listOf(
            "MOCK_SMS", "MOCK_SMS", "MOCK_SMS",
            "MOCK_NOTIFICATION", "MOCK_NOTIFICATION", "MOCK_NOTIFICATION",
            "MOCK_API", "MOCK_API", "MOCK_ORDER", "MOCK_ORDER",
        )
        repeat(10) { k ->
            val shippedAt = now - (1L + k % 5) * DAY
            add(
                status = MockParcelStatus.IN_TRANSIT,
                orderTime = shippedAt - 3 * HOUR,
                shippedAt = shippedAt,
                firstDetectedAt = shippedAt,
                lastUpdatedAt = now - (k % 4) * 2 * HOUR,
                source = transitSources[k],
                scenario = "运输中",
                trackingNumber = tracking(),
                carrierCode = CARRIERS[k % CARRIERS.size],
                locationText = "MOCK 转运中心",
            )
        }

        // ---- 5. 派送中 ×7（今天有更新，有单号） ----
        val deliveringSources = listOf(
            "MOCK_NOTIFICATION", "MOCK_NOTIFICATION", "MOCK_NOTIFICATION",
            "MOCK_SMS", "MOCK_SMS", "MOCK_ORDER", "MOCK_ORDER",
        )
        repeat(7) { k ->
            val shippedAt = now - 2 * DAY
            add(
                status = MockParcelStatus.DELIVERING,
                orderTime = shippedAt - 3 * HOUR,
                shippedAt = shippedAt,
                firstDetectedAt = shippedAt,
                lastUpdatedAt = now - (k % 4) * HOUR,
                source = deliveringSources[k],
                scenario = "派送中：快递员正在派送",
                trackingNumber = tracking(),
                carrierCode = CARRIERS[k % CARRIERS.size],
                locationText = "MOCK 配送站",
            )
        }

        // ---- 6. 已到驿站 / 待取 ×7（今天到站，有单号 + 取件码） ----
        val stationSources = listOf(
            "MOCK_SMS", "MOCK_SMS", "MOCK_SMS",
            "MOCK_NOTIFICATION", "MOCK_NOTIFICATION",
            "MOCK_ACCESSIBILITY", "MOCK_ACCESSIBILITY",
        )
        repeat(7) { k ->
            val shippedAt = now - 2 * DAY
            add(
                status = MockParcelStatus.ARRIVED_STATION,
                orderTime = shippedAt - 3 * HOUR,
                shippedAt = shippedAt,
                firstDetectedAt = shippedAt,
                lastUpdatedAt = now - (k % 3) * HOUR,
                source = stationSources[k],
                scenario = "已到驿站：待取件",
                trackingNumber = tracking(),
                carrierCode = CARRIERS[k % CARRIERS.size],
                pickupCode = pickup(),
                locationText = "MOCK 模拟驿站${('A'.code + k).toChar()} 区",
            )
        }

        // ---- 7. 家门口已送达 ×4（今天送达，无取件码，地址=家门口） ----
        val homeSources = listOf("MOCK_SMS", "MOCK_SMS", "MOCK_NOTIFICATION", "MOCK_NOTIFICATION")
        repeat(4) { k ->
            val shippedAt = now - 2 * DAY
            add(
                status = MockParcelStatus.DELIVERED_HOME,
                orderTime = shippedAt - 3 * HOUR,
                shippedAt = shippedAt,
                firstDetectedAt = shippedAt,
                lastUpdatedAt = now - (k + 1) * HOUR,
                source = homeSources[k],
                scenario = "家门口已送达",
                trackingNumber = tracking(),
                carrierCode = CARRIERS[k % CARRIERS.size],
                locationText = "MOCK 测试地址·家门口",
            )
        }

        // ---- 8. 已取件 / 已签收 ×4（1/3/5/7 天前签收，落库 user_status=PICKED_UP） ----
        val signedSources = listOf("MOCK_API", "MOCK_API", "MOCK_SMS", "MOCK_NOTIFICATION")
        repeat(4) { k ->
            val signedAt = now - (1L + k * 2) * DAY
            val shippedAt = signedAt - 2 * DAY
            add(
                status = MockParcelStatus.SIGNED,
                orderTime = shippedAt - 3 * HOUR,
                shippedAt = shippedAt,
                firstDetectedAt = shippedAt,
                lastUpdatedAt = signedAt,
                source = signedSources[k],
                scenario = "已取件 / 已签收",
                trackingNumber = tracking(),
                carrierCode = CARRIERS[k % CARRIERS.size],
                locationText = "MOCK 测试地址·已签收",
            )
        }

        // ---- 边界行（不计入 50）：场景 E——已发货 >72h 仍无单号 → 测 EXPIRED（规格 §6/§9.10） ----
        val eShippedAt = now - 73 * HOUR
        add(
            status = MockParcelStatus.ORDER_SHIPPED_WAITING_TRACKING,
            orderTime = eShippedAt - 2 * HOUR,
            shippedAt = eShippedAt,
            firstDetectedAt = eShippedAt,
            lastUpdatedAt = eShippedAt,
            source = "MOCK_ORDER",
            scenario = "场景E：已发货超过 72 小时仍无单号 → 应转 EXPIRED",
        )
        // 标题唯一化：第 51 行与第 1 行同为无线键盘，换成第三种配色，51 行商品全不重复
        out[out.size - 1] = out[out.size - 1].copy(productTitle = "无线键盘（石墨黑）")

        return out
    }

    /** 固定主键 → 固定待补全主键（900014 → 910014），重复注入不撞行 */
    fun pendingIdFor(shipmentId: Long): Long =
        PENDING_ID_BASE + (shipmentId - SHIPMENT_ID_BASE)

    /** Mock 行 → 正式包裹行（规格状态 → 现有 [ShipmentStatus] 的唯一映射点） */
    fun toShipmentEntity(p: MockParcel): ShipmentEntity = ShipmentEntity(
        id = p.id,
        trackingNumber = p.trackingNumber,
        carrier = p.carrierCode,
        sourcePlatform = p.source,
        status = when (p.status) {
            MockParcelStatus.CREATED -> ShipmentStatus.UNKNOWN.name
            MockParcelStatus.ORDER_SHIPPED_WAITING_TRACKING -> ShipmentStatus.SHIPPED.name
            MockParcelStatus.IN_TRANSIT -> ShipmentStatus.IN_TRANSIT.name
            MockParcelStatus.DELIVERING -> ShipmentStatus.OUT_FOR_DELIVERY.name
            MockParcelStatus.ARRIVED_STATION -> ShipmentStatus.ARRIVED.name
            MockParcelStatus.DELIVERED_HOME, MockParcelStatus.SIGNED -> ShipmentStatus.DELIVERED.name
        },
        userStatus = if (p.status == MockParcelStatus.SIGNED) {
            UserStatus.PICKED_UP.name
        } else {
            UserStatus.UNPROCESSED.name
        },
        pickedUpAt = if (p.status == MockParcelStatus.SIGNED) p.lastUpdatedAt else null,
        pickupCode = p.pickupCode,
        pickupLocation = if (p.status == MockParcelStatus.ARRIVED_STATION) p.locationText else null,
        lockerNumber = null,
        address = if (p.status == MockParcelStatus.DELIVERED_HOME) p.locationText else null,
        latestEventId = null,
        firstSeenAt = p.firstDetectedAt,
        lastUpdatedAt = p.lastUpdatedAt,
        createdAt = p.orderTime,
        updatedAt = p.lastUpdatedAt,
    )

    /** 「已发货无单号」行 → ShipmentPending 行（SOP §21；expire = 发货 +72h） */
    fun toPendingEntity(p: MockParcel): PendingShipmentEntity? {
        val shippedAt = p.shippedAt ?: return null
        if (!p.isWaitingTracking) return null
        return PendingShipmentEntity(
            id = pendingIdFor(p.id),
            orderKey = p.orderKey,
            platform = p.platform,
            shipmentId = p.id,
            shippedAt = shippedAt,
            trackingNumber = null,
            carrier = null,
            status = PendingShipmentStatus.WAITING_TRACKING.name,
            lastCheckAt = null,
            nextCheckAt = shippedAt + 24 * HOUR,
            expireAt = shippedAt + 72 * HOUR,
            // 来源识别（需求 §二）：Mock 行按 MOCK_* 来源标注采集链路，
            // 应用名用平台中文名、包名用 rules.json 真实包名（详情页可直接「去对应 App 查看」）
            sourceType = mockSourceType(p.source).name,
            sourcePackageName = PLATFORM_PACKAGES[p.platform],
            sourceAppName = p.platform,
            // 详情页（需求 §五）：Mock 数据带完整商品 / 商家，真实链路通知解析不到 → null
            productTitle = p.productTitle,
            sellerName = p.sellerName,
            createdAt = shippedAt,
            updatedAt = p.lastUpdatedAt,
        )
    }

    /** Mock 来源串 → 采集链路类型（需求 §二 枚举全覆盖；未知来源按 ECOMMERCE 处理） */
    private fun mockSourceType(source: String): PendingSourceType = when (source) {
        "MOCK_SMS" -> PendingSourceType.SMS
        "MOCK_NOTIFICATION" -> PendingSourceType.NOTIFICATION
        "MOCK_ACCESSIBILITY" -> PendingSourceType.ACCESSIBILITY
        "MOCK_OCR" -> PendingSourceType.SCREEN_OCR
        else -> PendingSourceType.ECOMMERCE // MOCK_ORDER / MOCK_API：购物 App 下单链路
    }
}
