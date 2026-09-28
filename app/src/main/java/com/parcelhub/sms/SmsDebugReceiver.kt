/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.parcelhub.BuildConfig
import com.parcelhub.util.AppLog

/**
 * 短信注入的调试入口（快递短信 SOP §44 集成测试）。
 *
 * 为什么需要它：`android.provider.Telephony.SMS_RECEIVED` 是**受保护广播**，
 * 真机上 adb 发不出去（本机实测 AMS 直接 `Permission Denial: not allowed to send`），
 * 而没有 SIM 收信时也无法走真实链路。这里提供一条等价通道：
 * 用 adb 喂一条短信进来，走与 [SmsReceiver] **完全相同**的过滤 → 去重 → 入队 → 解析 → 落库链路。
 *
 * 两道护栏：
 *  1. Manifest 上 `android:permission="android.permission.DUMP"`——该权限只授予 adb shell
 *     这类系统调用方，普通三方应用拿不到，外部无法伪造短信注入；
 *  2. [BuildConfig.DEBUG] 判断，release 构建收到也不执行。
 */
class SmsDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (intent.action != ACTION_DEBUG_SMS) return

        val body = intent.getStringExtra(EXTRA_BODY)?.takeIf { it.isNotBlank() } ?: run {
            AppLog.d("sms debug: empty body")
            return
        }
        val sender = intent.getStringExtra(EXTRA_SENDER) ?: DEFAULT_SENDER
        val timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())

        dispatchSms(context, listOf(SmsEnvelope(sender = sender, body = body, timestampMs = timestamp)))
    }

    companion object {
        const val ACTION_DEBUG_SMS = "com.parcelhub.DEBUG_SMS"
        const val EXTRA_BODY = "body"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_TIMESTAMP = "timestamp"
        const val DEFAULT_SENDER = "1069000000000"
    }
}
