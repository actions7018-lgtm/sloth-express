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

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.parcelhub.util.AppLog
import java.util.concurrent.Executors

/**
 * 无障碍单帧截图桥（API 30+）：
 * [AccessibilityService.takeScreenshot] 复用已有无障碍授权，**不需要**
 * MediaProjection 弹窗——OPPO 等 API 30+ 设备走这条最轻路径；
 * API 29（本机 HLK-AL00）没有该接口，走 [ScreenCaptureService]。
 *
 * 服务实例由 [CainiaoAccessibilityService] 连接时挂上、断开时摘下。
 */
object A11yCaptureBridge {

    @Volatile
    private var service: AccessibilityService? = null

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun attach(instance: AccessibilityService) {
        service = instance
    }

    fun detach(instance: AccessibilityService) {
        if (service === instance) service = null
    }

    /**
     * 截取当前屏幕一帧；不可用（API<30 / 服务未连）返回 false，
     * 由调用方回退 MediaProjection 路径。结果始终回调一次（位图或 null）。
     */
    fun capture(onDone: (Bitmap?) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val s = service ?: return false
        return try {
            s.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val bitmap = try {
                            Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (t: Throwable) {
                            AppLog.w("ocr bitmap convert failed", t)
                            null
                        } finally {
                            result.hardwareBuffer.close()
                        }
                        mainHandler.post { onDone(bitmap) }
                    }

                    override fun onFailure(errorCode: Int) {
                        AppLog.w("ocr a11y screenshot failed code=$errorCode")
                        mainHandler.post { onDone(null) }
                    }
                },
            )
            true
        } catch (t: Throwable) {
            AppLog.w("ocr a11y screenshot threw", t)
            false
        }
    }
}
