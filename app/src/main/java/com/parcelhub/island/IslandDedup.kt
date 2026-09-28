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

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日期桶：`yyyyMMdd`（SOP §15 的“事件版本/时间桶”，示例 `7515_ARRIVED_20260927`）。
 *
 * 每次新建 `SimpleDateFormat`：它不是线程安全的，而灵动岛事件频率很低，开销可忽略；
 * 也刻意不用 `java.time.LocalDate`——minSdk 24 上没有脱糖会直接崩。
 */
fun islandDayBucket(millis: Long): String =
    SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(millis))

/**
 * 防重复提醒（SOP §15）。
 *
 * eventId = `parcelId_eventType_日期桶`（示例：`7515_ARRIVED_20260927`），
 * 同一包裹同一类型同一自然日只展示一次——“同一个状态连续刷新不反复弹出”（SOP §8.2）。
 *
 * 存储做成接口：真机用 [SharedPreferencesSeenStore]（跨进程/重启都保留），
 * 单测用 [InMemorySeenStore]，不依赖 Android 运行时。
 */
class IslandDedup(
    private val store: IslandSeenStore,
    private val dayOf: (Long) -> String = ::islandDayBucket,
) {

    /** 生成 eventId（SOP §15） */
    fun eventId(event: IslandEvent, now: Long): String =
        "${event.shipmentId}_${event.kind.name}_${dayOf(now)}"

    /**
     * 判断是否应该展示，并在放行时记账。
     *
     * @return true = 首次出现，应该展示；false = 今天已经展示过，禁止再次弹出
     */
    fun shouldShow(event: IslandEvent, now: Long): Boolean {
        store.rollDay(dayOf(now))
        val id = eventId(event, now)
        if (store.hasSeen(id)) return false
        store.mark(id)
        return true
    }
}

/** 已展示事件的存储抽象（SOP §15 的 `IslandEventRecord`） */
interface IslandSeenStore {
    /** 切换日期桶：跨天时清空旧记录，保证存储量随天数而不是随事件数增长 */
    fun rollDay(day: String)

    fun hasSeen(eventId: String): Boolean

    fun mark(eventId: String)
}

/** JVM 单测用的内存实现 */
class InMemorySeenStore : IslandSeenStore {
    private var day: String = ""
    private val seen = linkedSetOf<String>()

    override fun rollDay(day: String) {
        if (this.day != day) {
            this.day = day
            seen.clear()
        }
    }

    override fun hasSeen(eventId: String): Boolean = eventId in seen

    override fun mark(eventId: String) {
        seen += eventId
    }

    /** 测试辅助：已记录的 eventId 数量 */
    fun size(): Int = seen.size
}

/**
 * 真机实现：SharedPreferences 字符串集合。
 *
 * 跨 App 重启仍然有效（SOP §22 测试 5「重新打开 App」不重复弹）；
 * 与 `rollDay` 配合，跨天自动清空，避免无界增长。
 */
class SharedPreferencesSeenStore(context: Context) : IslandSeenStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun rollDay(day: String) {
        if (prefs.getString(KEY_DAY, null) == day) return
        prefs.edit().putString(KEY_DAY, day).putStringSet(KEY_SEEN, emptySet()).apply()
    }

    override fun hasSeen(eventId: String): Boolean =
        prefs.getStringSet(KEY_SEEN, emptySet())?.contains(eventId) == true

    override fun mark(eventId: String) {
        val current = prefs.getStringSet(KEY_SEEN, emptySet())?.toMutableSet() ?: mutableSetOf()
        current += eventId
        prefs.edit().putStringSet(KEY_SEEN, current).apply()
    }

    private companion object {
        const val PREFS = "parcelhub_island_seen"
        const val KEY_DAY = "day"
        const val KEY_SEEN = "seen"
    }
}
