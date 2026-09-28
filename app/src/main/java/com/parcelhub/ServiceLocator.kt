/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.parcelhub.data.db.AppDatabase
import com.parcelhub.data.entity.RuleEntity
import com.parcelhub.data.repository.ShipmentRepository
import com.parcelhub.model.RawNotification
import com.parcelhub.autoquery.AutoQueryTaskManager
import com.parcelhub.autoquery.cainiao.CainiaoAutomationSession
import com.parcelhub.autoquery.cainiao.CainiaoLaunchGate
import com.parcelhub.ingest.HealthState
import com.parcelhub.ingest.IngestPipeline
import com.parcelhub.ingest.SourceSettings
import com.parcelhub.island.IslandDedup
import com.parcelhub.island.IslandEventDispatcher
import com.parcelhub.island.IslandManager
import com.parcelhub.island.IslandNotificationManager
import com.parcelhub.island.SharedPreferencesSeenStore
import com.parcelhub.notification.AppNotificationManager
import com.parcelhub.parser.ParserEngine
import com.parcelhub.parser.RuleManager
import com.parcelhub.sms.SmsContract
import com.parcelhub.sms.SmsIngestor
import com.parcelhub.sms.SharedPreferencesSmsSeenStore
import com.parcelhub.util.AppLog
import com.parcelhub.widget.TodoWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手工依赖容器：一个 App 只有一个实例。
 *
 * 分层遵循 SOP §3 的数据链路：
 * 数据源(NotificationListener / 分享导入 / 后续 API)
 *   → RawNotification → ParserEngine → ParcelEvent → ShipmentMatcher → Shipment → UI
 */
