/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.widget

import android.content.Context
import com.parcelhub.data.db.AppDatabase

/**
 * Widget 数据源（用户需求里的「Widget 数据 Repository」）。
 *
 * 唯一数据来源是 App 自己的 Room 数据库 —— Widget 不另建表、不缓存快照，
 * 因此数据库、App 页面、Widget 三者的状态天然一致（需求 1 / 13 / 17）。
 *
 * 只在真正需要渲染时读一次，读完即用；没有轮询、没有常驻内存副本。
 */
object TodoWidgetData {

    /** Widget 一次渲染最多读取的行数（防止极端数据量下把 Binder 事务撑爆） */
    private const val MAX_ROWS = 200

    suspend fun load(
        context: Context,
        feedbackIds: Set<Long> = emptySet(),
        anchors: Map<Long, Int> = emptyMap(),
    ): List<TodoWidgetItem> {
        val rows = AppDatabase.get(context).shipmentDao().page(MAX_ROWS, 0)
        return TodoWidgetItem.from(rows, feedbackIds, anchors)
    }
}
