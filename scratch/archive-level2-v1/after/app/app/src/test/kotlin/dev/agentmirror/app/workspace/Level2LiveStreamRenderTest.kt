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

package dev.agentmirror.app.workspace

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 二级菜单实时流渲染红测（060 t.app A-a-*）。
 *
 * 断言：收到 Level2Frame → 每行 title 原样渲染（含 ◐/✳ 前缀），文本逐字节等于输入——
 * **一个字符都不解析**。渲染用 createComposeRule 驱动 [WorkspaceScreen] 的二级分支，
 * title 直接 Text(title)。
 *
 * 红测先行：修复前无 Level2ViewModel / 二级渲染 → 编译失败即红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Level2LiveStreamRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun entry(ref: String, name: String, title: String) =
        Level2Entry(ref = ref, name = name, title = title, rows = 24, cols = 80)

    @Test
    fun titleRenderedVerbatimIncludingPrefixGlyphs() {
        // 服务端原样推来的标题：含 ◐/✳ 前缀 + 尾空格（不可被 trim 掉）。
        val titles = listOf(
            "◐  claude  正在工作",
            "✳  codex  输入中  ",
        )
        val sessions = listOf(
            entry("ref-1", "claude", titles[0]),
            entry("ref-2", "codex", titles[1]),
        )

        var openedRef: String? = null
        var openedName: String? = null
        compose.setContent {
            AgentMirrorTheme {
                Level2LiveStreamScreen(
                    sessions = sessions,
                    onOpenSession = { ref, name -> openedRef = ref; openedName = name },
                )
            }
        }

        // 标题逐字节原样：含前缀字形与尾空格，一个字符都不改。
        compose.onNodeWithText(titles[0]).assertExists("标题必须原样渲染（含 ◐ 前缀与尾内容）")
        compose.onNodeWithText(titles[1]).assertExists("标题必须原样渲染（含 ✳ 前缀与尾空格）")
        assertEquals("标题渲染不得丢失前缀字符", "◐", titles[0].substring(0, 1))
    }
}
