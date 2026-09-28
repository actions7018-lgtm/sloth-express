/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.autoquery.cainiao

/**
 * 菜鸟启动结果（参考 SOP §11.4）。
 *
 * [Opened] 只表示“成功启动菜鸟”，**不等于物流查询成功**（SOP §2.2 / §39.6）。
 */
sealed class CainiaoLaunchResult {

    data class Opened(val packageName: String) : CainiaoLaunchResult()

    /** 走官方 Deep Link 打开；仅在真机验证过官方 Scheme 后才会产生（SOP §12） */
    data class DeepLinkOpened(val packageName: String, val uri: String) : CainiaoLaunchResult()

    /** 设备未安装任一候选包（SOP §13：UI 显示安装入口，不显示技术错误） */
    data object NotInstalled : CainiaoLaunchResult()

    /** 后台启动被系统限制（SOP §14：改走通知按钮由用户点击） */
    data class BackgroundLaunchBlocked(val packageName: String) : CainiaoLaunchResult()

    data class Failed(val reason: String) : CainiaoLaunchResult()

    /** 是否已经把菜鸟拉起来（可用于进入 WAITING_AUTOMATION，但不代表查询成功） */
    val isOpened: Boolean
        get() = this is Opened || this is DeepLinkOpened
}
