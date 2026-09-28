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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自动查询决策引擎单测（EXPRESS_SMART_QUERY SOP §10 核心测试 TC-001~TC-015）。
 *
 * 决策引擎是纯函数，全部用例不依赖 Android 与网络，可直接 JVM 运行。
 */
class AutoQueryDecisionEngineTest {

    private val now = 1_700_000_000_000L
    private val min = 60_000L

    /** 默认构造：只给单号，其余按「信息不足」处理，各用例只覆盖自己关心的字段 */
    private fun input(
        tracking: String? = "YT1234567890123",
        pickupCode: String? = null,
        status: DeliveryStatus = DeliveryStatus.UNKNOWN,
        mode: DeliveryMode = DeliveryMode.UNKNOWN,
        sourcePlace: String? = null,
        actualPlace: String? = null,
        preferredPlace: String? = null,
        enabled: Boolean = true,
        lastAutoQueryAt: Long = 0L,
        manual: Boolean = false,
        now: Long = this.now,
    ) = AutoQueryInput(
        autoQueryEnabled = enabled,
        trackingNumber = tracking,
        pickupCode = pickupCode,
        sourcePlace = sourcePlace,
        actualPlace = actualPlace,
        preferredPlace = preferredPlace,
        deliveryStatus = status,
        deliveryMode = mode,
        lastAutoQueryAt = lastAutoQueryAt,
        now = now,
        manual = manual,
    )

    private fun decide(i: AutoQueryInput) = AutoQueryDecisionEngine.decide(i)

    // ------------------------------------------------------------------ §10 TC-001~TC-015

    /** TC-001：单号 + 家门口 + 已送达 → NOT_REQUIRED */
    @Test
    fun tc001_tracking_door_delivered_not_required() {
        assertEquals(
            AutoQueryDecisionType.NOT_REQUIRED,
            decide(input(actualPlace = "家门口", status = DeliveryStatus.DELIVERED_DOOR)),
        )
    }

    /** TC-002：默认驿站，但平台实际显示家门口已送达 → NOT_REQUIRED（SOP §11.4 默认位置不覆盖真实位置） */
    @Test
    fun tc002_preferred_station_loses_to_actual_door_delivered() {
        val i = input(
            preferredPlace = "余杭区东连街道菜鸟驿站",
            actualPlace = "家门口",
            status = DeliveryStatus.DELIVERED_DOOR,
        )
        assertEquals(PlacementType.DOOR, AutoQueryDecisionEngine.resolvePlacement(i))
        assertEquals(AutoQueryDecisionType.NOT_REQUIRED, decide(i))
    }

    /** TC-003：驿站 + 无取件码 + 已到站 → QUERY */
    @Test
    fun tc003_station_no_pickup_code_arrived_query() {
        assertEquals(
            AutoQueryDecisionType.QUERY,
            decide(
                input(
                    sourcePlace = "余杭区东连街道菜鸟驿站",
                    status = DeliveryStatus.ARRIVED_STATION,
                ),
            ),
        )
    }

    /** TC-004：驿站 + 有取件码 → NOT_REQUIRED */
    @Test
    fun tc004_station_with_pickup_code_not_required() {
        assertEquals(
            AutoQueryDecisionType.NOT_REQUIRED,
            decide(
                input(
                    sourcePlace = "菜鸟驿站",
                    pickupCode = "5-6-1122",
                    status = DeliveryStatus.ARRIVED_STATION,
                ),
            ),
        )
    }

    /** TC-005：快递柜 + 无取件码 → QUERY */
    @Test
    fun tc005_locker_without_pickup_code_query() {
        assertEquals(
            AutoQueryDecisionType.QUERY,
            decide(input(sourcePlace = "丰巢快递柜", status = DeliveryStatus.UNKNOWN)),
        )
    }

    /** TC-006：只有单号 → QUERY（兜底查询） */
    @Test
    fun tc006_tracking_number_only_query() {
        assertEquals(AutoQueryDecisionType.QUERY, decide(input(status = DeliveryStatus.WAITING)))
        assertEquals(AutoQueryDecisionType.QUERY, decide(input(status = DeliveryStatus.UNKNOWN)))
    }

    /** TC-007：运输中 → QUERY */
    @Test
    fun tc007_in_transit_query() {
        assertEquals(
            AutoQueryDecisionType.QUERY,
            decide(input(status = DeliveryStatus.IN_TRANSIT)),
        )
    }

