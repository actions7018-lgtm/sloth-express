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

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.parcelhub.App
import com.parcelhub.util.AppLog

/**
 * OCR 截图授权（SOP V2.0 §19，仅 API 29 设备走到这里）：
 * 设置页开「OCR 截图兜底」时弹一次系统录屏授权，同意即启动
 * [ScreenCaptureService] 会话；之后 OCR 只在触发条件满足时按需截 1 帧。
 * API 30+ 无障碍自带截图接口，无需本页。
 */
class OcrConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                ScreenCaptureService.start(this, resultCode, data)
                App.graph.ocrFallbackEnabled = true
                AppLog.i("ocr consent granted, session starting")
            } else {
                AppLog.i("ocr consent denied")
            }
        }
        finish()
    }

    private companion object {
        const val REQUEST_CAPTURE = 88071
    }
}
