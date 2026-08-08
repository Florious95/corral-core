/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.agentmirror.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 品牌主色：深蓝（终端深夜配色基调，与资源 colors.xml 一致）。 */
val brandPrimary: Color = Color(0xFF1B2A4A)

/** 品牌背景色：接近黑的深蓝，用于深色终端背景。 */
val brandBackground: Color = Color(0xFF0D1626)

/** 深色配色：深夜终端基调。 */
private val DarkColorScheme = darkColorScheme(
    primary = brandPrimary,
    onPrimary = Color.White,
    background = brandBackground,
)

/** 浅色配色：品牌蓝主色 + 白底。 */
private val LightColorScheme = lightColorScheme(
    primary = brandPrimary,
    onPrimary = Color.White,
    background = Color(0xFFF6F7F9),
)

/**
 * 全局 Material3 主题入口。
 *
 * 依据系统深浅色自动切换配色方案；后续配色定稿（品牌设计任务）后在此替换。
 */
@Composable
fun AgentMirrorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
