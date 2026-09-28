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

import com.parcelhub.autoquery.cainiao.CainiaoLaunchGate
import com.parcelhub.autoquery.cainiao.CainiaoLaunchResult
import com.parcelhub.autoquery.cainiao.CainiaoPackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 菜鸟启动门禁单测（参考 SOP §10 防重复与二次判断 / §11.2 包名候选 / §11.4 结果语义）。
 */
class CainiaoLaunchGateTest {

    private val now = 1_700_000_000_000L
    private val gate = CainiaoLaunchGate()

    private fun input(
        tracking: String? = "SF123456789",
        pickupCode: String? = null,
        status: DeliveryStatus = DeliveryStatus.ARRIVED_STATION,
        sourcePlace: String? = "东连街道菜鸟驿站",
        enabled: Boolean = true,
        manual: Boolean = false,
    ) = AutoQueryInput(
        autoQueryEnabled = enabled,
        trackingNumber = tracking,
        pickupCode = pickupCode,
        sourcePlace = sourcePlace,
        deliveryStatus = status,
        now = now,
        manual = manual,
    )

    /** T04：同单号连续 5 条通知 → 只放行 1 次触发（SOP §10.1 默认 30 秒去重） */
    @Test
    fun five_consecutive_notifications_acquire_once() {
        var allowed = 0
        for (i in 0 until 5) {
            if (gate.tryAcquire("SF123456789", now + i * 200L)) allowed++
        }
        assertEquals(1, allowed)
    }

    /** 去重窗口过后可再次触发；任务结束后 release 可立即再次触发 */
    @Test
    fun dedup_window_and_release() {
        assertTrue(gate.tryAcquire("SF1", now))
        assertFalse(gate.tryAcquire("SF1", now + 29_999L))
        assertTrue(gate.tryAcquire("SF1", now + CainiaoLaunchGate.DEDUP_WINDOW_MS))

        assertTrue(gate.tryAcquire("SF2", now))
        gate.release("SF2")
        assertTrue(gate.tryAcquire("SF2", now + 1L))
    }

    /** 单号为空不放行；不同单号互不影响 */
    @Test
    fun blank_tracking_rejected_and_per_tracking_isolated() {
        assertFalse(gate.tryAcquire(null, now))
        assertFalse(gate.tryAcquire("", now))
        assertFalse(gate.tryAcquire("   ", now))

        assertTrue(gate.tryAcquire("SF1", now))
        assertTrue(gate.tryAcquire("YT2", now))
        assertEquals(0L, gate.lastTriggeredAt("UNKNOWN"))
        assertEquals(now, gate.lastTriggeredAt("SF1"))
    }

    /** reset 清空窗口（服务重连恢复时用，SOP §31） */
    @Test
    fun reset_clears_window() {
        assertTrue(gate.tryAcquire("SF1", now))
        gate.reset()
        assertTrue(gate.tryAcquire("SF1", now + 1L))
    }

    /** SOP §10.2 延迟二次判断：补到取件码 / 变成家门口已送达 → 取消打开菜鸟 */
    @Test
    fun recheck_cancels_when_pickup_code_appears() {
        // 原始通知：驿站 + 无取件码 → 需要查询，不取消
        assertFalse(CainiaoLaunchGate.shouldCancel(input()))

        // 1～2 秒后重新读库，取件码到了 → 取消
        assertTrue(CainiaoLaunchGate.shouldCancel(input(pickupCode = "A12-35")))

        // 变成家门口已送达 → 取消
        assertTrue(
            CainiaoLaunchGate.shouldCancel(
                input(status = DeliveryStatus.DELIVERED_DOOR, sourcePlace = "家门口"),
            ),
        )

        // 自动查询被关闭 → 同样取消
        assertTrue(CainiaoLaunchGate.shouldCancel(input(enabled = false)))
    }

    /** 冷却（EXPRESS_SMART_QUERY §9）在二次判断里同样生效 */
    @Test
    fun recheck_respects_cooldown() {
        val cooling = input().copy(lastAutoQueryAt = now - 60_000L)
        assertTrue(CainiaoLaunchGate.shouldCancel(cooling))
    }

    /** SOP §11.2：包名候选 */
    @Test
    fun cainiao_package_candidates() {
        assertTrue(CainiaoPackages.candidates.contains("com.cainiao.wireless"))
        assertTrue(CainiaoPackages.isCainiao("com.cainiao.wireless"))
        assertFalse(CainiaoPackages.isCainiao("com.taobao.taobao"))
        assertFalse(CainiaoPackages.isCainiao(null))
    }

    /** SOP §11.4：Opened 只代表启动成功，不代表查询成功 */
    @Test
    fun launch_result_opened_is_not_query_success() {
        assertTrue(CainiaoLaunchResult.Opened("com.cainiao.wireless").isOpened)
        assertTrue(
            CainiaoLaunchResult.DeepLinkOpened("com.cainiao.wireless", "cainiao://x").isOpened,
        )
        assertFalse(CainiaoLaunchResult.NotInstalled.isOpened)
        assertFalse(CainiaoLaunchResult.BackgroundLaunchBlocked("com.cainiao.wireless").isOpened)
        assertFalse(CainiaoLaunchResult.Failed("ActivityNotFound").isOpened)
    }

    /** 端到端（纯逻辑）：决策 → 门禁放行 → 只建一个任务 */
    @Test
    fun decision_plus_gate_creates_single_task() {
        val i = input()
        assertEquals(AutoQueryDecisionType.QUERY, AutoQueryDecisionEngine.decide(i))
        assertTrue(gate.tryAcquire(i.trackingNumber, now))
        // 第二条通知：决策仍为 QUERY，但门禁拒绝 → 不会重复建任务
        assertEquals(AutoQueryDecisionType.QUERY, AutoQueryDecisionEngine.decide(i))
        assertFalse(gate.tryAcquire(i.trackingNumber, now + 1_000L))
    }
}
