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
        scheduleScan(SCAN_DELAY_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
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
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
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
    }
}
