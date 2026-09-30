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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.parcelhub.util.AppLog

/**
 * 本地 OCR 识别（SOP V2.0 §19：OCR 只做最后兜底，纯本地、不联网）。
 * 中文 + 数字英文混合模型（ML Kit 端侧 bundled，无 Google Play 服务也能用）。
 */
object OcrTextRecognizer {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** 识别位图文本；失败 / 空文本回 null（调用方按失败退避） */
    fun recognize(bitmap: Bitmap, onDone: (String?) -> Unit) {
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val text = result.text.trim()
                    onDone(text.takeIf { it.isNotEmpty() })
                }
                .addOnFailureListener { e ->
                    AppLog.w("ocr recognize failed", e)
                    onDone(null)
                }
        } catch (t: Throwable) {
            AppLog.w("ocr recognize threw", t)
            onDone(null)
        }
    }
}
