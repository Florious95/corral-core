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

import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.ui.model.SessionRowMotion
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.model.sessionRowMotion
import org.junit.Assert.assertEquals
import org.junit.Test

class PiWorkingDtoProjectionTest {
    @Test
    fun piWorkingNormalDtoProjectsToWorkingMotion() {
        val session = Session(
            ref = "r",
            name = "pi",
            cwd = "/w",
            rows = 24,
            cols = 80,
            provider = "pi",
            activity = "working",
            status = "working",
            health = "normal",
        )
        assertEquals("working", session.effectiveActivity)
        assertEquals("pi", session.provider)
        assertEquals("normal", session.health)
        val item = session.toL2Entry().toSessionItem(false)
        assertEquals("pi", item.provider)
        assertEquals(SessionStatus.Busy, item.status)
        assertEquals("normal", item.health)
        assertEquals(true, item.isOnline)
        assertEquals(
            SessionRowMotion.Working,
            sessionRowMotion(item.status, item.health, item.isOnline),
        )
    }
}
