/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.pending

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 待补全自动读页候选集（需求「待补全改全自动」+ 设置页自动开关）。
 *
 * 两条进入路径（[shouldAutoRead]）：
 *  1. **有待补全单**：`WAITING_TRACKING` 单的 `sourcePackageName`——补全 / 过期(72h) / 删除自动移出；
 *  2. **自动开关开**（持久化，默认开）：来源管理里**启用中**的来源 App 也进候选——
 *     打开即读页，读到单号建单/补全，读不到（门禁不过）零副作用。
 *
 * 无障碍服务收到窗口切换 / 内容变化事件时同步查候选（纯内存零 IO），
 * 命中才走读取链路；开关关后只剩手动通路（详情页「去对应 App 查看」
 * 与设置页手动按钮的 [PendingFocus] 焦点，不受本集合约束）。
 *
 * 无 Android 依赖：纯内存 + StateFlow，可被 JVM 单测覆盖。
 */
object PendingWatch {

    private val pendingPackages = MutableStateFlow<Set<String>>(emptySet())

    private val enabledPackages = MutableStateFlow<Set<String>>(emptySet())

    /** 自动读页开关（由 ServiceLocator 的持久化属性在启动与切换时灌入） */
    @Volatile
    private var autoOn: Boolean = true

    /** 有待补全单的来源包名集合；仅供诊断 / 测试读取 */
    val watched: StateFlow<Set<String>> = pendingPackages.asStateFlow()

    /** 覆盖式更新（每次 observePending 发射全量集合，含 null 来源已过滤） */
    fun update(sourcePackages: Set<String>) {
        pendingPackages.value = sourcePackages
    }

    /** 覆盖式更新启用中的来源 App（来源管理开关变化时发射） */
    fun updateEnabled(sourcePackages: Set<String>) {
        enabledPackages.value = sourcePackages
    }

    /** 自动开关同步（持久化属性 setter 调用） */
    fun setAuto(enabled: Boolean) {
        autoOn = enabled
    }

    /** 该包名是否有在等的待补全单 */
    fun hasWaiting(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        return packageName in pendingPackages.value
    }

    /**
     * 事件回调判定：是否要自动读该包名的当前页面。
     * 有待补全单恒定读；启用来源只在自动开关打开时读。
     */
    fun shouldAutoRead(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        if (packageName in pendingPackages.value) return true
        return autoOn && packageName in enabledPackages.value
    }

    /** 测试用：复位空集合与开关默认值 */
    fun reset() {
        pendingPackages.value = emptySet()
        enabledPackages.value = emptySet()
        autoOn = true
    }
}
