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

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 短信权限（快递短信 SOP §5 / §45 权限测试）。
 *
 * 只申请 [Manifest.permission.RECEIVE_SMS]（监听新短信）：
 *  - **不申请** `READ_SMS`——历史短信扫描（SOP §36/§37）本期没做，最小权限原则；
 *  - **不申请** `SEND_SMS` / `WRITE_SMS`——本功能不发短信也不改短信（SOP §5）。
 *
 * 授权由用户在来源管理里打开“短信”时触发，不做首启强弹（与通知使用权 / 悬浮窗同一口径）；
 * 被永久拒绝时回落到系统权限页，由用户自己决定（SOP §45 的“权限再次允许”）。
 */
object SmsPermissionManager {

    const val PERMISSION: String = Manifest.permission.RECEIVE_SMS

    fun hasPermission(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context,
        PERMISSION,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * 跳系统里本应用的权限页（被“不再询问”挡住时的兜底）。
     * O 以下没有 `ACTION_APP_DETAILS_SETTINGS` 之外的细分页，统一走应用详情页。
     */
    fun openPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
    }
}
