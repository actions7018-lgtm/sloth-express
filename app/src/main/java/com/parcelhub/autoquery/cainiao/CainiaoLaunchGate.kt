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

import com.parcelhub.autoquery.AutoQueryDecisionEngine
import com.parcelhub.autoquery.AutoQueryDecisionType
import com.parcelhub.autoquery.AutoQueryInput

/**
 * 菜鸟启动门禁（参考 SOP §10 防重复与二次判断 / §9 QueryTask / §14 后台启动策略）。
 *
 * 只做纯决策，不持有 Context、不发起任何启动动作：
 *  1. **去重**：同一单号在 [DEDUP_WINDOW_MS]（30 秒）内只放行一次触发（SOP §10.1），
 *     连续 5 条同单号通知也只会创建 1 个有效任务；
 *  2. **延迟 + 二次判断**：触发后等待 [RECHECK_DELAY_MS] 再重新决策，
 *     若期间补到了取件码 / 变成家门口已送达，则 [shouldCancel] 返回 true、取消打开菜鸟（SOP §10.2）。
 *
 * 冷却（10 分钟，EXPRESS_SMART_QUERY SOP §9）由 [AutoQueryDecisionEngine] 负责，本类不重复实现。
 */
class CainiaoLaunchGate(
    private val dedupWindowMs: Long = DEDUP_WINDOW_MS,
) {

    private val lastTriggeredAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 请求放行一次触发。
     *
     * @return true 表示允许创建/执行任务（并已记录触发时间）；false 表示处于去重窗口内或单号无效
     */
    fun tryAcquire(trackingNumber: String?, now: Long): Boolean {
        if (trackingNumber.isNullOrBlank()) return false
        val last = lastTriggeredAt[trackingNumber]
        if (last != null && now - last < dedupWindowMs) return false
        lastTriggeredAt[trackingNumber] = now
        return true
    }

    /** 任务已结束（完成 / 失败 / 取消）时释放该单号，下次可立即重新触发 */
    fun release(trackingNumber: String?) {
        if (trackingNumber != null) lastTriggeredAt.remove(trackingNumber)
    }

    /** 清空去重窗口（服务重连恢复配置时使用，SOP §31） */
    fun reset() {
        lastTriggeredAt.clear()
    }

    /** 当前记录的触发时间（诊断用），从未触发过返回 0 */
    fun lastTriggeredAt(trackingNumber: String): Long = lastTriggeredAt[trackingNumber] ?: 0L

    companion object {
        /** SOP §10.1：默认 30 秒内不重复触发 */
        const val DEDUP_WINDOW_MS: Long = 30_000L

        /** SOP §10.2：延迟 1～2 秒后二次判断（取中值） */
        const val RECHECK_DELAY_MS: Long = 1_500L

        /**
         * 延迟二次判断：重新走一遍决策链。
         *
         * 只要结论不再是“需要查询”（例如补到取件码、变成已送达），就取消打开菜鸟。
         */
        fun shouldCancel(input: AutoQueryInput): Boolean =
            AutoQueryDecisionEngine.decide(input) != AutoQueryDecisionType.QUERY
    }
}
