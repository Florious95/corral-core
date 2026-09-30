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

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MediaStore 展示名到 multipart 文件名的纯推导契约。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentNameTest {

    @Test
    fun missingExtension_isInferredFromMimeType() {
        assertEquals("海边照片.png", attachmentFileName("海边照片", "image/png"))
    }

    @Test
    fun chineseNameWithExtension_isPreserved() {
        assertEquals("周末 合影.JPG", attachmentFileName("周末 合影.JPG", "image/jpeg"))
    }

    @Test
    fun duplicateDisplayNames_remainStableAndKeepExtension() {
        // 唯一落盘由服务端时间戳 + O_EXCL 重试负责；客户端不得为重名牺牲真实展示名。
        val first = attachmentFileName("截图", "image/jpeg")
        val second = attachmentFileName("截图", "image/jpeg")
        assertEquals("截图.jpg", first)
        assertEquals(first, second)
    }

    @Test
    fun missingDisplayName_usesTypedFallback() {
        assertEquals("image.webp", attachmentFileName(null, "image/webp"))
    }
}
