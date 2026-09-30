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

package dev.agentmirror.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayViewportTest {

    @Test
    fun widerPanelRequestsMoreCols() {
        val narrow = OverlayViewport.colsFor(400f, 10f)
        val wide = OverlayViewport.colsFor(1200f, 10f)
        assertTrue("wide=$wide narrow=$narrow", wide > narrow)
        assertEquals(40, OverlayViewport.colsFor(400f, 10f))
        assertEquals(120, OverlayViewport.colsFor(1200f, 10f))
    }

    @Test
    fun tallerPanelRequestsMoreRows() {
        val short = OverlayViewport.rowsFor(240f, 20f)
        val tall = OverlayViewport.rowsFor(800f, 20f)
        assertTrue("tall=$tall short=$short", tall > short)
        assertEquals(12, OverlayViewport.rowsFor(240f, 20f))
        assertEquals(40, OverlayViewport.rowsFor(800f, 20f))
    }
}
