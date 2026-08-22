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

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * D-22 根因探针：生产上传器必须把调用方提供的非空认证信息带进 HTTP 请求。
 *
 * 用户场景层另测配对配置到会话 VM 的整链；本类只锁 HTTP 接缝的可观察行为，避免把测试
 * 绑定到 ServiceWire/ViewModel 的具体传参实现。测试不读取真实凭据，也不输出假凭据。
 */
class SessionUploadTokenChainTest {

    private lateinit var server: MockWebServer
    private val sawAcceptedBearer = AtomicBoolean(false)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val authorization = request.getHeader("Authorization")
                val carriesConfiguredBearer = authorization != null &&
                    authorization.startsWith("Bearer ") &&
                    authorization.removePrefix("Bearer ").isNotBlank() &&
                    authorization == "Bearer $FAKE_PAIRING_TOKEN"
                sawAcceptedBearer.set(carriesConfiguredBearer)
                return if (carriesConfiguredBearer) {
                    MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"path":"/host/uploads/probe.png"}""")
                } else {
                    MockResponse().setResponseCode(401)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun uploader_nonBlankCredential_isAcceptedByAuthenticatedEndpoint() {
        val result = HttpUrlConnectionUploader().upload(
            server.url("/").toString(),
            FAKE_PAIRING_TOKEN,
            attachment(),
        )

        assertTrue(
            "上传器必须把非空认证信息作为 Bearer 携带，令鉴权端点接受请求",
            sawAcceptedBearer.get(),
        )
        assertTrue("携带有效认证信息时上传必须成功", result is UploadOutcome.Success)
    }

    @Test
    fun uploader_blankCredential_remainsUnauthenticated() {
        val result = HttpUrlConnectionUploader().upload(
            server.url("/").toString(),
            "   ",
            attachment(),
        )

        assertFalse("空白认证信息不得伪造成可接受的 Bearer", sawAcceptedBearer.get())
        assertTrue("鉴权端点拒绝未认证上传时必须返回可见失败", result is UploadOutcome.Failure)
    }

    private fun attachment() = Attachment(
        name = "probe.png",
        mimeType = "image/png",
        bytes = byteArrayOf(1, 2, 3),
    )

    private companion object {
        const val FAKE_PAIRING_TOKEN = "fake-pairing-token"
    }
}
