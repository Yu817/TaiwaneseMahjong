package com.mahjongplay.display

import com.mahjongplay.game.OpeningDice
import kotlin.math.hypot

/**
 * Pure geometry for the concealed wall.  The renderer converts these points
 * into Bukkit Locations; keeping the layout Bukkit-free makes the 18-pier
 * rule directly testable.
 */
data class WallPoint(val x: Double, val y: Double, val z: Double) {
    fun horizontalDistanceTo(other: WallPoint): Double = hypot(x - other.x, z - other.z)
}

data class WallSlot(
    val id: Int,
    val side: Int,
    val pier: Int,
    val layer: Int,
    val position: WallPoint,
    val yaw: Float,
)

class WallLayout private constructor(
    val slots: List<WallSlot>,
    val pierCount: Int,
) {
    fun slot(id: Int): WallSlot? = slots.getOrNull(id)?.takeIf { it.id == id }

    fun liveSlotIds(liveTileCount: Int): List<Int> {
        require(liveTileCount in 0..slots.size) { "Live wall size must be inside the layout." }
        return slots.take(liveTileCount).map { it.id }
    }

    fun supplementSlotIds(liveTileCount: Int): List<Int> {
        require(liveTileCount in 0..slots.size) { "Live wall size must be inside the layout." }
        return slots.drop(liveTileCount).map { it.id }
    }

    /**
     * Rotate the physical wall so its first logical tile is the upper tile of
     * the pier immediately after the dice count.  The alternating pier order
     * follows each seated player's right-to-left view around one continuous
     * square wall.
     */
    fun slotIdsFromOpening(dice: OpeningDice, dealerSeatIndex: Int): List<Int> {
        val piersPerSide = pierCount / 4
        val selectedSide = dice.selectedWall(dealerSeatIndex)
        val perimeterPiers = buildList(pierCount) {
            repeat(4) { side ->
                for (pier in (piersPerSide - 1) downTo 0) {
                    add(side to pier)
                }
            }
        }
        val selectedRightEnd = perimeterPiers.indexOfFirst { it.first == selectedSide }
        val firstDrawIndex = (selectedRightEnd + dice.total) % perimeterPiers.size
        val rotatedPiers = List(perimeterPiers.size) { offset ->
            perimeterPiers[(firstDrawIndex + offset) % perimeterPiers.size]
        }
        val idsByPosition = slots.associateBy { Triple(it.side, it.pier, it.layer) }
        return rotatedPiers.flatMap { (side, pier) ->
            listOf(1, 0).map { layer ->
                requireNotNull(idsByPosition[Triple(side, pier, layer)]).id
            }
        }
    }

    companion object {
        /**
         * Center-line distance for a square wall whose adjacent sides meet at
         * their endpoints. Anything smaller makes the two side rows cross in
         * the middle, producing a # instead of a square.
         */
        fun squareRadialOffset(tileCount: Int, tileWidth: Double): Double {
            require(tileCount > 0 && tileCount % 8 == 0) { "Wall must have an equal double-layer side layout." }
            require(tileWidth > 0.0) { "Tile width must be positive." }
            return tileCount / 8 * tileWidth / 2.0
        }

        /**
         * Build a clockwise, four-sided wall.  A 144-tile Taiwanese wall has
         * 18 piers per side; disabled flowers naturally produce 17 piers per
         * side for the 136-tile variant.
         */
        fun create(
            tileCount: Int,
            centerX: Double,
            centerY: Double,
            centerZ: Double,
            radialOffset: Double,
            tileWidth: Double,
            tileHeight: Double,
        ): WallLayout {
            require(tileCount > 0 && tileCount % 8 == 0) { "Wall must have an equal double-layer side layout." }
            require(tileWidth > 0.0 && tileHeight > 0.0) { "Tile dimensions must be positive." }

            val piersPerSide = tileCount / 8
            val slots = buildList(tileCount) {
                var id = 0
                repeat(4) { side ->
                    repeat(piersPerSide) { pier ->
                        val offset = (piersPerSide - 1) * tileWidth / 2.0 - pier * tileWidth
                        val (x, z, yaw) = when (side) {
                            0 -> Triple(centerX + radialOffset, centerZ + offset, -90f)
                            1 -> Triple(centerX - offset, centerZ + radialOffset, 0f)
                            2 -> Triple(centerX - radialOffset, centerZ - offset, 90f)
                            else -> Triple(centerX + offset, centerZ - radialOffset, 180f)
                        }

                        // Top is consumed before bottom so a draw never makes
                        // the remaining lower tile float in mid-air.
                        add(WallSlot(id++, side, pier, 1, WallPoint(x, centerY + tileHeight, z), yaw))
                        add(WallSlot(id++, side, pier, 0, WallPoint(x, centerY, z), yaw))
                    }
                }
            }
            return WallLayout(slots, piersPerSide * 4)
        }
    }
}
