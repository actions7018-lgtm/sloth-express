/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.pending

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 来源识别单测（需求 §二 sourceType / §三 来源识别 / §十一 来源显示 / §八 焦点）。
 *
 * 覆盖 [PendingSourceResolver] 全部分支、[pendingSourceLabel] 显示顺序
 * 与 [PendingFocus] 的包名命中 / 过期语义。
 */
class PendingSourceTest {

    private val PDD = "com.xunmeng.pinduoduo"
    private val ZTO = "com.zto.zto"

    // ---------- §三 来源识别 ----------

    @Test
    fun sms_relay_source_maps_to_sms_without_app_info() {
        val info = PendingSourceResolver.resolve(
            sourcePackage = PendingSourceResolver.SMS_SOURCE,
            channel = null,
            ruleType = "OTHER",
            ruleAppName = "短信",
            appLabel = "信息",
        )
        assertEquals(PendingSourceType.SMS, info.type)
        assertNull("中转来源不带购物 App 包名", info.packageName)
        assertNull(info.appName)
    }

    @Test
    fun share_relay_source_maps_to_user_input() {
        val info = PendingSourceResolver.resolve(
            sourcePackage = PendingSourceResolver.SHARE_SOURCE,
            channel = null,
            ruleType = null,
            ruleAppName = null,
            appLabel = null,
        )
        assertEquals(PendingSourceType.USER_INPUT, info.type)
        assertNull(info.packageName)
        assertNull(info.appName)
    }

    @Test
    fun ecommerce_rule_yields_ecommerce_with_package_and_label() {
        val info = PendingSourceResolver.resolve(
            sourcePackage = PDD,
            channel = null,
            ruleType = "ECOMMERCE",
            ruleAppName = "拼多多",
            appLabel = "拼多多",
        )
        assertEquals(PendingSourceType.ECOMMERCE, info.type)
        assertEquals(PDD, info.packageName)
        assertEquals("拼多多", info.appName)
    }

    @Test
    fun app_label_wins_over_rule_app_name_and_blank_label_falls_back() {
        // 系统 applicationLabel 优先（需求 §三-2）
        val labeled = PendingSourceResolver.resolve(PDD, null, "ECOMMERCE", "拼多多", "拼多多国际版")
        assertEquals("拼多多国际版", labeled.appName)
        // 标签拿不到 / 空 → 规则 appName 兜底
        val fallback = PendingSourceResolver.resolve(PDD, null, "ECOMMERCE", "拼多多", null)
        assertEquals("拼多多", fallback.appName)
        val blank = PendingSourceResolver.resolve(PDD, null, "ECOMMERCE", "拼多多", "  ")
        assertEquals("拼多多", blank.appName)
    }

    @Test
    fun non_ecommerce_rule_maps_to_notification() {
        // 快递公司通知（rules.json sourceType=LOGISTICS）→ NOTIFICATION（需求 §三-2）
        val info = PendingSourceResolver.resolve(
            sourcePackage = ZTO,
            channel = null,
            ruleType = "LOGISTICS",
            ruleAppName = "中通快递",
            appLabel = "中通快递",
        )
        assertEquals(PendingSourceType.NOTIFICATION, info.type)
        assertEquals(ZTO, info.packageName)
        assertEquals("中通快递", info.appName)
    }

    @Test
    fun explicit_channel_wins_over_package_inference() {
        // 需求 §三-3：无障碍读取产生的事件，即使来自购物 App 也标 ACCESSIBILITY
        val info = PendingSourceResolver.resolve(
            sourcePackage = PDD,
            channel = PendingSourceType.ACCESSIBILITY.name,
            ruleType = "ECOMMERCE",
            ruleAppName = "拼多多",
            appLabel = "拼多多",
        )
        assertEquals(PendingSourceType.ACCESSIBILITY, info.type)
        assertEquals(PDD, info.packageName)
        assertEquals("拼多多", info.appName)
    }

    @Test
    fun unknown_channel_name_degrades_to_notification() {
        assertEquals(
            PendingSourceType.NOTIFICATION,
            PendingSourceResolver.resolve(ZTO, "NOT_A_CHANNEL", "LOGISTICS", "中通", null).type,
        )
    }

    @Test
    fun relay_constants_drift_against_pending_matcher() {
        // 与 PendingMatcher.RELAY_SOURCES 防漂移：两处必须指同一批伪包名
        assertTrue(PendingSourceResolver.SMS_SOURCE in PendingMatcher.RELAY_SOURCES)
        assertTrue(PendingSourceResolver.SHARE_SOURCE in PendingMatcher.RELAY_SOURCES)
        assertEquals(2, PendingMatcher.RELAY_SOURCES.size)
    }

