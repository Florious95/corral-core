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

import kotlin.math.max
import kotlin.math.min

/**
 * 框线 U+2500–257F 与块元素 U+2580–259F 的几何，不用字形。
 *
 * 根因：SYSTEM_FALLBACK 走 [TermSurfaceView] 的 drawCentered，依赖字形自然宽度
 * 再亚像素取整，非整数密度上相邻格接不上。本对象按**整数像素格边界**吐矩形。
 */
internal object BoxBlockGeometry {

    fun handles(cp: Int): Boolean =
        cp in 0x2500..0x2570 || cp in 0x2574..0x259F

    data class IRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    fun cellBox(originX: Int, originY: Int, cellW: Int, cellH: Int): IRect =
        IRect(originX, originY, originX + cellW, originY + cellH)

    /**
     * 本格要画的填充矩形（已是 view 坐标，格边界整数对齐）。
     * [alpha] 仅 ░▒▓ 使用，默认不透明。
     */
    data class Fill(val rect: IRect, val alpha: Int = 255)

    fun fills(cp: Int, originX: Int, originY: Int, cellW: Int, cellH: Int): List<Fill> {
        val local = localFills(cp, cellW, cellH)
        return local.map { (r, a) ->
            Fill(
                IRect(originX + r.left, originY + r.top, originX + r.right, originY + r.bottom),
                a,
            )
        }
    }

    private fun localFills(cp: Int, cellW: Int, cellH: Int): List<Pair<IRect, Int>> {
        if (cp in 0x2580..0x259F) return blockFills(cp, cellW, cellH)
        return boxFills(cp, cellW, cellH)
    }

    private fun blockFills(cp: Int, w: Int, h: Int): List<Pair<IRect, Int>> {
        fun frac(n: Int, d: Int, size: Int): Int = size * n / d
        fun box(l: Int, t: Int, r: Int, b: Int, a: Int = 255) =
            IRect(l, t, r.coerceAtLeast(l + 1).coerceAtMost(w), b.coerceAtLeast(t + 1).coerceAtMost(h)) to a
        return when (cp) {
            0x2580 -> listOf(box(0, 0, w, frac(1, 2, h))) // ▀
            0x2581 -> listOf(box(0, frac(7, 8, h), w, h)) // ▁
            0x2582 -> listOf(box(0, frac(3, 4, h), w, h))
            0x2583 -> listOf(box(0, frac(5, 8, h), w, h))
            0x2584 -> listOf(box(0, frac(1, 2, h), w, h)) // ▄
            0x2585 -> listOf(box(0, frac(3, 8, h), w, h))
            0x2586 -> listOf(box(0, frac(1, 4, h), w, h))
            0x2587 -> listOf(box(0, frac(1, 8, h), w, h))
            0x2588 -> listOf(box(0, 0, w, h)) // █
            0x2589 -> listOf(box(0, 0, frac(7, 8, w), h))
            0x258A -> listOf(box(0, 0, frac(3, 4, w), h))
            0x258B -> listOf(box(0, 0, frac(5, 8, w), h))
            0x258C -> listOf(box(0, 0, frac(1, 2, w), h)) // ▌
            0x258D -> listOf(box(0, 0, frac(3, 8, w), h))
            0x258E -> listOf(box(0, 0, frac(1, 4, w), h))
            0x258F -> listOf(box(0, 0, frac(1, 8, w), h))
            0x2590 -> listOf(box(frac(1, 2, w), 0, w, h)) // ▐
            0x2591 -> listOf(box(0, 0, w, h, 64)) // ░
            0x2592 -> listOf(box(0, 0, w, h, 128))
            0x2593 -> listOf(box(0, 0, w, h, 192))
            0x2594 -> listOf(box(0, 0, w, frac(1, 8, h)))
            0x2595 -> listOf(box(frac(7, 8, w), 0, w, h))
            0x2596 -> listOf(box(0, frac(1, 2, h), frac(1, 2, w), h)) // ▖
            0x2597 -> listOf(box(frac(1, 2, w), frac(1, 2, h), w, h))
            0x2598 -> listOf(box(0, 0, frac(1, 2, w), frac(1, 2, h)))
            0x2599 -> listOf( // ▙
                box(0, 0, frac(1, 2, w), h),
                box(frac(1, 2, w), frac(1, 2, h), w, h),
            )
            0x259A -> listOf( // ▚
                box(0, 0, frac(1, 2, w), frac(1, 2, h)),
                box(frac(1, 2, w), frac(1, 2, h), w, h),
            )
            0x259B -> listOf(
                box(0, 0, w, frac(1, 2, h)),
                box(0, frac(1, 2, h), frac(1, 2, w), h),
            )
            0x259C -> listOf(
                box(0, 0, w, frac(1, 2, h)),
                box(frac(1, 2, w), frac(1, 2, h), w, h),
            )
            0x259D -> listOf(box(frac(1, 2, w), 0, w, frac(1, 2, h)))
            0x259E -> listOf(
                box(frac(1, 2, w), 0, w, frac(1, 2, h)),
                box(0, frac(1, 2, h), frac(1, 2, w), h),
            )
            0x259F -> listOf(
                box(frac(1, 2, w), 0, w, h),
                box(0, frac(1, 2, h), frac(1, 2, w), h),
            )
            else -> listOf(box(0, 0, w, h))
        }
    }

