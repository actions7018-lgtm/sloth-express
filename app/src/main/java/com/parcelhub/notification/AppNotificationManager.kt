/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.parcelhub.R
import com.parcelhub.autoquery.cainiao.CainiaoOpenActivity
import com.parcelhub.data.repository.IngestOutcome
import com.parcelhub.data.repository.ShipmentRepository
import com.parcelhub.matcher.DedupEngine
import com.parcelhub.matcher.StateMachine
import com.parcelhub.model.ShipmentStatus
import com.parcelhub.ui.MainActivity
import com.parcelhub.util.AppLog
import com.parcelhub.util.TimeUtil

/**
 * App 自己的提醒（SOP §12）：与“来源 App 通知”完全分离。
 *
 * 触发条件（§12.1）：
 *  - 到站 / 入柜 / 发现取件码 → 强提醒
 *  - 派送中 → 可选提醒（默认开）
 *  - 运输中 → 可选提醒（默认关，避免打扰）
 * 去重窗口（§12.2）：同 Shipment 相同状态 + 相同取件码 + 相同地点在窗口内只提醒一次。
 *
 * 事件驱动、即时发送，不引入轮询 / WorkManager / 常驻任务。
 */
class AppNotificationManager(
    private val context: Context,
    private val repository: ShipmentRepository,
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 提醒指纹缓存：按访问顺序限制容量，防止无限增长（SOP §9.1） */
    private val reminders = object : LinkedHashMap<Long, DedupEngine.ReminderSignature>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, DedupEngine.ReminderSignature>): Boolean =
            size > MAX_REMINDER_CACHE
    }

    private val lock = Any()

    var outForDeliveryEnabled: Boolean
        get() = prefs.getBoolean(KEY_OUT_FOR_DELIVERY, true)
        set(value) = prefs.edit().putBoolean(KEY_OUT_FOR_DELIVERY, value).apply()

    var transitEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRANSIT, false)
        set(value) = prefs.edit().putBoolean(KEY_TRANSIT, value).apply()

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val arrival = NotificationChannel(
            CHANNEL_ARRIVAL,
            "到站与取件码提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "包裹到站、入柜、出现取件码时提醒" }
        val logistics = NotificationChannel(
            CHANNEL_LOGISTICS,
            "物流状态提醒",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "派送中、运输中等状态更新提醒" }
        val autoQuery = NotificationChannel(
            CHANNEL_AUTO_QUERY,
            "自动查询",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "需要查询时提供“打开菜鸟并填入单号”的入口（默认不横幅打扰）" }
        notificationManager.createNotificationChannel(arrival)
        notificationManager.createNotificationChannel(logistics)
        notificationManager.createNotificationChannel(autoQuery)
    }

    fun hasPermission(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    /** 事件入库后的提醒决策（由解析队列串行调用） */
    suspend fun onOutcome(outcome: IngestOutcome) {
        if (outcome.kind == IngestOutcome.Kind.DUPLICATE ||
            outcome.kind == IngestOutcome.Kind.IGNORED
        ) {
            return
        }
        if (outcome.shipmentId <= 0L) return

        val strong = StateMachine.isStrongReminder(outcome.status) || !outcome.pickupCode.isNullOrEmpty()
        val wanted = when {
            strong -> true
            outcome.status == ShipmentStatus.OUT_FOR_DELIVERY -> outForDeliveryEnabled
            outcome.status == ShipmentStatus.SHIPPED ||
                outcome.status == ShipmentStatus.IN_TRANSIT -> transitEnabled
            else -> false
        }
        if (!wanted) return
        if (!hasPermission()) {
            AppLog.d("notification permission denied, skip remind")
            return
        }

        val now = System.currentTimeMillis()
        val signature = DedupEngine.ReminderSignature(
            status = outcome.status,
            pickupCode = outcome.pickupCode,
            pickupLocation = outcome.pickupLocation,
            firstSeenAt = now,
        )

        synchronized(lock) {
            val previous = reminders[outcome.shipmentId]
            if (!DedupEngine.shouldRemind(previous, signature, now)) return
            reminders[outcome.shipmentId] = signature
        }

        val shipment = repository.getShipment(outcome.shipmentId) ?: return
        val title = when {
            !outcome.pickupCode.isNullOrEmpty() -> "📦 有取件码，快去取件"
            outcome.status == ShipmentStatus.ARRIVED -> "📦 包裹已到站"
            outcome.status == ShipmentStatus.PICKUP_READY -> "📦 包裹可取件"
            outcome.status == ShipmentStatus.OUT_FOR_DELIVERY -> "🚚 快递派送中"
            else -> "🚚 快递状态更新"
        }
        val carrier = shipment.carrier ?: "快递"
        val lines = buildString {
            append(carrier)
            if (!outcome.pickupLocation.isNullOrBlank()) {
                append(" · ").append(outcome.pickupLocation)
            }
            if (!outcome.pickupCode.isNullOrEmpty()) {
                append("\n取件码：").append(outcome.pickupCode)
            } else {
                append("\n状态：").append(pretty(outcome.status))
            }
        }

        show(
            notificationId = outcome.shipmentId.toInt(),
            channelId = if (strong) CHANNEL_ARRIVAL else CHANNEL_LOGISTICS,
            title = title,
            text = lines,
            shipmentId = outcome.shipmentId,
        )
    }

    /**
     * 自动查询入口通知（EXPRESS_SMART_QUERY §8/§14/§32）。
     *
     * 内容只写用户看得懂的信息；按钮动作 = 打开菜鸟并填入单号，
     * 由用户点击触发（Android 12+ 不允许后台直接拉起，SOP §14）。
     * 第一版**不**在通知里回显物流结果（SOP §23：不回读查询结果）。
     */
    fun notifyAutoQuery(
        taskId: String,
        trackingNumber: String,
        carrier: String?,
        pickupCode: String?,
    ) {
        if (!hasPermission()) {
            AppLog.d("notification permission denied, skip auto query")
            return
        }
        val request = taskId.hashCode()
        val openIntent = CainiaoOpenActivity.intent(context, trackingNumber)
        val openPending = PendingIntent.getActivity(
            context,
            request,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SHIPMENT_ID, 0L)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPending = PendingIntent.getActivity(
            context,
            request + 1,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (pickupCode.isNullOrEmpty()) "🔍 可以查一下这个包裹" else "📦 包裹信息有更新"
        val body = buildString {
            append(carrier ?: "快递")
            append(" · 单号 ").append(tail(trackingNumber))
            if (!pickupCode.isNullOrEmpty()) append('\n').append("取件码：").append(pickupCode)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_AUTO_QUERY)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body.lines().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(mainPending)
            .addAction(
                R.drawable.ic_launcher,
                "打开菜鸟并填单号",
                openPending,
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(request, notification)
        } catch (t: SecurityException) {
            AppLog.w("notify auto query failed", t)
        }
    }

    /** 二次判断取消后撤掉入口通知（SOP §10.2） */
    fun cancelAutoQuery(taskId: String) {
        try {
            NotificationManagerCompat.from(context).cancel(taskId.hashCode())
        } catch (t: SecurityException) {
            AppLog.w("cancel auto query failed", t)
        }
    }

    /**
     * 自动化失败后通知用户手动查询（SOP Step 20 / T07 / T08 / §31）。
     *
     * 只说用户听得懂的话（见 [AutoQueryFailureHint]），技术原因只进日志与数据库。
     * 第一版止步于填单号（SOP §23），失败=回退到手动，不会自动重试拉起。
     */
    fun notifyAutoQueryFailed(trackingNumber: String, reason: String?) {
        if (!hasPermission()) {
            AppLog.d("notification permission denied, skip auto query failure")
            return
        }
        val hint = com.parcelhub.autoquery.cainiao.AutoQueryFailureHint.hint(reason)
        val notification = NotificationCompat.Builder(context, CHANNEL_AUTO_QUERY)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("自动填单号没成功")
            .setContentText("$hint，请手动查一下 ${tail(trackingNumber)}")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$hint，自动填单号已停止。\n请打开菜鸟手动查一下 ${tail(trackingNumber)}。",
                ),
            )
            .setContentIntent(mainPending("fail:$trackingNumber"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(("fail:$trackingNumber").hashCode(), notification)
        } catch (t: SecurityException) {
            AppLog.w("notify auto query failure failed", t)
        }
    }

    /**
     * 菜鸟未安装：给安装入口，不给技术错误（SOP §13）。
     *
     * [安装菜鸟] 跳应用市场详情页（`market://` 由系统应用市场处理，
     * 不需要本 App 申请联网权限）。
     */
    fun notifyCainiaoMissing(trackingNumber: String) {
        if (!hasPermission()) {
            AppLog.d("notification permission denied, skip cainiao missing")
            return
        }
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            android.net.Uri.parse("market://details?id=com.cainiao.wireless"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val marketPending = PendingIntent.getActivity(
            context,
            ("market:$trackingNumber").hashCode(),
            marketIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_AUTO_QUERY)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("需要查询包裹")
            .setContentText("当前设备没有安装菜鸟，装上后可自动填单号。")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "当前设备没有安装菜鸟。\n单号 ${tail(trackingNumber)}，装上菜鸟后可自动填单号查询。",
                ),
            )
            .setContentIntent(mainPending("missing:$trackingNumber"))
            .addAction(R.drawable.ic_launcher, "安装菜鸟", marketPending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(("missing:$trackingNumber").hashCode(), notification)
        } catch (t: SecurityException) {
            AppLog.w("notify cainiao missing failed", t)
        }
    }

    /**
     * 无障碍未开启：提示开启（SOP T06）。
     *
     * [去开启] 跳系统无障碍设置，**由用户手动开启**（SOP §5/§6，App 不得偷开）。
     */
    fun notifyAccessibilityMissing() {
        if (!hasPermission()) {
            AppLog.d("notification permission denied, skip accessibility missing")
            return
        }
        val settingsIntent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val settingsPending = PendingIntent.getActivity(
            context,
            REQUEST_ACCESSIBILITY_SETTINGS,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_AUTO_QUERY)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("自动填单号未开启")
            .setContentText("去系统设置里打开“树懒快递助手 · 自动填单号”即可。")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "自动填单号需要你在系统设置里手动开启无障碍服务。\n只在菜鸟查快递页填入单号，不读取其他应用内容。",
                ),
            )
            .setContentIntent(settingsPending)
            .addAction(R.drawable.ic_launcher, "去开启", settingsPending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID_ACCESSIBILITY, notification)
        } catch (t: SecurityException) {
            AppLog.w("notify accessibility missing failed", t)
        }
    }

    /** 回到 App 首页的 PendingIntent（失败 / 缺菜鸟通知共用） */
    private fun mainPending(tag: String): PendingIntent {
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SHIPMENT_ID, 0L)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            tag.hashCode(),
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 单号脱敏展示：只露头尾（隐私口径：解析与保存都在本机） */
    private fun tail(trackingNumber: String): String =
        if (trackingNumber.length <= 6) trackingNumber
        else trackingNumber.take(3) + "…" + trackingNumber.takeLast(3)

    private fun show(
        notificationId: Int,
        channelId: String,
        title: String,
        text: String,
        shipmentId: Long,
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SHIPMENT_ID, shipmentId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            shipmentId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text.lines().firstOrNull())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (t: SecurityException) {
            AppLog.w("notify failed", t)
        }
    }

    private fun pretty(status: ShipmentStatus): String = when (status) {
        ShipmentStatus.UNKNOWN -> "已收到新事件"
        ShipmentStatus.SHIPPED -> "已发货"
        ShipmentStatus.IN_TRANSIT -> "运输中"
        ShipmentStatus.OUT_FOR_DELIVERY -> "派送中"
        ShipmentStatus.ARRIVED -> "已到站"
        ShipmentStatus.PICKUP_READY -> "可取件"
        ShipmentStatus.DELIVERED -> "已签收"
        ShipmentStatus.RETURNED -> "已退回"
        ShipmentStatus.CANCELLED -> "已取消"
    }

    /** 诊断页展示：最近一次提醒时间 */
    fun lastReminderAt(): Long = synchronized(lock) { reminders.values.maxOfOrNull { it.firstSeenAt } ?: 0L }

    private companion object {
        const val PREFS = "parcelhub_notify"
        const val KEY_OUT_FOR_DELIVERY = "out_for_delivery"
        const val KEY_TRANSIT = "transit"
        const val CHANNEL_ARRIVAL = "arrival"
        const val CHANNEL_LOGISTICS = "logistics"
        const val CHANNEL_AUTO_QUERY = "auto_query"

        private const val NOTIFY_ID_ACCESSIBILITY = 0xA11CE
        private const val REQUEST_ACCESSIBILITY_SETTINGS = 0xA11C5
        const val MAX_REMINDER_CACHE = 64
    }
}
