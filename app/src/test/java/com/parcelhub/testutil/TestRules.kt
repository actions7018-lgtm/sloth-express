/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.testutil

import com.parcelhub.parser.CompiledRules
import com.parcelhub.parser.RuleJsonParser
import java.io.File

/**
 * 单测用规则加载器。
 *
 * 规则单一数据源是 `app/src/main/assets/rules.json`（SOP §18），
 * 单测直接读同一份文件，保证“测的就是线上跑的规则”。
 */
object TestRules {

    val compiled: CompiledRules by lazy { CompiledRules(RuleJsonParser.parse(json())) }

    private fun json(): String {
        val candidates = listOf(
            File("src/main/assets/rules.json"),
            File("app/src/main/assets/rules.json"),
            File("../src/main/assets/rules.json"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 rules.json，尝试路径: ${candidates.map { it.absolutePath }}")
        return file.readText(Charsets.UTF_8)
    }
}
