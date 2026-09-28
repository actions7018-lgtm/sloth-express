/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓动曲线（CSS/Material 同款 cubic-bezier）。
 * 曲线解错了的表现是「动画忽快忽慢甚至倒着走」，单测比真机更容易发现。
 */
class EasingTest {

    @Test
    fun endpoints_are_exact() {
        val f = Easing.cubicBezier(0.4f, 0f, 0.2f, 1f)
        assertEquals(0f, f(0f), 1e-4f)
        assertEquals(1f, f(1f), 1e-4f)
        // 越界直接短路，不能返回越界值
        assertEquals(0f, f(-1f), 1e-4f)
        assertEquals(1f, f(2f), 1e-4f)
    }

    @Test
    fun curves_are_monotonic() {
        // 非单调 = 动画会「倒退一下」，肉眼看着就是抖
        for (curve in listOf(Easing.standard, Easing.decelerate, Easing.accelerate)) {
            var prev = -1f
            var x = 0f
            while (x <= 1f) {
                val y = curve(x)
                assertTrue("曲线在 x=$x 处回退（$y < $prev）", y >= prev - 1e-4f)
                prev = y
                x += 0.05f
            }
        }
    }

    @Test
    fun decelerate_starts_fast_and_accelerate_starts_slow() {
        // 进入曲线前段就要走完大部分位移；退出曲线前段几乎不动 —— 手感的来源
        assertTrue("decelerate 前段应超过线性", Easing.decelerate(0.25f) > 0.3f)
        assertTrue("accelerate 前段应低于线性", Easing.accelerate(0.25f) < 0.15f)
    }

    @Test
    fun standard_midpoint_is_front_loaded_s_curve() {
        // material standard 是 S 曲线：前半段先压住、后半段加速再收住。
        // 中点算出来约 0.78（P1=(0.4,0)、P2=(0.2,1) 决定），明显高于 0.5 才说明解对了
        val mid = Easing.standard(0.5f)
        assertTrue("standard(0.5)=$mid 不在预期区间", mid in 0.6f..0.9f)
        // 同一条曲线在前段要压住（u=0.25 时进度应略低于线性）
        assertTrue("standard 前段应略低于线性", Easing.standard(0.25f) < 0.35f)
    }

    @Test
    fun back_out_overshoots_then_returns_to_one() {
        assertEquals(0f, Easing.backOut(0f), 1e-4f)
        assertEquals(1f, Easing.backOut(1f), 1e-4f)
        var max = Float.MIN_VALUE
        var x = 0f
        while (x <= 1f) {
            max = maxOf(max, Easing.backOut(x))
            x += 0.02f
        }
        assertTrue("backOut 必须冲过 1 才有回弹感（max=$max）", max > 1.02f)
        assertTrue("回弹不能太夸张（max=$max）", max < 1.2f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun x_control_points_outside_01_are_rejected() {
        // x 不在 [0,1] 会让 x(s) 非单调 → 解出来的曲线会倒退，直接禁止
        Easing.cubicBezier(1.5f, 0f, 0.2f, 1f)
    }
}
