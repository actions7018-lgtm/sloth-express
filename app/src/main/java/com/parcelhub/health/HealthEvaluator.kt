/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.health

/**
 * 健康判定纯函数（检测 SOP §四~§十 各模块规则 + §十五 总状态）。
 *
 * 全部无 IO、无 Android 依赖，JVM 单测直接覆盖（HealthEvaluatorTest）。
 * 证据采集在 [HealthCollector]；本类只回答「这组证据等于什么状态」。
 *
 * 反例守则（检测 §十二 / §二十）：
 *  「今天没有事件」永远推不出 ERROR；「一次失败」永远推不出 ERROR。
 */
object HealthEvaluator {

    // ---- 阈值（检测 §四/§六/§七/§八） ----

    /** 通知服务断开超过该时长仍属可自愈的重连窗口 */
    const val NOTIFY_DISCONNECT_GRACE_MS: Long = 30 * 60_000L

    /** 通知服务断开超过 6 小时 → ERROR（§4.4） */
    const val NOTIFY_DISCONNECT_ERROR_MS: Long = 6 * 60 * 60_000L

    /** 无障碍断开超过 30 分钟 → ERROR（§6.4；有连接态证据时才可用） */
    const val A11Y_DISCONNECT_ERROR_MS: Long = 30 * 60_000L

    /** 待补全任务过计划时间 6 小时内 → WARNING（§8.3），24 小时 → ERROR（§8.4） */
    const val PENDING_DELAY_WARN_MS: Long = 6 * 60 * 60_000L
    const val PENDING_DELAY_ERROR_MS: Long = 24 * 60 * 60_000L

    /** 待补全订单 24 小时内到期仍没单号 → WARNING（§8.3） */
    const val PENDING_EXPIRING_WARN_MS: Long = 24 * 60 * 60_000L

    /** 连续失败达到 3 次 → ERROR（各模块统一口径；一次失败不是故障，§二十） */
    const val FAIL_STREAK_ERROR: Int = 3

    // ---- §四 通知监听 ----

    /**
     * @param permissionGranted 通知使用权是否授予
     * @param connected NotificationListenerService 当前是否连接
     * @param disconnectedForMs 断开已持续时长；从未连接过/未断开为 null
     * @param consecutiveFailures 连续监听/解析失败次数
     */
    fun notification(
        permissionGranted: Boolean,
        connected: Boolean,
        disconnectedForMs: Long?,
        consecutiveFailures: Int,
    ): HealthStatus {
        if (!permissionGranted) return HealthStatus.DISABLED // §4.5
        if (consecutiveFailures >= FAIL_STREAK_ERROR) return HealthStatus.ERROR // §4.4 连续失败
        if (!connected) {
            // §4.4 持续未连接 > 6h；从未连接（无断开时长证据）只到 WARNING，不猜 ERROR（§二十）
            if (disconnectedForMs != null && disconnectedForMs > NOTIFY_DISCONNECT_ERROR_MS) {
                return HealthStatus.ERROR
            }
            // §4.3 断开但仍在可恢复窗口内（或刚断开）→ 提示，不说坏了
            return HealthStatus.WARNING
        }
        // §4.3 最近解析有异常（1~2 次）→ 提示
        if (consecutiveFailures > 0) return HealthStatus.WARNING
        // §4.2 已连接即健康；今天没有快递通知 ≠ 故障（§十二）
        return HealthStatus.HEALTHY
    }

    // ---- §五 短信识别 ----

    /**
     * @param enabled 来源开关（source_apps.enabled）
     * @param permissionGranted RECEIVE_SMS 权限
     * @param consecutiveFailures 连续处理失败次数（异常，不含正常过滤）
     *
     * 开关开 + 缺权限 = 「需要授权」（WARNING）——既不是「已开启正常」也不是「未开启」，
     * 行状态词与摘要必须同侧，不许出现「摘要已开启 + 右侧未开启」的自相矛盾；
     * 开关关 = DISABLED。没有短信 ≠ 异常（§5.2 / §十二）。
     */
    fun sms(
        enabled: Boolean,
        permissionGranted: Boolean,
        consecutiveFailures: Int,
    ): HealthStatus {
        if (!enabled) return HealthStatus.DISABLED // 用户关闭
        if (!permissionGranted) return HealthStatus.WARNING // 功能开着但缺权限 → 需要授权
        if (consecutiveFailures >= FAIL_STREAK_ERROR) return HealthStatus.ERROR // §5.4
        if (consecutiveFailures > 0) return HealthStatus.WARNING // §5.3
        return HealthStatus.HEALTHY // §5.2
    }

