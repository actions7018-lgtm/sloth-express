/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.parcelhub.data.entity.ParcelEventEntity
import com.parcelhub.data.entity.QueryTaskEntity
import com.parcelhub.data.entity.RuleEntity
import com.parcelhub.data.entity.ShipmentEntity
import com.parcelhub.data.entity.SourceAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ShipmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(shipment: ShipmentEntity): Long

    @Update
    suspend fun update(shipment: ShipmentEntity)

    @Query("SELECT * FROM shipments WHERE id = :id")
    suspend fun findById(id: Long): ShipmentEntity?

    @Query("SELECT * FROM shipments WHERE id = :id")
    fun observeById(id: Long): Flow<ShipmentEntity?>

    @Query("SELECT * FROM shipments WHERE tracking_number = :tn LIMIT 1")
    suspend fun findByTrackingNumber(tn: String): ShipmentEntity?

    @Query("SELECT * FROM shipments WHERE tracking_number = :tn LIMIT 1")
    fun observeByTrackingNumber(tn: String): Flow<ShipmentEntity?>

    @Query("SELECT * FROM shipments ORDER BY updated_at DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<ShipmentEntity>

    @Query("SELECT * FROM shipments ORDER BY updated_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ShipmentEntity>>

    @Query("SELECT * FROM shipments ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<ShipmentEntity>>

    @Query("SELECT status, COUNT(*) AS count FROM shipments GROUP BY status")
    fun observeStatusCounts(): Flow<List<StatusCount>>

    @Query("SELECT COUNT(*) FROM shipments")
    suspend fun count(): Int

    @Query("DELETE FROM shipments WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * 标记已取件：**只按主键 id 更新**，返回受影响行数（0 = 这行不存在 / 没更新成功）。
     *
     * 用 @Query 而不是 @Update：@Update 不返回行数，无法判断“是否真的写进去了”，
     * 出问题时只能靠 UI 假装成功。平台状态 status 不动（SOP §4.3 平台/用户状态分离）。
     */
    @Query(
        "UPDATE shipments SET user_status = :status, picked_up_at = :pickedUpAt, " +
            "updated_at = :now WHERE id = :id",
    )
    suspend fun markPickedUp(id: Long, status: String, pickedUpAt: Long, now: Long): Int

    /**
     * 其他用户状态（未处理 / 已忽略）：同样按 id 更新并返回行数；
     * 取消已取件时把 picked_up_at 清回 NULL。
     */
    @Query(
        "UPDATE shipments SET user_status = :status, picked_up_at = NULL, " +
            "updated_at = :now WHERE id = :id",
    )
    suspend fun updateUserStatus(id: Long, status: String, now: Long): Int
}

data class StatusCount(
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "count") val count: Int,
)

@Dao
interface ParcelEventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: ParcelEventEntity): Long

    @Query("SELECT * FROM parcel_events WHERE id = :id")
    suspend fun findById(id: Long): ParcelEventEntity?

    @Query("SELECT shipment_id FROM parcel_events WHERE dedup_key = :key LIMIT 1")
    suspend fun findShipmentIdByDedupKey(key: String): Long?

    @Query("SELECT * FROM parcel_events WHERE shipment_id = :shipmentId ORDER BY event_time DESC")
    fun observeByShipment(shipmentId: Long): Flow<List<ParcelEventEntity>>

    @Query("SELECT * FROM parcel_events WHERE shipment_id = :shipmentId ORDER BY event_time DESC")
    suspend fun listByShipment(shipmentId: Long): List<ParcelEventEntity>

    @Query(
        "SELECT * FROM parcel_events WHERE order_key = :orderKey " +
            "ORDER BY event_time DESC LIMIT 1",
    )
    suspend fun findLatestByOrderKey(orderKey: String): ParcelEventEntity?

    /** 三级匹配候选窗口：同来源 + 时间窗内 */
    @Query(
        "SELECT * FROM parcel_events WHERE source_package = :pkg " +
            "AND event_time BETWEEN :from AND :to ORDER BY event_time DESC LIMIT 20",
    )
    suspend fun findCandidates(pkg: String, from: Long, to: Long): List<ParcelEventEntity>

    @Query("SELECT COUNT(*) FROM parcel_events")
    suspend fun count(): Int
}

@Dao
interface SourceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: SourceAppEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sources: List<SourceAppEntity>)

    @Query("SELECT * FROM source_apps ORDER BY app_name ASC")
    fun observeAll(): Flow<List<SourceAppEntity>>

    @Query("SELECT * FROM source_apps")
    suspend fun listAll(): List<SourceAppEntity>

    @Query("UPDATE source_apps SET enabled = :enabled WHERE package_name = :pkg")
    suspend fun setEnabled(pkg: String, enabled: Boolean)

    @Query("SELECT enabled FROM source_apps WHERE package_name = :pkg")
    suspend fun isEnabled(pkg: String): Boolean?
}

@Dao
interface QueryTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: QueryTaskEntity)

    @Query("SELECT * FROM query_tasks WHERE task_id = :taskId LIMIT 1")
    suspend fun findById(taskId: String): QueryTaskEntity?

    /**
     * 同单号的有效任务（SOP §10.1）：完成 / 失败 / 取消的不算。
     * 用于“已有有效任务就不重复创建”。
     */
    @Query(
        "SELECT * FROM query_tasks WHERE tracking_number = :tn " +
            "AND status NOT IN (:terminal) ORDER BY created_at DESC LIMIT 1",
    )
    suspend fun findActiveByTrackingNumber(tn: String, terminal: List<String>): QueryTaskEntity?

    @Query(
        "SELECT * FROM query_tasks WHERE tracking_number = :tn " +
            "ORDER BY created_at DESC LIMIT :limit",
    )
    fun observeByTrackingNumber(tn: String, limit: Int): Flow<List<QueryTaskEntity>>

    @Query("SELECT * FROM query_tasks ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<QueryTaskEntity>>

    @Query(
        "UPDATE query_tasks SET status = :status, updated_at = :now, last_action = :action " +
            "WHERE task_id = :taskId",
    )
    suspend fun updateStatus(taskId: String, status: String, action: String?, now: Long)

    @Query(
        "UPDATE query_tasks SET status = :status, retry_count = :retry, failure_reason = :reason, " +
            "updated_at = :now WHERE task_id = :taskId",
    )
    suspend fun fail(taskId: String, status: String, retry: Int, reason: String?, now: Long)

    @Query("SELECT COUNT(*) FROM query_tasks")
    suspend fun count(): Int

    /** 上次自动查询时间（冷却基准，EXPRESS_SMART_QUERY SOP §9） */
    @Query("SELECT MAX(created_at) FROM query_tasks WHERE tracking_number = :tn")
    suspend fun lastTaskAt(tn: String): Long?

    /**
     * 当前任务之前的上次查询时间。
     *
     * 二次判断（SOP §10.2）必须用它做冷却基准，否则刚建的任务会把自己算进冷却，
     * 导致每次都被误取消。
     */
    @Query(
        "SELECT MAX(created_at) FROM query_tasks WHERE tracking_number = :tn " +
            "AND task_id != :excludeTaskId",
    )
    suspend fun lastTaskAtBefore(tn: String, excludeTaskId: String): Long?
}

@Dao
interface RuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: RuleEntity)

    @Query("SELECT * FROM parser_rules WHERE enabled = 1")
    suspend fun listEnabled(): List<RuleEntity>

    @Query("SELECT * FROM parser_rules WHERE rule_id = :ruleId")
    suspend fun findById(ruleId: String): RuleEntity?
}
