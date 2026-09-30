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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自动检测判定规则（检测 SOP §四~§十 模块规则 + §十五 总状态 + §二十 反误判守则）。
 *
 * 重点覆盖反例：没有事件 ≠ ERROR、功能关闭 ≠ ERROR、一次失败 ≠ ERROR。
 */
class HealthEvaluatorTest {

    private val h = 60 * 60 * 1000L

    // ---------- §四 通知监听 ----------

    @Test
    fun notification_permission_off_is_disabled_not_error() {
        assertEquals(
            HealthStatus.DISABLED,
            HealthEvaluator.notification(false, false, null, 0),
        )
    }

    @Test
    fun notification_connected_is_healthy_even_without_events() {
        // §十二：今天没收到快递通知 ≠ 故障
        assertEquals(
            HealthStatus.HEALTHY,
            HealthEvaluator.notification(true, true, null, 0),
        )
    }

    @Test
    fun notification_disconnected_within_6h_is_warning() {
        assertEquals(
            HealthStatus.WARNING,
            HealthEvaluator.notification(true, false, 2 * h, 0),
        )
    }

    @Test
    fun notification_disconnected_over_6h_is_error() {
        assertEquals(
            HealthStatus.ERROR,
            HealthEvaluator.notification(true, false, 7 * h, 0),
        )
    }

    @Test
    fun notification_never_connected_is_warning_not_error() {
        // 没有断开时长证据 → 不许猜 ERROR（§二十）
        assertEquals(
            HealthStatus.WARNING,
            HealthEvaluator.notification(true, false, null, 0),
        )
    }

    @Test
    fun notification_single_failure_is_warning_not_error() {
        assertEquals(
            HealthStatus.WARNING,
            HealthEvaluator.notification(true, true, null, 1),
        )
    }

    @Test
    fun notification_3_consecutive_failures_is_error() {
        assertEquals(
            HealthStatus.ERROR,
            HealthEvaluator.notification(true, true, null, 3),
        )
    }

    // ---------- §五 短信识别 ----------

    @Test
    fun sms_switch_off_is_disabled() {
        assertEquals(HealthStatus.DISABLED, HealthEvaluator.sms(false, true, 0))
    }

    @Test
    fun sms_enabled_but_permission_missing_is_warning_not_disabled() {
        // 开关开 + 缺权限 = 「需要授权」：状态词与摘要必须同侧，不许一边“已开启”一边“未开启”
        assertEquals(HealthStatus.WARNING, HealthEvaluator.sms(true, false, 0))
    }

    @Test
    fun sms_switch_off_stays_disabled_even_without_permission() {
        assertEquals(HealthStatus.DISABLED, HealthEvaluator.sms(false, false, 0))
    }

    @Test
    fun sms_enabled_with_permission_is_healthy_without_any_sms() {
        // §5.2/§12：30 天没短信也不能判异常
        assertEquals(HealthStatus.HEALTHY, HealthEvaluator.sms(true, true, 0))
    }

    @Test
    fun sms_3_consecutive_failures_is_error() {
        assertEquals(HealthStatus.ERROR, HealthEvaluator.sms(true, true, 3))
    }

    @Test
    fun sms_single_failure_is_warning() {
        assertEquals(HealthStatus.WARNING, HealthEvaluator.sms(true, true, 1))
    }

    // ---------- §六 无障碍识别 ----------

    @Test
    fun a11y_disabled_is_disabled() {
        assertEquals(HealthStatus.DISABLED, HealthEvaluator.accessibility(false))
    }

    @Test
    fun a11y_enabled_is_healthy() {
        assertEquals(HealthStatus.HEALTHY, HealthEvaluator.accessibility(true))
    }

    @Test
    fun a11y_enabled_but_disconnected_is_warning() {
        assertEquals(
            HealthStatus.WARNING,
            HealthEvaluator.accessibility(true, connected = false, disconnectedForMs = 5 * 60_000L),
        )
    }

