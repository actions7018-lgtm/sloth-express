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

import android.content.Context

/**
 * 短信去重存储（SOP §42 短信重复处理）。
 *
 * 记账的只有 [SmsEnvelope.hash]（`sha256(sender|body|timestamp)`），
 * **不存原文、不存发件人、不存时间戳**——即使导出也还原不出任何短信内容（SOP §38/§39）。
 */
interface SmsSeenStore {
    fun isSeen(hash: String): Boolean

    fun mark(hash: String)
}

/** JVM 单测用的内存实现 */
class InMemorySmsSeenStore : SmsSeenStore {
    private val seen = linkedSetOf<String>()

    override fun isSeen(hash: String): Boolean = hash in seen

    override fun mark(hash: String) {
        seen += hash
    }

    /** 测试辅助：已记录的哈希数量 */
    fun size(): Int = seen.size
}

/**
 * 真机实现：SharedPreferences 里的**有界**哈希队列。
 *
 * 与灵动岛的日期桶去重不同，短信不需要按天清空——“同一条短信重复投递”
 * 可能跨天发生（运营商重发 / 双卡双收），所以改成“保留最近 N 条”的滑动窗口：
 * 既能跨重启去重，又保证存储量有上界。
 */
class SharedPreferencesSmsSeenStore(
    context: Context,
    private val maxEntries: Int = MAX_ENTRIES,
) : SmsSeenStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    override fun isSeen(hash: String): Boolean = read().contains(hash)

    @Synchronized
    override fun mark(hash: String) {
        val current = read().toMutableList()
        current.remove(hash)
        current.add(hash)
        val trimmed = if (current.size > maxEntries) current.takeLast(maxEntries) else current
        prefs.edit().putString(KEY_SEEN, trimmed.joinToString("\n")).apply()
    }

    private fun read(): List<String> =
        prefs.getString(KEY_SEEN, "").orEmpty().split('\n').filter { it.isNotEmpty() }

    private companion object {
        const val PREFS = "parcelhub_sms_seen"
        const val KEY_SEEN = "seen"

        /** 最近 200 条：短信重复投递的窗口远小于这个量级，够用且有界 */
        const val MAX_ENTRIES = 200
    }
}