    /** TC-008：派送中但未最终送达 → QUERY */
    @Test
    fun tc008_out_for_delivery_query() {
        assertEquals(
            AutoQueryDecisionType.QUERY,
            decide(input(status = DeliveryStatus.OUT_FOR_DELIVERY)),
        )
    }

    /** TC-009：家门口 + 派送中 → QUERY（还没送到，不能因为是门口就放弃） */
    @Test
    fun tc009_door_out_for_delivery_query() {
        assertEquals(
            AutoQueryDecisionType.QUERY,
            decide(
                input(actualPlace = "家门口", status = DeliveryStatus.OUT_FOR_DELIVERY),
            ),
        )
    }

    /** TC-010：已送达但地点未知 → NOT_REQUIRED */
    @Test
    fun tc010_delivered_with_unknown_place_not_required() {
        assertEquals(
            AutoQueryDecisionType.NOT_REQUIRED,
            decide(input(status = DeliveryStatus.DELIVERED_DOOR)),
        )
    }

    /** TC-011：5 分钟前已自动查询 → COOLDOWN */
    @Test
    fun tc011_queried_five_minutes_ago_cooldown() {
        assertEquals(
            AutoQueryDecisionType.COOLDOWN,
            decide(
                input(
                    sourcePlace = "菜鸟驿站",
                    status = DeliveryStatus.ARRIVED_STATION,
                    lastAutoQueryAt = now - 5 * min,
                ),
            ),
        )
    }

    /** TC-012：11 分钟前已自动查询 → QUERY（冷却已过） */
    @Test
    fun tc012_queried_eleven_minutes_ago_query() {
        assertEquals(
            AutoQueryDecisionType.QUERY,
            decide(
                input(
                    sourcePlace = "菜鸟驿站",
                    status = DeliveryStatus.ARRIVED_STATION,
                    lastAutoQueryAt = now - 11 * min,
                ),
            ),
        )
    }

    /** TC-013：有取件码 + 状态未知 → NOT_REQUIRED */
    @Test
    fun tc013_pickup_code_with_unknown_status_not_required() {
        assertEquals(
            AutoQueryDecisionType.NOT_REQUIRED,
            decide(input(pickupCode = "9988", status = DeliveryStatus.UNKNOWN)),
        )
    }

    /** TC-014：已取件 → NOT_REQUIRED */
    @Test
    fun tc014_picked_up_not_required() {
        assertEquals(
            AutoQueryDecisionType.NOT_REQUIRED,
            decide(
                input(
                    sourcePlace = "菜鸟驿站",
                    pickupCode = "9988",
                    status = DeliveryStatus.PICKED_UP,
                ),
            ),
        )
    }