    /** 0=无 1=细 2=粗 3=双线 */
    private fun boxFills(cp: Int, w: Int, h: Int): List<Pair<IRect, Int>> {
        val spec = boxSpec(cp)
        val light = max(1, min(w, h) / 8)
        val heavy = max(light + 1, light * 2)
        val out = ArrayList<Pair<IRect, Int>>(6)
        fun thick(kind: Int) = when (kind) {
            2 -> heavy
            3 -> light
            else -> light
        }
        val midX = w / 2
        val midY = h / 2
        val l = spec[0]
        val r = spec[1]
        val u = spec[2]
        val d = spec[3]
        if (l != 0 || r != 0) {
            val kind = max(l, r)
            val th = thick(kind)
            val x0 = if (l != 0) 0 else (midX - th / 2).coerceAtLeast(0)
            val x1 = if (r != 0) w else (midX + th / 2).coerceAtMost(w)
            if (kind == 3) {
                val gap = light
                out += IRect(x0, (midY - gap - th).coerceAtLeast(0), x1, (midY - gap).coerceAtLeast(th)) to 255
                out += IRect(x0, (midY + gap).coerceAtMost(h - th), x1, (midY + gap + th).coerceAtMost(h)) to 255
            } else {
                val top = (midY - th / 2).coerceAtLeast(0)
                out += IRect(x0, top, x1, (top + th).coerceAtMost(h)) to 255
            }
        }
        if (u != 0 || d != 0) {
            val kind = max(u, d)
            val th = thick(kind)
            val y0 = if (u != 0) 0 else (midY - th / 2).coerceAtLeast(0)
            val y1 = if (d != 0) h else (midY + th / 2).coerceAtMost(h)
            if (kind == 3) {
                val gap = light
                out += IRect((midX - gap - th).coerceAtLeast(0), y0, (midX - gap).coerceAtLeast(th), y1) to 255
                out += IRect((midX + gap).coerceAtMost(w - th), y0, (midX + gap + th).coerceAtMost(w), y1) to 255
            } else {
                val left = (midX - th / 2).coerceAtLeast(0)
                out += IRect(left, y0, (left + th).coerceAtMost(w), y1) to 255
            }
        }
        return out
    }

