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

/**
 * 灵动岛 UI 状态机（SOP §7）。
 *
 * ```text
 * HIDDEN → COMPACT → EXPANDED → SUCCESS → COMPACT → HIDDEN
 * ```
 *
 * 设计要点：
 *  - **纯 Kotlin + 注入时钟**：所有推进都由显式的 `now` 驱动，不依赖 Handler / 系统时间，
 *    便于 JVM 单测覆盖（SOP §33 先写测试）。
 *  - 每个态只记一个 `advanceAt` 自动推进时刻，`0` 表示“不自动推进”；
 *    真正的时间推进在 [tick]，UI 侧用一次 `postDelayed` 喂进来，**不轮询数据库**（SOP §14）。
 *  - COMPACT 是“中转站”：展开前先出胶囊，收起后回胶囊再隐藏（SOP §18 动画顺序）。
 *
 * 时间口径（SOP §18：展开/收起 200~300ms，成功态 1~2 秒）：
 *  - [Timings.expandDelayMs] 胶囊态停留，正好给“展开动画”跑完；
 *  - [Timings.expandedHoldMs] 展开内容展示；
 *  - [Timings.successHoldMs] `✓ 取件完成` 展示（1~2 秒）；
 *  - [Timings.compactHoldMs] 收起后胶囊态停留，然后回到 HIDDEN。
 */
class IslandStateMachine(
    val timings: Timings = Timings.DEFAULT,
) {

    /** 当前状态 */
    var state: IslandState = IslandState.HIDDEN
        private set

    /** 当前应展示的内容；HIDDEN 时为 null */
    var content: IslandContent? = null
        private set

    /** COMPACT 态展示的小胶囊内容（收起后要回到它） */
    var compactContent: IslandContent? = null
        private set

    /** 下一次自动推进的时刻（ms）；0 = 不自动推进 */
    var advanceAt: Long = 0L
        private set

    /** COMPACT 之后要进入的目标态与内容 */
    private var pendingState: IslandState? = null
    private var pendingContent: IslandContent? = null

    /**
     * 事件到达（SOP §8 触发后的展示入口）。
     *
     * @param event 触发事件（用于判定目标态：取件完成 → SUCCESS，其余 → EXPANDED）
     * @param content 事件内容（展开/成功态展示）
     * @param compact 胶囊内容（通常是个数，SOP §7.2）
     * @param now 当前时间
     * @return true = 状态或内容发生变化，UI 需要重新渲染
     */
    fun onEvent(
        event: IslandEvent,
        content: IslandContent,
        compact: IslandContent,
        now: Long,
    ): Boolean {
        compactContent = compact
        val target = if (event.kind == IslandEvent.Kind.PICKED_UP) {
            IslandState.SUCCESS
        } else {
            IslandState.EXPANDED
        }

        // 隐藏中 → 先出胶囊（SOP §7 流程的 HIDDEN → COMPACT），延迟一拍再展开
        if (state == IslandState.HIDDEN) {
            state = IslandState.COMPACT
            this.content = compact
            pendingState = target
            pendingContent = content
            advanceAt = now + timings.expandDelayMs
            return true
        }

        // 已在展示：直接换内容（正在胶囊态则立即进入目标态）
        val changed = state != target || this.content != content
        pendingState = null
        pendingContent = null
        state = target
        this.content = content
        advanceAt = now + holdFor(target)
        return changed
    }

    /**
     * 时间推进：到点则自动流转（SOP §7 状态流程）。
     *
     * @return true = 状态发生变化，UI 需要重新渲染
     */
    fun tick(now: Long): Boolean {
        if (advanceAt == 0L || now < advanceAt) return false
        advanceAt = 0L

        return when (state) {
            IslandState.HIDDEN -> false

            IslandState.COMPACT -> {
                val target = pendingState
                if (target != null) {
                    state = target
                    content = pendingContent
                    pendingState = null
                    pendingContent = null
                    advanceAt = now + holdFor(target)
                } else {
                    // 胶囊收尾 → 隐藏
                    state = IslandState.HIDDEN
                    content = null
                    compactContent = null
                }
                true
            }

            IslandState.EXPANDED, IslandState.SUCCESS -> {
                // 展示结束 → 收起回胶囊（SOP §18：收起动画 200~300ms，胶囊态短暂停留）
                state = IslandState.COMPACT
                content = compactContent
                advanceAt = now + timings.compactHoldMs
                true
            }
        }
    }

    /** 立即收起（SOP §16.3 关闭当前灵动岛） */
    fun hide(): Boolean {
        val changed = state != IslandState.HIDDEN
        state = IslandState.HIDDEN
        content = null
        compactContent = null
        pendingState = null
        pendingContent = null
        advanceAt = 0L
        return changed
    }

    /** 目标态的停留时长 */
    private fun holdFor(target: IslandState): Long = when (target) {
        IslandState.EXPANDED -> timings.expandedHoldMs
        IslandState.SUCCESS -> timings.successHoldMs
        IslandState.COMPACT -> timings.compactHoldMs
        IslandState.HIDDEN -> 0L
    }

    /** 各阶段停留时长（毫秒），可注入以便单测 */
    data class Timings(
        val expandDelayMs: Long,
        val expandedHoldMs: Long,
        val successHoldMs: Long,
        val compactHoldMs: Long,
    ) {
        companion object {
            /** 默认取值对齐 SOP §18：展开/收起 200~300ms、成功态 1~2 秒 */
            val DEFAULT: Timings = Timings(
                expandDelayMs = 260L,
                expandedHoldMs = 3_000L,
                successHoldMs = 1_600L,
                compactHoldMs = 900L,
            )
        }
    }
}