    // ---- §六 无障碍识别 ----

    /**
     * @param enabled 服务是否在系统无障碍列表中启用
     * @param connected 连接态；系统不提供时为 null（多数 ROM 只能读到启用态，启用 ≈ 已连接）
     * @param disconnectedForMs 断开持续时长（有连接态证据时才有意义）
     */
    fun accessibility(
        enabled: Boolean,
        connected: Boolean? = null,
        disconnectedForMs: Long? = null,
    ): HealthStatus {
        if (!enabled) return HealthStatus.DISABLED // §6.5
        if (connected == false) {
            // §6.4 持续断开 > 30 分钟 → ERROR；否则 §6.3 暂时断开 → WARNING
            return if (disconnectedForMs != null && disconnectedForMs > A11Y_DISCONNECT_ERROR_MS) {
                HealthStatus.ERROR
            } else {
                HealthStatus.WARNING
            }
        }
        // 已启用（且无连接态反证）→ §6.2 口径的可用状态
        return HealthStatus.HEALTHY
    }

    // ---- §七 后台运行 ----

    /**
     * 后台运行判定——**只看实际运行证据**，不看“理论风险”：
     *
     *  1. 用户关闭后台自动运行 → DISABLED；
     *  2. 开启 + 没有待补全任务 → HEALTHY（有没有任务是业务事实，不是故障）；
     *  3. 开启 + 有任务 + 调度正常（nextCheckAt 未过期 / 近期成功执行）→ HEALTHY；
     *  4. 开启 + 有任务 + nextCheckAt 已过期、延迟 ≤ 24h → WARNING（含 ≤6h）；
     *  5. 开启 + 有任务 + 延迟 > 24h 仍未执行，或有任务却无调度（Worker 被取消/未注册）→ ERROR；
     *  6. 开启 + 无实际失败证据 → 有正常运行证据 = HEALTHY，没有足够证据 = UNKNOWN。
     *
     * **系统电池优化 / OEM 后台管理 / 自启动管理一律不参与状态判定**——
     * “理论上国产 ROM 可能限制后台”只能作为「系统限制风险」单独展示，
     * 不允许直接判 WARNING/ERROR（风险提示 ≠ 故障）。
     *
     * @param nextCheckAt 最早待补全任务的计划检查时间；有任务但为 null = 没有调度
     */
    fun background(
        enabled: Boolean,
        hasPendingWork: Boolean,
        nextCheckAt: Long?,
        lastAttemptAt: Long?,
        lastSuccessAt: Long?,
        consecutiveFailures: Int,
        now: Long,
    ): HealthStatus {
        if (!enabled) return HealthStatus.DISABLED // 1. 用户主动关闭
        if (consecutiveFailures >= FAIL_STREAK_ERROR) return HealthStatus.ERROR // 真实失败证据
        if (hasPendingWork) {
            if (nextCheckAt == null) return HealthStatus.ERROR // 5. 有任务无调度（未注册/被取消）
            val delay = now - nextCheckAt
            if (delay > PENDING_DELAY_ERROR_MS) return HealthStatus.ERROR // 5. > 24h 仍未执行
            if (delay > 0) return HealthStatus.WARNING // 4. 过期未执行 ≤ 24h（含 ≤ 6h）
            if (consecutiveFailures > 0) return HealthStatus.WARNING // 真实失败证据 1~2 次
            return HealthStatus.HEALTHY // 3. 调度正常
        }
        if (consecutiveFailures > 0) return HealthStatus.WARNING // 真实失败证据 1~2 次
        // 2/6. 无任务：有运行证据 = HEALTHY，没有足够证据 = UNKNOWN（电池/OEM 状态不参与）
        val hasRunEvidence = lastAttemptAt != null || lastSuccessAt != null
        return if (hasRunEvidence) HealthStatus.HEALTHY else HealthStatus.UNKNOWN
    }

    // ---- §八 待补全任务 ----

