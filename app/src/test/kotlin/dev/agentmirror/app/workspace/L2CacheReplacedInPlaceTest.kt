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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 062 §四：新数据到达后原地替换，不经过空态。
 */
class L2CacheReplacedInPlaceTest {

    @Test
    fun newFrameReplacesInPlaceWithoutEmpty() {
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
        )
        val sizes = mutableListOf<Int>()
        val refs = mutableListOf<List<String>>()
        val job = Job()
        CoroutineScope(Dispatchers.Unconfined + job).launch {
            vm.level2.collect {
                sizes.add(it.sessions.size)
                refs.add(it.sessions.map { s -> s.ref })
            }
        }

        vm.enterLevel2("/proj/a")
        vm.onFrame(level2("/proj/a", 1, "old-a", "old-b"))
        val fromReplace = sizes.size

        vm.onFrame(level2("/proj/a", 2, "new-a"))

        assertEquals(listOf("new-a"), vm.level2.value.sessions.map { it.ref })
        val after = sizes.drop(fromReplace)
        assertFalse(
            "原地替换不得经过空列表，sizes=$after refs=${refs.drop(fromReplace)}",
            after.contains(0),
        )
        assertTrue(after.isNotEmpty())
        assertEquals(listOf(listOf("new-a")), refs.drop(fromReplace))
        job.cancel()
    }

    @Test
    fun reenterThenNewFrameStillSkipsEmpty() {
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
        )
        val sizes = mutableListOf<Int>()
        val job = Job()
        CoroutineScope(Dispatchers.Unconfined + job).launch {
            vm.level2.collect { sizes.add(it.sessions.size) }
        }

        vm.enterLevel2("/proj/a")
        vm.onFrame(level2("/proj/a", 1, "old-a"))
        vm.leaveLevel2()
        val from = sizes.size
        vm.enterLevel2("/proj/a")
        vm.onFrame(level2("/proj/a", 2, "new-a", "new-b"))

        assertEquals(listOf("new-a", "new-b"), vm.level2.value.sessions.map { it.ref })
        assertFalse(
            "再进 + 新帧之间不得空，序列=${sizes.drop(from)}",
            sizes.drop(from).contains(0),
        )
        job.cancel()
    }

    private fun level2(ws: String, seq: Long, vararg refs: String) = Level2Frame(
        workspace = ws,
        seq = seq,
        sessions = refs.map { ref ->
            Session(ref = ref, name = ref, cwd = ws, rows = 24, cols = 80, status = "working")
        },
    )
}
