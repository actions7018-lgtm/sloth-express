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

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.parcelhub.ui.diagnostics.DiagnosticsScreen
import com.parcelhub.ui.home.HomeScreen
import com.parcelhub.ui.onboarding.OnboardingScreen
import com.parcelhub.ui.settings.SettingsScreen
import com.parcelhub.ui.shipment.ShipmentDetailScreen
import com.parcelhub.ui.shipment.ShipmentListScreen
import com.parcelhub.ui.source.SourceScreen

/**
 * 页面结构（SOP §14）：
 * P1 启动/权限引导 · P2 首页 · P3 快递列表 · P4 详情 · P5 设置 · P6 来源管理 · P7 诊断
 *
 * 底部导航只有「首页 / 快递 / 设置」三项，
 * 不为“通知/事件/平台”创建独立底部导航。
 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val LIST = "list"
    const val SETTINGS = "settings"
    const val SOURCES = "sources"
    const val DIAGNOSTICS = "diagnostics"
    const val DETAIL = "detail/{shipmentId}"

    fun detail(id: Long): String = "detail/$id"
}

private data class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val bottomTabs = listOf(
    BottomTab(Routes.HOME, "首页", Icons.Default.Home),
    BottomTab(Routes.LIST, "快递", Icons.Default.Inventory2),
    BottomTab(Routes.SETTINGS, "设置", Icons.Default.Settings),
)

@Composable
fun AppNav(
    startDestination: String = Routes.HOME,
    initialShipmentId: Long? = null,
    onOnboardingDone: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // 从提醒通知进入：直达该包裹详情
    LaunchedEffect(initialShipmentId) {
        if (initialShipmentId != null && initialShipmentId > 0L) {
            navController.navigate(Routes.detail(initialShipmentId)) {
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (currentRoute in bottomTabs.map { it.route }) {
                NavigationBar {
                    bottomTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenSources = { navController.navigate(Routes.SOURCES) },
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onOpenDetail = { navController.navigate(Routes.detail(it)) },
                )
            }
            composable(Routes.LIST) {
                ShipmentListScreen(
                    onOpenDetail = { navController.navigate(Routes.detail(it)) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenSources = { navController.navigate(Routes.SOURCES) },
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onOpenOnboarding = { navController.navigate(Routes.ONBOARDING) },
                )
            }
            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument("shipmentId") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("shipmentId") ?: return@composable
                ShipmentDetailScreen(
                    shipmentId = id,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SOURCES) {
                SourceScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.DIAGNOSTICS) {
                DiagnosticsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onDone = {
                        onOnboardingDone()
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
}
