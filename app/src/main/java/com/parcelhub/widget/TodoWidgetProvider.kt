/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.parcelhub.App
import com.parcelhub.R
import com.parcelhub.model.UserStatus
import com.parcelhub.ui.MainActivity
import com.parcelhub.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * 桌面「快递待办」Widget（Android 官方 App Widget 架构）。
 *
 * 设计要点：
 *  - **事件驱动，零轮询**：`updatePeriodMillis=0`，刷新只在
 *    ①数据变化（[onDataChanged]，由 ServiceLocator 里的 Room Flow 触发）、
 *    ②用户点勾选、③系统首次添加 Widget 这三种情况下发生。
 *  - **与快递状态引擎解耦**：Widget 只读 App 的 Room 数据库、只写「用户状态」，
 *    不碰菜鸟、不做自动化（需求 12）。
 *  - **写库成功才更新 UI**：勾选先写库，成功才展示勾选态，失败弹提示（需求 4 / 11 / 12）。
 *  - **固定布局 + 翻页**：集合 item 里子视图的点击在这台设备上不生效
 *    （只有 item 根视图的点击会被处理），会导致「点方框完成」完全无反应；
 *    且 RemoteViews 白名单不放 ScrollView（放进去整个 Widget 加载失败）。
 *    改用 `RemoteViews.addView` 动态填充后，「方框=完成」「条目=详情」两个动作都能用，
 *    超出可视高度的部分靠标题行右上角**翻页**（见 [TodoWidgetCapacity]）。
 *  - **勾选反馈是逐帧动画**：RemoteViews 没有动画 API，改用 ~30fps 的帧序列
 *    （✓ pop 回弹 → 整行按缓动曲线淡出 → 下面条目同步上滑 → 移除），
 *    曲线与帧距见 [TodoWidgetFeedback] / [Easing]。
 *    动画只用 RemoteViews 反射白名单放行的方法：`setTextColor` / `setImageAlpha`
 *    和**不走反射**的 `setViewPadding` —— 试过 `setScaleX/setAlpha/setTranslationY`，
 *    华为桌面直接抛 ActionException、弹「加载窗口小工具时出现问题」、整块组件变空。
 */
class TodoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        requestRefresh(context)
    }

    /** 用户拖拽改大小后重算每屏条数（不然还按旧尺寸放条目，会空一块或被裁） */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        requestRefresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PICK_UP -> markPickedUp(context, intent.getLongExtra(EXTRA_SHIPMENT_ID, -1L))
            ACTION_NEXT_PAGE -> flipPage(context)
            else -> super.onReceive(context, intent)
        }
    }

    /**
     * 点标题行右上角「还有 N 项 ›」翻页（需求：超过一屏要能看到剩下的）。
     *
     * 不能用 ScrollView 滚动 —— RemoteViews 白名单不放它（放进去整个 Widget 加载失败），
     * 所以超出容量靠翻页。**必须自己算下一页再取模**：只把页码 +1 交给 render 夹的话，
     * 末页点一下还是末页（+1 被夹回原处），永远回不到第一页 —— 真机踩过。
     */
    private fun flipPage(context: Context) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        scope.launch {
            try {
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val items = TodoWidgetData.load(appContext, feedback.keys.toSet(), anchors.toMap())
                val rows = rowsForWidget(appContext)
                val pages = TodoWidgetCapacity.pageCount(items.size, rows)
                val current =
                    TodoWidgetCapacity.clampPage(prefs.getInt(KEY_PAGE, 0), items.size, rows)
                val next = if (pages <= 1) 0 else (current + 1) % pages
                prefs.edit().putInt(KEY_PAGE, next).apply()
                render(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 用户在 Widget 上手动完成（需求 10）：
     * 与 App 内详情页「标记已取件」走**同一个** Repository 方法，口径完全一致。
     *
     * 展示顺序（用户反馈「打勾后要看到打勾标志，整条要有消失动画，不要直接消失」，
     * 且「动画不够丝滑」→ 参考 UI 动画库改成 ~30fps + 缓动曲线）：
     *   ① 进入 7 帧：方框换成 ✓ 并 pop 回弹（backOut 曲线，靠改方框内边距做，见 [TodoWidgetFeedback]）；
     *   ② 退出 10 帧：整行按 standard 曲线淡出，**它下面的条目同步上滑到移除后的位置**；
     *   ③ 末帧再过一拍移除：此时布局前移恰好等于预滑位移，收尾不跳一下。
     *
     * 两个真机踩过的坑：
     *  - **必须先置反馈位再写库**：写库成功会让 Room Flow 立刻推一次刷新，
     *    那次刷新若还没看到反馈位，条目就已经被判定「不满足待办」→ 直接消失、
     *    用户根本看不到 ✓（用户反馈的现象就是这个）。写库失败再回滚反馈位并提示，
     *    假成功只存在写库那一瞬（需求 12）。
     *  - **条目必须原地淡出**：写库会把 `updated_at` 顶到最新、勾选项也不再满足 needsPickup，
     *    直接排序/插队都会把它顶到列表最前 → 整页先重排一次（用户看到的就是「乱跳一下」）。
     *    所以**写库前**先记下它当时的位置（`anchors`），渲染时按锚点插回原位；
     *    只有没锚到（兜底走「排最前」）才把页码拉回第 1 页，否则条目在原页、
     *    用户停在别的页就看不到勾号与淡出动画。
     */
    private fun markPickedUp(context: Context, shipmentId: Long) {
        if (shipmentId <= 0L) return
        val appContext = context.applicationContext
        // goAsync：把广播的存活期延长到淡出动画走完，
        // 否则动画期间进程可能被回收，条目会卡在勾选态不消失。
        val pendingResult = goAsync()
        scope.launch {
            try {
                // **写库之前**先记下这条在列表里的位置：写库后 updated_at 变最新、
                // 且它已不满足 needsPickup，排序一定把它顶到最前 → 动画就得先看着
                // 整页重排一次（用户反馈的「直接消失/乱跳」之一）。查不到（-1）才退回兜底。
                val anchor = runCatching {
                    TodoWidgetData.load(appContext).indexOfFirst { it.shipmentId == shipmentId }
                }.getOrDefault(-1)

                feedback[shipmentId] = TodoWidgetFeedback.PHASE_CHECK
                anchors[shipmentId] = anchor

                val ok = runCatching {
                    App.graph.repository.setUserStatus(shipmentId, UserStatus.PICKED_UP)
                }.getOrDefault(false)

                if (!ok) {
                    // 需求 12：失败要如实告诉用户，不能假装成功
                    feedback.remove(shipmentId)
                    anchors.remove(shipmentId)
                    render(appContext)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            R.string.widget_todo_pick_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return@launch
                }

                if (anchor < 0) {
                    // 没锚到位置（兜底走「排最前」）→ 才需要把页码拉回第 1 页，
                    // 否则条目在第 1 页、用户停在别的页就看不到动画。
                    // 锚到原位时**不能动页码**：条目留在用户点勾的那一页就地淡出。
                    appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putInt(KEY_PAGE, 0).apply()
                }

                // 帧调度按「截止时间」对齐，而不是「render 完再等 33ms」：
                // 后者会把 render 自身耗时（查库 + 重建视图树 + Binder）叠进帧距，
                // 实测帧距被拖到 55~75ms（≈15fps，肉眼能看出一格一格跳）。
                // 用 deadline 后帧距 = max(render耗时, STEP_MS)，render ≤33ms 就是稳定 30fps。
                var deadline = android.os.SystemClock.elapsedRealtime()
                var phase = TodoWidgetFeedback.PHASE_CHECK
                AppLog.d("widget pick-up id=$shipmentId frame=$phase")
                render(appContext)

                while (phase < TodoWidgetFeedback.LAST_PHASE) {
                    deadline += TodoWidgetFeedback.frameDelayMs()
                    val wait = deadline - android.os.SystemClock.elapsedRealtime()
                    if (wait > 0) delay(wait)
                    phase += 1
                    feedback[shipmentId] = phase
                    val frame = TodoWidgetFeedback.frameOf(phase)
                    AppLog.d(
                        "widget pick-up id=$shipmentId frame=$phase" +
                            " alpha=${"%.2f".format(frame.alpha)}" +
                            " checkPad=${"%.2f".format(frame.checkPadDp)}" +
                            " shift=${"%.2f".format(frame.shift)}",
                    )
                    render(appContext)
                }

                // 末帧再停一拍，然后移除：下面条目已预滑到位，布局一变正好接上（不跳）
                deadline += TodoWidgetFeedback.frameDelayMs()
                val tail = deadline - android.os.SystemClock.elapsedRealtime()
                if (tail > 0) delay(tail)
                feedback.remove(shipmentId)
                anchors.remove(shipmentId)
                AppLog.d("widget pick-up id=$shipmentId frame=done")
                render(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_PICK_UP = "com.parcelhub.widget.ACTION_PICK_UP"
        const val EXTRA_SHIPMENT_ID = "shipment_id"

        /** 点「还有 N 项」翻页 */
        const val ACTION_NEXT_PAGE = "com.parcelhub.widget.ACTION_NEXT_PAGE"

        /** 页码存放（只影响展示，不碰业务数据；进程被杀也不丢） */
        private const val PREFS_NAME = "widget_todo"
        private const val KEY_PAGE = "page"

        /** 两套 requestCode 基址，避免「完成」与「详情」的 PendingIntent 互相覆盖 */
        private const val REQUEST_PICK_BASE = 0x10000
        private const val REQUEST_DETAIL_BASE = 0x50000
        private const val REQUEST_MORE = 0x7F01

        /** 刚完成的包裹 → 当前反馈帧号（[TodoWidgetFeedback]）。内存态，进程重启即清空，不落库 */
        private val feedback = ConcurrentHashMap<Long, Int>()

        /**
         * 刚完成的包裹 → 它在**写库前**列表里的下标（见 [TodoWidgetItem.from]）。
         * 写库会把 `updated_at` 顶到最新、勾选项也不再满足 needsPickup，
         * 不锚定就会插到最前 → 整页先重排一次再做动画，「原地淡出」就废了。
         */
        private val anchors = ConcurrentHashMap<Long, Int>()

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 数据变化（通知入库 / App 内操作 / 删除）后由 App 侧触发 */
        fun onDataChanged(context: Context) {
            requestRefresh(context)
        }

        fun requestRefresh(context: Context) {
            // 动画期间不接受外部插队的刷新：动画每一帧都会重新查库并重绘，
            // 插进来的那次额外 render 只会让屏幕多 hold 几十毫秒（真机实测 53ms ≈ 掉 1~2 帧）。
            // 不会丢数据 —— 下一帧动画自己 load 的就是最新数据。
            if (feedback.isNotEmpty()) return
            val appContext = context.applicationContext
            scope.launch { render(appContext) }
        }

        /** 读取数据并重绘所有 Widget 实例 */
        private suspend fun render(context: Context) {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, TodoWidgetProvider::class.java))
            if (ids.isEmpty()) return // 桌面上没有 Widget：一点活都不干

            val items = TodoWidgetData.load(context, feedback.keys.toSet(), anchors.toMap())
            val empty = items.isEmpty()
            val title = if (empty) {
                context.getString(R.string.widget_todo_empty)
            } else {
                context.getString(R.string.widget_todo_count, items.size)
            }

            for (appWidgetId in ids) {
                // 一屏放几条由桌面给的高度决定（RemoteViews 没有测量回调，只能按 dp 算）。
                // 取 MAX_HEIGHT：MIN 是「可缩到的最小值」，按它算会退化成每页 1 条（真机踩过）
                val heightDp = heightDpOf(manager, appWidgetId)
                val rows = TodoWidgetCapacity.rowsFor(heightDp)
                val totalPages = TodoWidgetCapacity.pageCount(items.size, rows)
                val page = TodoWidgetCapacity.clampPage(readPage(context), items.size, rows)
                AppLog.d(
                    "widget height=$heightDp rows=$rows" +
                        " page=$page/$totalPages items=${items.size}",
                )
                val start = TodoWidgetCapacity.startOf(page, rows)
                val shown =
                    if (empty) emptyList()
                    else items.subList(start, minOf(start + rows, items.size))
                val remaining = items.size - start - shown.size

                val views = RemoteViews(context.packageName, R.layout.widget_todo)
                views.setTextViewText(R.id.widget_title, title)
                // 标题可点 = 打开 App
                views.setOnClickPendingIntent(R.id.widget_title, openAppPendingIntent(context))
                // 标题行右上角的翻页字（单页时隐藏，不占条目区高度）
                if (totalPages > 1) {
                    val text = if (page >= totalPages - 1) {
                        context.getString(R.string.widget_todo_more_last)
                    } else {
                        context.getString(R.string.widget_todo_more, remaining)
                    }
                    views.setViewVisibility(R.id.more_text, View.VISIBLE)
                    views.setTextViewText(R.id.more_text, text)
                    views.setOnClickPendingIntent(R.id.more_text, nextPagePendingIntent(context))
                } else {
                    views.setViewVisibility(R.id.more_text, View.GONE)
                }
                views.setViewVisibility(R.id.widget_empty, if (empty) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.widget_page, if (empty) View.GONE else View.VISIBLE)
                views.setViewVisibility(R.id.widget_items, if (empty) View.GONE else View.VISIBLE)
                views.removeAllViews(R.id.widget_items)

                if (!empty) {
                    // 收尾不跳一下的关键：动画条目**下面**的条目按同一进度提前上滑
                    // 到「移除后」的位置；末帧移除时布局前移一行 = 预滑位移，两者抵消。
                    val animIndex = shown.indexOfFirst { feedback.containsKey(it.shipmentId) }
                    val progress = if (animIndex >= 0) {
                        TodoWidgetFeedback.frameOf(feedback[shown[animIndex].shipmentId]!!).shift
                    } else {
                        0f
                    }
                    // 行高按**像素**算：方框 24dp + 根视图上下内边距（dimens.xml），
                    // 而不是 ROW_H_DP×density —— 后者为了容量公式取了保守的 25.7dp，会差 0.1px
                    val res = context.resources
                    val rowPx = (TodoWidgetCapacity.ROW_CONTENT_H_DP * res.displayMetrics.density + 0.5f).toInt() +
                        res.getDimensionPixelSize(R.dimen.widget_item_pad_top) +
                        res.getDimensionPixelSize(R.dimen.widget_item_pad_bottom)
                    val shiftPx = progress * rowPx

                    shown.forEachIndexed { index, item ->
                        // 帧号走 feedback 表：任何刷新路径（Room Flow / 翻页 / 点勾）都用同一份状态；
                        // 没被勾过的条目传 null → 恒等帧，一个动画 setter 都不加
                        val phase = feedback[item.shipmentId]
                        // **只有紧邻的下一行**吃这个位移：它的上内边距变负 → 行高变矮 →
                        // 再往下的所有行会跟着整体上移（级联）。每行都设的话位移会累加，
                        // 下面条目会往上冲过头（旧实现是 setTranslationY 逐行平移，改方案时踩过）。
                        val below = animIndex >= 0 && index == animIndex + 1
                        views.addView(
                            R.id.widget_items,
                            buildItem(context, item, phase, if (below) shiftPx else 0f),
                        )
                    }
                }
                manager.updateAppWidget(appWidgetId, views)
            }
            // render 耗时决定动画帧距的下限（帧距 = max(render, STEP_MS)），真机抖动要能看见
            AppLog.d("widget render=${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
        }

        private fun readPage(context: Context): Int =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_PAGE, 0)

        /** 桌面当前给这个实例的高度（dp）；取 MAX_HEIGHT，理由见 TodoWidgetCapacity 注释 */
        private fun heightDpOf(manager: AppWidgetManager, appWidgetId: Int): Int =
            manager.getAppWidgetOptions(appWidgetId)
                ?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                ?: 0

        /** 桌面给的第一个 Widget 实例的高度 → 每屏条数（多实例尺寸通常一致） */
        private fun rowsForWidget(context: Context): Int {
            val manager = AppWidgetManager.getInstance(context)
                ?: return TodoWidgetCapacity.rowsFor(0)
            val id = manager
                .getAppWidgetIds(ComponentName(context, TodoWidgetProvider::class.java))
                .firstOrNull()
                ?: return TodoWidgetCapacity.rowsFor(0)
            return TodoWidgetCapacity.rowsFor(heightDpOf(manager, id))
        }

        /**
         * 单条待办：左侧方框 = 手动完成，整行 = 打开详情。
         *
         * @param phase 反馈动画帧号（null = 普通条目 → 恒等帧，不加任何动画 setter）
         * @param shiftPx 本条需要预先上滑的像素（只给「动画条目下面」的**第一行**传非 0：
         *                它变矮就会把再往下的所有行一起顶上去）
         *
         * 动画 setter 受 RemoteViews 反射白名单约束（目标方法必须带 `@RemotableViewMethod`，
         * 否则桌面 apply() 时抛 ActionException、整块组件变空 —— 真机踩过）：
         *  - `TextView.setTextColor` → 退出段文字淡出
         *  - `ImageView.setImageAlpha` → 退出段方框/包裹图标淡出
         *  - `RemoteViews.setViewPadding` → 专用 action **不走反射**，进入段做 ✓ pop、
         *    退出段给本行设负上内边距做预滑
         * `View.setAlpha / setScaleX / setTranslationY` 三个都试过，全部被白名单拒。
         * 曲线与帧距见 [TodoWidgetFeedback]。
         */
        internal fun buildItem(
            context: Context,
            item: TodoWidgetItem,
            phase: Int? = null,
            shiftPx: Float = 0f,
        ): RemoteViews {
            val frame = TodoWidgetFeedback.frameFor(phase)
            val res = context.resources
            val density = res.displayMetrics.density
            val padTopPx = res.getDimensionPixelSize(R.dimen.widget_item_pad_top)
            val padBottomPx = res.getDimensionPixelSize(R.dimen.widget_item_pad_bottom)
            val textColor = res.getColor(R.color.widget_text, context.theme)
            val textSubColor = res.getColor(R.color.widget_text_sub, context.theme)
            return RemoteViews(context.packageName, R.layout.widget_todo_item).apply {
                setTextViewText(R.id.item_title, item.title)
                setImageViewResource(
                    R.id.item_check,
                    if (item.checked) {
                        R.drawable.ic_widget_check_done
                    } else {
                        R.drawable.ic_widget_check_empty
                    },
                )
                setViewVisibility(R.id.item_icon, if (item.showParcelIcon) View.VISIBLE else View.GONE)

                val tracking = item.trackingNumber
                if (tracking.isNullOrBlank()) {
                    setViewVisibility(R.id.item_sub, View.GONE)
                } else {
                    setViewVisibility(R.id.item_sub, View.VISIBLE)
                    setTextViewText(
                        R.id.item_sub,
                        context.getString(R.string.widget_todo_tracking, tracking),
                    )
                }

                // 动画帧（恒等帧一个 setter 都不加，静态条目零开销）：
                //  - 进入段：方框内边距 4.5dp →(冲到 ~2.4dp)→ 3dp = ✓ pop
                //  - 退出段：文字颜色与图片透明度按 standard 曲线淡出（没有 View.setAlpha 可用）
                //  - 预滑：本行上内边距扣掉位移 → 行变矮、内容顶出自己的边界，
                //    下面所有行跟着上移（item_root 已设 clipChildren=false 才画得出来）
                if (frame.checkPadDp != TodoWidgetFeedback.REST_CHECK_PAD_DP) {
                    val pad = (frame.checkPadDp * density + 0.5f).toInt()
                    setViewPadding(R.id.item_check, pad, pad, pad, pad)
                }
                if (frame.alpha < 1f) {
                    val alpha = (frame.alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
                    setInt(R.id.item_title, "setTextColor", withAlpha(textColor, alpha))
                    if (!tracking.isNullOrBlank()) {
                        setInt(R.id.item_sub, "setTextColor", withAlpha(textSubColor, alpha))
                    }
                    setInt(R.id.item_check, "setImageAlpha", alpha)
                    if (item.showParcelIcon) {
                        setInt(R.id.item_icon, "setImageAlpha", alpha)
                    }
                }
                if (shiftPx != 0f) {
                    setViewPadding(
                        R.id.item_root,
                        0,
                        padTopPx - shiftPx.roundToInt(),
                        0,
                        padBottomPx,
                    )
                }

                // 方框：标记已取件
                setOnClickPendingIntent(
                    R.id.item_check,
                    pickUpPendingIntent(context, item.shipmentId),
                )
                // 整行：详情（子视图的点击优先于父视图，两者不冲突）
                setOnClickPendingIntent(
                    R.id.item_root,
                    openDetailPendingIntent(context, item.shipmentId),
                )
            }
        }

        /** 只换 alpha、保留 RGB —— 淡出用（读的是资源色，深色模式自动跟随） */
        private fun withAlpha(color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or (alpha shl 24)


        /**
         * 翻页 PendingIntent：点标题行右上角「还有 N 项 ›」时广播给自己。
         * （翻页字现在是静态布局里的 `more_text`，不再用 addView 动态插行。）
         */
        private fun nextPagePendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, TodoWidgetProvider::class.java).apply {
                action = ACTION_NEXT_PAGE
            }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_MORE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        internal fun pickUpPendingIntent(context: Context, shipmentId: Long): PendingIntent {
            val intent = Intent(context, TodoWidgetProvider::class.java).apply {
                action = ACTION_PICK_UP
                putExtra(EXTRA_SHIPMENT_ID, shipmentId)
            }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_PICK_BASE + (shipmentId % 0x8000).toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun openDetailPendingIntent(context: Context, shipmentId: Long): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_SHIPMENT_ID, shipmentId)
            }
            return PendingIntent.getActivity(
                context,
                REQUEST_DETAIL_BASE + (shipmentId % 0x8000).toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun openAppPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(
                context,
                REQUEST_MORE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
