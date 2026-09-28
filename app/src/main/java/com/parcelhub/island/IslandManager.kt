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

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.parcelhub.data.entity.needsPickup
import com.parcelhub.data.repository.IngestOutcome
import com.parcelhub.data.repository.ShipmentRepository
import com.parcelhub.model.UserStatus
import com.parcelhub.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 灵动岛总控（SOP §12 `IslandManager`）。
 *
 * 一条链路把三端串起来（SOP §3）：
 * ```text
 * Repository（唯一数据源）
 *      ├─ 入库结果        → onIngest        → 事件 → 去重 → 状态机 → 展示
 *      ├─ 标记已取件成功   → onUserStatusChanged → PickedUp → SUCCESS → 1~2 秒收起
 *      └─ 数据变化         → observeAll() → 桌面 Widget 自动刷新（ServiceLocator 里已接）
 * ```
 *
 * 展示路由（SOP §11 双模式）：
 *  - 用户开了「悬浮显示」且权限在手 → [IslandOverlayService]；
 *  - 否则 / 悬浮不可用 → [IslandNotificationManager]（默认模式，SOP §11.1、§13、§19）。
 *
 * 线程约定：状态机与展示只在主线程执行（[Dispatchers.Main]），
 * 数据库读取在 IO；因此不需要给状态机加锁。
 */
