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

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/**
 * 打开菜鸟 App（参考 SOP §11.4 启动结果 / §12 官方 Scheme / §14 后台启动策略）。
 *
 * **真机实测（菜鸟 8.11.923）**：SOP §12 列的官方 Scheme 全部不可用——
 * `guoguo://go/…` 目标 Activity 未导出（SecurityException: Permission Denial），
 * `cainiao://index/my?operation=expressTrace` 无法解析（Activity not found）。
 * 因此本实现只走 `getLaunchIntentForPackage` 打开首页，再由无障碍点进查询页。
 *
 * 若将来真机验证出可用 Scheme：实现 [CainiaoDeepLinkProvider] 并传入本类即可，
 * [openCainiao] 会先试 Deep Link（返回 [CainiaoLaunchResult.DeepLinkOpened]），
 * 失败再回落启动 Intent——顺序只改这一处（SOP §12）。
 *
 * 注意：[CainiaoLaunchResult.Opened] 只代表“把菜鸟拉起来了”，不等于物流查询成功（SOP §2.2 / §39.6）。
 */
class CainiaoLauncher(
    private val context: Context,
    private val deepLinkProvider: CainiaoDeepLinkProvider = CainiaoDeepLinkProvider.NONE,
) {

    fun openCainiao(trackingNumber: String? = null, carrier: String? = null): CainiaoLaunchResult {
        // SOP §12：官方 Deep Link 已验证才用；没有就正常启动首页
        val deepLink = trackingNumber?.trim().orEmpty().takeIf { it.isNotEmpty() }
            ?.let { runCatching { deepLinkProvider.buildTrackingLink(it, carrier) }.getOrNull() }
        if (deepLink != null) {
            val packageName = CainiaoPackages.candidates.firstOrNull().orEmpty()
            return try {
                val intent = Intent(Intent.ACTION_VIEW, deepLink)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (packageName.isNotEmpty()) intent.setPackage(packageName)
                context.startActivity(intent)
                CainiaoLaunchResult.DeepLinkOpened(packageName, deepLink.toString())
            } catch (se: SecurityException) {
                CainiaoLaunchResult.BackgroundLaunchBlocked(packageName)
            } catch (anf: ActivityNotFoundException) {
                CainiaoLaunchResult.Failed("DeepLinkNotFound:$deepLink")
            } catch (t: Throwable) {
                CainiaoLaunchResult.Failed(t.javaClass.simpleName)
            }
        }

        val pm = context.packageManager
        for (packageName in CainiaoPackages.candidates) {
            val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: continue
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return try {
                context.startActivity(launchIntent)
                CainiaoLaunchResult.Opened(packageName)
            } catch (se: SecurityException) {
                // Android 12+ 后台启动受限（SOP §14）：改走通知按钮由用户点击
                CainiaoLaunchResult.BackgroundLaunchBlocked(packageName)
            } catch (anf: ActivityNotFoundException) {
                CainiaoLaunchResult.Failed("ActivityNotFound:$packageName")
            } catch (t: Throwable) {
                CainiaoLaunchResult.Failed(t.javaClass.simpleName)
            }
        }
        return CainiaoLaunchResult.NotInstalled
    }
}
