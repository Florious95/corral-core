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

package dev.agentmirror.app.termview

import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧唤醒契约（fix-term-render-debt 缺陷①：增量流不唤醒渲染帧循环）。
 *
 * 真机实证：clear+printf 注入新内容后画面纹丝不动、swipe 无效、重 attach 才刷新。
 * 根因：postFrame 仅 presenter 注入与 damage 自续两处触发，WS 增量到达路径
 * （OkHttp 收件线程 → SessionViewModel.onBinary → emulator.feed → damageListener
 * → presenter 缓存脏区）终点无人唤醒 Choreographer。
 *
 * 契约：presenter 是渲染状态的唯一汇聚点，任何"需要重画"的状态变化必须触发
 * [TermViewPresenter.onFrameRequested]——数据到达才唤醒，空闲零帧循环（静默经济红线）。
 * 夹具用真实 pipe-pane 字节（隔离 tmux 采集的 clear+printf 序列，CR LF 行尾）。
 */
class TermViewFrameWakeTest {

    private class Harness {
        val emulator = TerminalEmulator(80, 24)
        val presenter = TermViewPresenter(emulator) { _, _ -> }
        var frameRequests = 0

        init {
            presenter.onFrameRequested = { frameRequests++ }
        }
    }

    /** 读真实 pipe-pane 增量夹具（缺失即测试基建损坏，立刻失败）。 */
    private fun deltaFixture(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/capture/delta.bin")) { "缺夹具资源 /capture/delta.bin" }
            .use { it.readBytes() }

    /** 缺陷①红测主体：真实增量字节注入后，damage 链路与帧请求都必须触发。 */
    @Test
    fun feedingRealDeltaBytesTriggersFrameRequest() {
        val h = Harness()
        h.emulator.feed(deltaFixture())
        // 内核 damage 链路本来就通（脏区确实缓存了）——断掉的是最后一跳唤醒。
        assertTrue("feed 后应有脏区", h.presenter.takeDamage().isNotEmpty())
        assertTrue("增量注入后必须请求帧（画面冻结根因）", h.frameRequests > 0)
    }

    /** swipe 无效的同根修复：滚动视口变化必须请求帧。 */
    @Test
    fun scrollingViewportTriggersFrameRequest() {
        val h = Harness()
        // 制造历史行，让滚动有意义。
        h.emulator.feed("a\r\nb\r\nc\r\n".repeat(20))
        h.frameRequests = 0
        h.presenter.onScrollBy(3)
        assertTrue("滚动锁定历史必须请求帧", h.frameRequests > 0)
        h.frameRequests = 0
        h.presenter.onScrollToBottom()
        assertTrue("回到底部必须请求帧", h.frameRequests > 0)
    }

    /** 静默经济红线：无数据、无手势时不许有任何自发帧请求（空闲零帧循环）。 */
    @Test
    fun idlePresenterMakesNoSpontaneousFrameRequests() {
        val h = Harness()
        h.emulator.feed(deltaFixture())
        h.presenter.takeDamage() // 排空一帧
        val after = h.frameRequests
        // 无新数据/无交互：帧请求数不许增长（数据到达才唤醒）。
        h.presenter.beginFrame()
        h.presenter.takeDamage()
        assertEquals(after, h.frameRequests)
    }
}
