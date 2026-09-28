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
import android.provider.Telephony
import com.parcelhub.App
import com.parcelhub.util.AppLog
import kotlinx.coroutines.launch

/**
 * 实时短信接收（快递短信 SOP §7/§8）。
 *
 * Receiver 只做三件事：**接收 → 提取 → 分发**；不查物流接口、不跑批量任务、不长时间运行，
 * 后续解析/落库全部在 `IngestPipeline` 的单队列里异步完成。
 *
 * 隐私（SOP §38/§39）：
 *  - 正文只在内存里流转，本类不打印任何正文与发件人，日志只记计数与原因；
 *  - 关不掉的永远是“读到的内容”，所以去重只记 `sha256(sender|body|timestamp)`（SOP §42）。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val envelopes = envelopesFrom(intent)
        if (envelopes.isEmpty()) {
            AppLog.d("sms receive: empty payload")
            return
        }
        dispatchSms(context, envelopes)
    }
}

/**
 * 把一批短信交给统一链路（SOP §49 最终数据链路的前半段）。
 *
 * [goAsync] 把进程多留一会儿：开关状态要从 `source_apps` 读真实值，
 * 冷进程下内存镜像还没 `refresh()`，直接读会拿到“默认开”的过期状态。
 */
internal fun BroadcastReceiver.dispatchSms(context: Context, envelopes: List<SmsEnvelope>) {
    if (context.applicationContext !is App) return
    val graph = App.graph
    val pending = goAsync()
    graph.appScope.launch {
        try {
            val enabled = runCatching { graph.isSmsSourceEnabled() }.getOrDefault(true)
            val outcome = graph.smsIngestor.ingest(envelopes, enabled)
            // 隐私（SOP §39）：只记开关、计数与脱敏原因，绝不输出正文 / 发件人
            AppLog.d(
                "sms ingest: enabled=$enabled enqueued=${outcome.enqueued} " +
                    "skipped=${outcome.skipped} reason=${outcome.reason}",
            )
        } catch (t: Throwable) {
            AppLog.w("sms ingest failed", t)
        } finally {
            pending.finish()
        }
    }
}

/**
 * 从广播里取出信封，**多段短信按发件人合并**（SOP §46 边界测试：短信分段）。
 *
 * 时间取各分段里最早的时间戳——同一通短信的分段秒级到达，用最早的那个做去重哈希，
 * 免得两次投递顺序不同算出两个哈希。
 */
internal fun envelopesFrom(intent: Intent): List<SmsEnvelope> {
    val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }
        .getOrNull()
        ?.filterNotNull()
        .orEmpty()
    if (messages.isEmpty()) return emptyList()

    return messages
        .groupBy { message ->
            (message.displayOriginatingAddress ?: message.originatingAddress ?: "").trim()
        }
        .mapNotNull { (sender, parts) ->
            val body = parts.joinToString("") { part ->
                (part.displayMessageBody ?: part.messageBody).orEmpty()
            }
            if (body.isBlank()) return@mapNotNull null
            val at = parts.map { it.timestampMillis }.filter { it > 0L }.minOrNull()
                ?: System.currentTimeMillis()
            SmsEnvelope(sender = sender, body = body, timestampMs = at)
        }
}
