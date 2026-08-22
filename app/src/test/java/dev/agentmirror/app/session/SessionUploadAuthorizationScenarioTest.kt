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

import dev.agentmirror.app.pairing.PairingConfig
import dev.agentmirror.app.pairing.startPersistentConnection
import dev.agentmirror.app.service.NoopTransportFactory
import dev.agentmirror.app.service.ServiceWire
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** D-22 场景红测：已配对用户上传图片时，请求必须携带当前配对凭据。 */
class SessionUploadAuthorizationScenarioTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        ServiceWire.uiConnector = null
        ServiceWire.uploadBaseUrl = null
        ServiceWire.transportFactory = NoopTransportFactory
        ServiceWire.releaseManager()
        ServiceWire.resetConfigForTest()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        ServiceWire.uiConnector = null
        ServiceWire.uploadBaseUrl = null
        ServiceWire.releaseManager()
        ServiceWire.resetConfigForTest()
    }

    @Test
    fun pairedSession_uploadSucceedsWithConfiguredBearer() {
        val fakeToken = "fake-upload-credential"
        val sawConfiguredBearer = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val authorized = request.getHeader("Authorization") == "Bearer $fakeToken"
                sawConfiguredBearer.set(authorized)
                return if (authorized) {
                    MockResponse().setResponseCode(200).setBody("""{"path":"/host/uploads/photo.png"}""")
                } else {
                    MockResponse().setResponseCode(401)
                }
            }
        }

        // 从用户已完成配对的生产入口起步，覆盖配置 → 会话路由 → 上传请求整链。
        startPersistentConnection(
            PairingConfig(
                url = server.url("/ws").toString().replaceFirst("http", "ws"),
                token = fakeToken,
            ),
        )
        val viewModel = createSessionViewModel("session-1")

        assertNotNull("已配对会话必须可创建", viewModel)
        viewModel!!.uploadAttachment(
            Attachment(
                name = "photo.png",
                mimeType = "image/png",
                bytes = byteArrayOf(1, 2, 3),
            ),
        )

        assertTrue("上传请求必须携带当前配对的非空 Bearer", sawConfiguredBearer.get())
        assertTrue("鉴权配置完整时图片上传必须成功", viewModel.uploadStatus is UploadStatus.Success)
    }
}
