/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.ingest

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.parcelhub.App
import com.parcelhub.model.RawNotification
import com.parcelhub.util.AppLog

/**
 * 默认采集模式（SOP §3.1）：NotificationListenerService。
 *
 * onNotificationPosted() 只做 6 件事（SOP §7.1）：
 *  1. 取 packageName
 *  2. 判断是否在来源白名单
 *  3. 提取 title/text/bigText
 *  4. 极轻量关键词门禁
 *  5. 生成 RawNotification
 *  6. 放入单线程解析队列
 *
 * 禁止：网络、批量 Room 查询、复杂正则、JSON、OCR、AI、图片、同步磁盘 IO。
 * 正则、匹配、落库全部发生在 [IngestPipeline] 的后台协程中。
 */
class NotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        App.graph.health.onListenerChanged(true)
        App.graph.health.onNotificationAccess(true)
        AppLog.i("notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        App.graph.health.onListenerChanged(false)
        AppLog.w("notification listener disconnected")
        // Android 官方恢复机制：系统可能因内存压力解绑，这里请求重新绑定
        runCatching {
            requestRebind(ComponentName(this, NotificationListener::class.java))
        }.onFailure { AppLog.w("requestRebind failed", it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val packageName = sbn?.packageName?.toString() ?: return
        if (packageName == this.packageName) return
        if (sbn.isOngoing) return

        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        if (title.isBlank() && text.isBlank()) return

        // 4) 极轻量关键词门禁（只做字符串包含判断）
        val pipeline = App.graph.pipeline
        val gateText = if (title.isBlank()) text else "$title\n$text"
        var pass = pipeline.gate(packageName, gateText)
        if (!pass && !bigText.isNullOrBlank()) {
            pass = pipeline.gate(packageName, bigText)
        }
        if (!pass) return

        // 5) 生成事件对象（原始文本随后由解析线程消费并释放）
        val raw = RawNotification(
            sourcePackage = packageName,
            sourceAppName = null,
            notificationKey = sbn.key,
            title = title,
            text = text,
            bigText = bigText,
            subText = subText,
            receivedAt = if (sbn.postTime > 0L) sbn.postTime else System.currentTimeMillis(),
            isOngoing = sbn.isOngoing,
        )

        // 6) 入队（单消费者，顺序稳定）
        pipeline.enqueue(raw)
    }
}
