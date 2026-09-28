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
 * 菜鸟 App 包名候选（参考 SOP §11.2）。
 *
 * 代码只维护候选列表，**最终以真机验证结果为准**，不要永久依赖网上整理的包名资料。
 */
object CainiaoPackages {

    val candidates: List<String> = listOf(
        "com.cainiao.wireless",
        "com.cainiao.cnglobal",
        "com.cainiao.cnintl4android",
    )

    fun isCainiao(packageName: String?): Boolean =
        packageName != null && packageName in candidates
}
