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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * cwdDisplayName 纯函数单测（ui-redesign 交付物）。
 *
 * 018 §一.3 信息层级：列表行主标题 = 路径末段目录名（用户扫读认「项目名」），
 * 完整路径降为辅信息。本测试锁定末段提取的边界语义——错误的末段会让列表主信息误导
 * （例如尾随斜杠取出空串标题 = 行首空白，比撑爆更糟）。
 */
class WorkspacePathTest {

    @Test
    fun regularPath_takesLastSegment() {
        assertEquals("proj", cwdDisplayName("/home/alice/proj"))
        assertEquals("远程Agent安卓", cwdDisplayName("/Volumes/nvme/Projects/远程Agent安卓"))
    }

    @Test
    fun trailingSlash_doesNotYieldEmptyTitle() {
        // 尾随斜杠不产生空段：主标题绝不为空串。
        assertEquals("proj", cwdDisplayName("/home/alice/proj/"))
    }

    @Test
    fun rootAndEmpty_fallBackToRawInput() {
        // 根目录与空串无可取段 → 原样返回（不造假名，halt 精神：判不出不猜）。
        assertEquals("/", cwdDisplayName("/"))
        assertEquals("", cwdDisplayName(""))
    }

    @Test
    fun relativePath_stillTakesLastSegment() {
        // 服务端理论上只下发绝对路径；相对路径防御性兜底同语义。
        assertEquals("cwd", cwdDisplayName("e2e-l2/cwd"))
    }
}
