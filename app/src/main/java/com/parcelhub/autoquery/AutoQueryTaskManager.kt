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

import android.content.Context
import com.parcelhub.autoquery.cainiao.AutoQueryLog
import com.parcelhub.autoquery.cainiao.CainiaoLaunchGate
import com.parcelhub.data.db.QueryTaskDao
import com.parcelhub.data.entity.QueryTaskEntity
import com.parcelhub.data.entity.statusEnum
import com.parcelhub.data.repository.IngestOutcome
import com.parcelhub.data.repository.ShipmentRepository
import com.parcelhub.model.ShipmentStatus
import com.parcelhub.notification.AppNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 自动查询任务编排（EXPRESS_SMART_QUERY SOP §4 决策链 / §8 查询链路 / §10 防重复与二次判断）。
 *
 * 每次事件入库后走一遍：
 * ```text
 * 入库事件 → 组装 AutoQueryInput → 决策引擎（§4/§5/§6/§9）
 *         → 不是 QUERY？结束
 *         → 30 秒去重门禁 + 已有有效任务？结束（§10.1）
 *         → 建 QueryTask（§9/§29）→ 延迟 1.5s 二次判断（§10.2）
 *         → 仍需查询 → 发通知（按钮 = 打开菜鸟填单号，§14/§32）
 * ```
 *
 * 第一版只到「打开菜鸟 + 无障碍填单号」（参考 SOP §23），
 * **不回读查询结果**，因此不会写入物流回执，也不会自作主张改用户状态。
 *
 * 纯编排、不发请求、不轮询；所有等待都是有界的一次性 delay。
 */
