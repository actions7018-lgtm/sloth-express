/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible

/**
 * 灵动岛悬浮视图（SOP §12 `DynamicIslandView`）。
 *
 * 职责：**只管 UI、动画、点击**，状态流转由 [IslandStateMachine] 决定，
 * 窗口生命周期由 [IslandOverlayService] 决定。
 *
 * 实现选择：用原生 View 而不是 ComposeView——悬浮窗没有 LifecycleOwner / Activity 环境，
 * ComposeView 在 `TYPE_APPLICATION_OVERLAY` 窗口里容易拿不到生命周期而崩溃；
 * 原生 View 只依赖 Context，悬浮场景更稳（SOP §19：异常不能导致崩溃）。
 *
 * 视觉：**OPPO 流体云胶囊口径** —— 纯黑底 + 白字、**固定盒 40dp 高 × 屏宽 33%**（2026-09-28 对齐，
 * 用户指定「按 OPPO 手机的灵动岛大小/样式设计」，后续追加「取件完成要和到站一样大」）。
 * SOP §17 已同步改写成 OPPO 口径（原「浅色」）；
 * §17 真正反对的反模式（纯黑**巨大**胶囊、复杂光效、持续旋转、大面积遮挡顶部）仍然不碰。
 */
class DynamicIslandView(context: Context) : LinearLayout(context) {

    /** 点击灵动岛 → 打开对应快递详情（SOP §16.1）；shipmentId 为 0 表示回首页 */
    var onTap: ((Long) -> Unit)? = null

    private val titleView = TextView(context)
    private val line1View = TextView(context)

    private var current: IslandContent? = null

    /** 固定盒基准宽 = 屏宽 33%；副行在最小字号下仍差几个像素时按实差小幅放宽（见 fitSubText） */
    private val baseBoxWidth: Int =
        (resources.displayMetrics.widthPixels * MAX_WIDTH_RATIO).toInt()

    /** 固定盒当前宽（默认 = 基准宽，副行溢出时小幅上调后不回缩） */
    private var boxWidth: Int = baseBoxWidth

    /** 固定盒高：标题行 + 间距 + 副行 + 上下 padding 各态恒等（到站 / 取件完成 / 小胶囊） */
    private val boxHeight: Int = dp(CAPSULE_BOX_HEIGHT_DP)

    /** 副行可用文本宽（盒宽 - 左右 padding），取件码收缩字号 / 放宽盒宽时按它对齐 */
    private var subTextWidth: Int = boxWidth - dp(H_PAD_DP) * 2

    init {
        orientation = VERTICAL
        val pad = dp(H_PAD_DP)
        setPadding(pad, dp(V_PAD_DP), pad, dp(V_PAD_DP))
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = dp(6).toFloat()
        }

