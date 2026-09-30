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

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 项目对外链接的唯一出处：设置页「关于」区的版权 / 开源 / 许可证入口都从这里取 URL。
 *
 * 地址以 `git remote -v` 实测结果为准（origin = actions7018-lgtm/sloth-express），
 * 不在 UI 文件里散落字符串。
 *
 * 打开方式只用标准 `Intent.ACTION_VIEW` + 系统 https 处理器：
 * 不内嵌 WebView、不指定 Chrome/Edge、不依赖 Google Play——国产 ROM 装什么浏览器就交给谁，
 * 没有可用浏览器时 Toast 提示而不是崩溃。
 */
object ProjectLinks {

    /** 项目主页（GitHub 仓库地址，去 .git 后缀） */
    const val GITHUB_REPOSITORY = "https://github.com/actions7018-lgtm/sloth-express"

    /** 开源许可证正文（MPL-2.0） */
    const val LICENSE = "$GITHUB_REPOSITORY/blob/main/LICENSE"

    /** 打开 GitHub 项目主页（设置页版权行 / 开源项目行入口） */
    fun openProjectPage(context: Context) = openUrl(context, GITHUB_REPOSITORY)

    /** 打开 LICENSE 页面（设置页开源许可证入口） */
    fun openLicensePage(context: Context) = openUrl(context, LICENSE)

    private fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            // 系统没有任何可处理 https 的应用：提示而不崩溃
            Toast.makeText(context, "未找到可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }
}
