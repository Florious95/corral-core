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

import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.diag.DiagLog
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 契约 096：二级停更横幅只说人话。
 *
 * 先验红：当前 [WorkspaceViewModel.checkLevel2Quiet] 把 ms / last_at / now / 主机路径
 * 原样拼进 [L2UiState.banner]。断言这些调试串不得上屏。
 */
class L2QuietBannerHumanizeTest {

    @Before
    fun setUp() {
        DiagLog.resetForTest()
        DiagLog.initialize(null)
    }

    @After
    fun tearDown() {
        DiagLog.resetForTest()
    }

    @Test
    fun quietBanner_omitsDebugOperandsAndHostPath() {
        var now = 1_000_000L
        val ws = "/Volumes/nvme/Projects/远程Agent安卓"
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            nowMs = { now },
        )
        vm.enterLevel2(ws)
        vm.onFrame(
            Level2Frame(
                workspace = ws,
                seq = 1,
                sessions = listOf(
                    Session(ref = "r1", name = "n", cwd = ws, rows = 24, cols = 80, status = "working"),
                ),
            ),
        )

        now += 1_374_029L
        vm.checkLevel2Quiet(now)

        val banner = vm.level2.value.banner
        assertNotNull("超时后必须有横幅", banner)
        val text = banner!!
        assertFalse("横幅不得含 ms（，got=$text", text.contains("ms（"))
        assertFalse("横幅不得含 last_at=，got=$text", text.contains("last_at="))
        assertFalse("横幅不得含 now=，got=$text", text.contains("now="))
        val leakedPath = HOST_PATH.find(text)
        assertNull("横幅不得含以 / 开头的主机路径，got=$text leak=${leakedPath?.value}", leakedPath)
        assertFalse("横幅不得回显 workspace cwd，got=$text", text.contains(ws))
        assertTrue("横幅必须说人话（未更新），got=$text", text.contains("未更新"))
        assertTrue("横幅必须说人话（正在重连），got=$text", text.contains("正在重连"))
        assertTrue("用户截图像素对应约 23 分钟，got=$text", text.contains("23 分钟"))

        val logs = DiagLog.snapshotForTest().joinToString("\n")
        assertTrue("调试参数必须进 DiagLog last_at=，logs=$logs", logs.contains("last_at="))
        assertTrue("调试参数必须进 DiagLog now=，logs=$logs", logs.contains("now="))
        assertTrue("调试参数必须进 DiagLog quiet_for_ms=1374029，logs=$logs", logs.contains("quiet_for_ms=1374029"))
        assertTrue("调试参数必须进 DiagLog timeout_ms=，logs=$logs", logs.contains("timeout_ms="))
        assertTrue("调试参数必须进 DiagLog workspace=，logs=$logs", logs.contains("workspace=$ws"))
    }

    @Test
    fun quietBanner_absentBeforeTimeout() {
        var now = 1_000_000L
        val ws = "/proj/a"
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
            nowMs = { now },
        )
        vm.enterLevel2(ws)
        vm.onFrame(
            Level2Frame(
                workspace = ws,
                seq = 1,
                sessions = listOf(
                    Session(ref = "r1", name = "n", cwd = ws, rows = 24, cols = 80, status = "idle"),
                ),
            ),
        )
        now += 19_999L
        vm.checkLevel2Quiet(now)
        assertNull("未超时不得出横幅", vm.level2.value.banner)
    }

    private companion object {
        val HOST_PATH = Regex("""(?<![A-Za-z0-9_])(/[A-Za-z0-9._-]+)""")
    }
}
