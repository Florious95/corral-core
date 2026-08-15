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

package dev.agentmirror.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 五态徽章色板 token 单测（ui-redesign 交付物，018 §一.5 状态可视）。
 *
 * StateBadgeTest 锁的是语义侧（label/contentDescription 可辨）；本测试锁色板侧：
 * 深浅两套各自五态点色/容器色两两不同（无两态共色 = 颜色维度也可辨），且每态
 * 容器色 ≠ 内容色（同色即文字隐形，渲染侧最低可读保证）。
 * 纯 token 断言不渲染（androidx Color 是纯值类），无需 Robolectric。
 */
class StateTonesTest {

    private fun tones(set: StateTones) = listOf(set.working, set.blocked, set.done, set.idle, set.unknown)

    @Test
    fun lightTones_fiveStatesDistinct() {
        val list = tones(LightStateTones)
        assertEquals(5, list.map { it.dot }.toSet().size)
        assertEquals(5, list.map { it.container }.toSet().size)
    }

    @Test
    fun darkTones_fiveStatesDistinct() {
        val list = tones(DarkStateTones)
        assertEquals(5, list.map { it.dot }.toSet().size)
        assertEquals(5, list.map { it.container }.toSet().size)
    }

    @Test
    fun contentNeverEqualsContainer_bothThemes() {
        // 容器色 == 内容色会让徽章文字隐形（静默失效的视觉版），两套逐态排除。
        (tones(LightStateTones) + tones(DarkStateTones)).forEach { tone ->
            org.junit.Assert.assertNotEquals(tone.container, tone.content)
        }
    }
}
