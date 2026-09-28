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
 * 放置位置文本 → [PlacementType] 分类（SOP §3 / §5 的「驿站 / 快递柜 / 家门口」判定依据）。
 *
 * 词表与 `rules.json → locations.placePatterns` 的落点词保持一致（站点后缀 + 门口投放词），
 * 规则文件负责「从通知里抽出地点」，本类只负责「把地点归类」，两者不互相越界。
 *
 * 纯字符串匹配、无 IO、无反射。
 */
object PlacementClassifier {

    /**
     * 判定顺序：柜 → 站 → 办公 → 门口。
     *
     * 必须先判柜与站再判门口：否则「驿站门口」会被「门口」抢走，误判成 DOOR。
     */
    private val LOCKER_WORDS = listOf(
        "快递柜", "自提柜", "智能柜", "丰巢", "柜机", "格口",
    )

    private val STATION_WORDS = listOf(
        "驿站", "代收点", "快递超市", "服务站", "服务点", "自提点",
        "门卫室", "门卫", "传达室", "物业", "店里", "店内",
    )

    private val OFFICE_WORDS = listOf(
        "前台", "办公桌", "办公室", "工位", "收发室", "公司",
    )

    private val DOOR_WORDS = listOf(
        "家门口", "门口", "门前", "上门", "入户", "家里", "阳台",
    )

    /** 把地点文本归类为 [PlacementType]，空值或认不出时返回 [PlacementType.UNKNOWN] */
    fun classify(place: String?): PlacementType {
        if (place.isNullOrBlank()) return PlacementType.UNKNOWN
        val text = place.trim()
        if (LOCKER_WORDS.any { text.contains(it) }) return PlacementType.LOCKER
        if (STATION_WORDS.any { text.contains(it) }) return PlacementType.STATION
        if (OFFICE_WORDS.any { text.contains(it) }) return PlacementType.OFFICE
        if (DOOR_WORDS.any { text.contains(it) }) return PlacementType.DOOR
        return PlacementType.UNKNOWN
    }
}
