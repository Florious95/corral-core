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

package dev.agentmirror.app.workspace

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.agentmirror.app.conn.AgentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * StateBadge 接缝零测（test-app-android-seams 交付物；D9 实锤：纯 Composable 零测）。
 *
 * 覆盖知识基底 §0 第四类：每态**文案**可辨 + 语义映射双射（Compose rule，Robolectric JVM
 * 渲染不起模拟器）；顺带 R-7（017 当期裁定）——contentDescription 语义标注断言。
 *
 * 颜色断言说明：Robolectric 下 captureToImage（forceRedraw 截图）实测 2s 超时（探针
 * ProbeCaptureToImageTest 红），像素/背景色断言不可靠——故颜色可辨不在此做渲染截图，
 * 改由**色板映射表单测**：`StateBadgeStyle.of` 五值双射 + label 唯一 + contentDescription
 * 语义存在。色板色值（badgeColors，private 渲染侧单一事实源）经代码注释声明五态各异
 * （008 裁定），本测试锁定语义侧，两者合起来覆盖 D9。
 *
 * 基建：createComposeRule 需 Robolectric 环境，@RunWith(RobolectricTestRunner)
 * + @Config(sdk=[34])；@GraphicsMode(NATIVE) 支撑语义树渲染（LEGACY 亦可用，NATIVE 兜底）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StateBadgeTest {

    @get:Rule
    val compose = createComposeRule()

    /** 渲染单个徽章；每个测试只调一次 setContent（同一 rule 的 Activity 单次铁律）。 */
    private fun renderBadge(state: AgentState) {
        compose.setContent {
            MaterialTheme {
                BadgeHost(state)
            }
        }
        compose.waitForIdle()
    }

    /** 状态徽章宿主：包 MaterialTheme 外框，镜像真实用法（列表内渲染）。 */
    @Composable
    private fun BadgeHost(state: AgentState) {
        StateBadge(state = state)
    }

    // ---- 映射双射：四值闭集各得其样（008 语义源） ----

    @Test
    fun styleMapping_coversAllAgentStates() {
        // StateBadgeStyle.of 是 AgentState→Style 双射：四值一一对应，不遗漏不撞样。
        assertEquals(StateBadgeStyle.BLOCKED, StateBadgeStyle.of(AgentState.BLOCKED))
        assertEquals(StateBadgeStyle.WORKING, StateBadgeStyle.of(AgentState.WORKING))
        assertEquals(StateBadgeStyle.IDLE, StateBadgeStyle.of(AgentState.IDLE))
        assertEquals(StateBadgeStyle.UNKNOWN, StateBadgeStyle.of(AgentState.UNKNOWN))
    }

    @Test
    fun styleLabels_areAllDistinct() {
        // 四态 label 两两不同：纯文案也能区分状态（D9 每态文案可辨，不依赖颜色）。
        val labels = AgentState.entries.map { StateBadgeStyle.of(it).label }.toSet()
        assertEquals(4, labels.size)
    }

    @Test
    fun styles_areDistinctPerState() {
        // 集合级：四态得到四个不同 StateBadgeStyle（无两态共样）。
        val styles = AgentState.entries.map { StateBadgeStyle.of(it) }.toSet()
        assertEquals(4, styles.size)
        assertNotEquals(StateBadgeStyle.UNKNOWN, StateBadgeStyle.BLOCKED) // 占位防语义漂移
    }

    // ---- 每态：文案 + R-7 语义，逐一可辨 ----

    @Test
    fun badge_blocked_labelAndSemantics() {
        renderBadge(AgentState.BLOCKED)
        compose.onNodeWithText("需人").assertExists() // 文案
        compose.onNodeWithContentDescription("状态：需人").assertExists() // R-7 语义
    }

    @Test
    fun badge_working_labelAndSemantics() {
        renderBadge(AgentState.WORKING)
        compose.onNodeWithText("工作中").assertExists()
        compose.onNodeWithContentDescription("状态：工作中").assertExists()
    }

    @Test
    fun badge_idle_labelAndSemantics() {
        renderBadge(AgentState.IDLE)
        compose.onNodeWithText("空闲").assertExists()
        compose.onNodeWithContentDescription("状态：空闲").assertExists()
    }

    @Test
    fun badge_unknown_labelAndSemantics() {
        // unknown 一等公民：灰显、不报错（008 状态/镜像解耦），文案与语义照常可辨。
        renderBadge(AgentState.UNKNOWN)
        compose.onNodeWithText("未知").assertExists()
        compose.onNodeWithContentDescription("状态：未知").assertExists()
    }

    // ---- R-7 完整覆盖：五态语义标注都存在（017 当期裁定） ----

    @Test
    fun allStates_haveContentDescription() {
        // 单次渲染五徽章 Row（setContent 一次），逐一断言语义标注存在。
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Row {
                    AgentState.entries.forEach { state -> StateBadge(state = state) }
                }
            }
        }
        compose.waitForIdle()
        AgentState.entries.forEach { state ->
            compose.onNodeWithContentDescription("状态：${StateBadgeStyle.of(state).label}").assertExists()
        }
    }
}
