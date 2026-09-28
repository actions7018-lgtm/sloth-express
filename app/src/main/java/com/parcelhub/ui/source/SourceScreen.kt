/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.ui.source

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.parcelhub.App
import com.parcelhub.model.SourceType
import com.parcelhub.sms.SmsContract
import com.parcelhub.sms.SmsPermissionManager
import com.parcelhub.util.PrivacyUtil
import kotlinx.coroutines.launch

/**
 * 来源管理（P6）：白名单开关。
 * 关闭来源后，监听回调会在纯内存判断阶段直接丢弃该来源（不写库、不解析）。
 *
 * 里面也包含**短信**（快递短信 SOP §6 可插拔）：它和其它来源一样是 `source_apps`
 * 里的一行、同一个开关、同一套默认值（默认开），只是额外多一行“短信权限”状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceScreen(onBack: () -> Unit) {
    val repository = App.graph.repository
    val sourceSettings = App.graph.sourceSettings
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val sources by repository.observeSources().collectAsState(initial = emptyList())
    val genericEnabled by sourceSettings.genericEnabled.collectAsState()

    // 短信权限三态：已授权 / 未问过 / 被拒绝（被拒绝后改走系统权限页，SOP §45）
    var smsGranted by remember { mutableStateOf(SmsPermissionManager.hasPermission(context)) }
    var smsDenied by remember { mutableStateOf(false) }
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        smsGranted = granted
        smsDenied = !granted
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("来源管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("未知来源（通用识别）", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "仅在命中强快递关键词时才解析；不会保存无关通知",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = genericEnabled,
                            onCheckedChange = { sourceSettings.setGenericEnabled(it) },
                        )
                    }
                }
            }

            items(sources, key = { it.packageName }) { source ->
                val isSms = source.packageName == SmsContract.SOURCE_PACKAGE
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = source.appName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (isSms) {
                                // 短信行专属：状态行可点，负责“授权 / 去系统设置”两个入口
                                val status = when {
                                    smsGranted -> "读到的物流短信只在本机解析，短信原文不保存"
                                    smsDenied -> "权限未授予 · 点这里打开系统权限设置"
                                    else -> "未授予短信权限 · 点这里授权后才会读取"
                                }
                                Text(
                                    text = status,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (smsGranted) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    modifier = Modifier.clickable {
                                        when {
                                            smsGranted -> Unit
                                            smsDenied -> SmsPermissionManager.openPermissionSettings(context)
                                            else -> smsPermissionLauncher.launch(SmsPermissionManager.PERMISSION)
                                        }
                                    },
                                )
                            } else {
                                Text(
                                    text = sourceTypeLabel(source.sourceType),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = source.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(
                            checked = source.enabled,
                            onCheckedChange = { enabled ->
                                scope.launch { sourceSettings.setEnabled(source.packageName, enabled) }
                                // 开“短信”时顺手要权限：默认就是开，缺权限时这里就是唯一的授权入口
                                if (isSms && enabled && !smsGranted) {
                                    smsPermissionLauncher.launch(SmsPermissionManager.PERMISSION)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun sourceTypeLabel(raw: String): String = when (SourceType.from(raw)) {
    SourceType.ECOMMERCE -> "电商平台"
    SourceType.LOGISTICS -> "物流服务"
    SourceType.COURIER -> "快递员 App"
    SourceType.PICKUP_STATION -> "驿站代收"
    SourceType.LOCKER -> "智能快递柜"
    SourceType.OTHER -> "其他来源"
}

@Suppress("unused")
private fun maskPackage(packageName: String): String = PrivacyUtil.summarize(packageName, 40)
