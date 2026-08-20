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

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.agentmirror.app.ui.components.StatusChip
import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * 契约 088 E9/E16：排序 (收藏↓, 运行↓, 名称↑)；「运行」与「空闲」等宽。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SortSessionsAndLabelTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sortSessions_fourBucketsNameAscInside() {
        val shuffled = listOf(
            item("zeta-ui", SessionStatus.Idle, starred = false),
            item("zeta-sb", SessionStatus.Busy, starred = true),
            item("alpha-ui", SessionStatus.Idle, starred = false),
            item("zeta-ub", SessionStatus.Busy, starred = false),
            item("alpha-si", SessionStatus.Idle, starred = true),
            item("alpha-ub", SessionStatus.Busy, starred = false),
            item("zeta-si", SessionStatus.Idle, starred = true),
            item("alpha-sb", SessionStatus.Busy, starred = true),
        )
        val got = sortSessions(shuffled).map { it.displayName }
        assertEquals(
            listOf(
                "alpha-sb", "zeta-sb",
                "alpha-si", "zeta-si",
                "alpha-ub", "zeta-ub",
                "alpha-ui", "zeta-ui",
            ),
            got,
        )
    }

    @Test
    fun statusChips_runAndIdleHaveEqualWidth() {
        compose.setContent {
            AgentMirrorTheme {
                Row {
                    StatusChip(SessionStatus.Busy, Modifier.testTag("chip-busy"))
                    StatusChip(SessionStatus.Idle, Modifier.testTag("chip-idle"))
                }
            }
        }
        compose.waitForIdle()
        val busy = compose.onNodeWithTag("chip-busy").getUnclippedBoundsInRoot()
        val idle = compose.onNodeWithTag("chip-idle").getUnclippedBoundsInRoot()
        assertTrue(
            "运行/空闲芯片宽度必须相等 busy=${busy.right - busy.left} idle=${idle.right - idle.left}",
            abs((busy.right - busy.left).value - (idle.right - idle.left).value) < 0.5f,
        )
        assertEquals("运行", L2Status.WORKING.label)
    }

    private fun item(name: String, status: SessionStatus, starred: Boolean) = SessionItem(
        id = name,
        displayName = name,
        path = "/p",
        status = status,
        starred = starred,
    )
}