class ServiceLocator(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 应用级协程作用域：只用于装配与低频后台任务，不用于常驻轮询 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val settingsPrefs by lazy {
        appContext.getSharedPreferences(KEY_SETTINGS, Context.MODE_PRIVATE)
    }

    fun isOnboardingDone(): Boolean = settingsPrefs.getBoolean(KEY_ONBOARDING_DONE, false)

    fun markOnboardingDone() {
        settingsPrefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
    }

    val database: AppDatabase by lazy { AppDatabase.get(appContext) }

    val repository: ShipmentRepository by lazy { ShipmentRepository(database) }

    val health: HealthState by lazy { HealthState() }

    private val rulesJson: String by lazy {
        appContext.assets.open(RULES_ASSET).bufferedReader().use { it.readText() }
    }

    val ruleManager: RuleManager by lazy { RuleManager { rulesJson } }

    val engine: ParserEngine by lazy { ParserEngine(ruleManager.rules()) }

    val sourceSettings: SourceSettings by lazy {
        SourceSettings(
            repository = repository,
            rules = ruleManager.rules(),
            initialGenericEnabled = settingsPrefs.getBoolean(KEY_GENERIC_SOURCE, true),
            onGenericChanged = { enabled ->
                settingsPrefs.edit().putBoolean(KEY_GENERIC_SOURCE, enabled).apply()
            },
        )
    }

    val notifier: AppNotificationManager by lazy { AppNotificationManager(appContext, repository) }

    /**
     * 快递灵动岛（SOP §11/§12）：与首页、桌面 Widget 共用同一份 [repository]，
     * **不建第二套数据库**（SOP §24 核心原则 2）。
     *
     * 创建时把“标记已取件写库结果”回调挂到仓储上，保证三个写入口
     * （首页卡片、详情页、桌面 Widget）都会触发同一个 `✓ 取件完成` 事件。
     */
    val island: IslandManager by lazy {
        IslandManager(
            context = appContext,
            repository = repository,
            dispatcher = IslandEventDispatcher(),
            dedup = IslandDedup(SharedPreferencesSeenStore(appContext)),
            notifier = IslandNotificationManager(appContext),
            scope = appScope,
        ).also { manager ->
            repository.onUserStatusChanged = { shipmentId, status, updated ->
                manager.onUserStatusChanged(shipmentId, status, updated)
            }
        }
    }

    /** 自动查询开关（EXPRESS_SMART_QUERY §4 第一优先级），默认开 */
    var autoQueryEnabled: Boolean
        get() = settingsPrefs.getBoolean(KEY_AUTO_QUERY_ENABLED, true)
        set(value) {
            settingsPrefs.edit().putBoolean(KEY_AUTO_QUERY_ENABLED, value).apply()
        }

    /** 同单号 30 秒去重门禁（SOP §10.1），与任务表一起构成“唯一有效任务”约束 */
    val autoQueryGate: CainiaoLaunchGate by lazy { CainiaoLaunchGate() }

    val autoQueryTaskManager: AutoQueryTaskManager by lazy {
        AutoQueryTaskManager(
            context = appContext,
            dao = database.queryTaskDao(),
            repository = repository,
            gate = autoQueryGate,
            notifier = notifier,
            scope = appScope,
            autoQueryEnabled = { autoQueryEnabled },
        )
    }

    /**
     * 菜鸟自动填单号会话（SOP §16~§19/§25/§26）。
     * 单例：无障碍服务与中转 Activity 由系统创建，统一从这里取同一份状态。
     */
    val cainiaoAutomation: CainiaoAutomationSession by lazy {
        CainiaoAutomationSession(
            onStatus = { tracking, status ->
                // 回调触发时会话单例已初始化完成，直接读失败现场（SOP §31 落库失败原因）
                val failure = cainiaoAutomation.lastFailure
                val attempts = cainiaoAutomation.attempts
                appScope.launch {
                    runCatching {
                        autoQueryTaskManager.onSessionStatus(tracking, status, failure, attempts)
                    }.onFailure { AppLog.w("persist task status failed", it) }
                }
            },
        )
    }

    val pipeline: IngestPipeline by lazy {
        IngestPipeline(
            engine = engine,
            repository = repository,
            health = health,
            sourceSettings = sourceSettings,
            notifier = notifier,
            scope = appScope,
            autoQuery = autoQueryTaskManager,
            island = island,
        )
    }

    /**
     * 短信来源（快递短信 SOP §3 / §49）：短信只是又一个数据来源，
     * 与通知监听、分享导入**共用同一个 [pipeline]**（同一套解析 / 合并 / 状态机 /
     * 灵动岛 / 自动查询），不为短信建第二条链路。
     */
    val smsIngestor: SmsIngestor by lazy {
        SmsIngestor(
            seen = SharedPreferencesSmsSeenStore(appContext),
            enqueue = { pipeline.enqueue(it) },
        )
    }

    /**
     * 来源管理里“短信”开关的真实状态（默认开，SOP §6 可插拔）。
     *
     * 直接读 `source_apps` 而不是内存镜像：`SmsReceiver` 是冷进程被广播拉起的，
     * `SourceSettings` 的 StateFlow 可能还没 `refresh()`，读镜像会拿到过期值。
     */
    suspend fun isSmsSourceEnabled(): Boolean =
        repository.getSource(SmsContract.SOURCE_PACKAGE)?.enabled ?: true

    fun start() {
        health.onNotificationAccess(
            NotificationManagerCompat.from(appContext).areNotificationsEnabled(),
        )
        notifier.ensureChannels()
        island.ensureChannels()

        // 首次启动：来源种子数据 + 规则版本落库（一次性，不轮询）
        appScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { sourceSettings.seed() }
                    .onFailure { AppLog.w("seed sources failed", it) }
                runCatching {
                    val rules = ruleManager.rules().rules
                    database.ruleDao().upsert(
                        RuleEntity(
                            ruleId = "assets_rules",
                            source = "assets",
                            version = rules.version,
                            ruleJson = rulesJson,
                            enabled = true,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }.onFailure { AppLog.w("persist rule version failed", it) }
            }
        }

        // 桌面 Widget 刷新：**事件驱动**——只订阅一次数据流，快递数据一变就刷。
        // 不轮询、不常驻服务；桌面没有 Widget 时 TodoWidgetProvider 会直接跳过，不做任何工作。
        appScope.launch {
            runCatching {
                repository.observeAll().collect {
                    TodoWidgetProvider.onDataChanged(appContext)
                }
            }.onFailure { AppLog.w("widget observe failed", it) }
        }
    }

    /**
     * 分享导入兜底（SOP §16）：用户显式操作，直接进入同一条解析链路，
     * 跳过监听门禁但仍要经过解析置信度校验。
     */
    fun importSharedText(text: String) {
        val normalized = text.trim()
        if (normalized.isEmpty()) return
        val lines = normalized.lines()
        val raw = RawNotification(
            sourcePackage = SHARE_SOURCE,
            sourceAppName = null,
            notificationKey = "share|${normalized.hashCode()}|${System.currentTimeMillis()}",
            title = lines.firstOrNull().orEmpty(),
            text = lines.drop(1).joinToString("\n"),
            bigText = null,
            subText = null,
            receivedAt = System.currentTimeMillis(),
            isOngoing = false,
        )
        pipeline.enqueue(raw)
    }

    companion object {
        const val RULES_ASSET = "rules.json"
        const val KEY_SETTINGS = "parcelhub_settings"
        const val KEY_GENERIC_SOURCE = "generic_source_enabled"
        const val KEY_AUTO_QUERY_ENABLED = "auto_query_enabled"
        const val KEY_ONBOARDING_DONE = "onboarding_done"

        /** 分享导入的伪来源包名（注册在 GenericParser 路径上） */
        const val SHARE_SOURCE = "com.parcelhub.share"
    }
}
