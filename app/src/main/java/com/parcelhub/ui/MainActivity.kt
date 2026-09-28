/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.parcelhub.App
import com.parcelhub.ui.theme.ParcelHubTheme

/**
 * 单 Activity + Compose 导航。
 *
 * 从 App 自身提醒进入时携带 EXTRA_SHIPMENT_ID，直接打开对应包裹详情。
 */
class MainActivity : ComponentActivity() {

    private val shipmentIdState: MutableState<Long?> = mutableStateOf(null)
    private val startRouteState: MutableState<String> = mutableStateOf(Routes.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        startRouteState.value = if (
            !App.graph.isOnboardingDone() &&
            !com.parcelhub.util.PermissionUtil.isNotificationListenerEnabled(this)
        ) {
            Routes.ONBOARDING
        } else {
            Routes.HOME
        }
        shipmentIdState.value = resolveShipmentId(intent)

        setContent {
            ParcelHubTheme {
                AppNav(
                    startDestination = startRouteState.value,
                    initialShipmentId = shipmentIdState.value,
                    onOnboardingDone = { App.graph.markOnboardingDone() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val id = resolveShipmentId(intent)
        if (id != null) shipmentIdState.value = id
    }

    private fun resolveShipmentId(intent: Intent?): Long? =
        intent?.getLongExtra(EXTRA_SHIPMENT_ID, -1L)?.takeIf { it > 0L }

    companion object {
        const val EXTRA_SHIPMENT_ID = "shipment_id"
    }
}
