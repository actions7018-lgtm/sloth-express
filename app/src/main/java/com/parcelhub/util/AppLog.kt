/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.util

import com.parcelhub.BuildConfig

/**
 * 统一日志出口（SOP §19.2）。
 *
 * - release 版本一律不输出 debug 日志；
 * - 调用方必须传入已脱敏文本，禁止把通知原文传进来。
 */
object AppLog {
    private const val TAG = "ParcelHub"

    fun d(message: String) {
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, message)
    }

    fun i(message: String) {
        android.util.Log.i(TAG, message)
    }

    fun w(message: String, throwable: Throwable? = null) {
        if (throwable == null) android.util.Log.w(TAG, message)
        else android.util.Log.w(TAG, message, throwable)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable == null) android.util.Log.e(TAG, message)
        else android.util.Log.e(TAG, message, throwable)
    }
}
