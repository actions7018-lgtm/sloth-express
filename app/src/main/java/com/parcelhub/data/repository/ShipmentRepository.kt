/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.data.repository

import com.parcelhub.data.db.AppDatabase
import com.parcelhub.data.entity.ParcelEventEntity
import com.parcelhub.data.entity.ShipmentEntity
import com.parcelhub.data.entity.SourceAppEntity
import com.parcelhub.matcher.DedupEngine
import com.parcelhub.matcher.ShipmentMatcher
import com.parcelhub.matcher.StateMachine
import com.parcelhub.model.ParcelEvent
import com.parcelhub.model.ShipmentStatus
import com.parcelhub.model.UserStatus
import com.parcelhub.util.AppLog
import kotlinx.coroutines.flow.Flow

/** 单条事件入库结果，驱动 UI 刷新与提醒决策 */
data class IngestOutcome(
    val kind: Kind,
    val shipmentId: Long,
    val previousStatus: ShipmentStatus,
    val status: ShipmentStatus,
    val statusChanged: Boolean,
    val pickupCode: String?,
    val pickupLocation: String?,
    val pickupChanged: Boolean,
    val confidence: Double,
) {
    enum class Kind {
        /** 新建包裹 */
        NEW_SHIPMENT,

        /** 命中已有包裹并更新 */
        UPDATED,

        /** 重复通知，已忽略 */
        DUPLICATE,

        /** 置信度过低，不创建正式包裹 */
        IGNORED,
    }
}

/**
 * 包裹仓储：SOP §5 合并规则 + §11 状态机的落库实现。
 *
 * 所有写入都由单消费者事件队列串行调用（SOP §7.2），因此内部不加锁。
 */
class ShipmentRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val shipmentDao = db.shipmentDao()
    private val eventDao = db.parcelEventDao()
    private val sourceDao = db.sourceDao()

    /**
     * 用户状态写库结果回调：**灵动岛事件入口**（SOP §9 链路 / §8.3）。
     *
     * 固定调用顺序：`UPDATE → 确认行数 → 本回调 → 刷新 Home/Widget → 灵动岛`。
     * 由 ServiceLocator 注入 `IslandManager`；不注入时为 null，
     * 仓储保持纯数据层、可被 JVM 单测直接使用。
     *
     * `updated = false`（UPDATE 影响 0 行）也会回调，由分发器保证
     * “没写进数据库就不给成功反馈”（SOP §9 / §19）。
     */
    var onUserStatusChanged: ((shipmentId: Long, status: UserStatus, updated: Boolean) -> Unit)? =
        null

    // ---------- 观察（UI 只读 Flow，增量刷新） ----------

    fun observeAll(): Flow<List<ShipmentEntity>> = shipmentDao.observeAll()

    fun observeRecent(limit: Int): Flow<List<ShipmentEntity>> = shipmentDao.observeRecent(limit)

    fun observeStatusCounts() = shipmentDao.observeStatusCounts()

    fun observeShipment(id: Long): Flow<ShipmentEntity?> = shipmentDao.observeById(id)

    fun observeEvents(shipmentId: Long): Flow<List<ParcelEventEntity>> =
        eventDao.observeByShipment(shipmentId)

    fun observeSources(): Flow<List<SourceAppEntity>> = sourceDao.observeAll()

    suspend fun getShipment(id: Long): ShipmentEntity? = shipmentDao.findById(id)

    suspend fun pageShipments(limit: Int, offset: Int): List<ShipmentEntity> =
        shipmentDao.page(limit, offset)

    suspend fun countShipments(): Int = shipmentDao.count()

    suspend fun countEvents(): Int = eventDao.count()

    // ---------- 事件入库 ----------

    /**
     * 处理一条已解析事件：去重 → 匹配 → 状态合并 → 落库。
     *
     * 必须保证：
     *  - 同一包裹的多条通知只更新同一条 Shipment（SOP §28.11）
     *  - 旧通知不覆盖新状态（SOP §28.12）
     */
    suspend fun ingest(event: ParcelEvent): IngestOutcome {
        val now = clock()
        val dedupKey = DedupEngine.eventDedupKey(
            sourcePackage = event.sourcePackage,
            notificationKey = event.notificationKey,
            eventType = event.eventType,
        )

        // 1) 事件层去重：同一通知重复 post
        eventDao.findShipmentIdByDedupKey(dedupKey)?.let { shipmentId ->
            return duplicate(shipmentId, ShipmentStatus.UNKNOWN, event.confidence)
        }

        // 2) 置信度过低：不创建正式包裹（SOP §6.5）
        if (!ShipmentMatcher.canCreateShipment(event.confidence)) {
            AppLog.d("drop low-confidence event: ${event.eventType} ${event.confidence}")
            return IngestOutcome(
                kind = IngestOutcome.Kind.IGNORED,
                shipmentId = 0L,
                previousStatus = ShipmentStatus.UNKNOWN,
                status = ShipmentStatus.UNKNOWN,
                statusChanged = false,
                pickupCode = null,
                pickupLocation = null,
                pickupChanged = false,
                confidence = event.confidence,
            )
        }

        val tracking = ShipmentMatcher.normalizeTracking(event.trackingNumber)
        val eventEntity = event.toEntity(dedupKey, tracking)

        // 3) 匹配目标包裹（一级 → 二级 → 三级）
        var isNew = false
        val targetId = resolveTargetShipment(eventEntity, tracking)
        val current: ShipmentEntity = (targetId?.let { shipmentDao.findById(it) }) ?: run {
            isNew = true
            val id = shipmentDao.insert(
                ShipmentEntity(
                    trackingNumber = tracking,
                    carrier = event.carrier,
                    sourcePlatform = event.sourcePackage,
                    status = ShipmentStatus.UNKNOWN.name,
                    userStatus = UserStatus.UNPROCESSED.name,
                    firstSeenAt = now,
                    lastUpdatedAt = now,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            shipmentDao.findById(id) ?: error("failed to create shipment")
        }

        // 4) 状态合并（只前进，不倒退）
        val previousStatus = ShipmentStatus.from(current.status)
        val nextStatus = StateMachine.resolve(previousStatus, ShipmentStatus.from(eventEntity.status))

        // 5) 取件码 / 地点合并：新信息优先，空值不覆盖已有值（SOP §11.2）
        val (code, location, locker) = ShipmentMatcher.mergePickup(
            currentCode = current.pickupCode,
            currentLocation = current.pickupLocation,
            currentLocker = current.lockerNumber,
            eventCode = eventEntity.pickupCode,
            eventLocation = eventEntity.pickupLocation,
        )
        val pickupChanged = code != current.pickupCode || location != current.pickupLocation

        // 6) 写入事件（绑定 shipment_id）
        val insertedEventId = eventDao.insert(eventEntity.copy(shipmentId = current.id))
        if (insertedEventId < 0L) {
            return duplicate(current.id, previousStatus, event.confidence)
        }

        // 7) 增量更新包裹
        shipmentDao.update(
            current.copy(
                trackingNumber = current.trackingNumber ?: tracking,
                carrier = current.carrier ?: event.carrier,
                sourcePlatform = current.sourcePlatform ?: event.sourcePackage,
                status = nextStatus.name,
                pickupCode = code,
                pickupLocation = location,
                lockerNumber = locker,
                // 放置位置：新信息优先，空值不覆盖已有值（SOP §11.2）
                address = event.destination ?: current.address,
                latestEventId = insertedEventId,
                lastUpdatedAt = now,
                updatedAt = now,
            ),
        )

        val statusChanged = StateMachine.changed(previousStatus, nextStatus)
        if (statusChanged) {
            AppLog.d("shipment ${current.id}: $previousStatus -> $nextStatus")
        }

        return IngestOutcome(
            kind = if (isNew) IngestOutcome.Kind.NEW_SHIPMENT else IngestOutcome.Kind.UPDATED,
            shipmentId = current.id,
            previousStatus = previousStatus,
            status = nextStatus,
            statusChanged = statusChanged,
            pickupCode = code,
            pickupLocation = location,
            pickupChanged = pickupChanged,
            confidence = event.confidence,
        )
    }

    private fun duplicate(
        shipmentId: Long,
        status: ShipmentStatus,
        confidence: Double,
    ) = IngestOutcome(
        kind = IngestOutcome.Kind.DUPLICATE,
        shipmentId = shipmentId,
        previousStatus = status,
        status = status,
        statusChanged = false,
        pickupCode = null,
        pickupLocation = null,
        pickupChanged = false,
        confidence = confidence,
    )

    private suspend fun resolveTargetShipment(
        event: ParcelEventEntity,
        tracking: String?,
    ): Long? {
        // 一级匹配：carrier + trackingNumber（最高优先级）
        if (!tracking.isNullOrBlank()) {
            shipmentDao.findByTrackingNumber(tracking)?.let { return it.id }
        }

        // 二级匹配：平台订单号 / 事件关联 ID
        val orderKey = event.orderKey
        if (!orderKey.isNullOrBlank()) {
            val hit = eventDao.findLatestByOrderKey(orderKey)?.shipmentId
            if (hit != null && hit > 0L) return hit
        }

        // 三级匹配：来源 + 时间窗 + 地点/指纹（兜底；两侧单号不同则不归并，SOP §6.4）
        val window = ShipmentMatcher.TIME_WINDOW_MS
        val candidates = eventDao.findCandidates(
            pkg = event.sourcePackage,
            from = event.eventTime - window,
            to = event.eventTime + window,
        )
        return ShipmentMatcher.matchByContext(event, candidates)
    }

    // ---------- 用户操作 ----------

    /**
     * 更新用户状态（SOP §4.3 平台状态与用户状态分离：只改 user_status，不动 status）。
     *
     * **必须真正写库并返回结果**：DAO 用 `UPDATE ... WHERE id = :id` 返回受影响行数，
     * 行数 > 0 才算成功。调用方据此决定是否提示「标记失败，请重试」，不允许只改内存对象。
     *
     * 写库成功后无需手动刷新：首页 / 列表 / 详情都订阅 Room 的 Flow，
     * 数据变化会自动重新推送（SOP §25 Phase 5）。
     *
     * @return true=数据库确实更新了 1 行；false=这行不存在或写入失败
     */
    suspend fun setUserStatus(shipmentId: Long, status: UserStatus): Boolean {
        val now = clock()
        val updated = if (status == UserStatus.PICKED_UP) {
            shipmentDao.markPickedUp(shipmentId, status.name, now, now) > 0
        } else {
            shipmentDao.updateUserStatus(shipmentId, status.name, now) > 0
        }
        runCatching { onUserStatusChanged?.invoke(shipmentId, status, updated) }
            .onFailure { AppLog.w("user status callback failed", it) }
        return updated
    }

    suspend fun deleteShipment(shipmentId: Long) {
        shipmentDao.delete(shipmentId)
    }

    // ---------- 来源管理 ----------

    suspend fun listSources(): List<SourceAppEntity> = sourceDao.listAll()

    suspend fun getSource(packageName: String): SourceAppEntity? =
        sourceDao.listAll().firstOrNull { it.packageName == packageName }

    suspend fun upsertSources(sources: List<SourceAppEntity>) = sourceDao.upsertAll(sources)

    suspend fun setSourceEnabled(packageName: String, enabled: Boolean) =
        sourceDao.setEnabled(packageName, enabled)
}

private fun ParcelEvent.toEntity(dedupKey: String, tracking: String?): ParcelEventEntity =
    ParcelEventEntity(
        shipmentId = shipmentId,
        sourceType = sourceType.name,
        sourcePackage = sourcePackage,
        notificationKey = notificationKey,
        eventType = eventType.name,
        status = status.name,
        trackingNumber = tracking,
        carrier = carrier,
        pickupCode = pickupCode,
        pickupLocation = pickupLocation,
        destination = destination,
        orderKey = orderKey,
        fingerprint = fingerprint,
        dedupKey = dedupKey,
        rawSummary = rawSummary,
        confidence = confidence,
        eventTime = eventTime,
        createdAt = createdAt,
    )