    /** TC-015：自动查询关闭 → DISABLED */
    @Test
    fun tc015_auto_query_disabled() {
        assertEquals(
            AutoQueryDecisionType.DISABLED,
            decide(
                input(
                    enabled = false,
                    sourcePlace = "菜鸟驿站",
                    status = DeliveryStatus.ARRIVED_STATION,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ SOP §5 / §6 / §9 / §11 补充

    /** SOP §5：无单号 → 不查询 */
    @Test
    fun no_tracking_number_not_required() {
        assertEquals(
            AutoQueryDecisionType.NOT_REQUIRED,
            decide(input(tracking = null, status = DeliveryStatus.IN_TRANSIT)),
        )
        assertEquals(
            AutoQueryDecisionType.NOT_REQUIRED,
            decide(input(tracking = "   ", status = DeliveryStatus.IN_TRANSIT)),
        )
    }

    /** SOP §5：已取件 / 自动查询关闭 优先于其它一切判定 */
    @Test
    fun switched_off_wins_over_everything() {
        assertEquals(
            AutoQueryDecisionType.DISABLED,
            decide(
                input(
                    enabled = false,
                    status = DeliveryStatus.PICKED_UP,
                    lastAutoQueryAt = now - 5 * min,
                ),
            ),
        )
    }

    /** SOP §6：sourcePlace > actualPlace > preferredPlace */
    @Test
    fun source_beats_actual_beats_preferred() {
        val all = input(
            sourcePlace = "丰巢快递柜",
            actualPlace = "东门代收点",
            preferredPlace = "家门口",
        )
        assertEquals(PlacementType.LOCKER, AutoQueryDecisionEngine.resolvePlacement(all))

        val noSource = input(
            sourcePlace = null,
            actualPlace = "东门代收点",
            preferredPlace = "家门口",
        )
        assertEquals(PlacementType.STATION, AutoQueryDecisionEngine.resolvePlacement(noSource))

        val onlyPreferred = input(
            sourcePlace = null,
            actualPlace = null,
            preferredPlace = "家门口",
        )
        assertEquals(PlacementType.DOOR, AutoQueryDecisionEngine.resolvePlacement(onlyPreferred))
    }

    /** SOP §11.4：用户默认放置位置不得覆盖平台显示的实际位置 */
    @Test
    fun preferred_place_never_overrides_platform_place() {
        val i = input(
            preferredPlace = "余杭区东连街道菜鸟驿站",
            actualPlace = "家门口",
            status = DeliveryStatus.OUT_FOR_DELIVERY,
        )
        assertEquals(PlacementType.DOOR, AutoQueryDecisionEngine.resolvePlacement(i))
        assertEquals(AutoQueryDecisionType.QUERY, decide(i))
    }

    /** SOP §2：三处位置文本都为空时，才回落到已存的 deliveryMode */
    @Test
    fun delivery_mode_used_only_when_no_place_text() {
        assertEquals(
            AutoQueryDecisionType.QUERY,
            decide(input(mode = DeliveryMode.LOCKER, status = DeliveryStatus.UNKNOWN)),
        )
        assertEquals(
            AutoQueryDecisionType.NOT_REQUIRED,
            decide(
                input(
                    mode = DeliveryMode.STATION,
                    actualPlace = "家门口",
                    status = DeliveryStatus.DELIVERED_DOOR,
                ),
            ),
        )
    }

    /** SOP §5：状态已是「已到站」的包裹，即便没有地点文本也按驿站/柜处理 */
    @Test
    fun arrived_station_without_place_text_still_queries() {
        assertEquals(
            AutoQueryDecisionType.QUERY,
            decide(input(status = DeliveryStatus.ARRIVED_STATION)),
        )
    }

    /** SOP §4 第 10 步：状态未知 / 信息不足 → 不查询 */
    @Test
    fun insufficient_info_not_required() {
        assertEquals(
            AutoQueryDecisionType.NOT_REQUIRED,
            decide(input(actualPlace = "家门口", status = DeliveryStatus.WAITING)),
        )
    }

    /** SOP §11.6：手动查询绕过自动冷却 */
    @Test
    fun manual_query_bypasses_cooldown() {
        assertEquals(
            AutoQueryDecisionType.QUERY,
            decide(
                input(
                    sourcePlace = "菜鸟驿站",
                    status = DeliveryStatus.ARRIVED_STATION,
                    lastAutoQueryAt = now - 5 * min,
                    manual = true,
                ),
            ),
        )
        assertEquals(false, AutoQueryDecisionEngine.isCoolingDown(input(lastAutoQueryAt = now, manual = true)))
    }

    /** SOP §9：从未自动查询过 → 不在冷却中 */
    @Test
    fun never_auto_queried_is_not_in_cooldown() {
        assertEquals(false, AutoQueryDecisionEngine.isCoolingDown(input(lastAutoQueryAt = 0L)))
        assertEquals(true, AutoQueryDecisionEngine.isCoolingDown(input(lastAutoQueryAt = now - 1 * min)))
        assertEquals(false, AutoQueryDecisionEngine.isCoolingDown(input(lastAutoQueryAt = now - 10 * min)))
    }

    /** SOP §3：位置词表归类（与 rules.json → locations.placePatterns 落点词一致） */
    @Test
    fun placement_classifier_covers_known_places() {
        assertEquals(PlacementType.STATION, PlacementClassifier.classify("余杭区东连街道菜鸟驿站"))
        assertEquals(PlacementType.STATION, PlacementClassifier.classify("东门代收点"))
        assertEquals(PlacementType.LOCKER, PlacementClassifier.classify("丰巢快递柜"))
        assertEquals(PlacementType.LOCKER, PlacementClassifier.classify("菜鸟智能柜 3 号格口"))
        assertEquals(PlacementType.DOOR, PlacementClassifier.classify("家门口"))
        assertEquals(PlacementType.OFFICE, PlacementClassifier.classify("公司前台"))
        assertEquals(PlacementType.UNKNOWN, PlacementClassifier.classify(null))
        assertEquals(PlacementType.UNKNOWN, PlacementClassifier.classify("文一西路 969 号"))
        // 「驿站门口」必须归驿站，不能被「门口」抢走
        assertEquals(PlacementType.STATION, PlacementClassifier.classify("驿站门口"))
    }
}