        // 固定盒：宽 = 屏宽 33%、高 = 40dp —— 到站 / 取件完成 / 小胶囊三态**等大**
        // （用户要求「第二个取件完成和第一个一样大」）。两行子 View 行盒**固定**（按 fontScale
        // 等比放大），emoji / 省略号 fallback 字体的行高差异不再透上来；onMeasure 再强制外框。
        val fs = resources.configuration.fontScale
        val titleLineH = (dp(TITLE_LINE_DP) * fs + 0.5f).toInt()
        val bodyLineH = (dp(BODY_LINE_DP) * fs + 0.5f).toInt()
        addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, titleLineH))
        addView(
            line1View,
            LayoutParams(LayoutParams.MATCH_PARENT, bodyLineH).apply {
                // 间距走 margin：行盒高度才是纯文本盒，固定行盒语义不被 padding 污染
                topMargin = dp(SUB_GAP_DP)
            },
        )

        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
        titleView.gravity = Gravity.CENTER
        titleView.typeface = Typeface.DEFAULT_BOLD
        titleView.includeFontPadding = false
        titleView.maxWidth = subTextWidth
        titleView.maxLines = 1
        titleView.ellipsize = TextUtils.TruncateAt.END

        // 副行只放 line1（OPPO 胶囊两行封顶；line2 交给通知与详情页，见 subLineOf）
        line1View.setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY_SP)
        line1View.gravity = Gravity.CENTER
        line1View.includeFontPadding = false
        line1View.maxWidth = subTextWidth
        line1View.maxLines = 1
        line1View.ellipsize = null // 副行永不省略（取件码收缩字号 / 放宽盒宽兜底）

        // 圆角始终 = 高度 / 2：高度随字号缩放变化，收成标准药丸形
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncCorner() }

        setOnClickListener { current?.let { onTap?.invoke(it.shipmentId) } }
    }

    /**
     * 固定盒（2026-09-28，用户要求「取件完成要和到站一样大」）：
     * 宽恒 ≥ 屏宽 33%（副行超长先缩字号、再按实差放宽，标题超长才省略），高恒 = 40dp
     * （固定行盒已消掉自然高差）。
     * 原先两态尺寸全靠文案撑：到站 338×115px、取件完成 298×109px（宽差 13dp 来自文案长度、
     * 高差 2dp 来自标题 emoji 行高），现在三态（到站 / 取件完成 / 小胶囊）等大。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(boxWidth, maxOf(boxHeight, measuredHeight))
    }

    /**
     * 渲染内容。
     *
     * @param animate 内容发生变化时是否播放入场动画（展开/收起由 [IslandOverlayService] 的
     *  状态切换触发，时长与 SOP §18 的 200~300ms 一致）
     */
    fun render(content: IslandContent, animate: Boolean) {
        val changed = current != content
        current = content

        titleView.text = content.title
        titleView.setTextColor(titleColor())
        val sub = subLineOf(content)
        line1View.text = sub
        line1View.isVisible = sub.isNotEmpty()
        // 副行（含取件码）：白字 + 永不省略 —— 先收缩字号，仍差几个像素就放宽盒宽（2026-09-28
        // 用户要求「取件码不要省略，实在不行适当加一两个像素；数字改回白色」）
        line1View.setTextColor(bodyColor())
        line1View.ellipsize = null
        if (sub.isNotEmpty()) {
            fitSubText(sub)
        } else {
            resetBoxWidth()
            line1View.setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY_SP)
        }
        contentDescription = listOfNotNull(content.title, sub.takeIf { it.isNotEmpty() })
            .joinToString("，")

        background = backgroundFor()
        syncCorner()
        if (animate && changed) animateIn()
    }

    /**
     * 副行文案：胶囊里**只放主行 line1**（OPPO 流体云胶囊口径：信息精简、两行封顶）。
     * line2（地址 / 单号）留给通知模式与详情页 —— 118dp 宽的胶囊塞不下，硬塞只会把
     * 取件码挤成省略号，反而丢掉最要紧的信息。
     */
    private fun subLineOf(content: IslandContent): String =
        content.line1.orEmpty().trim()

    /**
     * 取件码「撑满不折叠」（2026-09-28 用户口径：不要省略号，实在不行适当加一两个像素）：
     * 1. 先从 [BODY_SP] 按 0.5sp 步进收缩，直到整行 ≤ 可用宽（下限 [MIN_SUB_SP]，再小看不清）；
     * 2. 收到下限仍差几个像素 → 按**实差**放宽固定盒宽（宽高比只增不减，封顶屏宽
     *    [MAX_WIDTH_RATIO_GROW]，不会变成大胶囊）。
     * `ellipsize` 已永久关闭，任何文案都不会被折叠。
     */
    private fun fitSubText(text: String) {
        resetBoxWidth()
        var sp = BODY_SP
        line1View.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        while (sp > MIN_SUB_SP && line1View.paint.measureText(text) > subTextWidth) {
            sp -= 0.5f
            line1View.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        }
        val needed = line1View.paint.measureText(text).toInt() + dp(H_PAD_DP) * 2
        val grown = minOf(
            maxOf(baseBoxWidth, needed),
            (resources.displayMetrics.widthPixels * MAX_WIDTH_RATIO_GROW).toInt(),
        )
        if (grown > boxWidth) applyBoxWidth(grown)
    }

    /** 盒宽回到基准值（换内容时先复位，短文案不让上一条的放宽量残留） */
    private fun resetBoxWidth() {
        if (boxWidth != baseBoxWidth) applyBoxWidth(baseBoxWidth)
    }

    /** 应用盒宽并同步两个 TextView 的 `maxWidth`，触发重新测量 */
    private fun applyBoxWidth(width: Int) {
        boxWidth = width
        subTextWidth = boxWidth - dp(H_PAD_DP) * 2
        titleView.maxWidth = subTextWidth
        line1View.maxWidth = subTextWidth
        requestLayout()
    }

    /** 展开：从 0.7 倍缩放 + 透明度 0 进入，240ms（SOP §18 展开 200~300ms） */
    fun animateIn() {
        cancelAnimation()
        scaleX = 0.7f
        scaleY = 0.7f
        alpha = 0f
        animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(ENTER_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
            .setListener(null)
            .start()
    }

    /** 收起：缩回 0.7 倍并淡出，220ms；结束回调由服务移除窗口用 */
    fun animateOut(onEnd: () -> Unit) {
        cancelAnimation()
        animate()
            .scaleX(0.7f)
            .scaleY(0.7f)
            .alpha(0f)
            .setDuration(EXIT_MS)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.2f))
            .setListener(
                object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled) onEnd()
                    }
                },
            )
            .start()
    }

    fun cancelAnimation() {
        animate().setListener(null).cancel()
        isVisible = true
    }

    // ---------- 视觉（OPPO 流体云口径：胶囊底固定纯黑，不允许自定义配色） ----------

    /**
     * 胶囊底 = **纯黑**（OPPO 流体云规范：「胶囊背景固定为黑色，不允许开发者自定义」）。
     * 不再按 [IslandTone] 分色 —— 2026-09-28 按用户要求对齐 OPPO 样，配色交给文字层级。
     * 尺寸仍是 36dp 小药丸，不属于 SOP §17「纯黑巨大胶囊 / 大面积遮挡顶部」的反模式。
     */
    private fun backgroundFor(): GradientDrawable {
        @Suppress("DEPRECATION")
        return GradientDrawable().apply {
            setColor(Color.BLACK)
            // 初值 = 胶囊高的一半；实际高度测出后由 layout 监听再校正成完整药丸形
            cornerRadius = dp(CAPSULE_HEIGHT_DP / 2).toFloat()
        }
    }

    /** 标题：纯白（黑底上对比度最高，OPPO 样） */
    private fun titleColor(): Int = Color.WHITE

    /** 副行：70% 白 —— 黑底上压一级层级，避免与标题抢眼 */
    private fun bodyColor(): Int = Color.parseColor("#B3FFFFFF")

    /** 圆角 = 实测高 / 2（药丸形）；渲染与布局各调一次，避免重建背景后圆角回退 */
    private fun syncCorner() {
        if (height <= 0) return
        val radius = height / 2f
        val drawable = background as? GradientDrawable ?: return
        if (drawable.cornerRadius != radius) drawable.cornerRadius = radius
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val ENTER_MS = 240L
        const val EXIT_MS = 220L

        /** 胶囊基准高（OPPO 流体云胶囊 ≈ 36dp），单行胶囊态正好一整颗药丸 */
        const val CAPSULE_HEIGHT_DP = 36

        /**
         * 固定盒高 40dp = 上下 padding 3+3 + 标题行 17 + 间距 1 + 副行 16 ——
         * 到站 / 取件完成 / 小胶囊三态恒等（用户要求「第二个取件完成和第一个一样大」）
         */
        const val CAPSULE_BOX_HEIGHT_DP = 40

        /**
         * 固定行盒：标题 17dp、副行 16dp（按 fontScale 等比放大）。
         * 两行自然高不齐（到站带 emoji 115px / 取件完成 109px / 超长省略号 120px，
         * 来自 emoji 与省略号 fallback 字体行高差），固定行盒后三者同高
         */
        const val TITLE_LINE_DP = 17
        const val BODY_LINE_DP = 16

        /** 水平内边距 14dp / 垂直 3dp（副行排版后整颗 ≈36dp） */
        const val H_PAD_DP = 14
        const val V_PAD_DP = 3

        /** 标题与副行的间距 1dp */
        const val SUB_GAP_DP = 1

        /** 宽度上限 = 屏宽 33%，副行实差放宽的硬顶 = 屏宽 40% */
        const val MAX_WIDTH_RATIO = 0.33f
        const val MAX_WIDTH_RATIO_GROW = 0.40f

        /** 字号：标题 14sp 加粗、副行 11sp（OPPO 胶囊两行口径）；取件码收缩下限 8sp */
        const val TITLE_SP = 14f
        const val BODY_SP = 11f
        const val MIN_SUB_SP = 8f
    }
}
