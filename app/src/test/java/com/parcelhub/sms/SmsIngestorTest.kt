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

import com.parcelhub.model.RawNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 短信入队器行为（快递短信 SOP §40 过滤 / §42 去重 / §38~§39 隐私不变量）。
 *
 * 与 [SmsDetectionTest] 分工：那边管“解析层认得出来”，这边管
 * “进不进队列、能不能重复、落库字段里有没有原文与手机号”。
 */
class SmsIngestorTest {

    private val seen = InMemorySmsSeenStore()
    private val queued = mutableListOf<RawNotification>()
    private val ingestor = SmsIngestor(seen = seen, enqueue = { queued += it; true })

    private fun envelope(
        body: String = "【顺丰速运】您的快递已到达，运单号SF1234567890123，取件码A328",
        sender: String = "1069000000000",
        timestamp: Long = 1_760_000_000_000L,
    ) = SmsEnvelope(sender = sender, body = body, timestampMs = timestamp)

    @Test
    fun `开关关闭 - 一条都不入队`() {
        val outcome = ingestor.ingest(listOf(envelope()), enabled = false)

        assertEquals(0, outcome.enqueued)
        assertTrue("关开关必须直接丢弃", queued.isEmpty())
        assertEquals("disabled", outcome.reason)
    }

    @Test
    fun `物流短信 - 入队并落成统一原始通知`() {
        val sms = envelope()
        val outcome = ingestor.ingest(listOf(sms), enabled = true)

        assertEquals(1, outcome.enqueued)
        assertEquals(1, queued.size)

        val raw = queued.single()
        assertEquals("来源必须是短信伪包名", SmsContract.SOURCE_PACKAGE, raw.sourcePackage)
        assertEquals("notificationKey 就是短信哈希", sms.hash, raw.notificationKey)
        assertEquals("短信正文只在内存流转", sms.body, raw.text)
        assertEquals(sms.timestampMs, raw.receivedAt)
        assertFalse("通知标题不得是发件人原文", raw.title.contains(sms.sender))
    }

    @Test
    fun `同一条短信重复投递 - 只入队一次`() {
        val first = envelope()
        val second = first.copy()

        ingestor.ingest(listOf(first), enabled = true)
        val outcome = ingestor.ingest(listOf(second), enabled = true)

        assertEquals(1, queued.size)
        assertEquals(0, outcome.enqueued)
        assertEquals("duplicate", outcome.reason)
    }

    @Test
    fun `时间戳不同的同一段正文 - 视为两条新短信`() {
        ingestor.ingest(listOf(envelope(timestamp = 1_760_000_000_000L)), enabled = true)
        ingestor.ingest(listOf(envelope(timestamp = 1_760_000_060_000L)), enabled = true)

        assertEquals("哈希含时间戳，两条应分别入队", 2, queued.size)
    }

    @Test
    fun `验证码与银行短信 - 第一层就丢掉`() {
        val outcome = ingestor.ingest(
            listOf(
                envelope(body = "您的验证码是583921"),
                envelope(body = "您的账户收到一笔转账100元"),
            ),
            enabled = true,
        )

        assertEquals(0, outcome.enqueued)
        assertTrue(queued.isEmpty())
        assertEquals("excluded", outcome.reason)
    }

    @Test
    fun `没有物流关键词的短信 - 不入队`() {
        val outcome = ingestor.ingest(
            listOf(envelope(body = "在吗，晚上一起吃饭？"), envelope(body = "联系电话：13812345678")),
            enabled = true,
        )

        assertEquals(0, outcome.enqueued)
        assertTrue(queued.isEmpty())
        assertEquals("not_logistics", outcome.reason)
    }

    @Test
    fun `队列满入队失败 - 不记账，下次仍可重试`() {
        val failSeen = InMemorySmsSeenStore()
        val failing = SmsIngestor(seen = failSeen, enqueue = { false })
        val sms = envelope()

        val first = failing.ingest(listOf(sms), enabled = true)
        assertEquals(0, first.enqueued)
        assertEquals("queue_full", first.reason)
        assertFalse("入队失败不得记成已处理", failSeen.isSeen(sms.hash))

        val second = SmsIngestor(seen = failSeen, enqueue = { true })
            .ingest(listOf(sms), enabled = true)
        assertEquals("重试应成功", 1, second.enqueued)
    }

    @Test
    fun `去重记账里只有哈希 - 没有原文也没有发件人`() {
        val sms = envelope(body = "【中通快递】您的包裹已到驿站，取件码9988", sender = "13812345678")
        ingestor.ingest(listOf(sms), enabled = true)

        assertEquals(1, seen.size())
        assertFalse("哈希不得包含正文", sms.hash.contains("9988"))
        assertFalse("哈希不得包含发件人", sms.hash.contains("13812345678"))
        assertEquals("同一信封哈希稳定", sms.hash, sms.copy().hash)
    }
}
