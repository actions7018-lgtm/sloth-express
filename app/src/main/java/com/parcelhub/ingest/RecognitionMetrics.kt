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

import android.content.Context
import com.parcelhub.pending.PendingSourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 识别运行指标（SOP V2.0 §35）：只存时间戳与计数，不存页面内容——
 * 用于区分「这个 App 就是没快递」和「无障碍/OCR 根本没工作」：
 *  - 最近一次页面读取（含时间 + 包名脱敏前的包名，仅元数据）；
 *  - 最近一次成功取到单号；
 *  - 连续未取到单号的页面读取次数（取到即清零）；
 *  - OCR 兜底次数与最近一次 OCR 时间。
 *
 * 落 SharedPreferences：进程重启 / 服务重绑后设置页仍能看到历史证据。
 */
class RecognitionMetrics(context: Context) {

    data class Snapshot(
        val lastPageReadAt: Long = 0L,
        val lastPageReadPkg: String? = null,
        val lastTrackingFoundAt: Long = 0L,
        val lastOcrAt: Long = 0L,
        val ocrCount: Long = 0L,
        val consecutiveNoTracking: Int = 0,
    )

    private val prefs = context.applicationContext
        .getSharedPreferences("recognition_metrics", Context.MODE_PRIVATE)

    private val _snapshot = MutableStateFlow(load())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val lock = Any()

    private fun load() = Snapshot(
        lastPageReadAt = prefs.getLong(KEY_PAGE_READ_AT, 0L),
        lastPageReadPkg = prefs.getString(KEY_PAGE_READ_PKG, null),
        lastTrackingFoundAt = prefs.getLong(KEY_TRACKING_FOUND_AT, 0L),
        lastOcrAt = prefs.getLong(KEY_OCR_AT, 0L),
        ocrCount = prefs.getLong(KEY_OCR_COUNT, 0L),
        consecutiveNoTracking = prefs.getInt(KEY_NO_TRACKING_STREAK, 0),
    )

    private fun mutate(transform: (Snapshot) -> Snapshot) {
        synchronized(lock) {
            val next = transform(_snapshot.value)
            _snapshot.value = next
            prefs.edit()
                .putLong(KEY_PAGE_READ_AT, next.lastPageReadAt)
                .putString(KEY_PAGE_READ_PKG, next.lastPageReadPkg)
                .putLong(KEY_TRACKING_FOUND_AT, next.lastTrackingFoundAt)
                .putLong(KEY_OCR_AT, next.lastOcrAt)
                .putLong(KEY_OCR_COUNT, next.ocrCount)
                .putInt(KEY_NO_TRACKING_STREAK, next.consecutiveNoTracking)
                .apply()
        }
    }

    /** 无障碍读到一次有文本的页面（SOP §35「最近一次页面识别」） */
    fun onPageRead(packageName: String?) = mutate {
        it.copy(lastPageReadAt = System.currentTimeMillis(), lastPageReadPkg = packageName)
    }

    /** 解析链路取到单号：清空连续未取到计数 */
    fun onTrackingFound() = mutate {
        it.copy(lastTrackingFoundAt = System.currentTimeMillis(), consecutiveNoTracking = 0)
    }

    /** 页面通道（无障碍 / OCR）读取但没拿到单号：累计，只对页面通道计数 */
    fun onPageMiss(channel: String?) {
        if (channel != PendingSourceType.ACCESSIBILITY.name &&
            channel != PendingSourceType.SCREEN_OCR.name
        ) {
            return
        }
        mutate { it.copy(consecutiveNoTracking = it.consecutiveNoTracking + 1) }
    }

    /** OCR 兜底完成一次（无论识别结果如何都计数，成功与否看单号是否入库） */
    fun onOcrCapture() = mutate {
        it.copy(lastOcrAt = System.currentTimeMillis(), ocrCount = it.ocrCount + 1)
    }

    private companion object {
        const val KEY_PAGE_READ_AT = "last_page_read_at"
        const val KEY_PAGE_READ_PKG = "last_page_read_pkg"
        const val KEY_TRACKING_FOUND_AT = "last_tracking_found_at"
        const val KEY_OCR_AT = "last_ocr_at"
        const val KEY_OCR_COUNT = "ocr_count"
        const val KEY_NO_TRACKING_STREAK = "no_tracking_streak"
    }
}
