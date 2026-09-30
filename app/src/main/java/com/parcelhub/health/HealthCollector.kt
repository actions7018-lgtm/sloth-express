/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.health

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.parcelhub.App
import com.parcelhub.sms.SmsPermissionManager
import com.parcelhub.util.PermissionUtil
import com.parcelhub.util.TimeUtil
import com.parcelhub.widget.TodoWidgetProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 自动检测的证据采集（检测 SOP §4.1~§10.1 检查项目 + §十三 证据等级）。
 *
 * 只读「权限状态 / 服务状态 / 事件时间戳 / 数据库聚合」四类现成证据
 * （检测 §十九：禁止高频轮询、常驻线程、网络请求、读短信、截图 OCR）。
 * 判定逻辑全部在纯函数 [HealthEvaluator]，本类负责取证与拼装展示文案。
 *
 * 刷新时机（检测 §十八）：App 启动一次、进入设置页一次、用户点「重新检测」、
 * 页面回前台（权限可能在系统设置里变了）——绝不做每分钟级定时检测。
 */
object HealthCollector {

    private val _report = MutableStateFlow<HealthReport?>(null)

    /** 最近一次检测结果；null = 尚未检测（首次安装 / 进程刚起） */
    val report: StateFlow<HealthReport?> = _report.asStateFlow()

    /** 采集 + 判定 + 落缓存；返回本次报告 */
    suspend fun refresh(context: Context, now: Long = System.currentTimeMillis()): HealthReport =
        collect(context, now).also { _report.value = it }

