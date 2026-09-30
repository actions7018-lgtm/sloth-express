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

import com.parcelhub.autoquery.AutoQueryTaskManager
import com.parcelhub.data.repository.IngestOutcome
import com.parcelhub.data.repository.ShipmentRepository
import com.parcelhub.island.IslandManager
import com.parcelhub.model.RawNotification
import com.parcelhub.notification.AppNotificationManager
import com.parcelhub.parser.ParserEngine
import com.parcelhub.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * 事件队列（SOP §7.2）：
 * 单消费者 Channel，顺序稳定、内存占用低、去重简单；
 * 解析在 Dispatchers.Default（CPU），落库在 Dispatchers.IO。
 * 每条通知不会创建新线程 / 新协程。
 */
class IngestPipeline(
    private val engine: ParserEngine,
    private val repository: ShipmentRepository,
    private val health: HealthState,
    private val sourceSettings: SourceSettings,
    private val notifier: AppNotificationManager,
    scope: CoroutineScope,
    private val autoQuery: AutoQueryTaskManager? = null,
    private val island: IslandManager? = null,
    private val metrics: RecognitionMetrics? = null,
) {
    private val queue: Channel<RawNotification> = Channel(
        capacity = QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 队列中待处理条数（诊断页展示，纯内存计数） */
    val pending: Int get() = inFlight.get()

    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)

    init {
        scope.launch {
            for (raw in queue) {
                inFlight.incrementAndGet()
                try {
                    process(raw)
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
    }

    /**
     * 监听回调唯一入口：纯内存判断 + 入队，不做任何 IO（SOP §7.1）。
     * 顺序：来源是否被关闭 → 极轻量关键词门禁。
     */
    fun gate(packageName: String, body: String): Boolean {
        if (sourceSettings.isBlocked(packageName)) return false
        return engine.cheapGate(packageName, body)
    }

    /** 入队；队列满时丢弃最旧事件并计数（不阻塞监听回调） */
    fun enqueue(raw: RawNotification): Boolean {
        val result = queue.trySend(raw)
        if (result.isFailure) {
            health.onDropped()
            return false
        }
        health.onEvent()
        return true
    }

    private suspend fun process(raw: RawNotification) {
        val parseResult = try {
            withContext(Dispatchers.Default) { engine.parse(raw) }
        } catch (t: Throwable) {
            AppLog.w("parse failed: ${raw.sourcePackage}", t)
            health.onError()
            return
        }

        val events = parseResult.events
        if (events.isEmpty()) {
            health.onSkipped()
            metrics?.onPageMiss(raw.channel)
            AppLog.d("skip ${raw.sourcePackage}: ${parseResult.reason}")
            return
        }

        // §7/§8 列表批量：一页多单 → 逐单入库（每单一条时间线 / 一条提醒）；
        // 单事件场景就是原来的单条路径，行为不变。
        var anyTracking = false
        for (event in events) {
            val outcome: IngestOutcome = try {
                withContext(Dispatchers.IO) { repository.ingest(event) }
            } catch (t: Throwable) {
                AppLog.w("ingest failed: ${raw.sourcePackage}", t)
                health.onError()
                return
            }

            if (event.trackingNumber != null) anyTracking = true
            health.onParsed()

            // 灵动岛（SOP §3 EventDispatcher / §11.1 默认通知模式）：
            // 先交给灵动岛；它接管了（含“今天已经提醒过”）就不再发普通提醒，避免同一件事弹两条。
            val islandHandled = try {
                island?.onIngest(outcome) == true
            } catch (t: Throwable) {
                AppLog.w("island dispatch failed", t)
                false
            }
            if (!islandHandled) notifier.onOutcome(outcome)

            // 自动查询决策（EXPRESS_SMART_QUERY §8）：本地规则 + 落库，不发网络请求、不阻塞解析链
            if (autoQuery != null) {
                try {
                    autoQuery.onOutcome(outcome)
                } catch (t: Throwable) {
                    AppLog.w("auto query decision failed", t)
                }
            }
        }

        // SOP V2.0 §35/§36：识别指标（最近成功取号时间 / 连续未取号计数）
        // + 阶段日志（脱敏：包名 / 通道 / 条数 / 置信度，不含正文与单号）。
        if (anyTracking) {
            metrics?.onTrackingFound()
        } else {
            metrics?.onPageMiss(raw.channel)
        }
        AppLog.d(
            "ingest ${raw.sourcePackage} ch=${raw.channel} " +
                "n=${events.size} trk=$anyTracking conf=${parseResult.confidence}",
        )
    }

    private companion object {
        /** 队列容量：足够吸收通知突发，又不会无界增长（SOP §9.1） */
        const val QUEUE_CAPACITY = 128
    }
}