    private fun boxSpec(cp: Int): IntArray {
        // [L, R, U, D]
        return when (cp) {
            0x2500, 0x2504, 0x2508, 0x254C -> ia(1, 1, 0, 0)
            0x2501, 0x2505, 0x2509, 0x254D -> ia(2, 2, 0, 0)
            0x2502, 0x2506, 0x250A, 0x254E -> ia(0, 0, 1, 1)
            0x2503, 0x2507, 0x250B, 0x254F -> ia(0, 0, 2, 2)
            0x250C, 0x250D, 0x250E, 0x256D -> ia(0, 1, 0, 1)
            0x250F -> ia(0, 2, 0, 2)
            0x2510, 0x2511, 0x2512, 0x256E -> ia(1, 0, 0, 1)
            0x2513 -> ia(2, 0, 0, 2)
            0x2514, 0x2515, 0x2516, 0x256F -> ia(0, 1, 1, 0)
            0x2517 -> ia(0, 2, 2, 0)
            0x2518, 0x2519, 0x251A, 0x2570 -> ia(1, 0, 1, 0)
            0x251B -> ia(2, 0, 2, 0)
            0x251C, 0x251D, 0x251E, 0x251F, 0x2522 -> ia(0, 1, 1, 1)
            0x2520, 0x2521, 0x2523 -> ia(0, 2, 2, 2)
            0x2524, 0x2525, 0x2526, 0x2527, 0x252A -> ia(1, 0, 1, 1)
            0x2528, 0x2529, 0x252B -> ia(2, 0, 2, 2)
            0x252C, 0x252D, 0x252E, 0x252F, 0x2532 -> ia(1, 1, 0, 1)
            0x2530, 0x2531, 0x2533 -> ia(2, 2, 0, 2)
            0x2534, 0x2535, 0x2536, 0x2537, 0x253A -> ia(1, 1, 1, 0)
            0x2538, 0x2539, 0x253B -> ia(2, 2, 2, 0)
            0x253C, 0x253D, 0x253E, 0x253F, 0x2540, 0x2541, 0x2542 -> ia(1, 1, 1, 1)
            0x2543, 0x2544, 0x2545, 0x2546, 0x2547, 0x2548, 0x2549, 0x254A, 0x254B -> ia(2, 2, 2, 2)
            0x2550 -> ia(3, 3, 0, 0)
            0x2551 -> ia(0, 0, 3, 3)
            0x2552, 0x2553 -> ia(0, 3, 0, 1)
            0x2554 -> ia(0, 3, 0, 3)
            0x2555, 0x2556 -> ia(3, 0, 0, 1)
            0x2557 -> ia(3, 0, 0, 3)
            0x2558, 0x2559 -> ia(0, 3, 1, 0)
            0x255A -> ia(0, 3, 3, 0)
            0x255B, 0x255C -> ia(3, 0, 1, 0)
            0x255D -> ia(3, 0, 3, 0)
            0x255E, 0x255F -> ia(0, 3, 1, 1)
            0x2560 -> ia(0, 3, 3, 3)
            0x2561, 0x2562 -> ia(3, 0, 1, 1)
            0x2563 -> ia(3, 0, 3, 3)
            0x2564, 0x2565 -> ia(3, 3, 0, 1)
            0x2566 -> ia(3, 3, 0, 3)
            0x2567, 0x2568 -> ia(3, 3, 1, 0)
            0x2569 -> ia(3, 3, 3, 0)
            0x256A, 0x256B -> ia(3, 3, 1, 1)
            0x256C -> ia(3, 3, 3, 3)
            0x2574 -> ia(1, 0, 0, 0)
            0x2575 -> ia(0, 0, 1, 0)
            0x2576 -> ia(0, 1, 0, 0)
            0x2577 -> ia(0, 0, 0, 1)
            0x2578 -> ia(2, 0, 0, 0)
            0x2579 -> ia(0, 0, 2, 0)
            0x257A -> ia(0, 2, 0, 0)
            0x257B -> ia(0, 0, 0, 2)
            0x257C -> ia(1, 2, 0, 0)
            0x257D -> ia(0, 0, 1, 2)
            0x257E -> ia(2, 1, 0, 0)
            0x257F -> ia(0, 0, 2, 1)
            else -> ia(1, 1, 1, 1)
        }
    }

    private fun ia(l: Int, r: Int, u: Int, d: Int) = intArrayOf(l, r, u, d)
}
