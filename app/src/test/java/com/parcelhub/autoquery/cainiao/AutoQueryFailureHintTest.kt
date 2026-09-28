/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.autoquery.cainiao

import org.junit.Assert.assertEquals
import org.junit.Test

/** SOP §31 / T07 / T08：失败原因只说用户听得懂的话 */
class AutoQueryFailureHintTest {

    @Test
    fun timeout_maps_to_user_language() {
        assertEquals("菜鸟页面打开超时", AutoQueryFailureHint.hint("timeout"))
    }

    @Test
    fun loop_guard_maps_to_user_language() {
        assertEquals("菜鸟页面没有反应", AutoQueryFailureHint.hint("action_loop:click_search_entry"))
    }

    @Test
    fun verify_mismatch_maps_to_user_language() {
        assertEquals("填入后校验不一致", AutoQueryFailureHint.hint("verify_mismatch"))
    }

    @Test
    fun set_text_failures_map_to_user_language() {
        assertEquals("单号填入失败", AutoQueryFailureHint.hint("set_text_false"))
        assertEquals("单号填入失败", AutoQueryFailureHint.hint("retry_exhausted"))
    }

    @Test
    fun unknown_and_null_fall_back() {
        assertEquals("自动填单号中断", AutoQueryFailureHint.hint(null))
        assertEquals("自动填单号中断", AutoQueryFailureHint.hint(""))
        assertEquals("自动填单号中断", AutoQueryFailureHint.hint("something_new"))
    }
}
