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

import android.net.Uri

/**
 * 菜鸟官方 Deep Link 提供者（参考 SOP §12）。
 *
 * SOP §12 结论：当前没有可靠的公开资料证明存在可长期依赖的
 * “传入单号直达包裹详情”的通用官方 Deep Link，因此第一版**不要写死**
 * 未经验证的 `cainiao://tracking?number=xxx`。
 *
 * 本接口是保留的扩展点：
 * ```text
 * 官方 Deep Link 已真机验证 → 实现本接口返回可用 Uri
 * 没有官方 Deep Link        → 返回 null，正常启动菜鸟首页（当前行为）
 * ```
 *
 * 纯接口 + 默认空实现，可在 JVM 单测覆盖契约。
 */
interface CainiaoDeepLinkProvider {

    /**
     * 为指定单号构造官方 Deep Link。
     *
     * @return 已验证可用的 Uri；未经验证一律返回 null，不得猜测构造
     */
    fun buildTrackingLink(trackingNumber: String, carrier: String?): Uri?

    companion object {
        /** 默认实现：未经验证，永远返回 null（SOP §12 当前结论） */
        val NONE: CainiaoDeepLinkProvider = object : CainiaoDeepLinkProvider {
            override fun buildTrackingLink(trackingNumber: String, carrier: String?): Uri? = null
        }
    }
}
