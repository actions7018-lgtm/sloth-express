/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.autoquery.cainiao

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.parcelhub.App
import com.parcelhub.autoquery.QueryTaskStatus
import com.parcelhub.model.RawNotification
import com.parcelhub.ocr.A11yCaptureBridge
import com.parcelhub.pending.PendingFocus
import com.parcelhub.pending.PendingSourceType
import com.parcelhub.pending.PendingWatch
import android.os.SystemClock

/**
 * 菜鸟自动填单号无障碍服务（参考 SOP §10 流程 / §16 页面匹配 / §17 输入框定位 /
 * §18 焦点与写入 / §19 第一版边界 / §25 状态机 / §27 防死循环）。
 *
 * 职责边界（§2.2 / §15 / §19）：
 *  - **只**在 [CainiaoAutomationSession] 处于活动态时才读取菜鸟页面；
 *  - 只识别目标页、定位输入框、填入绑定单号、回读验证；
 *  - **不**点“查询”，不读物流结果，不遍历与单号无关的内容；
 *  - 不 OCR、不用屏幕坐标，一切基于无障碍节点与 resource-id。
 *
 * 事件驱动（§39.4）：回调里只做一次轻量调度，实际扫描在主线程合并执行；
 * 页面迟迟未到时做**有界**重试（受 [CainiaoAutomationSession.PAGE_TIMEOUT_MS] 约束），
 * 超时即失败，不保活、不常驻轮询。
 */
class CainiaoAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanPosted = false

    /** 最近一次前台窗口类名，用作 SOP §27 的页面指纹 */
    private var lastWindowClass: String = ""

    private val session: CainiaoAutomationSession
        get() = App.graph.cainiaoAutomation

    private val profile: CainiaoAutomationProfile
        get() = CainiaoAutomationProfile.DEFAULT

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 自证关键开关是否生效（FLAG_REPORT_VIEW_IDS 缺失 = 所有 resource-id 匹配失效，
        // 见 cainiao_accessibility.xml 里的真机踩坑注释）。只打 flags，不含任何业务数据。
        val serviceFlags = try {
            serviceInfo?.flags ?: 0
        } catch (t: Throwable) {
            0
        }
        AutoQueryLog.cainiao(
            "accessibility connected reportViewIds=" +
                ((serviceFlags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS) != 0) +
                " flags=0x${Integer.toHexString(serviceFlags)}",
        )
        session.onServiceConnected(System.currentTimeMillis())
        // OCR 截图桥：API 30+ 的单帧截图能力挂在本服务实例上
        A11yCaptureBridge.attach(this)
        scheduleScan(SCAN_DELAY_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 需求 §八 + 「待补全改全自动」+ 设置页自动开关：三重命中才调度读取——
        //  ① 手动焦点：详情页「去对应 App 查看」/ 设置页手动按钮（10 分钟 TTL，不受开关约束）；
        //  ② 有待补全单的来源包名（PendingWatch，补全即移出）；
        //  ③ 自动开关开 + 启用中的来源 App（打开即读，读到单号建单/补全）。
        // 窗口切换 **与** 内容变化都监听：开屏页常无文字，只听窗口切换会漏掉
        // “过场→首页内容出现”的时机（OPPO 真机踩坑：静默 return 吃掉冷却后再无事件）。
        // 事件回调不做重活：纯内存集合查询 + 合并调度一次读取。
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                // 换页：无身份快重试计数归零（新页面重新享有立即重读额度）
                identitylessStreak = 0
                // 换页：详情补扫额度归零（每个页面最多自动补扫一次）
                rescanUsedForWindow = false
            }
            val pkg = event.packageName?.toString()
            if (PendingFocus.matches(pkg, System.currentTimeMillis()) ||
                PendingWatch.shouldAutoRead(pkg)
            ) {
                schedulePendingRead(pkg!!)
            }
        }

        // 事件回调不做重活（SOP §7.1）：只判断包名与会话状态，然后合并调度一次
        if (!CainiaoPackages.isCainiao(event.packageName?.toString())) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.className?.toString()?.takeIf { it.isNotBlank() }?.let { lastWindowClass = it }
        }
        if (!session.isAutomating) return
        scheduleScan(SCAN_DELAY_MS)
    }

    override fun onInterrupt() {
        mainHandler.removeCallbacksAndMessages(null)
        scanPosted = false
        pendingScanPosted = false
    }

    override fun onDestroy() {
        A11yCaptureBridge.detach(this)
        mainHandler.removeCallbacksAndMessages(null)
        scanPosted = false
        pendingScanPosted = false
        super.onDestroy()
    }

    /** 合并调度：同一时刻最多挂着一次扫描，避免事件风暴（SOP §7.1） */
    private fun scheduleScan(delayMs: Long) {
        if (scanPosted) return
        scanPosted = true
        mainHandler.postDelayed({
            scanPosted = false
            scan()
        }, delayMs)
    }

    // ---- 需求 §八：待补全补全焦点（去对应 App 查看后的订单/物流页文本读取） ----

    /** 上次补全读取时间：防事件风暴，最小间隔见 [PENDING_SCAN_MIN_INTERVAL_MS] */
    private var lastPendingScanAt = 0L

    /** 补全读取是否已排队（与菜鸟会话的 [scanPosted] 分开，互不影响） */
    private var pendingScanPosted = false

    /** 同一页面连续「有文本但无单号/订单号」读取计数（渐进加载快重试用，换页归零） */
    private var identitylessStreak = 0

    /** 合并调度补全读取：焦点包名 + 冷却时间双闸门，窗口切换风暴只读一次 */
    private fun schedulePendingRead(packageName: String) {
        val now = System.currentTimeMillis()
        if (now - lastPendingScanAt < PENDING_SCAN_MIN_INTERVAL_MS) return
        if (pendingScanPosted) return
        lastPendingScanAt = now
        pendingScanPosted = true
        mainHandler.postDelayed({
            pendingScanPosted = false
            readPendingPage(packageName)
        }, PENDING_SCAN_DELAY_MS)
    }

    /**
     * 读取来源 App 当前页面文本（需求 §八）：
     * 有限遍历（节点数 / 深度 / 文本长度都有上限），命中轻量关键词门禁后
     * 作为 `channel=ACCESSIBILITY` 的原始事件进同一条解析链路——
     * 解析出的单号由 [com.parcelhub.pending.PendingMatcher] 落回对应 ShipmentPending
     * （TRACKING_FOUND）→ ParcelMergeEngine → ParcelRepository，不建第二套链路。
     *
     * 读取不到单号时的「截图 + OCR」兜底（SOP V2.0 §19，0.1.8 接入）：
     * API 30+ 用无障碍自带 takeScreenshot；API 29 走 MediaProjection 授权会话
     * （[com.parcelhub.ocr.OcrConsentActivity]）；识别结果以
     * `channel=SCREEN_OCR` 进同一条解析链路，触发条件与冷却见
     * [com.parcelhub.ocr.OcrController] / [com.parcelhub.ocr.OcrGate]。
     */
    private fun readPendingPage(packageName: String) {
        // 读取前复核三重闸门（延迟 300ms 后焦点可能已过期 / 单据可能已补全 / 开关可能已关）：
        // 手动焦点命中，或自动判定通过（待补全单来源 / 自动开关 + 启用来源）才读。
        if (!PendingFocus.matches(packageName, System.currentTimeMillis()) &&
            !PendingWatch.shouldAutoRead(packageName)
        ) {
            return
        }
        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            AutoQueryLog.warn("pending read root failed", t)
            null
        } ?: run {
            // 取根失败不消耗冷却，下个事件可立即重试
            lastPendingScanAt = 0
            return
        }

        val readStart = SystemClock.elapsedRealtime()
        val text = collectText(root)
        if (text.isBlank()) {
            // 开屏 / 过场页常无文字：留脱敏日志（不落原文）+ 归还冷却，
            // 内容出现后的下一个事件就能重读（否则 5s 冷却吃掉时机后永远沉默）。
            lastPendingScanAt = 0
            AutoQueryLog.cainiao("pending a11y read pkg=$packageName blank")
            return
        }
        // SOP V2.0 §35：运行检测指标——最近一次页面读取（有文本才算读到页）
        App.graph.recognitionMetrics.onPageRead(packageName)
        val pipeline = App.graph.pipeline
        val gatePass = pipeline.gate(packageName, text)
        // 只打脱敏元数据（包名 + 文本长度 + 门禁结论 + 耗时），不落页面原文（SOP §42 隐私口径）
        AutoQueryLog.cainiao(
            "pending a11y read pkg=$packageName len=${text.length} gate=$gatePass " +
                "ms=${SystemClock.elapsedRealtime() - readStart}",
        )
        if (!gatePass) return

        // 无障碍已尽力但页面没有单号形态 → OCR 兜底判定（SOP V2.0 §19：
        // 内部再查订单页 / 待补全 / 冷却，条件不齐零副作用）
        App.graph.ocrController.onRead(packageName, text)

        // 有文本但还没有单号 / 订单号（页面渐进加载中）：不进队——仓储同判据会丢弃
        // 这种「无身份」文本（防订单列表页垃圾行）。但**归还冷却**，让内容出现后的
        // 下一个事件立刻重读：否则 5s 冷却 + 用户停留短就永远错过
        // （真机反馈：进单页识别慢、看 3 个只识别出 1 个）。
        // 单页快重试有上限（防内容变化事件风暴），超限回到常规 5s 节奏；
        // 换页（窗口切换）时计数归零。只影响节奏，入库判定在解析层。
        if (!hasPageIdentity(text)) {
            identitylessStreak++
            if (identitylessStreak <= MAX_IDENTITYLESS_FAST_RETRIES) {
                lastPendingScanAt = 0
                AutoQueryLog.cainiao(
                    "pending a11y read pkg=$packageName len=${text.length} " +
                        "identityless retry=$identitylessStreak",
                )
            } else {
                AutoQueryLog.cainiao(
                    "pending a11y read pkg=$packageName len=${text.length} identityless",
                )
            }
            return
        }
        identitylessStreak = 0

        val now = System.currentTimeMillis()
        pipeline.enqueue(
            RawNotification(
                sourcePackage = packageName,
                sourceAppName = null,
                // 30s 桶内同一页面只进队一次（等价于通知去重键，防重复事件）
                notificationKey = "a11y|$packageName|${now / 30_000L}",
                title = "",
                text = text,
                bigText = null,
                subText = null,
                receivedAt = now,
                isOngoing = false,
                channel = PendingSourceType.ACCESSIBILITY.name,
            ),
        )
        // 只打脱敏元数据（包名 + 文本长度），不落页面原文（SOP §42 隐私口径）
        AutoQueryLog.cainiao("pending a11y read pkg=$packageName len=${text.length}")

        // SOP V2.0 §12/§18 详情稳定后补扫：有订单号但还没有单号形态
        // （单号晚于订单号渲染）→ 本页自动补扫一次，不只靠内容变化事件。
        if (!TRACKING_SHAPE_REGEX.containsMatchIn(text.uppercase())) {
            scheduleDetailRescan(packageName)
        }
    }

    /** 详情补扫是否在途 / 本页是否已用过额度（换页归零） */
    private var rescanPosted = false
    private var rescanUsedForWindow = false

    /** §18 DETAIL_RESCAN：身份页读完延一拍再读一次，抓渐进加载后出的单号 */
    private fun scheduleDetailRescan(packageName: String) {
        if (rescanPosted || rescanUsedForWindow) return
        rescanPosted = true
        mainHandler.postDelayed({
            rescanPosted = false
            rescanUsedForWindow = true
            AutoQueryLog.cainiao("pending a11y detail rescan pkg=$packageName")
            readPendingPage(packageName)
        }, DETAIL_RESCAN_DELAY_MS)
    }

    /**
     * 页面身份启发（**只影响重试节奏**，入库判定在解析层
     * [com.parcelhub.matcher.ShipmentMatcher.isIdentitylessA11yEvent]）：
     * 有「订单号/订单编号 + 值」或单号形态（2 字母 + 9~14 位数字 / 11~15 位连续数字）
     * 即认为该页可能可入库。列表页无这类字段 → 走快重试节奏判定。
     */
    private fun hasPageIdentity(text: String): Boolean = PAGE_IDENTITY_REGEX.containsMatchIn(text)

    /** 有限文本采集：节点数 / 深度 / 字符数三重上限，绝不整棵树深挖 */
    private fun collectText(root: AccessibilityNodeInfo): String {
        val out = StringBuilder(MAX_PENDING_TEXT)
        var nodes = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (nodes >= PENDING_MAX_NODES || depth > PENDING_MAX_DEPTH || out.length >= MAX_PENDING_TEXT) {
                return
            }
            nodes++
            for (value in listOf(node.text, node.contentDescription)) {
                val s = value?.toString()?.trim().orEmpty()
                if (s.isNotEmpty()) {
                    out.append(s).append('\n')
                    if (out.length >= MAX_PENDING_TEXT) return
                }
            }
            val childCount = node.childCount
            for (i in 0 until childCount) {
                if (nodes >= PENDING_MAX_NODES || out.length >= MAX_PENDING_TEXT) return
                val child = try {
                    node.getChild(i)
                } catch (t: Throwable) {
                    null
                } ?: continue
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return out.toString()
    }

    private fun scan() {
        val active = session.isAutomating
        if (!active) return

        val now = System.currentTimeMillis()
        if (session.onTimeout(now)) {
            AutoQueryLog.cainiao("timeout, stop")
            return
        }

        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            AutoQueryLog.warn("read root failed", t)
            null
        }
        if (root == null) {
            // 之前这里是静默重扫：真机排障时无法区分“窗口拿不到”和“匹配不上”
            AutoQueryLog.cainiao("scan root unavailable")
            scheduleScan(IDLE_RETRY_MS)
            return
        }

        val scanned = collect(root)
        val nodes = scanned.map { it.data }
        val input = CainiaoNodeMatcher.pickInput(nodes, profile)
        if (input != null) {
            fill(scanned.first { it.data == input })
            return
        }

        // 还没到查询页：尝试点首页搜索入口进入（基于节点，不用坐标）
        val entry = CainiaoNodeMatcher.pickSearchEntry(nodes, profile)
        if (entry != null) {
            // SOP §27：同任务 + 同页面 + 同动作短时间重复 → 禁止再点，等超时收口
            if (!session.noteAction(ACTION_CLICK_SEARCH_ENTRY, lastWindowClass, now)) {
                AutoQueryLog.cainiao("search entry click blocked (loop guard)")
                return
            }
            val ok = scanned.first { it.data == entry }.node
                .performAction(AccessibilityNodeInfo.ACTION_CLICK)
            AutoQueryLog.cainiao("search entry click=$ok")
            scheduleScan(IDLE_RETRY_MS)
            return
        }

        // 目标页未出现：有界等待，超时由上方 onTimeout 收口。
        // 诊断（SOP §19.2：只打脱敏元数据——包名/类名/节点数，不含单号与页面文本）
        AutoQueryLog.cainiao(
            "scan miss window=${root.packageName}/${root.className} nodes=${scanned.size}",
        )
        scheduleScan(IDLE_RETRY_MS)
    }

    /** SOP §17 → §18：聚焦并写入绑定单号 */
    private fun fill(scanned: Scanned) {
        val tracking = session.trackingNumber
        if (tracking.isEmpty()) return
        if (!session.onInputFound()) return
        if (!session.onFillAttempt(System.currentTimeMillis())) {
            AutoQueryLog.cainiao("fill aborted: ${session.lastFailure}")
            return
        }

        val node = scanned.node
        val focused = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                tracking,
            )
        }
        val written = try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (t: Throwable) {
            AutoQueryLog.warn("set text failed", t)
            false
        }
        AutoQueryLog.cainiao("set text requested focus=$focused write=$written")

        if (!written) {
            if (!session.onFillFailed("set_text_false")) {
                AutoQueryLog.cainiao("failed: ${session.lastFailure}")
            } else {
                scheduleScan(IDLE_RETRY_MS)
            }
            return
        }
        // 写入成功后延一拍回读验证（SOP §18.4）
        mainHandler.postDelayed({ verify() }, VERIFY_DELAY_MS)
    }

    /** SOP §18.4 验证填充结果：回读必须与绑定单号完全一致 */
    private fun verify() {
        if (!session.isAutomating) return
        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        } ?: run { scheduleScan(IDLE_RETRY_MS); return }

        val actual = findById(root, profile.inputViewId)
            ?.text
            ?.toString()
        if (session.onVerified(actual)) {
            AutoQueryLog.cainiao("text verified ${AutoQueryLog.mask(session.trackingNumber)}")
            session.finish(QueryTaskStatus.COMPLETED)
            return
        }
        if (!session.onFillFailed("verify_mismatch")) {
            AutoQueryLog.cainiao("failed: ${session.lastFailure}")
        } else {
            scheduleScan(RETRY_DELAY_MS)
        }
    }

    private fun findById(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? =
        try {
            root.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
        } catch (t: Throwable) {
            null
        }

    /**
     * 有限遍历采集（SOP §7.1 轻量 / §27 不做无界工作）。
     * 节点数与深度都有上限，超出即停，绝不整棵树深挖。
     */
    private fun collect(root: AccessibilityNodeInfo): List<Scanned> {
        val out = ArrayList<Scanned>(64)
        fun walk(node: AccessibilityNodeInfo, depth: Int, parents: List<String>) {
            if (out.size >= MAX_NODES || depth > MAX_DEPTH) return
            val data = toData(node, parents)
            out.add(Scanned(data, node))
            val childCount = node.childCount
            if (childCount <= 0) return
            val nextParents = parents + data.viewId
            for (i in 0 until childCount) {
                val child = try {
                    node.getChild(i)
                } catch (t: Throwable) {
                    null
                } ?: continue
                walk(child, depth + 1, nextParents)
            }
        }
        walk(root, 0, emptyList())
        return out
    }

    private fun toData(node: AccessibilityNodeInfo, parents: List<String>): CainiaoNode {
        val density = resources.displayMetrics.density
        val rect = android.graphics.Rect()
        val widthPx = try {
            node.getBoundsInScreen(rect)
            if (rect.isEmpty) 0 else rect.width()
        } catch (t: Throwable) {
            0
        }
        return CainiaoNode(
            viewId = node.viewIdResourceName.orEmpty(),
            className = node.className?.toString().orEmpty(),
            text = node.text?.toString().orEmpty(),
            contentDesc = node.contentDescription?.toString().orEmpty(),
            editable = node.isEditable,
            clickable = node.isClickable,
            enabled = node.isEnabled,
            visible = node.isVisibleToUser,
            focusable = node.isFocusable,
            focused = node.isFocused,
            password = node.isPassword,
            widthDp = if (density > 0f) (widthPx / density).toInt() else 0,
            parentIds = parents,
        )
    }

    private data class Scanned(val data: CainiaoNode, val node: AccessibilityNodeInfo)

    private companion object {
        const val SCAN_DELAY_MS = 120L
        const val VERIFY_DELAY_MS = 250L
        const val IDLE_RETRY_MS = 1_200L
        const val RETRY_DELAY_MS = 400L

        /** SOP §27 动作名：点击首页搜索入口 */
        const val ACTION_CLICK_SEARCH_ENTRY = "click_search_entry"

        /** 单次扫描的硬上限（SOP §27：有限重试、不无界遍历） */
        const val MAX_NODES = 400
        const val MAX_NODES_COARSE = 128
        const val MAX_DEPTH = 30

        // ---- 需求 §八：补全焦点读取（与菜鸟会话扫描分开的另一组限流上限） ----

        /** 两次补全读取的最小间隔：订单页切换风暴只读一次 */
        const val PENDING_SCAN_MIN_INTERVAL_MS = 5_000L

        /** 窗口切换后延一拍再读，等页面渲染稳定 */
        const val PENDING_SCAN_DELAY_MS = 300L

        /** 补全读取单次节点上限（600：真实订单页层级深，150 读不到底部单号段） */
        const val PENDING_MAX_NODES = 600

        /** 补全读取单次文本上限（6000 字：整段物流信息要能进解析） */
        const val MAX_PENDING_TEXT = 6_000

        /** 补全读取遍历深度上限（订单页嵌套深于菜鸟页，单独放宽不改会话扫描语义） */
        const val PENDING_MAX_DEPTH = 50

        /**
         * 单页「无身份」快重试上限：渐进加载页面（进页 300ms 先出状态行、
         * 后出单号）最多立即重读这么多次，之后回到 5s 冷却节奏，防事件风暴。
         */
        const val MAX_IDENTITYLESS_FAST_RETRIES = 3

        /** 页面身份启发正则：订单号字段值 / 快递单号形态（字母前缀或 11~15 位数字） */
        private val PAGE_IDENTITY_REGEX = Regex(
            """订单(?:号|编号)\s*[：:=为是]?\s*[0-9A-Za-z]{6,}|[A-Z]{2}\d{9,14}|\d{11,15}""",
        )

        /** 单号形态（与身份启发的单号分支同口径）：判断“这页还缺单号吗” */
        private val TRACKING_SHAPE_REGEX = Regex("""[A-Z]{2}\d{9,14}|\d{11,15}""")

        /** §18 详情补扫延时：等单号行渲染完（渐进加载第二拍） */
        const val DETAIL_RESCAN_DELAY_MS = 800L
    }
}
