/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.agentmirror.app.termview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ViewportGeomStoreTest {

    @Test
    fun saveThenLoadRoundTrip() {
        val store = SharedPreferencesViewportGeomStore(RuntimeEnvironment.getApplication())
        val geom = ViewportGeom(
            rows = 41,
            cols = 52,
            cellW = 19,
            cellH = 32,
            fontSizeSp = 14,
            viewW = 1080,
            viewH = 1400,
            densityDpi = 480,
        )
        store.save(geom)
        assertEquals(geom, store.load())
    }

    @Test
    fun emptyStoreIsNull() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("term_viewport_geom", 0).edit().clear().commit()
        val store = SharedPreferencesViewportGeomStore(ctx)
        assertNull(store.load())
    }
}
