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
import com.parcelhub.data.entity.PendingShipmentEntity
import com.parcelhub.data.entity.ShipmentEntity
import com.parcelhub.data.entity.SourceAppEntity
import com.parcelhub.data.entity.toPending
import com.parcelhub.matcher.DedupEngine
import com.parcelhub.matcher.ShipmentMatcher
import com.parcelhub.matcher.StateMachine
import com.parcelhub.model.EventType
import com.parcelhub.model.ParcelEvent
import com.parcelhub.model.ShipmentStatus
import com.parcelhub.model.UserStatus
import com.parcelhub.pending.PendingMatcher
import com.parcelhub.pending.PendingShipmentStatus
import com.parcelhub.pending.PendingSourceInfo
import com.parcelhub.pending.PendingSourceResolver
import com.parcelhub.pending.ShipmentReconciler
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
    /**
     * 来源识别（需求 §二/§三）：把「事件来源包名 + 采集通道」折算成
     * type/packageName/appName。由 ServiceLocator 注入真实实现
     * （rules.json + PackageManager 应用标签）；不注入时按包名推断、应用名为空，
     * 仓储保持可被 JVM 单测直接使用。
     */
    private val sourceInfoOf: (String, String?) -> PendingSourceInfo =
        { pkg, channel -> PendingSourceResolver.resolve(pkg, channel, null, null, null) },
) {
    private val shipmentDao = db.shipmentDao()
    private val eventDao = db.parcelEventDao()
    private val sourceDao = db.sourceDao()
    private val pendingDao = db.pendingShipmentDao()

    /**
     * 新建待补全订单后的唤醒回调：延迟补偿循环（SOP §23/§25）原本停在等待中，
     * 这里把它叫醒去重排检查时间；不注入时为 null，仓储保持可被 JVM 单测直接使用。
     */
    var onPendingCreated: ((shipmentId: Long?) -> Unit)? = null

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
        val tracking = ShipmentMatcher.normalizeTracking(event.trackingNumber)

        // 1) 无障碍「无身份文本」闸（SOP §6.4）：订单列表页这类多单混排文本
        //    无单号 + 无订单号，不创建包裹——凭状态文字只会造“认出来了但
        //    没单号”的垃圾行，还会作为无号桥接候选把后续事件并进同一行。
        if (ShipmentMatcher.isIdentitylessA11yEvent(event.channel, tracking, event.orderKey)) {
            AppLog.i("drop identityless a11y text: ${event.sourcePackage}")
            return ignored(event.confidence)
        }

        // 2) 事件层去重：
        //    - 无障碍按「单号 + 订单号」身份键：同一订单只记一条事件，
        //      页面滚动 / 反复进入不产生重复时间线，不同订单绝不互撞；
        //    - 其它通道保持原通知键（同一条通知重复 post 才算重复）。
        //    去重命中时补写行里还空着的字段（进页即读遇页面渐进加载：
        //    先出状态行、后出单号 / 收货地址），事件行不重复追加。
        val dedupKey = if (ShipmentMatcher.isPageChannel(event.channel)) {
            DedupEngine.eventDedupKey(
                sourcePackage = event.sourcePackage,
                notificationKey = "a11y|${tracking.orEmpty()}|${event.orderKey.orEmpty()}",
                eventType = event.eventType,
            )
        } else {
            DedupEngine.eventDedupKey(
                sourcePackage = event.sourcePackage,
                notificationKey = event.notificationKey,
                eventType = event.eventType,
            )
        }
        eventDao.findShipmentIdByDedupKey(dedupKey)?.let { shipmentId ->
            val row = shipmentDao.findById(shipmentId)
            if (row != null) {
                ShipmentMatcher.refillOnDedupHit(row, event, tracking, now)?.let { updated ->
                    shipmentDao.update(updated)
                    AppLog.d("dedup refill shipment=$shipmentId")
                }
            }
            return duplicate(shipmentId, ShipmentStatus.UNKNOWN, event.confidence)
        }

        // 3) 置信度过低：不创建正式包裹（SOP §6.5）
        if (!ShipmentMatcher.canCreateShipment(event.confidence)) {
            AppLog.d("drop low-confidence event: ${event.eventType} ${event.confidence}")
            return ignored(event.confidence)
        }

        val eventEntity = event.toEntity(dedupKey, tracking)

        // 2.5) 待补全匹配（SOP §30 / 场景 1·2）：带单号的事件先尝试落回
        //      某条「已发货无单号」的待补全订单，命中则直接绑定它的正式包裹，
        //      避免同一订单生成第二条 Shipment（SOP 场景 6 / 7）。
        val matchedPending = if (tracking != null) findPendingForEvent(event) else null
        if (matchedPending != null && tracking != null) {
            // 来源补全（需求 §三-5）：SMS 等中转来源创建的待补全没有购物 App 信息，
            // 后续通知 / 无障碍发现同订单时补上包名与应用名，但 **不改 source_type**
            // （保留 SMS + 对应购物 App）。返回值随下面的状态更新一起落库。
            markPendingFound(enrichPendingSource(matchedPending, event), tracking, event.carrier, now)
        }

        // 3) 匹配目标包裹（一级 → 二级 → 三级）；补全命中的待补全订单优先
        var isNew = false
        val targetId = matchedPending?.shipmentId?.takeIf { it > 0L }
            ?: resolveTargetShipment(eventEntity, tracking)
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

        // 8) 待补全（SOP §22）：「已发货 + 没有单号」不再直接结束流程，
        //    落一条 ShipmentPending 等后续通知 / 短信补全（SOP §一 根本原因）
        if (tracking == null && isShippedSignal(event)) {
            ensurePending(shipmentId = current.id, event = event, now = now)
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

    /** 置信度不足 / 无身份文本：不创建包裹、不产生任何提醒 */
    private fun ignored(confidence: Double) = IngestOutcome(
        kind = IngestOutcome.Kind.IGNORED,
        shipmentId = 0L,
        previousStatus = ShipmentStatus.UNKNOWN,
        status = ShipmentStatus.UNKNOWN,
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

        // 二级匹配：平台订单号 / 事件关联 ID（行级单号守卫：行内已有单号且
        // 与新事件冲突 → 这行不是同一个包裹，落到三级继续判，SOP §6.4）
        val orderKey = event.orderKey
        if (!orderKey.isNullOrBlank()) {
            val hit = eventDao.findLatestByOrderKey(orderKey)?.shipmentId
            if (hit != null && hit > 0L) {
                val row = shipmentDao.findById(hit)
                if (row == null || ShipmentMatcher.trackingRowUsable(tracking, row.trackingNumber)) {
                    return hit
                }
            }
        }

        // 三级匹配：来源 + 时间窗 + 地点/指纹（兜底；两侧单号不同则不归并，SOP §6.4）
        val window = ShipmentMatcher.TIME_WINDOW_MS
        val candidates = eventDao.findCandidates(
            pkg = event.sourcePackage,
            from = event.eventTime - window,
            to = event.eventTime + window,
        )
        val contextHit = ShipmentMatcher.matchByContext(event, candidates) ?: return null
        // 行级单号守卫：指纹（页面公共框架）把事件引到别单的行上时，
        // 用行内已有单号兜住——冲突就不归并，另起一行（防 3 单并 1 单）
        val row = shipmentDao.findById(contextHit)
        return if (row == null || ShipmentMatcher.trackingRowUsable(tracking, row.trackingNumber)) {
            contextHit
        } else {
            null
        }
    }

    // ---------- 待补全（SOP §21~§24） ----------

    /** 首页「待补全」区：只观察 WAITING_TRACKING */
    fun observePending(): Flow<List<PendingShipmentEntity>> =
        pendingDao.observeByStatus(PendingShipmentStatus.WAITING_TRACKING.name)

    /** 待补全详情页（需求 §五）：单条实时观察（补全到单号时 UI 自动刷新） */
    fun observePendingById(id: Long): Flow<PendingShipmentEntity?> = pendingDao.observeById(id)

    suspend fun countPendingWaiting(): Int =
        pendingDao.listByStatus(PendingShipmentStatus.WAITING_TRACKING.name).size

    /**
     * 指定来源包名下是否还有待补全单（OCR 兜底触发条件 SOP V2.0 §19：
     * 只在「待补全仍需单号」的来源页截图识别；补全后自然停止）。
     */
    suspend fun hasPendingWaitingFor(packageName: String): Boolean =
        pendingDao.listByStatus(PendingShipmentStatus.WAITING_TRACKING.name)
            .any { it.sourcePackageName == packageName }

    // ---- 健康检测证据（检测 SOP §8.1；只读聚合） ----

    /** 最早的计划检查时间；无 WAITING_TRACKING 为 null */
    suspend fun earliestPendingCheckAt(): Long? =
        pendingDao.earliestNextCheckAt(PendingShipmentStatus.WAITING_TRACKING.name)

    /** 最早的到期时间；无 WAITING_TRACKING 为 null */
    suspend fun earliestPendingExpireAt(): Long? =
        pendingDao.earliestExpireAt(PendingShipmentStatus.WAITING_TRACKING.name)

    /** 数据矛盾条数（TRACKING_FOUND 却没有单号，检测 §8.4 → ERROR） */
    suspend fun countPendingInconsistent(): Int =
        pendingDao.countInconsistent(PendingShipmentStatus.TRACKING_FOUND.name)

    /**
     * 在所有 WAITING_TRACKING 记录里找能承接 [event] 单号的那一条（SOP §30 匹配优先级）。
     * 没有任何待补全订单时直接返回 null，不产生额外查询（SOP §25）。
     */
    private suspend fun findPendingForEvent(event: ParcelEvent): PendingShipmentEntity? {
        val waiting = pendingDao.listByStatus(PendingShipmentStatus.WAITING_TRACKING.name)
        if (waiting.isEmpty()) return null
        val hit = PendingMatcher.match(
            candidates = waiting.map { it.toPending() },
            orderKey = event.orderKey?.takeIf { it.isNotBlank() },
            platform = PendingMatcher.sourcePlatform(event.sourcePackage),
            at = event.eventTime,
        ) ?: return null
        return pendingDao.findById(hit.pending.id)
    }

    private suspend fun markPendingFound(
        pending: PendingShipmentEntity,
        tracking: String,
        carrier: String?,
        now: Long,
    ) {
        pendingDao.update(
            pending.copy(
                status = PendingShipmentStatus.TRACKING_FOUND.name,
                trackingNumber = pending.trackingNumber ?: tracking,
                carrier = pending.carrier ?: carrier,
                lastCheckAt = now,
                updatedAt = now,
            ),
        )
        AppLog.d("pending ${pending.id}: WAITING_TRACKING -> TRACKING_FOUND ($tracking)")
    }

    /**
     * 来源补全（需求 §三-5「SMS + 对应购物 App」）：
     * 待补全还没有来源 App 信息时，用完成它的事件来源补上包名与应用名；
     * **不改 source_type**（SMS 创建的记录保持 SMS）。已有的值不覆盖。
     *
     * 纯内存计算，由调用方（[markPendingFound]）随状态更新一起落库。
     */
    private fun enrichPendingSource(
        pending: PendingShipmentEntity,
        event: ParcelEvent,
    ): PendingShipmentEntity {
        if (pending.sourcePackageName != null && pending.sourceAppName != null) return pending
        if (event.sourcePackage.isBlank()) return pending
        // 中转来源（短信 / 分享伪包名）解析出来仍是 null，不会写入任何东西
        val info = sourceInfoOf(event.sourcePackage, event.channel)
        if (info.packageName == null && info.appName == null) return pending
        return pending.copy(
            sourcePackageName = pending.sourcePackageName ?: info.packageName,
            sourceAppName = pending.sourceAppName ?: info.appName,
        )
    }

    /**
     * 「已发货 + 没有单号」→ 创建待补全记录（SOP §22：不要直接丢弃）。
     * 去重：同一正式包裹只挂一条；同平台同订单号已有的也不再重复建（SOP 场景 6）。
     */
    private suspend fun ensurePending(shipmentId: Long, event: ParcelEvent, now: Long) {
        if (pendingDao.findByShipment(shipmentId, PendingShipmentStatus.WAITING_TRACKING.name) != null) {
            return
        }
        val orderKey = event.orderKey?.takeIf { it.isNotBlank() }
        val platform = PendingMatcher.sourcePlatform(event.sourcePackage)
        // 来源识别（需求 §二/§三）：ECOMMERCE / NOTIFICATION / ACCESSIBILITY / SMS / USER_INPUT
        val source = sourceInfoOf(event.sourcePackage, event.channel)
        // 商品名 / 商家（需求 §十 去重键）：通知文本不解析、按隐私口径不存原文 → 当前恒 null。
        // 键位保留在去重条件里，将来接入结构化商品信息时无需再改匹配逻辑。
        val productTitle: String? = null
        val sellerName: String? = null
        if (orderKey != null) {
            // 去重（需求 §十）：orderKey + platform + productTitle + sellerName 全等视为同一单
            val exists = pendingDao
                .listByStatus(PendingShipmentStatus.WAITING_TRACKING.name)
                .any {
                    it.orderKey == orderKey &&
                        it.platform == platform &&
                        it.productTitle == productTitle &&
                        it.sellerName == sellerName
                }
            if (exists) return
        }

        val (nextCheckAt, expireAt) = ShipmentReconciler.initial(event.eventTime)
        pendingDao.insert(
            PendingShipmentEntity(
                orderKey = orderKey,
                platform = platform,
                shipmentId = shipmentId,
                shippedAt = event.eventTime,
                status = PendingShipmentStatus.WAITING_TRACKING.name,
                nextCheckAt = nextCheckAt,
                expireAt = expireAt,
                sourceType = source.type.name,
                sourcePackageName = source.packageName,
                sourceAppName = source.appName,
                productTitle = productTitle,
                sellerName = sellerName,
                createdAt = now,
                updatedAt = now,
            ),
        )
        AppLog.d(
            "pending created: shipment=$shipmentId expireAt=$expireAt " +
                "source=${source.type}/${source.appName ?: "-"}",
        )
        runCatching { onPendingCreated?.invoke(shipmentId) }
    }

    /** 是否是「已发货」信号（SOP §20 已发货待补全状态的触发条件） */
    private fun isShippedSignal(event: ParcelEvent): Boolean =
        event.eventType == EventType.SHIPPED || event.status == ShipmentStatus.SHIPPED

    /**
     * 延迟补偿（SOP §23 ShipmentReconciliationWorker / §24 补偿时间）：
     * 对 WAITING_TRACKING 逐条决策——补到单号转 TRACKING_FOUND、超 72h 转 EXPIRED、
     * 到 24h/48h 检查点则顺延；没有待补全订单时**不安排任何任务**（SOP §25）。
     *
     * @return 下一次应唤醒的绝对时间（ms）；没有待补全订单返回 null
     */
    suspend fun reconcilePending(now: Long): Long? {
        val waiting = pendingDao.listByStatus(PendingShipmentStatus.WAITING_TRACKING.name)
        if (waiting.isEmpty()) return null

        var earliest: Long? = null
        for (entity in waiting) {
            val shipmentTracking = entity.shipmentId
                ?.takeIf { it > 0L }
                ?.let { shipmentDao.findById(it)?.trackingNumber }
            val plan = ShipmentReconciler.decide(entity.toPending(), shipmentTracking, now)
            when (plan.decision) {
                ShipmentReconciler.Decision.FOUND -> pendingDao.update(
                    entity.copy(
                        status = PendingShipmentStatus.TRACKING_FOUND.name,
                        trackingNumber = plan.trackingNumber ?: entity.trackingNumber,
                        lastCheckAt = plan.lastCheckAt,
                        updatedAt = now,
                    ),
                )

                ShipmentReconciler.Decision.EXPIRE -> {
                    pendingDao.update(
                        entity.copy(
                            status = PendingShipmentStatus.EXPIRED.name,
                            lastCheckAt = plan.lastCheckAt,
                            updatedAt = now,
                        ),
                    )
                    AppLog.d("pending ${entity.id}: WAITING_TRACKING -> EXPIRED (72h)")
                }

                ShipmentReconciler.Decision.RESCHEDULE -> {
                    pendingDao.update(
                        entity.copy(
                            nextCheckAt = plan.nextCheckAt,
                            lastCheckAt = plan.lastCheckAt,
                            updatedAt = now,
                        ),
                    )
                    plan.nextCheckAt?.let { t ->
                        if (earliest == null || t < earliest) earliest = t
                    }
                }

                ShipmentReconciler.Decision.KEEP -> {
                    val next = plan.nextCheckAt ?: entity.nextCheckAt
                    if (next != null && (earliest == null || next < earliest)) earliest = next
                }
            }
        }
        return earliest
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
