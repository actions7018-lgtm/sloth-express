/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 来源 App 表（SOP §10.1 source_apps）：白名单与开关状态。 */
@Entity(tableName = "source_apps")
data class SourceAppEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "app_name") val appName: String = "",
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @ColumnInfo(name = "source_type") val sourceType: String = "OTHER",
    @ColumnInfo(name = "parser_version") val parserVersion: Int = 1,
)

/** 解析规则表（SOP §10.1 parser_rules）：记录当前生效规则版本。 */
@Entity(tableName = "parser_rules")
data class RuleEntity(
    @PrimaryKey
    @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "source") val source: String = "assets",
    @ColumnInfo(name = "version") val version: Int = 1,
    @ColumnInfo(name = "rule_json") val ruleJson: String = "",
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
)