    /** 只采集判定，不写缓存（测试/复用） */
    suspend fun collect(context: Context, now: Long): HealthReport {
        val snapshot = App.graph.health.snapshot.value

        // ---------- §四 通知监听 ----------
        val notifPermission = PermissionUtil.isNotificationListenerEnabled(context)
        val notifDisconnectedFor = if (!snapshot.listenerBound && snapshot.lastDisconnectedAt > 0) {
            now - snapshot.lastDisconnectedAt
        } else {
            null
        }
        val notifStatus = HealthEvaluator.notification(
            permissionGranted = notifPermission,
            connected = snapshot.listenerBound,
            disconnectedForMs = notifDisconnectedFor,
            consecutiveFailures = snapshot.consecutiveErrors,
        )
        val notification = ModuleHealth(
            module = HealthModule.NOTIFICATION,
            status = notifStatus,
            summary = when (notifStatus) {
                HealthStatus.DISABLED -> "通知监听未开启"
                HealthStatus.ERROR -> "通知监听无法正常工作"
                HealthStatus.WARNING ->
                    if (snapshot.listenerBound) "最近解析出现异常"
                    else "通知监听可能受到系统限制"
                HealthStatus.UNKNOWN -> "无法确认服务状态"
                HealthStatus.HEALTHY -> "权限与服务连接正常"
            },
            details = listOf(
                HealthDetail("通知使用权", if (notifPermission) "已授予" else "未授予"),
                HealthDetail("服务连接", if (snapshot.listenerBound) "已连接" else "未连接"),
                HealthDetail("最近连接", rel(now, snapshot.lastConnectedAt)),
                // 展示用证据：没有收到通知绝不参与判定（检测 §十二）
                HealthDetail("最近事件", rel(now, snapshot.lastEventAt)),
                HealthDetail("连续失败", snapshot.consecutiveErrors.toString()),
            ),
            action = if (notifPermission) null else HealthAction.OPEN_NOTIFICATION_ACCESS,
        )

        // ---------- §五 短信识别 ----------
        val smsEnabled = runCatching { App.graph.isSmsSourceEnabled() }.getOrDefault(true)
        val smsPermission = runCatching { SmsPermissionManager.hasPermission(context) }
            .getOrDefault(false)
        val smsStatus = HealthEvaluator.sms(
            enabled = smsEnabled,
            permissionGranted = smsPermission,
            consecutiveFailures = snapshot.consecutiveSmsFailures,
        )
        val sms = ModuleHealth(
            module = HealthModule.SMS,
            status = smsStatus,
            label = when {
                !smsEnabled -> "未开启"
                !smsPermission -> "需要授权" // 开关开 + 缺权限：状态词与摘要必须同侧
                else -> smsStatus.label
            },
            summary = when {
                !smsEnabled -> "短信自动识别未开启"
                !smsPermission -> "功能已开启，但缺少短信权限"
                smsStatus == HealthStatus.ERROR -> "短信识别无法正常工作"
                smsStatus == HealthStatus.WARNING -> "最近短信识别异常"
                snapshot.lastSmsEventAt == 0L ->
                    "已开启，尚未检测到新的物流短信" // 无事件 ≠ 异常（§十二），只作说明
                else -> "短信识别正常"
            },
            details = listOf(
                HealthDetail(
                    "来源开关",
                    if (smsEnabled) "已开启（默认开启）" else "已关闭（默认开启）",
                ),
                HealthDetail("短信权限", if (smsPermission) "已授予" else "未授予"),
                HealthDetail("最近短信", rel(now, snapshot.lastSmsEventAt)),
                HealthDetail("最近入库", rel(now, snapshot.lastSmsParsedAt)),
                HealthDetail("连续失败", snapshot.consecutiveSmsFailures.toString()),
            ),
            action = if (smsEnabled && !smsPermission) HealthAction.OPEN_SMS_PERMISSION else null,
        )

        // ---------- §六 无障碍识别 ----------
        val a11yEnabled = PermissionUtil.isAccessibilityEnabled(context)
        val a11yStatus = HealthEvaluator.accessibility(enabled = a11yEnabled)
        val accessibility = ModuleHealth(
            module = HealthModule.ACCESSIBILITY,
            status = a11yStatus,
            summary = when (a11yStatus) {
                HealthStatus.DISABLED -> "页面自动识别未开启"
                HealthStatus.ERROR -> "页面自动识别无法正常工作"
                HealthStatus.WARNING -> "无障碍服务暂时不稳定"
                HealthStatus.UNKNOWN -> "无法确认服务状态"
                HealthStatus.HEALTHY -> "自动填单号服务已启用"
            },
            details = listOf(
                HealthDetail("服务", if (a11yEnabled) "已启用" else "未启用"),
            ),
            action = if (a11yEnabled) null else HealthAction.OPEN_ACCESSIBILITY,
        )

        // ---------- §七 后台运行 + §八 待补全任务（共享数据库证据） ----------
        // 系统电池优化白名单 = 纯风险提示（展示用），不参与后台状态判定
        val batteryRestricted: Boolean? =
            runCatching { PermissionUtil.isIgnoringBatteryOptimizations(context) }
                .getOrNull()
                ?.let { ignoring -> !ignoring }

        val pendingEvidence = try {
            PendingEvidence(
                readable = true,
                waiting = App.graph.repository.countPendingWaiting(),
                nextCheckAt = App.graph.repository.earliestPendingCheckAt(),
                expireAt = App.graph.repository.earliestPendingExpireAt(),
                inconsistent = App.graph.repository.countPendingInconsistent(),
            )
        } catch (t: Throwable) {
            PendingEvidence(readable = false, waiting = 0, nextCheckAt = null, expireAt = null, inconsistent = 0)
        }

        val hasPendingWork = pendingEvidence.readable && pendingEvidence.waiting > 0
        val bgStatus = HealthEvaluator.background(
            enabled = true, // 无独立开关；DISABLED 分支留给未来开关
            hasPendingWork = hasPendingWork,
            nextCheckAt = if (hasPendingWork) pendingEvidence.nextCheckAt else null,
            lastAttemptAt = BackgroundHealth.lastAttemptAt.takeIf { it > 0 },
            lastSuccessAt = BackgroundHealth.lastSuccessAt.takeIf { it > 0 },
            consecutiveFailures = BackgroundHealth.consecutiveFailures,
            now = now,
        )
        // 系统电池优化 / OEM 后台管理 = 纯风险提示，只进 subLine，不参与状态判定
        val riskText = when (batteryRestricted) {
            true -> "存在系统限制"
            false -> "无明显限制"
            null -> "无法确认"
        }
        val delayed = hasPendingWork && pendingEvidence.nextCheckAt != null &&
            now > pendingEvidence.nextCheckAt
        val background = ModuleHealth(
            module = HealthModule.BACKGROUND,
            status = bgStatus,
            summary = when (bgStatus) {
                HealthStatus.DISABLED -> "后台自动运行已关闭"
                HealthStatus.ERROR -> "后台任务长时间未执行，可能受到系统限制"
                HealthStatus.WARNING ->
                    if (delayed) "后台任务存在延迟" else "后台任务近期出现失败"
                HealthStatus.UNKNOWN -> "暂时无法确认后台运行状态"
                HealthStatus.HEALTHY ->
                    if (hasPendingWork) "后台任务调度正常" else "当前没有需要执行的后台任务"
            },
            subLine = "系统限制风险：$riskText",
            details = listOf(
                HealthDetail("后台开关", "已开启"),
                HealthDetail("待补全任务", "${pendingEvidence.waiting} 个"),
                HealthDetail("下次执行", planAt(now, pendingEvidence.nextCheckAt)),
                HealthDetail("最近后台执行", rel(now, BackgroundHealth.lastAttemptAt)),
                HealthDetail("最近成功", rel(now, BackgroundHealth.lastSuccessAt)),
                HealthDetail("连续失败", BackgroundHealth.consecutiveFailures.toString()),
                HealthDetail("系统限制风险", riskText),
            ),
            action = if (batteryRestricted == true) HealthAction.OPEN_BATTERY_SETTINGS else null,
        )

        val pendingStatus = HealthEvaluator.pendingShipment(
            enabled = true, // 无独立开关（检测 §8.5 留给未来开关）
            readable = pendingEvidence.readable,
            waitingCount = pendingEvidence.waiting,
            nextCheckAt = pendingEvidence.nextCheckAt,
            expireAtMs = pendingEvidence.expireAt?.let { it - now },
            inconsistentCount = pendingEvidence.inconsistent,
            now = now,
        )
        val pendingShipment = ModuleHealth(
            module = HealthModule.PENDING_SHIPMENT,
            status = pendingStatus,
            summary = when {
                pendingStatus == HealthStatus.DISABLED -> "自动补全已关闭"
                pendingStatus == HealthStatus.ERROR ->
                    if (pendingEvidence.inconsistent > 0) "待补全数据存在矛盾"
                    else "待补全任务超时未执行"
                pendingStatus == HealthStatus.WARNING ->
                    if (pendingEvidence.waiting > 0 && pendingEvidence.nextCheckAt != null &&
                        now > pendingEvidence.nextCheckAt
                    ) {
                        "有待补全订单已过计划检查时间"
                    } else {
                        "有待补全订单即将到期"
                    }
                pendingStatus == HealthStatus.UNKNOWN -> "无法读取待补全数据"
                pendingEvidence.waiting == 0 -> "当前没有待补全订单"
                else -> "待补全任务调度正常"
            },
            details = listOf(
                HealthDetail("等待补全", "${pendingEvidence.waiting} 个"),
                HealthDetail("下次检查", pendingEvidence.nextCheckAt?.let { rel(now, it) } ?: "无"),
                HealthDetail("最近到期", pendingEvidence.expireAt?.let { rel(now, it) } ?: "无"),
                HealthDetail(
                    "数据一致性",
                    if (pendingEvidence.inconsistent == 0) "正常"
                    else "${pendingEvidence.inconsistent} 条矛盾",
                ),
            ),
        )

        // ---------- §九 Widget ----------
        val widgetCount = runCatching {
            AppWidgetManager.getInstance(context)
                ?.getAppWidgetIds(ComponentName(context, TodoWidgetProvider::class.java))
                ?.size
        }.getOrNull()
        val widgetStatus = HealthEvaluator.widget(widgetCount)
        val widget = ModuleHealth(
            module = HealthModule.WIDGET,
            status = widgetStatus,
            summary = when (widgetStatus) {
                HealthStatus.DISABLED -> "桌面 Widget 未添加"
                HealthStatus.ERROR -> "Widget 更新链路异常"
                HealthStatus.WARNING -> "Widget 更新出现失败"
                HealthStatus.UNKNOWN -> "无法确认 Widget 状态"
                HealthStatus.HEALTHY -> "Widget 更新链路正常"
            },
            details = listOf(
                HealthDetail(
                    "实例数",
                    widgetCount?.let { "$it 个" } ?: "系统未提供",
                ),
            ),
        )

        // ---------- §十 灵动岛 ----------
        val islandEnabled = App.graph.island.enabled
        val overlayPending = App.graph.island.overlayEnabled &&
            !PermissionUtil.canDrawOverlays(context)
        val islandStatus = HealthEvaluator.island(
            enabled = islandEnabled,
            overlayPending = overlayPending,
            consecutiveFailures = 0, // 无失败计数器：没有证据不猜 ERROR（检测 §二十）
        )
        val island = ModuleHealth(
            module = HealthModule.ISLAND,
            status = islandStatus,
            summary = when {
                !islandEnabled -> "未开启（关闭不影响数据）"
                islandStatus == HealthStatus.ERROR -> "灵动岛发送连续失败"
                overlayPending -> "悬浮授权未开启，已回退系统通知"
                else -> "灵动岛正常"
            },
            details = listOf(
                HealthDetail("功能开关", if (islandEnabled) "已开启" else "已关闭"),
                HealthDetail(
                    "悬浮模式",
                    when {
                        !App.graph.island.overlayEnabled -> "未启用"
                        overlayPending -> "待授权"
                        else -> "已授权"
                    },
                ),
            ),
            action = if (overlayPending) HealthAction.OPEN_OVERLAY else null,
        )

        val modules = listOf(
            notification,
            sms,
            accessibility,
            background,
            pendingShipment,
            widget,
            island,
        )
        return HealthReport(
            overall = HealthEvaluator.overall(modules.associate { it.module to it.status }),
            checkedAt = now,
            modules = modules,
        )
    }

    /** 待补全任务的数据库证据包（读库失败 → readable=false，检测 §8.6 → UNKNOWN） */
    private data class PendingEvidence(
        val readable: Boolean,
        val waiting: Int,
        val nextCheckAt: Long?,
        val expireAt: Long?,
        val inconsistent: Int,
    )

    private fun rel(now: Long, ts: Long): String =
        if (ts > 0) TimeUtil.relative(now, ts) else "无"

    /** 「下次执行」：未来时间 = N 分钟后；已过期走 [rel]（刚刚 / N 分钟前 / N 小时前） */
    private fun planAt(now: Long, ts: Long?): String = when {
        ts == null || ts <= 0L -> "无"
        ts > now -> "${(ts - now + 59_999L) / 60_000L} 分钟后"
        else -> rel(now, ts)
    }
}
