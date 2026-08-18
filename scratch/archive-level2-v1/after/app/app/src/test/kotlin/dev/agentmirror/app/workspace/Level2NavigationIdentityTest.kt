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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 二级菜单导航身份红测（060 t.app A-a-*）。
 *
 * 断言：点一行用 ref（结构字段身份，socket+paneid）导航，跳转**不依赖 title 内容**——
 * 两个 ref 不同但 title 相同的行，点各自必须进各自 ref 的会话。
 *
 * 红测先行：修复前二级不可点（拔除后无 onOpenSession）→ 编译/运行失败即红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Level2NavigationIdentityTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun clickUsesRefIdentityNotTitleContent() {
        // 两个会话 ref 不同、title 完全一致——身份必须只来自 ref（结构字段）。
        val refA = "/sockA%0"
        val refB = "/sockB%1"
        val sharedTitle = "◐  same-looking-agent"
        val sessions = listOf(
            Level2Entry(ref = refA, name = "claude", title = sharedTitle, rows = 24, cols = 80),
            Level2Entry(ref = refB, name = "codex", title = sharedTitle, rows = 24, cols = 80),
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

        // 点第二行（相同 title 的另一会话）：必须进 refB，不能因 title 相同而混淆。
        compose.onNodeWithTag("l2-row-$refB").performClick()
        assertEquals("点行必须用 ref（结构身份）导航", refB, openedRef)
        assertEquals("点行应带上结构字段展示名", "codex", openedName)
        assertNull("title 内容不得影响身份（未点第一行就不该开 refA）", openedRef?.takeIf { it == refA })
    }
}