    @Test
    fun a11y_disconnected_over_30min_is_error() {
        assertEquals(
            HealthStatus.ERROR,
            HealthEvaluator.accessibility(true, connected = false, disconnectedForMs = 31 * 60_000L),
        )
    }

    // ---------- §七 后台运行（只看实际运行证据；电池/OEM 限制 = 纯风险提示，不进状态） ----------

    private fun background(
        enabled: Boolean = true,
        pending: Boolean = false,
        nextCheckAt: Long? = null,
        lastAttemptAt: Long? = 1L,
        lastSuccessAt: Long? = 1L,
        failures: Int = 0,
        now: Long = 1_000 * h,
    ) = HealthEvaluator.background(
        enabled = enabled,
        hasPendingWork = pending,
        nextCheckAt = nextCheckAt,
        lastAttemptAt = lastAttemptAt,
        lastSuccessAt = lastSuccessAt,
        consecutiveFailures = failures,
        now = now,
    )

    @Test
    fun background_switch_off_is_disabled() {
        assertEquals(HealthStatus.DISABLED, background(enabled = false, pending = true))
    }

    @Test
    fun background_on_without_pending_is_healthy() {
        // 验证 1：后台开启 + 没有待补全任务 → HEALTHY（不看电池/OEM）
        assertEquals(HealthStatus.HEALTHY, background(pending = false))
    }

    @Test
    fun background_with_pending_and_normal_schedule_is_healthy() {
        // 验证 2：开启 + 有任务 + 调度正常（nextCheckAt 在未来）→ HEALTHY
        val now = 1_000 * h
        assertEquals(
            HealthStatus.HEALTHY,
            background(pending = true, nextCheckAt = now + h, now = now),
        )
    }

    @Test
    fun background_task_delay_2h_is_warning() {
        // 验证 3：开启 + 有任务 + 延迟 2 小时 → WARNING
        val now = 1_000 * h
        assertEquals(
            HealthStatus.WARNING,
            background(pending = true, nextCheckAt = now - 2 * h, now = now),
        )
    }

    @Test
    fun background_task_delay_over_24h_is_error() {
        // 验证 4：开启 + 有任务 + 超过 24 小时没执行 → ERROR
        val now = 1_000 * h
        assertEquals(
            HealthStatus.ERROR,
            background(pending = true, nextCheckAt = now - 25 * h, now = now),
        )
    }

    @Test
    fun background_pending_without_schedule_is_error() {
        // 有任务却无调度 = Worker 被取消 / 未注册 → 永远不会执行 → ERROR
        assertEquals(HealthStatus.ERROR, background(pending = true, nextCheckAt = null))
    }

    @Test
    fun background_without_run_evidence_is_unknown_not_warning() {
        // 验证 6：无失败证据 + 无运行证据 → UNKNOWN（不许 WARNING/ERROR）
        assertEquals(
            HealthStatus.UNKNOWN,
            background(lastAttemptAt = null, lastSuccessAt = null),
        )
    }

    @Test
    fun background_with_run_evidence_is_healthy_regardless_of_rom_risk() {
        // 验证 6/7：有正常运行证据 → HEALTHY；函数根本没有“电池/OEM 限制”入参，
        // “国产 ROM 可能限制后台”在结构上就无法判出 WARNING
        assertEquals(HealthStatus.HEALTHY, background(lastAttemptAt = 10L, lastSuccessAt = 10L))
    }

    @Test
    fun background_single_real_failure_is_warning() {
        // 真实失败证据（非“理论上可能受限”）才提示
        assertEquals(HealthStatus.WARNING, background(failures = 1))
    }

    @Test
    fun background_3_consecutive_failures_is_error() {
        assertEquals(HealthStatus.ERROR, background(failures = 3, pending = true, nextCheckAt = 1L))
    }

    // ---------- §八 待补全任务 ----------

