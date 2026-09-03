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
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderAssetProvenanceTest {
    @Test
    fun bundledOfficialMarksMatchPinnedSha256() {
        val expected = mapOf(
            "provider-icons/claude-color.svg" to "a3101f3047a119aa11825ad9369510f0c472428c8c52d420e31bc62db44a8364",
            "provider-icons/codex-color.svg" to "4a2f43ce46b5b6e3722c95088f88d26ef91e6a8c2e598e70642a1c54367386e4",
            "provider-icons/copilot-color.svg" to "04f15d0556fbac10a4e0f82d1560610db8b33cd04780a4f24ad3b91c4abd278d",
            "provider-icons/cursor.svg" to "0cb51bddf264ae108926fd554c063ef40fc1aac3c5c921ddb39ad184e4e5d0ef",
            "provider-icons/grok.svg" to "9175fc90c22655160231976c849f25a03b888d7cc0e04c5f1b987b659bb07c95",
            "provider-icons/pi.svg" to "d82978781b824273c55473822c1f243a6ed34fc6e8c2dbfe1a90dfc66ae43ee8",
        )
        val ctx = RuntimeEnvironment.getApplication()
        val digest = MessageDigest.getInstance("SHA-256")
        CanonicalProviderMarks.all.forEach { mark ->
            val bytes = ctx.assets.open(mark.assetPath).use { it.readBytes() }
            val hex = digest.digest(bytes).joinToString("") { b -> "%02x".format(b) }
            digest.reset()
            assertEquals(mark.assetPath, expected[mark.assetPath], hex)
        }
    }
}
