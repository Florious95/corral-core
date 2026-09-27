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

import android.content.Context
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.service.NoopTransportFactory
import dev.agentmirror.app.service.ServiceWire
import dev.agentmirror.app.termview.SharedPreferencesFontSizeStore
import dev.agentmirror.app.termview.SharedPreferencesViewportGeomStore
import dev.agentmirror.app.termview.ViewportGeom
import dev.agentmirror.app.termview.termTextSizePx
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 路由入口的 warm 几何判定：缓存与本次窗口/字号全等才在 RouteEnter 即订阅；任一条件
 * 不同（旋转/分屏、字体缩放、旧版缓存无窗口键）都退回布局后首订，绝不按错尺寸抢订。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionWarmGeometryRouteTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val vms = mutableListOf<SessionViewModel>()

    @Before
    fun reset() {
        ServiceWire.uiConnector = null
        ServiceWire.uploadBaseUrl = null
        ServiceWire.transportFactory = NoopTransportFactory
        ServiceWire.releaseManager()
        ServiceWire.resetConfigForTest()
        context.getSharedPreferences("term_viewport_geom", Context.MODE_PRIVATE).edit().clear().commit()
        ServiceWire.setConfig(ConnectionConfig("ws://127.0.0.1:9902/ws", "tok"))
    }

    @After
    fun teardown() {
        vms.forEach { it.dispose() }
        reset()
    }

    private fun seed(transform: (ViewportGeom) -> ViewportGeom = { it }) {
        val res = context.resources
        val fontSp = SharedPreferencesFontSizeStore.DEFAULT_FONT_SIZE_SP
        val exact = ViewportGeom(
            rows = 33,
            cols = 47,
            cellW = 19,
            cellH = 40,
            fontSizeSp = fontSp,
            viewW = 1000,
            viewH = 1400,
            densityDpi = res.displayMetrics.densityDpi,
            windowWidthDp = res.configuration.screenWidthDp,
            windowHeightDp = res.configuration.screenHeightDp,
            textSizePx = termTextSizePx(fontSp.toFloat(), res),
        )
        SharedPreferencesViewportGeomStore(context).save(transform(exact))
    }

    private fun open(): SessionViewModel = createSessionViewModel("s1", context)!!.also { vms += it }

    @Test
    fun exactCacheSubscribesAtRouteEnterWithCachedGeometry() {
        seed()
        val vm = open()
        assertEquals("RouteEnter 即订阅，不等布局", 33 to 47, ServiceWire.managerOrNull()!!.subscriptionSize("s1"))
        assertEquals(33, vm.emulator.rows)
        assertEquals(47, vm.emulator.cols)
    }

    @Test
    fun cacheFromAnotherWindowSizeDefersSubscribe() {
        seed { it.copy(windowWidthDp = it.windowWidthDp + 120) }
        open()
        assertNull("旋转/分屏后的旧几何不得抢订", ServiceWire.managerOrNull()!!.subscriptionSize("s1"))
    }

    @Test
    fun cacheFromAnotherTextScaleDefersSubscribe() {
        seed { it.copy(textSizePx = it.textSizePx * 1.15f) }
        open()
        assertNull("系统字体缩放变了，字格像素不同，不得抢订", ServiceWire.managerOrNull()!!.subscriptionSize("s1"))
    }

    @Test
    fun legacyCacheWithoutWindowKeysDefersSubscribe() {
        seed { it.copy(windowWidthDp = 0, windowHeightDp = 0, textSizePx = 0f) }
        open()
        assertNull(ServiceWire.managerOrNull()!!.subscriptionSize("s1"))
    }
}
