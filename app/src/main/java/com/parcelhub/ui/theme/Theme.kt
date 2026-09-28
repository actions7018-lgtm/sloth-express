/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BrandBlue = Color(0xFF1F6FEB)
private val BrandBlueDark = Color(0xFF7CB2FF)
private val Amber = Color(0xFFF5A524)
private val AmberDark = Color(0xFFFFC84A)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    secondary = Amber,
    tertiary = Color(0xFF2DA44E),
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    secondary = AmberDark,
    tertiary = Color(0xFF56D364),
)

/** 全局主题：跟随系统深浅色，第一版不做自定义主题页（SOP §14 未列入范围） */
@Composable
fun ParcelHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
