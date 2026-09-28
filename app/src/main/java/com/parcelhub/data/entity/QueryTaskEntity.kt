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
import com.parcelhub.autoquery.QueryTaskStateMachine
import com.parcelhub.autoquery.QueryTaskStatus

/**
 * 自动查询任务表（参考 SOP §9 QueryTask / §29 数据库）。
 *
 * 一个单号同一时刻只允许存在一个有效任务（SOP §10.1 去重），
 * 任务与包裹通过 [shipmentId] 关联（SOP §26 Task 绑定，防并发填错单号）。
 */
@Entity(
    tableName = "query_tasks",
    indices = [
        Index(value = ["tracking_number"]),
        Index(value = ["status"]),
    ],
)
data class QueryTaskEntity(
    @PrimaryKey
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "shipment_id") val shipmentId: Long? = null,
    @ColumnInfo(name = "tracking_number") val trackingNumber: String,
    @ColumnInfo(name = "carrier") val carrier: String? = null,
    @ColumnInfo(name = "status") val status: String = QueryTaskStatus.PENDING.name,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
    @ColumnInfo(name = "last_action") val lastAction: String? = null,
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,
    @ColumnInfo(name = "failure_reason") val failureReason: String? = null,
)

/** 状态列在库中是字符串，读取侧统一走扩展属性 */
val QueryTaskEntity.statusEnum: QueryTaskStatus
    get() = runCatching { QueryTaskStatus.valueOf(status) }.getOrDefault(QueryTaskStatus.PENDING)

/** 是否仍是有效任务（未完成、未失败、未取消） */
val QueryTaskEntity.isActive: Boolean
    get() = QueryTaskStateMachine.isActive(statusEnum)
