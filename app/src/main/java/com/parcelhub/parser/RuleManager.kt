/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.parser

import com.parcelhub.util.AppLog

/**
 * 规则管理器（SOP §18）。
 *
 * V0.1 只从本地 assets 读取，不联网；规则对象常驻复用（预编译）。
 * V0.2 的“下载 → 校验 → 冒烟测试 → 替换 / 回退”留出 reload() 入口，但第一版不接网络。
 */
class RuleManager(private val assetReader: () -> String) {

    @Volatile
    private var compiled: CompiledRules? = null

    /** 当前生效规则（首次访问时加载并预编译） */
    fun rules(): CompiledRules {
        compiled?.let { return it }
        return synchronized(this) {
            compiled ?: load().also { compiled = it }
        }
    }

    /** 当前规则版本号（写入 parser_rules 表） */
    fun version(): Int = rules().rules.version

    /** 重新加载（本地规则变更 / 后续热更新入口）。返回 false 表示新规则非法，已保留旧版本。 */
    fun reload(): Boolean {
        val candidate = try {
            load()
        } catch (t: Throwable) {
            AppLog.w("rule reload failed, keep previous", t)
            return false
        }
        synchronized(this) { compiled = candidate }
        return true
    }

    private fun load(): CompiledRules {
        val text = assetReader()
        check(text.isNotBlank()) { "rules.json is empty" }
        return CompiledRules(RuleJsonParser.parse(text))
    }
}