    private fun pending(
        readable: Boolean = true,
        waiting: Int = 0,
        nextCheckAt: Long? = null,
        expireAtMs: Long? = null,
        inconsistent: Int = 0,
        now: Long = 1_000 * h,
        enabled: Boolean = true,
    ) = HealthEvaluator.pendingShipment(
        enabled = enabled,
        readable = readable,
        waitingCount = waiting,
        nextCheckAt = nextCheckAt,
        expireAtMs = expireAtMs,
        inconsistentCount = inconsistent,
        now = now,
    )

    @Test
    fun pending_no_tasks_is_healthy() {
        assertEquals(HealthStatus.HEALTHY, pending())
    }

    @Test
    fun pending_unreadable_is_unknown_not_error() {
        assertEquals(HealthStatus.UNKNOWN, pending(readable = false))
    }

    @Test
    fun pending_schedule_in_future_is_healthy() {
        val now = 1_000 * h
        assertEquals(HealthStatus.HEALTHY, pending(waiting = 2, nextCheckAt = now + h, now = now))
    }

    @Test
    fun pending_delay_under_6h_is_warning() {
        val now = 1_000 * h
        assertEquals(
            HealthStatus.WARNING,
            pending(waiting = 1, nextCheckAt = now - 3 * h, now = now),
        )
    }

    @Test
    fun pending_delay_over_24h_is_error() {
        val now = 1_000 * h
        assertEquals(
            HealthStatus.ERROR,
            pending(waiting = 1, nextCheckAt = now - 25 * h, now = now),
        )
    }

    @Test
    fun pending_waiting_without_schedule_is_error() {
        assertEquals(HealthStatus.ERROR, pending(waiting = 1, nextCheckAt = null))
    }

    @Test
    fun pending_data_inconsistency_is_error() {
        // §8.4：TRACKING_FOUND 却没有单号 = 数据矛盾
        assertEquals(HealthStatus.ERROR, pending(inconsistent = 1))
    }

    @Test
    fun pending_expiring_within_24h_is_warning() {
        val now = 1_000 * h
        assertEquals(
            HealthStatus.WARNING,
            pending(waiting = 1, nextCheckAt = now + h, expireAtMs = 20 * h, now = now),
        )
    }

    @Test
    fun pending_switch_off_is_disabled() {
        assertEquals(HealthStatus.DISABLED, pending(enabled = false, waiting = 1))
    }

    // ---------- §九 Widget ----------

    @Test
    fun widget_not_added_is_disabled_not_error() {
        assertEquals(HealthStatus.DISABLED, HealthEvaluator.widget(0))
    }

    @Test
    fun widget_added_is_healthy() {
        assertEquals(HealthStatus.HEALTHY, HealthEvaluator.widget(2))
    }

    @Test
    fun widget_rom_state_unreadable_is_unknown() {
        assertEquals(HealthStatus.UNKNOWN, HealthEvaluator.widget(null))
    }

    // ---------- §十 灵动岛 ----------

    @Test
    fun island_off_is_disabled() {
        assertEquals(HealthStatus.DISABLED, HealthEvaluator.island(false, false, 0))
    }

    @Test
    fun island_on_is_healthy_without_recent_events() {
        // §10.2/§12：最近没有岛通知 ≠ 故障
        assertEquals(HealthStatus.HEALTHY, HealthEvaluator.island(true, false, 0))
    }

    @Test
    fun island_overlay_pending_is_warning() {
        assertEquals(HealthStatus.WARNING, HealthEvaluator.island(true, true, 0))
    }

    @Test
    fun island_3_consecutive_failures_is_error() {
        assertEquals(HealthStatus.ERROR, HealthEvaluator.island(true, false, 3))
    }

    // ---------- §十五 总状态 ----------

    private fun overall(vararg pairs: Pair<HealthModule, HealthStatus>) =
        HealthEvaluator.overall(pairs.toMap())

