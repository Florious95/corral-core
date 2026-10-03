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

package dev.agentmirror.app.notify

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.agentmirror.app.AgentMirrorApp
import dev.agentmirror.app.MainNavState
import dev.agentmirror.app.notify.NotificationRepositoryTest.Companion.record
import dev.agentmirror.app.service.ServiceWire
import dev.agentmirror.app.workspace.WorkspaceViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.Executor

/** Uses only golden-baseline APIs: the same tests observe real red UI on 2e2357d4. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageCenterWorkspaceScopeTest {
    @get:Rule val compose = createComposeRule()

    private val repository = NotificationRepository(null, Executor { it.run() })
    private val nav = MainNavState(initialShowPairing = false)

    @After
    fun tearDown() {
        NotificationCenter.installForTest(null)
        ServiceWire.resetConfigForTest()
    }

    private fun render(workspace: String? = null) {
        repository.acceptHistory(
            listOf(
                record("1", sessionRef = null).copy(workspace = "/work/project"),
                record("2", sessionRef = null).copy(workspace = "/work/other"),
            ),
        )
        nav.selectedWorkspaceCwd = workspace
        NotificationCenter.installForTest(NotificationHub(repository) {})
        val viewModel = WorkspaceViewModel()
        compose.setContent { AgentMirrorApp(navState = nav, workspaceViewModel = viewModel) }
        compose.waitForIdle()
    }

    @Test
    fun level1_bellAndCenterAreGlobal_andMarkAllReadIsGlobal() {
        render()
        compose.onNodeWithTag("message-center-badge").onChild().assertTextEquals("2")
        compose.onNodeWithTag("message-center-bell").performClick()
        compose.onNodeWithTag("message-card-id-1").assertExists()
        compose.onNodeWithTag("message-card-id-2").assertExists()
        compose.onNodeWithTag("message-center-mark-all").performClick()
        compose.runOnIdle { assertEquals(0, repository.unreadCount.value) }
    }

    @Test
    fun level2_centerShowsOnlyCurrentDirectory() {
        render("/work/project/")
        compose.onNodeWithTag("message-center-bell").performClick()
        compose.onNodeWithTag("message-card-id-1").assertExists()
        compose.onNodeWithTag("message-card-id-2").assertDoesNotExist()
    }

    @Test
    fun level2_bellCountsOnlyCurrentDirectoryUnread() {
        render("/work/project")
        compose.onNodeWithTag("message-center-badge").onChild().assertTextEquals("1")
    }

    @Test
    fun level2_markAllReadLeavesOtherDirectoryUnread() {
        render("/work/project")
        compose.onNodeWithTag("message-center-bell").performClick()
        compose.onNodeWithTag("message-center-mark-all").performClick()
        compose.runOnIdle {
            assertEquals(1, repository.unreadCount.value)
            assertTrue(repository.items.value.single { it.record.workspace == "/work/project" }.read)
            assertTrue(!repository.items.value.single { it.record.workspace == "/work/other" }.read)
        }
    }

    @Test
    fun level2_emptyScopeHasContextualEmptyState_notGlobalMessages() {
        render("/work/missing")
        compose.onNodeWithTag("message-center-bell").performClick()
        compose.onNodeWithTag("message-center-empty").assertExists()
        compose.onNodeWithText("当前工作区暂无任务消息").assertExists()
        compose.onNodeWithTag("message-card-id-1").assertDoesNotExist()
        compose.onNodeWithTag("message-card-id-2").assertDoesNotExist()
    }
}
