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

import dev.agentmirror.app.ui.components.CanonicalProviderMarks
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderAssetProvenanceTest {
    @Test
    fun noRuntimeSvgStackAndNoInventedDrawables() {
        CanonicalProviderMarks.all.forEach { mark ->
            assertNull(CanonicalProviderMarks.drawableRes(mark.id))
        }
        val ctx = RuntimeEnvironment.getApplication()
        val assets = ctx.assets.list("provider-icons")
        assertTrue(assets == null || assets.isEmpty())
        assertNull(javaClass.classLoader.getResource("com/caverock/androidsvg/SVG.class"))
    }
}