    // ---------- §十一 来源显示 ----------

    @Test
    fun source_label_never_hidden_and_falls_back_by_type() {
        assertEquals("拼多多", pendingSourceLabel("拼多多", PendingSourceType.ECOMMERCE.name))
        assertEquals("短信", pendingSourceLabel(null, PendingSourceType.SMS.name))
        assertEquals("手动分享", pendingSourceLabel(null, PendingSourceType.USER_INPUT.name))
        assertEquals("未知", pendingSourceLabel(null, PendingSourceType.NOTIFICATION.name))
        assertEquals("未知", pendingSourceLabel("  ", PendingSourceType.ECOMMERCE.name))
        assertEquals("未知", pendingSourceLabel(null, null))
    }

    // ---------- §八 补全焦点 ----------

    @Test
    fun focus_matches_only_target_package_within_ttl() {
        PendingFocus.set(PDD, 910_014L, now = 1_000L)
        assertTrue(PendingFocus.matches(PDD, now = 2_000L))
        assertFalse("只监控 sourcePackageName", PendingFocus.matches(ZTO, now = 2_000L))
        assertFalse(PendingFocus.matches(null, now = 2_000L))
        assertEquals(910_014L, PendingFocus.current(2_000L)?.pendingId)
        PendingFocus.clear()
        assertFalse(PendingFocus.matches(PDD, now = 2_000L))
    }

    @Test
    fun focus_expires_after_ttl_and_next_set_overwrites() {
        PendingFocus.set(PDD, 1L, now = 0L)
        assertFalse("TTL 10 分钟后自动失效", PendingFocus.matches(PDD, now = PendingFocus.TTL_MS + 1))
        assertNull(PendingFocus.current(PendingFocus.TTL_MS + 1))

        PendingFocus.set(PDD, 1L, now = 0L)
        PendingFocus.set(ZTO, 2L, now = 10L)
        assertFalse("新焦点覆盖旧焦点", PendingFocus.matches(PDD, now = 11L))
        assertTrue(PendingFocus.matches(ZTO, now = 11L))
        PendingFocus.clear()
    }

    // ---------- 待补全自动监控（PendingWatch） ----------

    @Test
    fun watch_matches_any_waiting_source_package_without_ttl() {
        PendingWatch.reset()
        PendingWatch.update(setOf(PDD, "com.taobao.taobao"))
        assertTrue(PendingWatch.hasWaiting(PDD))
        assertTrue("自动监控无 TTL：集合不更新就一直有效", PendingWatch.hasWaiting("com.taobao.taobao"))
        assertFalse(PendingWatch.hasWaiting(ZTO))
        assertFalse("null / 空包名一律不命中", PendingWatch.hasWaiting(null))
        assertFalse(PendingWatch.hasWaiting(""))
        PendingWatch.reset()
    }

    @Test
    fun watch_follows_full_set_updates_and_clears_when_empty() {
        PendingWatch.reset()
        PendingWatch.update(setOf(PDD))
        assertTrue(PendingWatch.hasWaiting(PDD))
        // 单据转 TRACKING_FOUND / EXPIRED 后 observePending 重发，集合不含该包名
        PendingWatch.update(emptySet())
        assertFalse("补全 / 过期后自动移出监控", PendingWatch.hasWaiting(PDD))
        PendingWatch.update(setOf(ZTO))
        assertTrue("覆盖式更新以最新集合为准", PendingWatch.hasWaiting(ZTO))
        assertFalse(PendingWatch.hasWaiting(PDD))
        PendingWatch.reset()
    }

    @Test
    fun watch_auto_toggle_gates_enabled_sources_pending_still_read() {
        PendingWatch.reset()
        PendingWatch.update(setOf(PDD))                       // 有在等的待补全单
        PendingWatch.updateEnabled(setOf(ZTO, "com.taobao.taobao"))  // 启用中的来源
        PendingWatch.setAuto(false)
        assertTrue("关自动后待补全来源仍读（补全兜底不受开关约束）",
            PendingWatch.shouldAutoRead(PDD))
        assertFalse("关自动后启用来源不读", PendingWatch.shouldAutoRead(ZTO))
        PendingWatch.setAuto(true)
        assertTrue("开自动后启用来源可读", PendingWatch.shouldAutoRead(ZTO))
        assertTrue("开自动后待补全来源可读", PendingWatch.shouldAutoRead(PDD))
        assertFalse("规则外包名永不读", PendingWatch.shouldAutoRead("com.unknown.app"))
        assertFalse("null / 空包名永不读", PendingWatch.shouldAutoRead(null))
        PendingWatch.reset()
    }
}