class IslandManager(
    private val context: Context,
    private val repository: ShipmentRepository,
    private val dispatcher: IslandEventDispatcher,
    private val dedup: IslandDedup,
    private val notifier: IslandNotificationManager,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val machine = IslandStateMachine()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 灵动岛总开关（SOP §13：默认可用，不要求任何额外权限） */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            if (!value) reset()
        }

    /** 悬浮模式开关（SOP §11.2：用户主动开启，缺权限时自动退回通知模式） */
    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_OVERLAY, value).apply()
            if (!value) hideOverlay()
        }

    fun ensureChannels() {
        notifier.ensureChannel()
        notifier.ensureOverlayServiceChannel()
    }

    // ---------- 事件入口 ----------

    /**
     * 解析入库结果 → 灵动岛（由 IngestPipeline 串行调用）。
     *
     * @return true = 灵动岛接管了这条事件（含“今天已经提醒过”的情况），
     * 调用方**不要再**发普通提醒，避免同一件事弹两条；
     * false = 灵动岛不处理，继续走原有提醒链路。
     */
    suspend fun onIngest(outcome: IngestOutcome): Boolean {
        if (!enabled) return false
        val event = dispatcher.fromIngest(outcome) ?: return false
        present(event)
        return true
    }

    /**
     * 「标记已取件」写库结果（由 ShipmentRepository 回调，SOP §8.3 / §9）。
     *
     * 只有 `updated = true`（UPDATE 影响行数 > 0）才会展示 `✓ 取件完成`，
     * 数据库没写进去时绝不给成功反馈（SOP §9 / §19）。
     */
    fun onUserStatusChanged(shipmentId: Long, status: UserStatus, updated: Boolean) {
        if (!enabled) return
        val event = dispatcher.fromUserStatus(shipmentId, status, updated) ?: return
        scope.launch { present(event) }
    }

    /** 事件 → 去重 → 取内容 → 交给状态机展示 */
    private suspend fun present(event: IslandEvent) {
        val now = clock()
        val prepared = withContext(Dispatchers.IO) {
            if (!dedup.shouldShow(event, now)) {
                AppLog.d("island dedup hit: ${event.kind} ${event.shipmentId}")
                return@withContext null
            }
            val content = buildContent(event) ?: return@withContext null
            content to buildCompactContent()
        } ?: return

        withContext(Dispatchers.Main) {
            machine.onEvent(event, prepared.first, prepared.second, clock())
            render()
            scheduleTick()
        }
    }

    // ---------- 内容 ----------

    private suspend fun buildContent(event: IslandEvent): IslandContent? {
        val shipment = repository.getShipment(event.shipmentId) ?: return null
        val carrier = shipment.carrier?.takeIf { it.isNotBlank() } ?: "快递"
        val code = shipment.pickupCode?.takeIf { it.isNotBlank() }
        // 单号不脱敏：详情页（DetailRow「运单号」）与列表都是完整单号，岛上再打点就是两套口径
        // （2026-09-28 用户要求「取件完成省略单号，待取不省略，统一下」）。落库摘要的脱敏
        // 走 PrivacyUtil，与展示无关。
        val tracking = shipment.trackingNumber?.takeIf { it.isNotBlank() }

        return when (event) {
            is IslandEvent.Arrived -> IslandContent(
                shipmentId = shipment.id,
                title = "📦 快递到站",
                line1 = code?.let { "取件码 $it" } ?: "包裹已到站",
                line2 = shipment.pickupLocation?.takeIf { it.isNotBlank() } ?: carrier,
                tone = IslandTone.ARRIVAL,
            )

            is IslandEvent.Delivering -> IslandContent(
                shipmentId = shipment.id,
                title = "🚚 $carrier",
                line1 = "正在派送",
                line2 = tracking?.let { "单号 $it" }
                    ?: shipment.pickupLocation?.takeIf { it.isNotBlank() },
                tone = IslandTone.LOGISTICS,
            )

            is IslandEvent.PickedUp -> IslandContent(
                shipmentId = shipment.id,
                title = "✓ 取件完成",
                line1 = code ?: tracking ?: carrier,
                line2 = code?.let { carrier },
                tone = IslandTone.SUCCESS,
            )

            is IslandEvent.StatusChanged -> IslandContent(
                shipmentId = shipment.id,
                title = statusTitle(event.status),
                line1 = carrier,
                line2 = tracking?.let { "单号 $it" },
                tone = IslandTone.LOGISTICS,
            )
        }
    }

    /** 小胶囊内容：`📦 N 个待取`（SOP §7.2），口径与首页「待取」分组一致 */
    private suspend fun buildCompactContent(): IslandContent {
        val pending = repository
            .pageShipments(COMPACT_SCAN_LIMIT, 0)
            .count { it.needsPickup }
        return IslandContent(
            shipmentId = 0L,
            title = if (pending > 0) "📦 $pending 个待取" else "📦 快递提醒",
            tone = IslandTone.ARRIVAL,
        )
    }

    private fun statusTitle(status: com.parcelhub.model.ShipmentStatus): String =
        when (status) {
            com.parcelhub.model.ShipmentStatus.DELIVERED -> "✓ 已签收"
            com.parcelhub.model.ShipmentStatus.RETURNED -> "↩ 已退回"
            com.parcelhub.model.ShipmentStatus.CANCELLED -> "已取消"
            else -> "🚚 状态更新"
        }

    // ---------- 展示路由 ----------

    /** 只在主线程调用 */
    private fun render() {
        val content = machine.content
        if (content == null) {
            // HIDDEN：撤掉悬浮胶囊
            hideOverlay()
            return
        }
        if (overlayEnabled && showOverlay(content, machine.state)) return
        notifier.show(content, machine.state)
    }

    private fun showOverlay(content: IslandContent, state: IslandState): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!Settings.canDrawOverlays(context)) {
            AppLog.d("island overlay permission missing, use notification")
            return false
        }
        val intent = Intent(context, IslandOverlayService::class.java).apply {
            action = IslandOverlayService.ACTION_SHOW
            putExtra(IslandOverlayService.EXTRA_SHIPMENT_ID, content.shipmentId)
            putExtra(IslandOverlayService.EXTRA_TITLE, content.title)
            putExtra(IslandOverlayService.EXTRA_LINE1, content.line1)
            putExtra(IslandOverlayService.EXTRA_LINE2, content.line2)
            putExtra(IslandOverlayService.EXTRA_TONE, content.tone.name)
            putExtra(IslandOverlayService.EXTRA_STATE, state.name)
        }
        return try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (t: Throwable) {
            // Android 12+ 后台启动前台服务受限等场景：回退通知模式（SOP §13 / §19）
            AppLog.w("island overlay start failed, fallback to notification", t)
            false
        }
    }

    /** 收起悬浮胶囊（服务不在时会抛后台启动异常，忽略即可） */
    private fun hideOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(context, IslandOverlayService::class.java).apply {
            action = IslandOverlayService.ACTION_HIDE
        }
        try {
            context.startService(intent)
        } catch (t: Throwable) {
            AppLog.d("island overlay hide skipped: ${t.javaClass.simpleName}")
        }
    }

    // ---------- 时间推进 ----------

    private val tickRunnable = Runnable {
        if (machine.tick(clock())) render()
        scheduleTick()
    }

    /** 按状态机的下一次自动推进时刻挂一次延迟回调（不轮询，SOP §14） */
    private fun scheduleTick() {
        mainHandler.removeCallbacks(tickRunnable)
        val at = machine.advanceAt
        if (at <= 0L) return
        val delay = (at - clock()).coerceAtLeast(0L)
        mainHandler.postDelayed(tickRunnable, delay)
    }

    /**
     * 用户点了悬浮胶囊（SOP §16.1）：详情已经打开，整岛立刻收起。
     *
     * 不这么做的话状态机还停在 EXPANDED，下一次 tick 会重新拉起前台服务，
     * 胶囊在详情页上方「复活」一次才消失（真机观察到的缺陷）。
     */
    fun onOverlayTapped() {
        mainHandler.post {
            mainHandler.removeCallbacks(tickRunnable)
            machine.hide()
            notifier.cancel()
        }
    }

    /** 关闭灵动岛 / 停用悬浮模式时清理现场（SOP §16 关闭当前灵动岛） */
    fun reset() {
        mainHandler.removeCallbacks(tickRunnable)
        machine.hide()
        hideOverlay()
        notifier.cancel()
    }

    private companion object {
        const val PREFS = "parcelhub_island"
        const val KEY_ENABLED = "island_enabled"
        const val KEY_OVERLAY = "island_overlay"

        /** 小胶囊个数统计的扫描上限：桌面/首页数据量级很小，避免大表全表扫描 */
        const val COMPACT_SCAN_LIMIT = 500
    }
}
