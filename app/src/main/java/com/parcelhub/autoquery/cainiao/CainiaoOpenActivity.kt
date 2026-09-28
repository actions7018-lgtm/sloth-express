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

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.parcelhub.App
import com.parcelhub.autoquery.QueryTaskStatus
import com.parcelhub.util.PermissionUtil
import kotlinx.coroutines.launch

/**
 * 打开菜鸟的中转 Activity（参考 SOP §14 后台启动策略 / §15 职责单一）。
 *
 * 为什么需要它：
 *  - Android 12+ 禁止后台直接拉起其他 App，只能由用户点击触发（通知按钮的 PendingIntent）；
 *  - 后台启动限制只针对“后台状态启动”，由用户点击通知进入前台后启动是允许的；
 *  - 本类只做「前置检查 + 启动菜鸟 + 挂上会话」三件事，
 *    **不遍历无障碍节点、不做任何页面点击**（SOP §15）。
 *
 * 前置检查顺序（SOP §31 / T05 / T06）：
 *  1. 无障碍未开启 → 不拉菜鸟，发通知引导开启（用户手动开，SOP §5/§6）；
 *  2. 菜鸟未安装 → 不报错，发安装入口通知（SOP §13）。
 *
 * 第一版不读查询结果（SOP §23）：填完单号后由用户自己点“查询”。
 */
class CainiaoOpenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tracking = intent.getStringExtra(EXTRA_TRACKING)
        if (tracking.isNullOrBlank()) {
            AutoQueryLog.warn("cainiao open without tracking number")
            finish()
            return
        }
        val masked = AutoQueryLog.mask(tracking)

        // T06：无障碍未开启 → 提示开启，不拉菜鸟。
        // 注意：此时会话尚未绑定该单号，不能用 automation.finish() 收尾，
        // 必须按单号直接取消任务并撤入口通知。
        if (!PermissionUtil.isAccessibilityEnabled(this)) {
            App.graph.appScope.launch {
                runCatching {
                    App.graph.autoQueryTaskManager.cancelForTracking(tracking, "a11y_off")
                }
            }
            App.graph.notifier.notifyAccessibilityMissing()
            AutoQueryLog.cainiao("blocked: accessibility off ($masked)")
            Toast.makeText(this, "请先开启自动填单号服务", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val automation = App.graph.cainiaoAutomation
        val now = System.currentTimeMillis()
        if (!automation.start(tracking, now)) {
            // 已有一个在飞的会话（SOP §10.1 去重），不重复拉起
            AutoQueryLog.cainiao("skip: session already running ($masked)")
            finish()
            return
        }

        when (val result = CainiaoLauncher(this).openCainiao(tracking)) {
            is CainiaoLaunchResult.NotInstalled -> {
                // SOP §13：不显示技术错误，给安装入口
                automation.finish(QueryTaskStatus.CANCELLED)
                App.graph.notifier.notifyCainiaoMissing(tracking)
                AutoQueryLog.cainiao("blocked: cainiao not installed ($masked)")
                Toast.makeText(this, "请先安装菜鸟 App", Toast.LENGTH_SHORT).show()
            }
            is CainiaoLaunchResult.BackgroundLaunchBlocked -> {
                automation.finish(QueryTaskStatus.FAILED)
                AutoQueryLog.cainiao("blocked: background launch ($masked)")
                Toast.makeText(this, "请从通知栏点按打开菜鸟", Toast.LENGTH_SHORT).show()
            }
            else -> AutoQueryLog.cainiao("launch: $result ($masked)")
        }
        finish()
    }

    companion object {
        const val EXTRA_TRACKING = "tracking_number"

        fun intent(context: android.content.Context, trackingNumber: String): Intent =
            Intent(context, CainiaoOpenActivity::class.java)
                .putExtra(EXTRA_TRACKING, trackingNumber)
    }
}
