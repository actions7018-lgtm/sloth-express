/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.island

/**
 * 灵动岛自身 UI 状态机的状态（SOP §7）。
 *
 * 流转：HIDDEN → COMPACT → EXPANDED → SUCCESS → COMPACT → HIDDEN
 * 实现见 [IslandStateMachine]。
 */
enum class IslandState {
    /** 没有事件：不显示 */
    HIDDEN,

    /** 小胶囊：`📦 3 个待取` */
    COMPACT,

    /** 事件展开态：到站 / 派送等内容 */
    EXPANDED,

    /** 完成态：`✓ 取件完成`，约 1～2 秒后自动收起 */
    SUCCESS,
}

/** 灵动岛配色语义（浅色 / 圆角 / 柔和渐变，SOP §17） */
enum class IslandTone {
    /** 到站与取件码 */
    ARRIVAL,

    /** 物流状态（派送、签收等） */
    LOGISTICS,

    /** 完成反馈 */
    SUCCESS,
}

/**
 * 灵动岛展示内容。
 *
 * 只放展示所需字段，不携带原始通知文本（SOP §17 UI 规范 + 隐私口径）。
 */
data class IslandContent(
    val shipmentId: Long,
    val title: String,
    val line1: String? = null,
    val line2: String? = null,
    val tone: IslandTone,
) {
    /** 是否为单行胶囊内容（COMPACT 态只显示 title） */
    val isSingleLine: Boolean get() = line1 == null && line2 == null
}
