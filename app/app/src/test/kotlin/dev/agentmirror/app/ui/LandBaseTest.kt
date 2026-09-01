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

package dev.agentmirror.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.agentmirror.app.ui.components.StatusChip
import dev.agentmirror.app.ui.components.runningDotColor
import dev.agentmirror.app.ui.components.statusVisuals
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.model.sessionStatusFromL2
import dev.agentmirror.app.ui.model.sessionStatusFromWire
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Appearance
import dev.agentmirror.app.ui.theme.DarkPalette
import dev.agentmirror.app.ui.theme.LightPalette
import dev.agentmirror.app.workspace.L2Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * t.base 落位验收：三态枚举、映射不把 unknown 染成 Idle、
 * StatusChip / RunningDot 三态取到不同颜色（unknown ≠ idle）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LandBaseTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sessionStatusHasThreeValues() {
        assertEquals(
            setOf(SessionStatus.Busy, SessionStatus.Idle, SessionStatus.Abnormal, SessionStatus.Unknown),
            SessionStatus.entries.toSet(),
        )
    }

    @Test
    fun mappingDoesNotTurnUnknownIntoIdle() {
        assertEquals(SessionStatus.Busy, sessionStatusFromL2(L2Status.WORKING))
        assertEquals(SessionStatus.Idle, sessionStatusFromL2(L2Status.IDLE))
        assertEquals(SessionStatus.Unknown, sessionStatusFromL2(L2Status.UNKNOWN))
        assertEquals(SessionStatus.Busy, sessionStatusFromWire("working"))
        assertEquals(SessionStatus.Idle, sessionStatusFromWire("idle"))
        assertEquals(SessionStatus.Unknown, sessionStatusFromWire("unknown"))
        assertEquals(SessionStatus.Unknown, sessionStatusFromWire(""))
        assertEquals(SessionStatus.Unknown, sessionStatusFromWire("garbage"))
        assertNotEquals(SessionStatus.Idle, sessionStatusFromWire("unknown"))
        assertNotEquals(SessionStatus.Idle, sessionStatusFromL2(L2Status.UNKNOWN))
    }

    @Test
    fun statusChipAndRunningDotThreeStatesTakeDistinctColors() {
        for (p in listOf(LightPalette, DarkPalette)) {
            val busy = statusVisuals(p, SessionStatus.Busy)
            val idle = statusVisuals(p, SessionStatus.Idle)
            val unknown = statusVisuals(p, SessionStatus.Unknown)

            assertEquals(setOf(busy.lamp, idle.lamp, unknown.lamp).size, 3)
            assertEquals(setOf(busy.chipBg, idle.chipBg, unknown.chipBg).size, 3)
            assertEquals(setOf(busy.chipText, idle.chipText, unknown.chipText).size, 3)

            assertNotEquals("unknown 灯色不得等于 idle", unknown.lamp, idle.lamp)
            assertNotEquals("unknown chip 底不得等于 idle", unknown.chipBg, idle.chipBg)
            assertNotEquals("unknown 文案色不得等于 idle", unknown.chipText, idle.chipText)

            assertEquals(p.busyDot, runningDotColor(p, SessionStatus.Busy))
            assertEquals(p.idleChipText, runningDotColor(p, SessionStatus.Idle))
            assertEquals(p.unknownDot, runningDotColor(p, SessionStatus.Unknown))
            assertNotEquals(
                runningDotColor(p, SessionStatus.Unknown),
                runningDotColor(p, SessionStatus.Idle),
            )
        }

        // Busy / Idle 数值对照设计包源文件，证明落位没改色。
        assertEquals(Color(0xFF12A594), LightPalette.busyDot)
        assertEquals(Color(0xFF5F6980), LightPalette.idleChipText)
        assertEquals(Color(0xFF4FD1C0), DarkPalette.busyDot)
        assertEquals(Color(0xFF8497B8), DarkPalette.idleChipText)
        // Unknown 红灯取自设计调色板已有红（浅：ANSI red / 深：keycapDangerText）。
        assertEquals(Color(0xFFC03A62), LightPalette.unknownDot)
        assertEquals(Color(0xFFB23A63), LightPalette.unknownChipText)
        assertEquals(Color(0xFFF0879F), DarkPalette.unknownDot)
        assertEquals(Color(0xFFF0879F), DarkPalette.unknownChipText)
        assertNotEquals(LightPalette.unknownDot, LightPalette.idleChipText)
        assertNotEquals(DarkPalette.unknownDot, DarkPalette.idleChipText)
    }

    @Test
    fun statusChipRendersThreeLabels() {
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                StatusChip(SessionStatus.Busy)
                StatusChip(SessionStatus.Idle)
                StatusChip(SessionStatus.Unknown)
            }
        }
        compose.onNodeWithText("进行中").assertExists()
        compose.onNodeWithText("空闲").assertExists()
        compose.onNodeWithText("未知").assertExists()
    }
}
