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

package dev.agentmirror.terminal

/**
 * 屏幕与滚回缓冲的最小显示单位：一格的文本（主字符+组合字符）、样式与占格宽度。
 *
 * 宽字符（CJK/emoji）占两格：首格 width=2 存字符，紧随一格放 width=0 的 [CONTINUATION]
 * 占位；渲染层遇 width=0 跳过即可。
 */
data class Cell(val text: String, val style: TextStyle, val width: Int) {
    companion object {
        /** 默认样式空白格单例。 */
        val BLANK = Cell(" ", TextStyle.DEFAULT, 1)

        /** 宽字符第二格的占位单例。 */
        val CONTINUATION = Cell("", TextStyle.DEFAULT, 0)

        /** 按给定样式生成空白格（默认样式时复用单例）。 */
        fun blank(style: TextStyle): Cell =
            if (style == TextStyle.DEFAULT) BLANK else Cell(" ", style, 1)
    }
}