    /**
     * @param enabled 自动补全是否开启（无开关时恒为 true）
     * @param readable ShipmentPending 数据是否可读
     * @param waitingCount WAITING_TRACKING 数量
     * @param nextCheckAt 最早的计划检查时间；有任务却为 null = 没有调度（§8.4）
     * @param expireAtMs 最早的到期剩余时长（expireAt - now）；无任务为 null
     * @param inconsistentCount 数据矛盾条数（如 TRACKING_FOUND 却没有单号，§8.4）
     */
    fun pendingShipment(
        enabled: Boolean,
        readable: Boolean,
        waitingCount: Int,
        nextCheckAt: Long?,
        expireAtMs: Long?,
        inconsistentCount: Int,
        now: Long,
    ): HealthStatus {
        if (!enabled) return HealthStatus.DISABLED // §8.5
        if (!readable) return HealthStatus.UNKNOWN // §8.6
        if (inconsistentCount > 0) return HealthStatus.ERROR // §8.4 数据矛盾
        if (waitingCount == 0) return HealthStatus.HEALTHY // §8.2 无任务
        if (nextCheckAt == null) return HealthStatus.ERROR // §8.4 有数据无调度
        val delay = now - nextCheckAt
        if (delay > PENDING_DELAY_ERROR_MS) return HealthStatus.ERROR // §8.4 超时 > 24h
        if (delay > PENDING_DELAY_WARN_MS) return HealthStatus.WARNING // §8.3 延迟 6~24h
        if (delay > 0) return HealthStatus.WARNING // §8.3 已过计划时间 ≤ 6h
        if (expireAtMs != null && expireAtMs in 1..PENDING_EXPIRING_WARN_MS) {
            return HealthStatus.WARNING // §8.3 24h 内到期仍无单号
        }
        return HealthStatus.HEALTHY // §8.2 调度正常
    }

    // ---- §九 Widget ----

    /** @param instanceCount 桌面已添加的实例数；ROM 读不到为 null */
    fun widget(instanceCount: Int?): HealthStatus = when {
        instanceCount == null -> HealthStatus.UNKNOWN // §9.6
        instanceCount <= 0 -> HealthStatus.DISABLED // §9.5 未添加不是故障
        // §9.2：链路可用即健康；没有更新失败证据时不猜 ERROR（§二十）
        else -> HealthStatus.HEALTHY
    }

    // ---- §十 灵动岛 ----

    /**
     * @param enabled 用户开关
     * @param overlayPending 悬浮模式已开但授权缺失（等待授权 / 系统限制风险，§10.3）
     * @param consecutiveFailures 连续发送失败次数
     */
    fun island(
        enabled: Boolean,
        overlayPending: Boolean,
        consecutiveFailures: Int,
    ): HealthStatus {
        if (!enabled) return HealthStatus.DISABLED // §10.5 关闭/不支持都是 DISABLED
        if (consecutiveFailures >= FAIL_STREAK_ERROR) return HealthStatus.ERROR // §10.4
        if (overlayPending) return HealthStatus.WARNING // §10.3 授权/策略风险
        return HealthStatus.HEALTHY // §10.2 没有岛通知 ≠ 故障（§十二）
    }

    // ---- §十五 自动检测总状态 ----

    /**
     * 按「已启用模块」聚合（§十五）：
     *  - DISABLED 的可选模块不拖累整体（§二）；
     *  - 核心模块 ERROR，或 ≥2 个已启用模块 ERROR → 整体 ERROR；
     *  - 单个非核心 ERROR 达不到 ERROR 阈值 → 至少 WARNING（不能显示正常）；
     *  - WARNING 优先于 UNKNOWN（§二优先级）；全部 DISABLED → DISABLED。
     */
    fun overall(statuses: Map<HealthModule, HealthStatus>): HealthStatus {
        if (statuses.isEmpty()) return HealthStatus.UNKNOWN
        if (statuses.values.all { it == HealthStatus.DISABLED }) return HealthStatus.DISABLED

        val enabled = statuses.values.filter { it != HealthStatus.DISABLED }
        val coreError = statuses
            .filterKeys { it.category == HealthCategory.CORE }
            .values.any { it == HealthStatus.ERROR }

        val errorCount = enabled.count { it == HealthStatus.ERROR }
        if (coreError || errorCount >= 2) return HealthStatus.ERROR
        if (errorCount == 1) return HealthStatus.WARNING // §十五 ERROR 阈值之外的单点异常

        if (enabled.any { it == HealthStatus.WARNING }) return HealthStatus.WARNING
        if (enabled.any { it == HealthStatus.UNKNOWN }) return HealthStatus.UNKNOWN
        return HealthStatus.HEALTHY
    }
}
