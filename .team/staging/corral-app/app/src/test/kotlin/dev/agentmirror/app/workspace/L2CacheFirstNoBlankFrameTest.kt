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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 062 §四：有缓存时进入二级，从进入到首帧有内容之间，帧序列不得含空列表。
 */
class L2CacheFirstNoBlankFrameTest {

    @Test
    fun reenterWithCacheNeverEmitsEmptyList() {
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
        vm.onFrame(level2("/proj/a", 1, "old-a", "old-b"))
        assertTrue("先有内容才能叫有缓存", vm.level2.value.sessions.isNotEmpty())

        vm.leaveLevel2()
        val fromReenter = sizes.size
        vm.enterLevel2("/proj/a")

        assertTrue(
            "有缓存再进必须立刻有内容，got=${vm.level2.value.sessions.map { it.ref }}",
            vm.level2.value.sessions.isNotEmpty(),
        )
        val afterEnter = sizes.drop(fromReenter)
        assertFalse(
            "有缓存时进入不得出现空列表帧，序列=$afterEnter 全序列=$sizes",
            afterEnter.contains(0),
        )
        assertTrue(vm.level2.value.sessions.any { it.ref == "old-a" })
        job.cancel()
    }

    private fun level2(ws: String, seq: Long, vararg refs: String) = Level2Frame(
        workspace = ws,
        seq = seq,
        sessions = refs.map { ref ->
            Session(ref = ref, name = ref, cwd = ws, rows = 24, cols = 80, status = "idle")
        },
    )
}
