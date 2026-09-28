/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub

import android.app.Application
import com.parcelhub.util.AppLog

/**
 * 应用入口。
 *
 * 依赖装配采用手工 ServiceLocator（无 DI 框架），保证启动路径短、可预测（SOP §20.1）。
 * 所有组件在 onCreate 中一次性就绪，监听服务访问 [graph] 时不会触发额外初始化。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        graph = ServiceLocator(this).also { it.start() }
        AppLog.d("ParcelHub started")
    }

    companion object {
        lateinit var graph: ServiceLocator
            private set
    }
}
