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

/**
 * Widget「点方框完成」的反馈动画 —— 把一次消失拆成 ~30fps 的一串 `updateAppWidget`。
 *
 * **为什么不能用真动画**：`RemoteViews` 没有动画 API（白名单里既没有动画类，
 * 也没有 `ViewPropertyAnimator`），所以丝滑只能靠两件事：
 *   ① 帧足够密（[STEP_MS]=33ms ≈ 30fps；60fps 每帧都要整棵重建视图树，会把桌面拖卡）；
 *   ② 每一帧走**缓动曲线**而不是线性插值（[Easing]）。
 *
 * **反射白名单（真机踩过，代价是整块组件被桌面弹掉）**：
 * `RemoteViews.setFloat/setInt` 只是「反射调用」的入口，能不能调还要看目标方法有没有
 * `@RemotableViewMethod` 注解（AOSP `ReflectionAction.apply()` 里直接抛 ActionException）。
 * 荣耀 9X 上实测：`View.setScaleX / setAlpha / setTranslationY` **都没有注解** →
 * 桌面报 `view: huawei.android.widget.ImageView can't use method with RemoteViews: setScaleX(float)`、
 * 弹「加载窗口小工具时出现问题」、整个组件变空。
 * 可用的通道只有这些（均已对照 AOSP Android 10 源码 + 真机验证）：
 *   - `TextView.setTextColor`（淡出文字）
 *   - `ImageView.setImageAlpha`（淡出方框 / 包裹图标）
 *   - `RemoteViews.setViewPadding`（专用 action，**不走反射**，连注解都不用）
 *
 * 时序（参照 Material Motion 的 emphasized motion）：
 *
 *   阶段        帧                     表现
 *   进入 7 帧   方框内边距 4.5→2.4→3dp  ✓ pop：起手缩小、冲过静止值再回弹
 *               （图标 15dp→19dp→18dp，靠 setViewPadding 改 item_check 的 padding）
 *   退出 10 帧  alpha 1→0（standard 曲线）文字颜色与图片透明度同步淡出
 *               下面条目上内边距 0→-行高  内容上滑到「移除后」的位置
 *   末帧        再过一拍移除条目 → 布局变化与预滑完全重合，**收尾不跳**
 *
 * 帧状态存在 [TodoWidgetProvider] 的 feedback 表里，**所有**刷新路径
 * （Room Flow 的数据变化、翻页、点勾）都读同一份状态，
 * 否则中途来的那次刷新会把整行按「无反馈」渲染 → 又变回直接消失（真机踩过）。
 */
object TodoWidgetFeedback {

    /** 第 0 帧：写库成功后立刻渲染的一帧（方框已开始 pop） */
    const val PHASE_CHECK = 0

    /** 帧间隔 ≈30fps。RemoteViews 每帧要整棵重建视图树，比这更快会把桌面拖卡 */
    const val STEP_MS = 33L

    /** 进入（打勾 pop）帧数：7 × 33ms ≈ 200ms */
    const val ENTER_FRAMES = 7

    /** 退出（淡出 + 后条目上滑）帧数：10 × 33ms ≈ 330ms */
    const val EXIT_FRAMES = 10

    /** 最后一帧：再过一拍就从列表移除 */
    const val LAST_PHASE: Int = ENTER_FRAMES + EXIT_FRAMES - 1

    /** 方框静止时的内边距（dp）——**必须**与 widget_todo_item.xml 里 item_check 的 padding 相同 */
    const val REST_CHECK_PAD_DP = 3f

    /** 打勾起手（按下）时的内边距（dp）：图标 18dp 先缩到 15dp，再弹回来 */
    const val PRESS_CHECK_PAD_DP = 4.5f

    /**
     * 单帧要渲染成什么样。恒等帧（[isIdentity]）表示「无需任何 setter」——
     * 普通待办条目一律走恒等帧，避免给静态内容加无谓的 RemoteViews action。
     */
    data class Frame(
        /** 本行内容透明度 1→0（驱动文字颜色与图片 alpha，白名单里没有 View.setAlpha） */
        val alpha: Float = 1f,
        /** 方框内边距（dp）：进入段用它做 pop；恒等于 [REST_CHECK_PAD_DP] 时不下发 setter */
        val checkPadDp: Float = REST_CHECK_PAD_DP,
        /** 后面条目提前上滑的进度 0..1（乘行高 = 位移） */
        val shift: Float = 0f,
    ) {
        val isIdentity: Boolean
            get() = alpha == 1f && checkPadDp == REST_CHECK_PAD_DP && shift == 0f
    }

    private val IDENTITY = Frame()

    /** 某一帧的渲染参数；越界按最近的帧处理 */
    fun frameOf(phase: Int): Frame {
        val p = phase.coerceIn(0, LAST_PHASE)

        // 进入段：方框 pop（先缩小、冲过静止值再回弹），行本身不动、完全不透明
        if (p < ENTER_FRAMES) {
            val t = p / (ENTER_FRAMES - 1f)
            // overshoot=4：静止值 3dp 会被冲到 ~2dp（图标 18→19dp 再落回），
            // 默认的 1.70158 只冲 0.1 → 0.25dp，在 3x 屏上只有 0.75px，看不出来
            val s = Easing.backOut(t, overshoot = 4f)
            return Frame(checkPadDp = REST_CHECK_PAD_DP + (PRESS_CHECK_PAD_DP - REST_CHECK_PAD_DP) * (1f - s))
        }

        // 退出段：standard 曲线同时驱动「本行淡出」与「后续行上滑」，两条进度同源才同步
        val u = ((p - ENTER_FRAMES) / (EXIT_FRAMES - 1f)).coerceIn(0f, 1f)
        val k = Easing.standard(u)
        return Frame(alpha = 1f - k, shift = k)
    }

    /**
     * 渲染时用：`phase == null` 表示普通条目（没被勾过）→ 恒等帧。
     * 不这么做的话，第 0 帧的 pop 会误伤所有未勾选的方框（真机踩过）。
     */
    fun frameFor(phase: Int?): Frame = if (phase == null) IDENTITY else frameOf(phase)

    /** 下一帧前等待多久（恒定帧距，保证节奏均匀） */
    fun frameDelayMs(): Long = STEP_MS

    /** 整段动画时长（含末帧后那一拍） */
    fun totalMs(): Long = (LAST_PHASE + 2) * STEP_MS
}
