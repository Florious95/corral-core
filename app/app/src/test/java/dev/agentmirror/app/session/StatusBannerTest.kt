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

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Appearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * t.e6 成功态不组节点（契约 089 §3）。
 *
 * A-e6-both：先把 Sent / UploadSuccess 造出来，再断言屏上节点数为 0。
 * A-e6-struct：只在测试里存在的成功态也走同一共同出口，默认不组节点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusBannerTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun ae6Both_sentAndUploadSuccessDoNotComposeNodes() {
        val h = OverlayTestHarness()
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                SessionScreen(viewModel = h.vm, name = "远控 leader", onBack = {})
            }
        }
        compose.waitForIdle()

        compose.runOnIdle { h.vm.inputStatus = InputStatus.Sent }
        compose.waitForIdle()
        assertEquals(
            "InputStatus.Sent 不得组「已发送」节点",
            0,
            compose.onAllNodesWithText("已发送", substring = true).fetchSemanticsNodes().size,
        )

        compose.runOnIdle {
            h.vm.inputStatus = InputStatus.Idle
            h.vm.uploadStatus = UploadStatus.Success("/tmp/shot.png")
        }
        compose.waitForIdle()
        assertEquals(
            "UploadStatus.Success 不得组「已附加图片」节点",
            0,
            compose.onAllNodesWithText("已附加图片", substring = true).fetchSemanticsNodes().size,
        )

        compose.runOnIdle {
            h.vm.uploadStatus = UploadStatus.Idle
            h.vm.inputStatus = InputStatus.Failed("发送失败：超时")
        }
        compose.waitForIdle()
        compose.onNodeWithText("发送失败：超时").assertIsDisplayed()
    }

    @Test
    fun ae6Struct_unknownSuccessGoesThroughCommonExit() {
        val onlyInTest = object : TransientSuccess {}
        assertNull(
            "新增成功态必须走 TransientSuccess 共同出口，默认不组节点",
            bannerFrom(onlyInTest),
        )
    }
}