class AutoQueryTaskManager(
    private val context: Context,
    private val dao: QueryTaskDao,
    private val repository: ShipmentRepository,
    private val gate: CainiaoLaunchGate,
    private val notifier: AppNotificationManager,
    private val scope: CoroutineScope,
    private val autoQueryEnabled: () -> Boolean,
) {

    private val sequence = AtomicInteger(0)

    /** 事件入库后的自动查询决策（由解析队列串行调用，内部只做本地判断 + 落库） */
    suspend fun onOutcome(outcome: IngestOutcome) {
        if (outcome.kind == IngestOutcome.Kind.DUPLICATE ||
            outcome.kind == IngestOutcome.Kind.IGNORED
        ) {
            return
        }
        if (outcome.shipmentId <= 0L) return

        val shipment = repository.getShipment(outcome.shipmentId) ?: return
        val tracking = shipment.trackingNumber?.trim().orEmpty()
        if (tracking.isEmpty()) {
            // SOP §4：没有单号就不进查询链路
            return
        }
        val deliveryStatus = toDeliveryStatus(outcome.status) ?: return

        val now = System.currentTimeMillis()
        val input = AutoQueryInput(
            autoQueryEnabled = autoQueryEnabled(),
            trackingNumber = tracking,
            pickupCode = shipment.pickupCode,
            sourcePlace = shipment.pickupLocation,
            actualPlace = shipment.pickupLocation,
            deliveryStatus = deliveryStatus,
            lastAutoQueryAt = dao.lastTaskAt(tracking) ?: 0L,
            createdAt = shipment.createdAt,
            updatedAt = shipment.updatedAt,
            now = now,
        )

        val decision = AutoQueryDecisionEngine.decide(input)
        if (decision != AutoQueryDecisionType.QUERY) {
            AutoQueryLog.decision("${AutoQueryLog.mask(tracking)} -> $decision")
            return
        }

        // §10.1 去重：30 秒窗口 + 同单号只允许一个有效任务
        if (!gate.tryAcquire(tracking, now)) return
        val active = dao.findActiveByTrackingNumber(tracking, TERMINAL_NAMES)
        if (active != null) {
            AutoQueryLog.task("skip ${AutoQueryLog.mask(tracking)}: active task ${active.taskId}")
            return
        }

        val task = QueryTaskEntity(
            taskId = newTaskId(now),
            shipmentId = shipment.id,
            trackingNumber = tracking,
            carrier = shipment.carrier,
            status = QueryTaskStatus.PENDING.name,
            createdAt = now,
            updatedAt = now,
            lastAction = "created",
        )
        dao.upsert(task)
        AutoQueryLog.task("created ${task.taskId} for ${AutoQueryLog.mask(tracking)}")

        // §10.2 延迟 1～2 秒后按最新数据二次判断，避免在“马上就有取件码”的场景里白开一次菜鸟
        scope.launch {
            delay(CainiaoLaunchGate.RECHECK_DELAY_MS)
            val fresh = repository.getShipment(outcome.shipmentId)
            val freshStatus = fresh?.let { toDeliveryStatus(outcome.status) } ?: deliveryStatus
            val recheck = AutoQueryInput(
                autoQueryEnabled = autoQueryEnabled(),
                trackingNumber = tracking,
                pickupCode = fresh?.pickupCode,
                sourcePlace = fresh?.pickupLocation ?: shipment.pickupLocation,
                actualPlace = fresh?.pickupLocation ?: shipment.pickupLocation,
                deliveryStatus = freshStatus,
                // 冷却基准排除当前任务自身，否则刚建的任务必被误取消
                lastAutoQueryAt = dao.lastTaskAtBefore(tracking, task.taskId) ?: 0L,
                createdAt = fresh?.createdAt ?: shipment.createdAt,
                updatedAt = fresh?.updatedAt ?: shipment.updatedAt,
                now = System.currentTimeMillis(),
            )
            if (CainiaoLaunchGate.shouldCancel(recheck)) {
                cancelTask(task.taskId, tracking, recheck)
                return@launch
            }
            val stored = dao.findById(task.taskId) ?: return@launch
            if (stored.statusEnum != QueryTaskStatus.PENDING) return@launch
            notifier.notifyAutoQuery(
                taskId = task.taskId,
                trackingNumber = tracking,
                carrier = shipment.carrier,
                pickupCode = fresh?.pickupCode,
            )
        }
    }

    /**
     * 无障碍会话状态回写（SOP §26 / §29 Task 绑定），由会话回调进入。
     *
     * FAILED 时（SOP Step 20 / T07 / T08 / §31）：失败原因写入任务表，
     * 并通知用户手动查询，不会自动重试拉起。
     * CANCELLED 时：撤掉入口通知并释放门禁。
     */
    suspend fun onSessionStatus(
        trackingNumber: String?,
        status: QueryTaskStatus,
        failureReason: String? = null,
        attempts: Int = 0,
    ) {
        val tracking = trackingNumber?.trim().orEmpty()
        if (tracking.isEmpty()) return
        val task = dao.findActiveByTrackingNumber(tracking, TERMINAL_NAMES) ?: return
        if (task.status == status.name) return
        val now = System.currentTimeMillis()
        if (status == QueryTaskStatus.FAILED) {
            dao.fail(
                taskId = task.taskId,
                status = status.name,
                retry = attempts,
                reason = failureReason,
                now = now,
            )
            gate.release(tracking)
            notifier.cancelAutoQuery(task.taskId)
            notifier.notifyAutoQueryFailed(tracking, failureReason)
            AutoQueryLog.task("${task.taskId} FAILED($failureReason) -> notified manual")
            return
        }
        dao.updateStatus(
            taskId = task.taskId,
            status = status.name,
            action = "a11y",
            now = now,
        )
        AutoQueryLog.task("${task.taskId} -> $status")
        if (status == QueryTaskStatus.COMPLETED || status == QueryTaskStatus.CANCELLED) {
            gate.release(tracking)
            notifier.cancelAutoQuery(task.taskId)
        }
    }

    private suspend fun cancelTask(taskId: String, tracking: String, input: AutoQueryInput) {
        dao.updateStatus(
            taskId = taskId,
            status = QueryTaskStatus.CANCELLED.name,
            action = "recheck:${AutoQueryDecisionEngine.decide(input)}",
            now = System.currentTimeMillis(),
        )
        gate.release(tracking)
        notifier.cancelAutoQuery(taskId)
        AutoQueryLog.task("$taskId cancelled by recheck")
    }

    /**
     * 中转页前置检查失败时按单号取消（SOP §31 / T05 / T06）。
     *
     * 与 [onSessionStatus] 的区别：调用时会话可能尚未绑定该单号
     * （例如无障碍没开，连 `start()` 都没走到），所以不走会话回调，
     * 直接按单号找有效任务取消并撤掉入口通知。
     */
    suspend fun cancelForTracking(trackingNumber: String, action: String) {
        val tracking = trackingNumber.trim()
        if (tracking.isEmpty()) return
        val task = dao.findActiveByTrackingNumber(tracking, TERMINAL_NAMES) ?: return
        if (task.status == QueryTaskStatus.CANCELLED.name) return
        dao.updateStatus(
            taskId = task.taskId,
            status = QueryTaskStatus.CANCELLED.name,
            action = action,
            now = System.currentTimeMillis(),
        )
        gate.release(tracking)
        notifier.cancelAutoQuery(task.taskId)
        AutoQueryLog.task("${task.taskId} cancelled: $action")
    }

    private fun newTaskId(now: Long): String =
        "q${now}_${sequence.incrementAndGet()}"

    /**
     * 平台状态 → 自动查询用的配送状态（SOP §3 枚举对齐）。
     *
     * @return null 表示这类状态不进查询链路（已退回 / 已取消不查）
     */
    private fun toDeliveryStatus(status: ShipmentStatus): DeliveryStatus? = when (status) {
        ShipmentStatus.UNKNOWN -> DeliveryStatus.UNKNOWN
        ShipmentStatus.SHIPPED, ShipmentStatus.IN_TRANSIT -> DeliveryStatus.IN_TRANSIT
        ShipmentStatus.OUT_FOR_DELIVERY -> DeliveryStatus.OUT_FOR_DELIVERY
        ShipmentStatus.ARRIVED, ShipmentStatus.PICKUP_READY -> DeliveryStatus.ARRIVED_STATION
        ShipmentStatus.DELIVERED -> DeliveryStatus.DELIVERED_DOOR
        ShipmentStatus.RETURNED, ShipmentStatus.CANCELLED -> null
    }

    companion object {
        /** 终态任务不再参与“有效任务”判定 */
        val TERMINAL_NAMES = listOf(
            QueryTaskStatus.COMPLETED.name,
            QueryTaskStatus.FAILED.name,
            QueryTaskStatus.CANCELLED.name,
        )
    }
}
