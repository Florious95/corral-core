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

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 键条展示顺序红测（ledger.state-detection.v1 t.keybar，2026-08-15 用户裁定）。
 *
 * 裁定：顺序改为 Esc / Tab / ↑ ↓ ← → / Ctrl-C——Ctrl-C 挪到末尾防误触（键条里唯一
 * 不可撤销的操作），Tab 提前是因为直通输入后要靠它做命令补全。017 R-1 只定义了最小集
 * （7 键），左右顺序由本条裁定覆盖。
 *
 * 断言方式：渲染 KeyBar，按未裁剪左边距（x）对每个键的 contentDescription 排序，
 * 还原屏幕实际左右顺序，再与裁定顺序整表比对。修前红（Ctrl-C 仍在第二位、Tab 在第三位）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyBarOrderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun keyBarDisplaysRulingOrder() {
        compose.setContent {
            AgentMirrorTheme {
                KeyBar(enabled = true, onKey = {})
            }
        }
        compose.waitForIdle()

        // 2026-08-15 用户裁定顺序：Esc / Tab / ↑ ↓ ← → / Ctrl-C。
        val rulingOrder = listOf(
            "Esc 键：中断当前步骤",
            "Tab 键：补全",
            "上方向键",
            "下方向键",
            "左方向键",
            "右方向键",
            "Ctrl-C 键：发送中断信号",
        )

        // 按未裁剪左边距升序还原屏幕实际顺序（与 KeyBarFitTest 同一套布局坐标 API）。
        val actualOrder = rulingOrder
            .map { desc -> desc to compose.onNodeWithContentDescription(desc).getUnclippedBoundsInRoot().left }
            .sortedBy { (_, left) -> left }
            .map { (desc, _) -> desc }

        assertEquals(
            "键条实际展示顺序 ≠ 2026-08-15 裁定顺序（期望 Esc / Tab / ↑ ↓ ← → / Ctrl-C）",
            rulingOrder,
            actualOrder,
        )
    }
}
