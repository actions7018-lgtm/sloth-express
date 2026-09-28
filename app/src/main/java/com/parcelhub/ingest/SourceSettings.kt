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

import com.parcelhub.data.repository.ShipmentRepository
import com.parcelhub.model.SourceType
import com.parcelhub.parser.CompiledRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 来源开关的内存镜像。
 *
 * 通知监听回调必须是纯内存读取（SOP §7.1），因此开关状态缓存在 StateFlow，
 * 由设置页写数据库后再刷新缓存。
 */
class SourceSettings(
    private val repository: ShipmentRepository,
    private val rules: CompiledRules,
    initialGenericEnabled: Boolean = true,
    private val onGenericChanged: (Boolean) -> Unit = {},
) {
    private val _blocked = MutableStateFlow<Set<String>>(emptySet())
    val blocked: StateFlow<Set<String>> = _blocked.asStateFlow()

    private val _known = MutableStateFlow<Set<String>>(emptySet())
    val known: StateFlow<Set<String>> = _known.asStateFlow()

    /** 是否允许未知来源（GenericSource）参与采集，默认开启 */
    private val _genericEnabled = MutableStateFlow(initialGenericEnabled)
    val genericEnabled: StateFlow<Boolean> = _genericEnabled.asStateFlow()

    /** 该来源是否被用户关闭 */
    fun isBlocked(packageName: String): Boolean = packageName in _blocked.value

    fun setGenericEnabled(enabled: Boolean) {
        _genericEnabled.value = enabled
        onGenericChanged(enabled)
    }

    /** 首次启动：把规则里的来源写入 source_apps，之后以数据库为准 */
    suspend fun seed() {
        val existing = repository.listSources().associateBy { it.packageName }
        val rows = rules.rules.sources.map { rule ->
            existing[rule.packageName] ?: com.parcelhub.data.entity.SourceAppEntity(
                packageName = rule.packageName,
                appName = rule.appName,
                enabled = rule.enabled,
                sourceType = rule.sourceType,
                parserVersion = rules.rules.version,
            )
        }
        val missing = rows.filter { row -> existing[row.packageName] == null }
        if (missing.isNotEmpty()) repository.upsertSources(missing)
        refresh()
    }

    suspend fun refresh() {
        val rows = repository.listSources()
        _known.value = rows.map { it.packageName }.toSet()
        _blocked.value = rows.filter { !it.enabled }.map { it.packageName }.toSet()
    }

    suspend fun setEnabled(packageName: String, enabled: Boolean) {
        repository.setSourceEnabled(packageName, enabled)
        if (repository.getSource(packageName) == null) {
            repository.upsertSources(
                listOf(
                    com.parcelhub.data.entity.SourceAppEntity(
                        packageName = packageName,
                        appName = packageName,
                        enabled = enabled,
                        sourceType = SourceType.OTHER.name,
                    ),
                ),
            )
        }
        refresh()
    }
}
