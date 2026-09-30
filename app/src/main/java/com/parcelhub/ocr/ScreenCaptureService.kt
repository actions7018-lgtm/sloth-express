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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.parcelhub.util.AppLog

/**
 * MediaProjection 截屏会话（SOP V2.0 §19/§20；**仅 API 29 设备需要**——
 * API 30+ 走 [A11yCaptureBridge]，无此服务）。
 *
 * 设计要点：
 *  - 用户在设置页开「OCR 截图兜底」时经 [OcrConsentActivity] 弹一次系统授权，
 *    授权即启动本前台服务（type=mediaProjection，系统要求）持有一个会话；
 *  - **按需截帧**：只在 OCR 触发时建 VirtualDisplay 取 1 帧，取完立刻销毁；
 *    空闲期间不产生任何画面数据（不做连续截图，SOP §42-11）；
 *  - 同一时刻只允许一帧在途（[frameInFlight]），超时兜底 3 秒。
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var frameInFlight = false
    private var worker: Handler? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 用户在系统 UI 里撤销投射 / 系统回收：会话失效，清状态并退出
            AppLog.w("ocr projection stopped by system")
            projection = null
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        worker = Handler(Looper.getMainLooper())
    }

    @Deprecated("Deprecated in Java")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        // 必须先挂前台通知再取 projection（Android 10+ 要求）
        startInForeground()

        if (projection == null) {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            @Suppress("DEPRECATION")
            val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            val mgr = getSystemService(MediaProjectionManager::class.java)
            val granted = if (resultCode != 0 && data != null) {
                try {
                    mgr.getMediaProjection(resultCode, data)
                } catch (t: Throwable) {
                    AppLog.w("ocr getMediaProjection failed", t)
                    null
                }
            } else {
                null
            }
            if (granted == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            granted.registerCallback(projectionCallback, mainHandler)
            projection = granted
            AppLog.i("ocr projection session ready")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanupFrame()
        try {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        } catch (t: Throwable) {
            AppLog.w("ocr projection release failed", t)
        }
        projection = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- 按需单帧 ----

    /** 取一帧并回调（主线程回调）；会话不在 / 在途 → 立即回 null */
    fun captureFrame(callback: (Bitmap?) -> Unit) {
        val p = projection
        if (p == null) {
            mainHandler.post { callback(null) }
            return
        }
        synchronized(this) {
            if (frameInFlight) {
                mainHandler.post { callback(null) }
                return
            }
            frameInFlight = true
        }

        val wm = getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi

        var reader: ImageReader? = null
        var vd: VirtualDisplay? = null
        var finished = false

        fun finish(bitmap: Bitmap?) {
            synchronized(this) {
                if (finished) return
                finished = true
                frameInFlight = false
            }
            try {
                vd?.release()
            } catch (_: Throwable) {
            }
            try {
                reader?.close()
            } catch (_: Throwable) {
            }
            if (virtualDisplay === vd) virtualDisplay = null
            if (imageReader === reader) imageReader = null
            mainHandler.post { callback(bitmap) }
        }

        try {
            val createdReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = createdReader
            imageReader = createdReader
            createdReader.setOnImageAvailableListener({ r ->
                val image = try {
                    r.acquireLatestImage()
                } catch (t: Throwable) {
                    AppLog.w("ocr acquire frame failed", t)
                    null
                } ?: return@setOnImageAvailableListener
                val bitmap = try {
                    imageToBitmap(image)
                } catch (t: Throwable) {
                    AppLog.w("ocr frame convert failed", t)
                    null
                } finally {
                    image.close()
                }
                if (bitmap != null) {
                    finish(bitmap)
                } else {
                    finish(null)
                }
            }, worker)
            vd = p.createVirtualDisplay(
                VD_NAME,
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                createdReader.surface,
                null,
                worker,
            ).also { virtualDisplay = it }
            if (vd == null) {
                finish(null)
                return
            }
            // 超时兜底：画面迟迟不来按失败处理（退避由 OcrGate 负责）
            worker?.postDelayed({ finish(null) }, FRAME_TIMEOUT_MS)
        } catch (t: Throwable) {
            AppLog.w("ocr capture frame failed", t)
            finish(null)
        }
    }

    private fun cleanupFrame() {
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        try {
            imageReader?.close()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        imageReader = null
        synchronized(this) { frameInFlight = false }
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        val rowPadding = rowStride - pixelStride * width
        return if (rowPadding == 0) {
            buffer.rewind()
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(buffer)
            }
        } else {
            val paddedWidth = width + rowPadding / pixelStride
            buffer.rewind()
            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(buffer)
            }
            Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
        }
    }

    private fun startInForeground() {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("截图识别")
            .setContentText("OCR 兜底授权会话运行中，仅在读不到单号时按需截取一帧")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "截图识别授权",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "OCR 截图兜底的授权会话（按需截帧，不连续录制）" },
            )
        }
    }

    companion object {
        private const val EXTRA_RESULT_CODE = "ph_ocr_result_code"
        private const val EXTRA_RESULT_DATA = "ph_ocr_result_data"
        private const val CHANNEL_ID = "ocr_capture"
        private const val NOTIF_ID = 8807
        private const val VD_NAME = "ph-ocr-frame"
        private const val FRAME_TIMEOUT_MS = 3_000L

        @Volatile
        private var instance: ScreenCaptureService? = null

        /** 会话是否存活（设置页显示“需重新授权”用） */
        val alive: Boolean get() = instance?.projection != null

        /** 由授权结果启动前台会话（同一进程可反复调用，已有会话则忽略） */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        /** 关闭会话（设置开关关闭时调用；未在运行时空操作） */
        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }

        /** 按需截一帧；无会话立即回 null（调用方据此走退避 / 提示重新授权） */
        fun requestFrame(callback: (Bitmap?) -> Unit) {
            val svc = instance
            if (svc == null) {
                callback(null)
                return
            }
            svc.captureFrame(callback)
        }
    }
}
