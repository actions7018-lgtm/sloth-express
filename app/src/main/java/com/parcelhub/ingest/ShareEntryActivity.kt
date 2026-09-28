/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.ingest

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.parcelhub.App

/**
 * 分享导入兜底入口（SOP §16）。
 *
 * 当通知 / 历史 / API 都没有覆盖时，用户可在电商或物流 App 中
 * “分享 → 树懒快递助手”，由同一条解析链路生成事件并匹配包裹。
 * 这是人工干预最少的兜底方式，不是正常主流程。
 */
class ShareEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = resolveSharedText(intent)
        if (!shared.isNullOrBlank()) {
            App.graph.importSharedText(shared)
        }
        finish()
    }

    private fun resolveSharedText(intent: Intent?): String? {
        if (intent == null) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        return listOfNotNull(subject, text)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
            .takeIf { it.isNotBlank() }
    }
}
