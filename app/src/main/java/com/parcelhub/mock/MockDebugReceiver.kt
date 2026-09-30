/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.mock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.parcelhub.App
import com.parcelhub.BuildConfig
import com.parcelhub.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Mock 数据的一键清空 / 重新生成调试入口（规格 §10，仅 Debug）。
 *
 * 用法（adb）：
 * ```
 * adb shell am broadcast -a com.parcelhub.DEBUG_MOCK_SEED    # 注入（幂等）
 * adb shell am broadcast -a com.parcelhub.DEBUG_MOCK_CLEAR   # 一键清空
 * adb shell am broadcast -a com.parcelhub.DEBUG_MOCK_RESEED  # 清空后重新生成
 * ```
 *
 * 两道护栏与 [com.parcelhub.sms.SmsDebugReceiver] 相同：
 *  1. Manifest 上 `android:permission="android.permission.DUMP"`（只有 adb shell 这类系统调用方持有）；
 *  2. [BuildConfig.DEBUG] 判断，release 构建收到也不执行。
 *
 * 注入完成后顺手叫醒待补全补偿循环：新落库的 WAITING_TRACKING 立即参与对账，
 * 场景 E（>72h）在 App 运行中重新生成也能马上转 EXPIRED。
 */
class MockDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val action = intent.action
        if (action !in ACTIONS) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_SEED -> MockDataSeeder.seed(context)
                    ACTION_CLEAR -> MockDataSeeder.clear(context)
                    ACTION_RESEED -> {
                        MockDataSeeder.clear(context)
                        MockDataSeeder.seed(context)
                    }
                }
                App.graph.repository.onPendingCreated?.invoke(null)
            } catch (t: Throwable) {
                AppLog.w("mock debug broadcast failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_SEED = "com.parcelhub.DEBUG_MOCK_SEED"
        const val ACTION_CLEAR = "com.parcelhub.DEBUG_MOCK_CLEAR"
        const val ACTION_RESEED = "com.parcelhub.DEBUG_MOCK_RESEED"
        private val ACTIONS = setOf(ACTION_SEED, ACTION_CLEAR, ACTION_RESEED)
    }
}
