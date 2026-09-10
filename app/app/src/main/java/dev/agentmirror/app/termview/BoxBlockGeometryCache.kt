/*
 * Copyright 2026 AgentMirror Project Authors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.agentmirror.app.termview

/**
 * View-owned, main-thread cache of local (0,0) geometry, not pixels or colours.
 * At most 160 entries for one cell size; font/size changes discard old plans.
 * Painting still happens on every requested frame, in the original order.
 */
internal class BoxBlockGeometryCache {
    data class Plan(
        val fills: List<BoxBlockGeometry.Fill>,
        val corner: BoxBlockGeometry.RoundedCorner?,
    )

    private val plans = arrayOfNulls<Plan>(0x259F - 0x2500 + 1)
    private var width = -1
    private var height = -1
    internal var buildCount = 0
        private set

    fun get(cp: Int, cellW: Int, cellH: Int): Plan {
        require(BoxBlockGeometry.handles(cp))
        require(cellW > 0 && cellH > 0)
        if (width != cellW || height != cellH) {
            clear()
            width = cellW
            height = cellH
        }
        val index = cp - 0x2500
        plans[index]?.let { return it }
        val corner = BoxBlockGeometry.roundedCorner(cp, 0, 0, cellW, cellH)
        val plan = Plan(
            if (corner == null) BoxBlockGeometry.fills(cp, 0, 0, cellW, cellH) else emptyList(),
            corner,
        )
        plans[index] = plan
        buildCount++
        return plan
    }

    fun clear() {
        plans.fill(null)
        width = -1
        height = -1
    }
}