    @Test
    fun overall_all_disabled_is_disabled() {
        val all = HealthModule.entries.associateWith { HealthStatus.DISABLED }
        assertEquals(HealthStatus.DISABLED, HealthEvaluator.overall(all))
    }

    @Test
    fun overall_sms_disabled_does_not_taint_health() {
        // §二/§十五：SMS 是可选数据源，DISABLED 不拖累整体
        assertEquals(
            HealthStatus.HEALTHY,
            overall(
                HealthModule.NOTIFICATION to HealthStatus.HEALTHY,
                HealthModule.SMS to HealthStatus.DISABLED,
                HealthModule.ACCESSIBILITY to HealthStatus.HEALTHY,
                HealthModule.BACKGROUND to HealthStatus.HEALTHY,
                HealthModule.PENDING_SHIPMENT to HealthStatus.HEALTHY,
                HealthModule.WIDGET to HealthStatus.DISABLED,
                HealthModule.ISLAND to HealthStatus.DISABLED,
            ),
        )
    }

    @Test
    fun overall_optional_warning_makes_overall_warning() {
        assertEquals(
            HealthStatus.WARNING,
            overall(
                HealthModule.NOTIFICATION to HealthStatus.WARNING,
                HealthModule.BACKGROUND to HealthStatus.HEALTHY,
                HealthModule.PENDING_SHIPMENT to HealthStatus.HEALTHY,
            ),
        )
    }

    @Test
    fun overall_core_error_is_error() {
        assertEquals(
            HealthStatus.ERROR,
            overall(
                HealthModule.BACKGROUND to HealthStatus.ERROR,
                HealthModule.NOTIFICATION to HealthStatus.HEALTHY,
            ),
        )
    }

    @Test
    fun overall_two_enabled_errors_is_error() {
        assertEquals(
            HealthStatus.ERROR,
            overall(
                HealthModule.NOTIFICATION to HealthStatus.ERROR,
                HealthModule.SMS to HealthStatus.ERROR,
            ),
        )
    }

    @Test
    fun overall_single_optional_error_is_warning_not_error() {
        // §十五：非核心单点 ERROR 达不到整体 ERROR 阈值，但绝不能显示 HEALTHY
        assertEquals(
            HealthStatus.WARNING,
            overall(
                HealthModule.NOTIFICATION to HealthStatus.HEALTHY,
                HealthModule.SMS to HealthStatus.ERROR,
                HealthModule.BACKGROUND to HealthStatus.HEALTHY,
            ),
        )
    }

    @Test
    fun overall_unknown_beats_nothing_but_warning_beats_unknown() {
        assertEquals(
            HealthStatus.UNKNOWN,
            overall(
                HealthModule.BACKGROUND to HealthStatus.UNKNOWN,
                HealthModule.ACCESSIBILITY to HealthStatus.HEALTHY,
                HealthModule.NOTIFICATION to HealthStatus.DISABLED,
                HealthModule.SMS to HealthStatus.DISABLED,
            ),
        )
        assertEquals(
            HealthStatus.WARNING,
            overall(
                HealthModule.BACKGROUND to HealthStatus.UNKNOWN,
                HealthModule.ACCESSIBILITY to HealthStatus.WARNING,
            ),
        )
    }

    // ---------- §二 模块内优先级 ----------

    @Test
    fun module_priority_order() {
        assertEquals(HealthStatus.ERROR, HealthStatus.highest(HealthStatus.WARNING, HealthStatus.ERROR))
        assertEquals(HealthStatus.WARNING, HealthStatus.highest(HealthStatus.UNKNOWN, HealthStatus.WARNING))
        assertEquals(HealthStatus.UNKNOWN, HealthStatus.highest(HealthStatus.HEALTHY, HealthStatus.UNKNOWN))
        assertEquals(HealthStatus.HEALTHY, HealthStatus.highest(HealthStatus.DISABLED, HealthStatus.HEALTHY))
    }
}
