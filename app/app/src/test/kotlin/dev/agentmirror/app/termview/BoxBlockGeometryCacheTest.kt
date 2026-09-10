/* Copyright 2026 AgentMirror Project Authors. Licensed under Apache-2.0. */
package dev.agentmirror.app.termview

import org.junit.Test

class BoxBlockGeometryCacheTest {
    @Test
    fun allSupportedGeometryMatchesOriginalAtTranslatedOrigins() {
        val cache = BoxBlockGeometryCache()
        for (w in listOf(1, 2, 3, 7, 8, 13, 19, 23, 32, 41)) {
            for (h in listOf(1, 2, 5, 8, 17, 28, 37, 64)) {
                for (cp in 0x2500..0x259F) {
                    if (!BoxBlockGeometry.handles(cp)) continue
                    val plan = cache.get(cp, w, h)
                    for ((x, y) in listOf(0 to 0, 4 to 13, 997 to 2049, -7 to -19)) {
                        val expected = BoxBlockGeometry.fills(cp, x, y, w, h)
                        val actual = plan.fills.map { fill ->
                            val r = fill.rect
                            BoxBlockGeometry.Fill(BoxBlockGeometry.IRect(
                                x + r.left, y + r.top, x + r.right, y + r.bottom,
                            ), fill.alpha)
                        }
                        check(actual == expected) { "fill cp=$cp size=$w,$h origin=$x,$y" }
                        val corner = plan.corner?.let {
                            it.copy(centerX = x + it.centerX, centerY = y + it.centerY,
                                horizontalEndX = x + it.horizontalEndX, verticalEndY = y + it.verticalEndY)
                        }
                        check(corner == BoxBlockGeometry.roundedCorner(cp, x, y, w, h)) {
                            "corner cp=$cp size=$w,$h origin=$x,$y"
                        }
                    }
                }
            }
        }
    }

    @Test
    fun steadyFramesReusePlansWithoutRebuilding() {
        val cache = BoxBlockGeometryCache()
        val cps = (0x2500..0x259F).filter(BoxBlockGeometry::handles)
        val plans = cps.map { cache.get(it, 19, 37) }
        val initial = cache.buildCount
        repeat(1000) {
            cps.forEachIndexed { i, cp -> check(cache.get(cp, 19, 37) === plans[i]) }
        }
        check(initial == cps.size && cache.buildCount == initial)
    }

    @Test
    fun dimensionsAndExplicitClearInvalidateButOldPlansStayImmutable() {
        val cache = BoxBlockGeometryCache()
        val first = cache.get(0x256D, 19, 37)
        val oldCorner = first.corner!!.copy()
        val second = cache.get(0x256D, 23, 41)
        check(first !== second && first.corner == oldCorner)
        val restored = cache.get(0x256D, 19, 37)
        check(restored !== first && restored == first)
        cache.clear()
        check(cache.get(0x256D, 19, 37) !== restored)
        check(cache.buildCount == 4)
    }

    @Test
    fun shadeAlphaAndRoundedTinyCellsArePreserved() {
        val cache = BoxBlockGeometryCache()
        for ((cp, alpha) in listOf(0x2591 to 64, 0x2592 to 128, 0x2593 to 192)) {
            check(cache.get(cp, 19, 37).fills.single().alpha == alpha)
        }
        for (cp in 0x256D..0x2570) {
            val p = cache.get(cp, 1, 1)
            check(p.fills.isEmpty() && p.corner != null && p.corner.radius == 0f)
        }
    }
}
