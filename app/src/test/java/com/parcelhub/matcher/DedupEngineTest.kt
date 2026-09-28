/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.matcher

import com.parcelhub.model.EventType
import com.parcelhub.model.ShipmentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 去重引擎单测（SOP §10.3 / §12.1 §12.2）。
 */
class DedupEngineTest {

    @Test
    fun event_dedup_key_is_stable_and_distinguishing() {
        val key = DedupEngine.eventDedupKey("com.cainiao.wireless", "key-1", EventType.ARRIVED)
        assertEquals("com.cainiao.wireless|key-1|ARRIVED", key)
        assertEquals(key, DedupEngine.eventDedupKey("com.cainiao.wireless", "key-1", EventType.ARRIVED))

        assertNotEquals(
            key,
            DedupEngine.eventDedupKey("com.cainiao.wireless", "key-2", EventType.ARRIVED),
        )
        assertNotEquals(
            key,
            DedupEngine.eventDedupKey("com.fcbox.bxlm", "key-1", EventType.ARRIVED),
        )
        assertNotEquals(
            key,
            DedupEngine.eventDedupKey("com.cainiao.wireless", "key-1", EventType.PICKUP_CODE_AVAILABLE),
        )
    }

    @Test
    fun semantic_key_buckets_time_window() {
        val window = 5 * 60_000L
        val a = DedupEngine.semanticKey("中通", "771234567890", EventType.IN_TRANSIT, window * 3 + 1)
        val b = DedupEngine.semanticKey("中通", "771234567890", EventType.IN_TRANSIT, window * 3 + 2)
        val c = DedupEngine.semanticKey("中通", "771234567890", EventType.IN_TRANSIT, window * 4 + 1)

        // 同窗口内视为同一条语义事件（通知更新不算新事件）
        assertEquals(a, b)
        assertNotEquals(a, c)

        // 承运商大小写归一
        assertEquals(
            DedupEngine.semanticKey("SF", "abc", EventType.SHIPPED, 0L),
            DedupEngine.semanticKey("sf", "abc", EventType.SHIPPED, 0L),
        )
    }

    @Test
    fun remind_only_once_in_window() {
        val t0 = 1_000_000L
        val signature = DedupEngine.ReminderSignature(
            status = ShipmentStatus.ARRIVED,
            pickupCode = "16-4-9626",
            pickupLocation = "东门驿站",
            firstSeenAt = t0,
        )

        // 首次必提醒
        assertTrue(DedupEngine.shouldRemind(null, signature, t0))

        // 同状态+同码+同地点，窗口内不重复提醒
        assertFalse(DedupEngine.shouldRemind(signature, signature, t0 + 60_000L))

        // 超过窗口后可以再次提醒
        assertTrue(
            DedupEngine.shouldRemind(
                signature,
                signature,
                t0 + 30 * 60_000L,
            ),
        )
    }

    @Test
    fun remind_when_signature_changes() {
        val t0 = 1_000_000L
        val previous = DedupEngine.ReminderSignature(
            status = ShipmentStatus.ARRIVED,
            pickupCode = "16-4-9626",
            pickupLocation = "东门驿站",
            firstSeenAt = t0,
        )
        val changed = previous.copy(pickupCode = "2-2-7508")
        val statusChanged = previous.copy(status = ShipmentStatus.OUT_FOR_DELIVERY)

        assertTrue(DedupEngine.shouldRemind(previous, changed, t0 + 1_000L))
        assertTrue(DedupEngine.shouldRemind(previous, statusChanged, t0 + 1_000L))
    }
}
