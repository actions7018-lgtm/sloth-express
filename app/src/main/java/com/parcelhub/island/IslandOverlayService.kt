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

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.parcelhub.App
import com.parcelhub.ui.MainActivity
import com.parcelhub.util.AppLog

/**
 * 灵动岛 **Overlay 模式**（SOP §11.2 / §12 `IslandOverlayService`）。
 *
 * 职责（SOP §12）：创建悬浮窗、维护显示生命周期、销毁悬浮窗。
 * 展示内容与状态流转由 [IslandManager] + [IslandStateMachine] 决定，本服务只执行。
 *
 * 生命周期策略：
 *  - 每次事件由 [IslandManager] 启动（O+ 走 `startForegroundService`），胶囊消失后
 *    `stopForeground + stopSelf` —— **不常驻、不轮询**（SOP §14）；
 *  - O+ 启动后必须在 5 秒内 `startForeground`，否则系统会杀进程：
 *    因此先 `startForeground`（低优先级、静默通知），胶囊消失时一并移除；
 *  - 任何一步失败（无悬浮窗权限 / addView 抛异常 / 前台服务不允许启动）
 *    都只记日志并关闭自己，**不崩溃**，由 [IslandManager] 回退到通知模式（SOP §19）。
 */
class IslandOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var notifier: IslandNotificationManager

    private var islandView: DynamicIslandView? = null
    private var shownContent: IslandContent? = null
    private var foregroundStarted = false

    private val handler = Handler(Looper.getMainLooper())

    private val finishExit = Runnable {
        detachView()
        stopEverything()
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        notifier = IslandNotificationManager(applicationContext)
        notifier.ensureOverlayServiceChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                val content = contentFrom(intent) ?: return stopSelfQuietly()
                val state = IslandState.valueOf(
                    intent.getStringExtra(EXTRA_STATE) ?: IslandState.EXPANDED.name,
                )
                show(content, state)
            }
            ACTION_HIDE -> hideWithAnimation()
            else -> AppLog.d("island overlay unknown action=${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        detachView()
        super.onDestroy()
    }

    // ---------- 展示 ----------

    private fun show(content: IslandContent, state: IslandState) {
        if (!enterForeground(content)) {
            stopEverything()
            return
        }
        if (!attachView()) {
            AppLog.w("island overlay unavailable, close self")
            stopEverything()
            return
        }
        val view = islandView ?: return
        val first = shownContent == null
        shownContent = content
        // 内容变了才播动画（收起态→展开态、胶囊→内容）；重复渲染同一内容不重复弹跳
        view.render(content, animate = true)
        AppLog.d("island overlay render state=$state first=$first")
    }

    /**
     * 前台服务：O+ 被 `startForegroundService` 启动后必须调用。
     * 失败时立刻停止自己，避免系统 5 秒后判定 `ForegroundServiceDidNotStartInTime`。
     */
    private fun enterForeground(content: IslandContent): Boolean {
        if (foregroundStarted) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return try {
            val notification = notifier.build(
                content = content,
                channelId = "island_overlay",
                priority = NotificationCompat.PRIORITY_LOW,
            )
            startForeground(FGS_ID, notification)
            foregroundStarted = true
            true
        } catch (t: Throwable) {
            AppLog.w("island startForeground failed", t)
            false
        }
    }

    private fun attachView(): Boolean {
        if (islandView != null) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AppLog.w("island overlay permission revoked")
            return false
        }
        val view = DynamicIslandView(this).apply {
            onTap = { id -> openDetail(id) }
        }
        return try {
            windowManager.addView(view, windowParams())
            islandView = view
            true
        } catch (t: Throwable) {
            AppLog.w("island addView failed", t)
            false
        }
    }

    private fun detachView() {
        islandView?.let { view ->
            view.cancelAnimation()
            runCatching { windowManager.removeViewImmediate(view) }
                .onFailure { AppLog.w("island removeView failed", it) }
        }
        islandView = null
        shownContent = null
    }

    /** 胶囊消失：先播收起动画（SOP §18 200~300ms），再移除窗口并结束服务 */
    private fun hideWithAnimation() {
        val view = islandView
        if (view == null) {
            stopEverything()
            return
        }
        handler.removeCallbacks(finishExit)
        handler.postDelayed(finishExit, EXIT_TOTAL_MS)
        view.animateOut { finishExit.run() }
    }

    private fun stopEverything() {
        handler.removeCallbacksAndMessages(null)
        if (foregroundStarted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { stopForeground(true) }
                .onFailure { AppLog.w("island stopForeground failed", it) }
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun stopSelfQuietly(): Int {
        stopEverything()
        return START_NOT_STICKY
    }

    /** 点击灵动岛 → 打开对应快递详情（SOP §16.1），随后收起 */
    private fun openDetail(shipmentId: Long) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SHIPMENT_ID, shipmentId)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            AppLog.w("island open detail failed", t)
        }
        // 详情已打开：让状态机立刻收工，否则下个 tick 会把胶囊重新拉起来
        runCatching { App.graph.island.onOverlayTapped() }
            .onFailure { AppLog.w("island tap dismiss failed", it) }
        hideWithAnimation()
    }

    private fun windowParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // 贴状态栏下方居中（OPPO 流体云胶囊位置口径：状态栏正下方 4dp）
            y = statusBarHeight() + dp(4)
        }
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun contentFrom(intent: Intent): IslandContent? {
        val shipmentId = intent.getLongExtra(EXTRA_SHIPMENT_ID, 0L)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return null
        val tone = IslandTone.valueOf(
            intent.getStringExtra(EXTRA_TONE) ?: IslandTone.LOGISTICS.name,
        )
        return IslandContent(
            shipmentId = shipmentId,
            title = title,
            line1 = intent.getStringExtra(EXTRA_LINE1),
            line2 = intent.getStringExtra(EXTRA_LINE2),
            tone = tone,
        )
    }

    companion object {
        const val ACTION_SHOW = "com.parcelhub.island.SHOW"
        const val ACTION_HIDE = "com.parcelhub.island.HIDE"

        const val EXTRA_SHIPMENT_ID = "shipment_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LINE1 = "line1"
        const val EXTRA_LINE2 = "line2"
        const val EXTRA_TONE = "tone"
        const val EXTRA_STATE = "state"

        /** 前台通知 id：与灵动岛通知错开，避免互相顶掉 */
        private const val FGS_ID = 0x151A18

        /** 等待收起动画跑完再移除窗口（SOP §18 收起 200~300ms） */
        private const val EXIT_TOTAL_MS = 260L
    }
}
