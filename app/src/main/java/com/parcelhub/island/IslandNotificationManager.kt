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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.parcelhub.R
import com.parcelhub.ui.MainActivity
import com.parcelhub.util.AppLog

/**
 * 灵动岛的**系统通知模式**（SOP §11.1 默认模式）。
 *
 * 为什么默认用通知：
 *  - 兼容性最好，不要求「显示在其他应用上层」权限（SOP §13）；
 *  - 功耗低，App 被回收后通知依然可用；
 *  - 悬浮（Overlay）模式不可用时也回退到这里（SOP §19）。
 *
 * 关键口径：
 *  - 同一时刻只有一条灵动岛通知（固定 id，新事件直接覆盖旧的）——灵动岛是“短时提醒”，不是列表；
 *  - [IslandState.SUCCESS] 的 `✓ 取件完成` 约 2 秒后自动消失（SOP §7.4 / §8.3）；
 *  - 点击进入对应快递详情（SOP §16.1）。
 */
class IslandNotificationManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val handler = Handler(Looper.getMainLooper())

    /** 自动隐藏任务：只给 SUCCESS 态用（一次性，不是轮询） */
    private val hideSuccess = Runnable { cancel() }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ISLAND,
            "快递灵动岛",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "包裹到站、派送中、取件完成的短时提醒（点击进入快递详情）"
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /** 悬浮服务的前台通知渠道（静默，仅在悬浮胶囊显示期间短暂存在） */
    fun ensureOverlayServiceChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_OVERLAY_SERVICE,
            "灵动岛悬浮显示",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "悬浮灵动岛显示期间的前台服务通知，胶囊消失后自动移除"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun hasPermission(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    /**
     * 展示一条灵动岛通知。
     *
     * @return true = 已展示；false = 无通知权限或系统拒绝（调用方记录日志即可，不影响数据）
     */
    fun show(content: IslandContent, state: IslandState): Boolean {
        if (!hasPermission()) {
            AppLog.d("island notification permission denied")
            return false
        }
        ensureChannel()

        val notification = build(content, CHANNEL_ISLAND, NotificationCompat.PRIORITY_HIGH)
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID_ISLAND, notification)
            scheduleAutoHide(state)
            true
        } catch (t: SecurityException) {
            AppLog.w("island notify failed", t)
            false
        }
    }

    /** 撤掉当前灵动岛通知 */
    fun cancel() {
        handler.removeCallbacks(hideSuccess)
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFY_ID_ISLAND)
        } catch (t: SecurityException) {
            AppLog.w("island cancel failed", t)
        }
    }

    /**
     * 通知模式下 SUCCESS 态的“1～2 秒后自动消失”（SOP §7.4）。
     * 展开态的通知保留，用户可以稍后回看。
     */
    private fun scheduleAutoHide(state: IslandState) {
        handler.removeCallbacks(hideSuccess)
        if (state == IslandState.SUCCESS) {
            handler.postDelayed(hideSuccess, SUCCESS_AUTO_HIDE_MS)
        }
    }

    /** 通知本体（点击进详情，SOP §16.1） */
    fun build(content: IslandContent, channelId: String, priority: Int): Notification {
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SHIPMENT_ID, content.shipmentId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            content.shipmentId.toInt(),
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val body = listOfNotNull(content.line1, content.line2).joinToString("\n")
            .ifEmpty { content.title }
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(content.title)
            .setContentText(body.lines().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
    }

    private companion object {
        /** 固定 id：同一时刻只保留一条灵动岛通知 */
        const val NOTIFY_ID_ISLAND = 0x151A17
        const val CHANNEL_ISLAND = "island"
        const val CHANNEL_OVERLAY_SERVICE = "island_overlay"

        /** SOP §7.4：成功态约 1～2 秒后消失 */
        const val SUCCESS_AUTO_HIDE_MS = 2_000L
    }
}
