/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR 触发闸单测（SOP V2.0 §19/§20）：基础冷却 → 指数退避 → 成功清零 → 换页重置。
 */
class OcrGateTest {

    @Test
    fun fresh_gate_allows_immediately() {
        val gate = OcrGate()
        assertTrue(gate.allow(1_000_000L))
        assertEquals(OcrGate.DEFAULT_BACKOFF_MS[0], gate.cooldownMs)
    }

    @Test
    fun attempt_starts_page_cooldown() {
        val gate = OcrGate()
        gate.markAttempt(1_000_000L)
        assertFalse("10s < 30s 基础冷却", gate.allow(1_010_000L))
        assertTrue("30s 后放行", gate.allow(1_030_001L))
    }

    @Test
    fun failures_back_off_exponentially() {
        val gate = OcrGate()
        val t = 10_000_000L
        gate.markAttempt(t)
        gate.markFailure()
        assertFalse("1 次失败 → 2 分钟退避", gate.allow(t + 31_000L))
        assertTrue(gate.allow(t + 120_001L))

        gate.markAttempt(t + 120_001L)
        gate.markFailure()
        assertFalse("2 次失败 → 10 分钟退避", gate.allow(t + 120_001L + 121_000L))
        assertTrue(gate.allow(t + 120_001L + 600_001L))

        repeat(5) { gate.markFailure() }
        assertEquals("退避封顶 10 分钟", OcrGate.DEFAULT_BACKOFF_MS.last(), gate.cooldownMs)
    }

    @Test
    fun success_resets_backoff_to_base_cooldown() {
        val gate = OcrGate()
        gate.markAttempt(1_000_000L)
        gate.markFailure()
        gate.markFailure()
        assertEquals(OcrGate.DEFAULT_BACKOFF_MS[2], gate.cooldownMs)
        gate.markSuccess()
        assertEquals(0, gate.failureCount)
        assertEquals(OcrGate.DEFAULT_BACKOFF_MS[0], gate.cooldownMs)
    }

    @Test
    fun reset_window_grants_immediate_retry() {
        val gate = OcrGate()
        gate.markAttempt(1_000_000L)
        gate.markFailure()
        assertFalse(gate.allow(1_001_000L))
        gate.resetWindow()
        assertTrue("换页 / 新焦点后立即放行", gate.allow(1_001_000L))
    }
}
