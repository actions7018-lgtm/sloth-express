/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 权限工具（SOP §15）。
 *
 * 必要权限只有 Notification Listener Access（用户在系统设置手动开启）；
 * POST_NOTIFICATIONS 只用于 App 自身提醒，拒绝后不得影响数据采集（SOP §12.3）。
 *
 * 短信权限**不在这里**：`RECEIVE_SMS` 属于「来源管理 → 短信」那一行的开关依赖，
 * 检查 / 申请 / 跳系统设置都在 `sms/SmsPermissionManager`（快递短信 SOP §5/§45），
 * 由用户打开该来源时才申请，首启不强弹。
 * 定位、相机、麦克风、通讯录、`SEND_SMS`、`WRITE_SMS`、`READ_SMS` 仍然一律不申请。
 */
object PermissionUtil {

    /** 通知使用权（NotificationListener）是否已开启 */
    fun isNotificationListenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            ENABLED_NOTIFICATION_LISTENERS,
        ) ?: return false
        if (flat.isBlank()) return false
        val pkg = context.packageName
        return flat.split(':').any { component ->
            component == pkg || component.startsWith("$pkg/")
        }
    }

    /** App 自身通知权限（Android 13+ 运行时权限） */
    fun hasPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun shouldExplainPostNotifications(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** 跳转通知使用权设置页 */
    fun openNotificationAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
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

    /** 跳转 App 通知设置（用户已拒绝系统权限弹窗时的兜底） */
    fun openAppNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
    }

    fun requestPostNotifications(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (hasPostNotifications(activity)) return
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            requestCode,
        )
    }

    /**
     * 自动填单号无障碍服务是否已开启（SOP T06）。
     *
     * 读系统 `enabled_accessibility_services`，匹配本 App 的服务组件。
     *
     * 真机实测（2026-09-26，荣耀 HLK-AL00 / Android 10）：系统写回的是**缩写形式**
     * `com.parcelhub/.autoquery.cainiao.CainiaoAccessibilityService`，
     * 完整类名只在部分 ROM / 手写 settings 时出现，两种都要认。
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        if (flat.isBlank()) return false
        val pkg = context.packageName
        val shortName = A11Y_SERVICE_CLASS.removePrefix("$pkg.")
        return flat.split(':').any { component ->
            val c = component.trim()
            c.endsWith("/$A11Y_SERVICE_CLASS") || c.endsWith("/.$shortName")
        }
    }

    /** 跳转系统无障碍设置（用户手动开启，SOP §5/§6） */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
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

    /**
     * 悬浮窗（显示在其他应用上层）权限是否已授予——灵动岛悬浮模式用（SOP §11.2）。
     * O 以下没有 TYPE_APPLICATION_OVERLAY，直接判否，回落通知模式。
     */
    fun canDrawOverlays(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Settings.canDrawOverlays(context)

    /**
     * 跳转悬浮窗授权页（SOP §13：**由用户主动开启**，不在首启强弹权限）。
     * 精确到本包的授权页；ROM 不支持时回退到系统设置首页。
     */
    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
    }

    /**
     * 本 App 是否已被加入电池优化白名单（后台运行健康检测用，检测 SOP §7）。
     * true=已放行后台；false=受系统电池限制；null=系统读不到（此时检测只能判 UNKNOWN，§7.6）。
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            ?: return null
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull()
    }

    /** 跳电池优化白名单页（健康检测异常详情 [去设置]，检测 SOP §17） */
    fun openBatteryOptimizationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
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

    private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
    private const val ENABLED_ACCESSIBILITY_SERVICES = "enabled_accessibility_services"

    /** 本 App 自动填单号服务的全限定类名 */
    private const val A11Y_SERVICE_CLASS =
        "com.parcelhub.autoquery.cainiao.CainiaoAccessibilityService"
}
