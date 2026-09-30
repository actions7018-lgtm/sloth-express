/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.ocr

import android.graphics.Bitmap
import com.parcelhub.ingest.RecognitionMetrics
import com.parcelhub.model.RawNotification
import com.parcelhub.pending.PendingSourceType
import com.parcelhub.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * OCR 截图兜底编排（SOP V2.0 §19/§20/§42-12）：
 * 无障碍读完一页**仍没有单号**且该来源有待补全单（或处于手动识别焦点）时，
 * 按 [OcrGate] 节奏截当前屏 1 帧 → 本地 OCR → 文本进同一条解析链路
 * （channel=SCREEN_OCR，身份闸 / 身份键去重与无障碍同口径）。
 *
 * 隐私口径（§42-16）：截图只在触发瞬间产生一帧、本地识别后即回收，
 * 不落盘、不上传；日志只记包名 + 文本长度。
 */
class OcrController(
    private val metrics: RecognitionMetrics,
    private val hasPendingFor: suspend (String) -> Boolean,
    private val isManualFocus: (String) -> Boolean,
    private val enqueue: (RawNotification) -> Boolean,
    private val isEnabled: () -> Boolean,
    private val scope: CoroutineScope,
    private val gate: OcrGate = OcrGate(),
) {

    /**
     * 一次页面读取完成后的 OCR 判定（在无障碍服务主线程调用，只做纯内存判断）：
     * 条件不齐直接返回；齐了再进协程查待补全，避免主线程 IO。
     */
    fun onRead(packageName: String?, text: String) {
        if (packageName.isNullOrBlank() || text.isBlank()) return
        if (!isEnabled()) return
        // ① 页面文本里已带单号形态 → 无障碍已经赢了，不截图
        if (TRACKING_SHAPE.containsMatchIn(text.uppercase())) return
        // ② 只截订单 / 物流页（别的页面截了也没用，SOP §19）
        if (!ORDER_PAGE_REGEX.containsMatchIn(text)) return
        val now = System.currentTimeMillis()
        // ③ 页面冷却 / 连续失败退避
        if (!gate.allow(now)) return

        scope.launch {
            // ④ 待补全仍需单号，或用户手动指定了这个 App（10 分钟焦点）
            val eligible = try {
                hasPendingFor(packageName) || isManualFocus(packageName)
            } catch (t: Throwable) {
                AppLog.w("ocr pending check failed", t)
                false
            }
            if (!eligible) return@launch

            gate.markAttempt(System.currentTimeMillis())
            metrics.onOcrCapture()
            AppLog.i("ocr attempt pkg=$packageName failStreak=${gate.failureCount}")
            captureAndRecognize(packageName)
        }
    }

    private fun captureAndRecognize(packageName: String) {
        val consume: (Bitmap?) -> Unit = { bitmap ->
            if (bitmap == null) {
                gate.markFailure()
                AppLog.w("ocr capture unavailable pkg=$packageName")
            } else {
                recognize(bitmap, packageName)
            }
        }
        // API 30+：无障碍自带截图（无弹窗、无前台服务）
        if (A11yCaptureBridge.capture(consume)) return
        // API 29：MediaProjection 会话（设置页授权过才存活）
        if (ScreenCaptureService.alive) {
            ScreenCaptureService.requestFrame(consume)
        } else {
            gate.markFailure()
            AppLog.w("ocr projection session dead pkg=$packageName (re-grant in settings)")
        }
    }

    private fun recognize(bitmap: Bitmap, packageName: String) {
        OcrTextRecognizer.recognize(bitmap) { ocrText ->
            bitmap.recycle()
            if (ocrText.isNullOrBlank()) {
                gate.markFailure()
                AppLog.w("ocr empty text pkg=$packageName")
                return@recognize
            }
            // 识别结果带单号形态 → 记成功（冷却回到基础 30s）；否则按失败退避，
            // 防止用户停在无单号列表页时被 30s 一帧反复打扰
            if (TRACKING_SHAPE.containsMatchIn(ocrText.uppercase())) {
                gate.markSuccess()
            } else {
                gate.markFailure()
            }
            val now = System.currentTimeMillis()
            enqueue(
                RawNotification(
                    sourcePackage = packageName,
                    sourceAppName = null,
                    notificationKey = "ocr|$packageName|${now / 30_000L}",
                    title = "",
                    text = ocrText,
                    bigText = null,
                    subText = null,
                    receivedAt = now,
                    isOngoing = false,
                    channel = PendingSourceType.SCREEN_OCR.name,
                ),
            )
            // 只打脱敏元数据（包名 + 文本长度）
            AppLog.i("ocr read pkg=$packageName len=${ocrText.length}")
        }
    }

    private companion object {
        /** 单号形态（与无障碍身份启发同口径）：2 字母 + 9~14 位数字 / 11~15 位数字 */
        val TRACKING_SHAPE = Regex("""[A-Z]{2}\d{9,14}|\d{11,15}""")

        /** 订单 / 物流页文本信号 */
        val ORDER_PAGE_REGEX = Regex("""订单|物流""")
    }
}
