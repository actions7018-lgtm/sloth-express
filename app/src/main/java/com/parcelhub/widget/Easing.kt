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

import kotlin.math.abs

/**
 * 三次贝塞尔缓动 —— 与 CSS `cubic-bezier(x1,y1,x2,y2)`、Material Motion、
 * Framer Motion / SwiftUI 用的是**同一条曲线**，只是这里要在 JVM 上算出来。
 *
 * 为什么要自己算：Widget 侧只有 `RemoteViews`，拿不到任何动画框架，
 * 所以「丝滑」只能靠**帧足够密 + 曲线正确**来实现（见 [TodoWidgetFeedback]）：
 * 线性插值在 30fps 下会看得出一格一格的跳，换上曲线后同样是 30fps 观感明显更顺。
 *
 * 曲线常量取业界默认值（改曲线先跑 [EasingTest]）：
 *  - [standard]   `cubic-bezier(0.4, 0, 0.2, 1)` —— Material 的 "standard"，进出通用
 *  - [decelerate] `cubic-bezier(0, 0, 0.2, 1)`    —— 进入：起手快、收尾缓
 *  - [accelerate] `cubic-bezier(0.4, 0, 1, 1)`    —— 退出：起手缓、收尾快
 */
object Easing {

    /** Material standard：进入与退出都适用，先慢后快再收住 */
    val standard: (Float) -> Float = cubicBezier(0.4f, 0f, 0.2f, 1f)

    /** 进入用：一开始就有速度，最后贴合目标 */
    val decelerate: (Float) -> Float = cubicBezier(0f, 0f, 0.2f, 1f)

    /** 退出用：开始几乎不动，越到后面走得越快（像被「吸」走） */
    val accelerate: (Float) -> Float = cubicBezier(0.4f, 0f, 1f, 1f)

    /**
     * 求解 `cubic-bezier(x1,y1,x2,y2)`：给定进度 [progress]∈[0,1]，返回缓动后的值。
     *
     * 做法与浏览器一致：先用牛顿法解 `Bx(s) = progress` 得参数 s（通常 3~4 次就收敛），
     * 收敛不了再二分兜底，最后把 s 代进 y 分量。
     * 端点直接短路，避免 `progress=0/1` 时除零。
     */
    fun cubicBezier(x1: Float, y1: Float, x2: Float, y2: Float): (Float) -> Float {
        require(x1 in 0f..1f && x2 in 0f..1f) { "x 控制点必须落在 [0,1]，否则曲线不单调" }

        // 一维三次贝塞尔：B(s) = 3(1-s)²s·a + 3(1-s)s²·b + s³
        fun curve(a: Float, b: Float, s: Float): Float {
            val inv = 1f - s
            return 3f * inv * inv * s * a + 3f * inv * s * s * b + s * s * s
        }
        fun slope(a: Float, b: Float, s: Float): Float {
            val inv = 1f - s
            return 3f * inv * inv * a + 6f * inv * s * (b - a) + 3f * s * s * (1f - b)
        }

        return lambda@{ progress: Float ->
            when {
                progress <= 0f -> 0f
                progress >= 1f -> 1f
                else -> {
                    var s = progress
                    repeat(8) {
                        val err = curve(x1, x2, s) - progress
                        if (abs(err) < 1e-5f) return@repeat
                        val d = slope(x1, x2, s)
                        if (abs(d) < 1e-6f) return@repeat
                        s -= err / d
                    }
                    if (abs(curve(x1, x2, s) - progress) > 1e-4f) {
                        // 牛顿法没收敛（极端曲线）→ 二分 20 次足够精确
                        var lo = 0f
                        var hi = 1f
                        repeat(20) {
                            val mid = (lo + hi) / 2f
                            if (curve(x1, x2, mid) < progress) lo = mid else hi = mid
                        }
                        s = (lo + hi) / 2f
                    }
                    curve(y1, y2, s.coerceIn(0f, 1f))
                }
            }
        }
    }

    /**
     * 回弹进入（"back out"）：冲过目标一点再回来 —— 打勾的「pop」用它，
     * 比线性缩放更有「按下去弹起来」的手感（[overshoot] 1.70158 ≈ CSS 的 `back(1.70158)`）。
     */
    fun backOut(progress: Float, overshoot: Float = 1.70158f): Float {
        val t = progress.coerceIn(0f, 1f) - 1f
        return t * t * ((overshoot + 1f) * t + overshoot) + 1f
    }
}
