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
import com.parcelhub.health.BackgroundHealth
import com.parcelhub.health.HealthCollector
import com.parcelhub.model.RawNotification
import com.parcelhub.autoquery.AutoQueryTaskManager
import com.parcelhub.autoquery.cainiao.CainiaoAutomationSession
import com.parcelhub.autoquery.cainiao.CainiaoLaunchGate
import com.parcelhub.ingest.HealthState
import com.parcelhub.ingest.IngestPipeline
import com.parcelhub.ingest.RecognitionMetrics
import com.parcelhub.ingest.SourceSettings
import com.parcelhub.island.IslandDedup
import com.parcelhub.island.IslandEventDispatcher
import com.parcelhub.island.IslandManager
import com.parcelhub.island.IslandNotificationManager
import com.parcelhub.island.SharedPreferencesSeenStore
import com.parcelhub.mock.MockDataSeeder
import com.parcelhub.notification.AppNotificationManager
import com.parcelhub.parser.ParserEngine
import com.parcelhub.parser.RuleManager
import com.parcelhub.ocr.OcrController
import com.parcelhub.ocr.ScreenCaptureService
import com.parcelhub.pending.PendingFocus
import com.parcelhub.pending.PendingSourceInfo
import com.parcelhub.pending.PendingSourceResolver
import com.parcelhub.pending.PendingWatch
import com.parcelhub.sms.SmsContract
import com.parcelhub.sms.SmsIngestor
import com.parcelhub.sms.SharedPreferencesSmsSeenStore
import com.parcelhub.util.AppLog
import com.parcelhub.widget.TodoWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    /** 待补全补偿循环的唤醒信号（SOP §23）：新建待补全订单 / 启动时对账用，合并突发 */
    private val pendingWake = Channel<Unit>(Channel.CONFLATED)

    private val settingsPrefs by lazy {
        appContext.getSharedPreferences(KEY_SETTINGS, Context.MODE_PRIVATE)
    }

    fun isOnboardingDone(): Boolean = settingsPrefs.getBoolean(KEY_ONBOARDING_DONE, false)

    fun markOnboardingDone() {
        settingsPrefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
    }

    val database: AppDatabase by lazy { AppDatabase.get(appContext) }

    val repository: ShipmentRepository by lazy {
        ShipmentRepository(database, sourceInfoOf = ::resolvePendingSource)
    }

    /**
     * 待补全来源识别（需求 §三）：规则类型来自 rules.json（ECOMMERCE 判定），
     * 应用名优先取系统 PackageManager 标签（applicationLabel），规则 appName 兜底。
     * 每次只在「新建 / 补全待补全」时调用，不在通知监听回调里跑（SOP §7.1）。
     */
    private fun resolvePendingSource(
        pkg: String,
        channel: String?,
    ): PendingSourceInfo {
        val rule = runCatching { ruleManager.rules().sourceFor(pkg) }.getOrNull()
        val label = runCatching {
            val pm = appContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
        return PendingSourceResolver.resolve(
            sourcePackage = pkg,
            channel = channel,
            ruleType = rule?.sourceType,
            ruleAppName = rule?.appName,
            appLabel = label,
        )
    }

    val health: HealthState by lazy { HealthState() }

    /** 识别运行指标（SOP V2.0 §35）：页面读取 / 取号成功时间戳 + OCR 计数，落 prefs */
    val recognitionMetrics: RecognitionMetrics by lazy { RecognitionMetrics(appContext) }

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

    /**
     * 待补全自动读页开关（设置页「自动识别订单页」），默认开。
     * 关掉后只剩手动通路（详情页「去查看」/ 设置页手动按钮的 10 分钟焦点）；
     * setter 同步 [PendingWatch]（无障碍服务事件回调读同一进程的纯内存状态）。
     */
    var autoReadEnabled: Boolean
        get() = settingsPrefs.getBoolean(KEY_AUTO_READ_ENABLED, true)
        set(value) {
            settingsPrefs.edit().putBoolean(KEY_AUTO_READ_ENABLED, value).apply()
            PendingWatch.setAuto(value)
        }

    /**
     * OCR 截图兜底开关（设置页「OCR 截图兜底」），**默认关**：
     * 开启在 API 29 需经 [com.parcelhub.ocr.OcrConsentActivity] 系统授权后
     * 由其写入 true（API 30+ 无弹窗，设置页直接写入）。
     * 关闭时同步停掉 MediaProjection 会话（若在运行）。
     */
    var ocrFallbackEnabled: Boolean
        get() = settingsPrefs.getBoolean(KEY_OCR_FALLBACK_ENABLED, false)
        set(value) {
            settingsPrefs.edit().putBoolean(KEY_OCR_FALLBACK_ENABLED, value).apply()
            if (!value) {
                runCatching { ScreenCaptureService.stop(appContext) }
            }
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
            metrics = recognitionMetrics,
        )
    }

    /**
     * OCR 截图兜底编排（SOP V2.0 §19）：判定条件在无障碍读页后触发，
     * 结果进同一 pipeline（channel=SCREEN_OCR，身份闸 / 身份键去重同口径）。
     * 待补全查询走 [repository]，手动焦点复用 [PendingFocus]（10 分钟 TTL）。
     */
    val ocrController: OcrController by lazy {
        OcrController(
            metrics = recognitionMetrics,
            hasPendingFor = { pkg -> repository.hasPendingWaitingFor(pkg) },
            isManualFocus = { pkg -> PendingFocus.matches(pkg, System.currentTimeMillis()) },
            enqueue = { raw -> pipeline.enqueue(raw) },
            isEnabled = { ocrFallbackEnabled },
            scope = appScope,
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

        // 待补全订单延迟补偿（SOP §23/§24/§25）：
        // 单个挂起协程睡到最近的检查点（24h/48h/72h），没有 WAITING_TRACKING 就一直等唤醒信号，
        // 不轮询、不建常驻服务；进程被杀后下次启动会重新对账（SOP §39 不丢待补全订单）。
        repository.onPendingCreated = { pendingWake.trySend(Unit) }
        appScope.launch {
            // Debug Mock 数据（MockDataSeeder §10）：仅 Debug 注入，Release 直接返回；
            // 必须排在对账循环**之前**——场景 E（>72h 无单号）首帧对账就要转 EXPIRED，
            // 冒烟脚本用 Settings.Global(parcelhub_mock_seed=0) 抑制，保绝对计数断言不变。
            runCatching { MockDataSeeder.seedIfNeeded(appContext) }
                .onFailure { AppLog.w("mock seed failed", it) }
            while (true) {
                val next = try {
                    repository.reconcilePending(System.currentTimeMillis())
                        .also { BackgroundHealth.recordSuccess(System.currentTimeMillis()) }
                } catch (t: Throwable) {
                    // 健康检测（检测 §7.4）：连续 3 次失败 → ERROR，失败要留证据
                    BackgroundHealth.recordFailure(System.currentTimeMillis())
                    AppLog.w("pending reconcile failed", t)
                    null
                }
                if (next == null) {
                    // 没有待补全订单：不安排任何补偿任务，只等新订单叫醒（SOP §25）
                    pendingWake.receive()
                } else {
                    val wait = (next - System.currentTimeMillis()).coerceAtLeast(MIN_RECONCILE_WAIT_MS)
                    withTimeoutOrNull(wait) { pendingWake.receive() }
                }
            }
        }

        // 自动检测（检测 §十八）：App 启动立即检测一次并缓存；设置页/回前台会再刷新
        appScope.launch {
            runCatching { HealthCollector.refresh(appContext) }
                .onFailure { AppLog.w("health refresh failed", it) }
        }

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

        // 待补全自动监控（需求「待补全改全自动」+ 设置页自动开关）：只订阅数据流，
        // 有待补全单的来源包名 +（开关开时）启用中的来源 App 进 PendingWatch 候选集；
        // 补全 / 过期 / 删除自动移出，来源管理关掉即移出。无障碍服务事件回调同步查集合，
        // 不轮询、不做常驻扫描——零 IO。开关本体走 settingsPrefs（见 autoReadEnabled）。
        PendingWatch.setAuto(autoReadEnabled)
        appScope.launch {
            runCatching {
                repository.observePending().collect { rows ->
                    PendingWatch.update(
                        rows.mapNotNull { it.sourcePackageName }.toSet(),
                    )
                }
            }.onFailure { AppLog.w("pending watch observe failed", it) }
        }
        appScope.launch {
            runCatching {
                repository.observeSources().collect { rows ->
                    PendingWatch.updateEnabled(
                        rows.filter { it.enabled }.map { it.packageName }.toSet(),
                    )
                }
            }.onFailure { AppLog.w("pending watch sources failed", it) }
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

        /** 设置页「自动识别订单页」开关（待补全自动读页），默认 true */
        const val KEY_AUTO_READ_ENABLED = "auto_read_enabled"
        const val KEY_OCR_FALLBACK_ENABLED = "ocr_fallback_enabled"
        const val KEY_ONBOARDING_DONE = "onboarding_done"

        /** 补偿循环最小睡醒间隔，防止时钟异常导致空转（SOP §40 低功耗） */
        const val MIN_RECONCILE_WAIT_MS = 60_000L

        /** 分享导入的伪来源包名（注册在 GenericParser 路径上） */
        const val SHARE_SOURCE = "com.parcelhub.share"
    }
}
