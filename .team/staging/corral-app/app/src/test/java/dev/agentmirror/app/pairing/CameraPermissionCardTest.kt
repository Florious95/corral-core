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

package dev.agentmirror.app.pairing

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 永久拒绝必须给出原因与可执行的系统设置出口，不能继续显示静默死按钮。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraPermissionCardTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun permanentlyDenied_showsReasonAndSettingsAction() {
        var openedSettings = false
        compose.setContent {
            MaterialTheme {
                NoPermissionCard(
                    state = CameraPermissionUiState.PermanentlyDenied,
                    onRequest = {},
                    onOpenSettings = { openedSettings = true },
                )
            }
        }

        compose.onNodeWithText("相机权限已被永久拒绝，请到系统设置中开启；也可改用下方手填连接。").assertExists()
        compose.onNodeWithText("打开系统设置").performClick()
        assertTrue(openedSettings)
    }

    @Test
    fun secondDenial_isClassifiedAsPermanent() {
        assertTrue(
            cameraPermissionUiState(
                granted = false,
                requested = true,
                shouldShowRationale = false,
            ) == CameraPermissionUiState.PermanentlyDenied,
        )
    }
}
