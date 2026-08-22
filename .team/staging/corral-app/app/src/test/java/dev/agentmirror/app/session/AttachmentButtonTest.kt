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

package dev.agentmirror.app.session

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 017 R-8：附件入口必须先展示拍照/相册分流，不能直接落入系统相册。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentButtonTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun plusMenu_exposesReachableCameraAction() {
        var cameraOpened = false
        compose.setContent {
            MaterialTheme {
                AttachmentButton(
                    enabled = true,
                    onPickImage = {},
                    onTakePhoto = { cameraOpened = true },
                )
            }
        }

        compose.onNodeWithContentDescription("添加图片附件").performClick()
        compose.onNodeWithText("拍照").assertExists().performClick()
        assertTrue(cameraOpened)
    }
}
