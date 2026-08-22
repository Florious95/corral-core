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

package dev.agentmirror.app.session

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 键条整排静置可见红测（fix-term-bg-cjk 顺带：用户真机实拍最右「→」被屏幕右缘裁半）。
 *
 * 根因：M3 可点 Surface 自带 48dp 最小触控布局宽（018 §一.4 特意保留），7 键固有宽度和
 * （48×5 + Ctrl-C 更宽 + 间距/边距）逼近并超出常见屏宽——横向滚动虽可及，但静置首屏
 * 最后一键呈"硬切半个键帽"观感（实拍即缺陷，工程红线 5：用户动作前的可见结果）。
 *
 * 断言几何不变量：在 411dp（用户 Pixel 级机型宽）静置状态下，「→」键的未裁剪右边界
 * 不得超出根宽——即整排键无需滚动即完整可见。修前红（固有和 > 屏宽，→ 溢出右缘）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyBarFitTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rightArrowKeyFullyVisibleWithoutScroll() {
        compose.setContent {
            // 注入 fontScale 1.4（系统"大"字号档）：411dp/默认字号下 7 键恰好放下（实测
            // 右边界 396dp），真机裁切由用户字号放大触发——文本键随 fontScale 变宽而
            // 48dp 触控地板不变，整排被撑出右缘。红测必须在放大档下打。
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 1.4f)) {
                AgentMirrorTheme {
                    KeyBar(enabled = true, onKey = {})
                }
            }
        }
        compose.waitForIdle()

        val rootRight = compose.onRoot().getUnclippedBoundsInRoot().right
        val arrow = compose.onNodeWithContentDescription("右方向键").getUnclippedBoundsInRoot()
        assertTrue(
            "「→」键右边界 ${arrow.right} 超出屏宽 $rootRight（静置被右缘裁切）",
            arrow.right <= rootRight,
        )
    }
}
