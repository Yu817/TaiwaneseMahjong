package com.mahjongplay.display

import com.mahjongplay.game.OpeningDice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WallLayoutTest {
    @Test
    fun `opening starts after counted pier on the selected wall and keeps all slots unique`() {
        val layout = WallLayout.create(
            tileCount = 144,
            centerX = 0.0,
            centerY = 0.0,
            centerZ = 0.0,
            radialOffset = 10.0,
            tileWidth = 1.0,
            tileHeight = 2.0,
        )
        val dice = OpeningDice(listOf(2, 3, 5)) // total 10, dealer 0 selects side 3

        val ordered = layout.slotIdsFromOpening(dice, dealerSeatIndex = 0)

        assertEquals(144, ordered.size)
        assertEquals(144, ordered.toSet().size)
        val first = layout.slot(ordered.first())!!
        assertEquals(3, first.side)
        assertEquals(7, first.pier)
        assertEquals(1, first.layer)
        assertEquals(0, layout.slot(ordered[1])!!.layer)
    }

    @Test
    fun `144 tile wall has eighteen tightly joined double-layer piers on every side`() {
        val layout = WallLayout.create(
            tileCount = 144,
            centerX = 0.0,
            centerY = 0.0,
            centerZ = 0.0,
            radialOffset = 10.0,
            tileWidth = 1.0,
            tileHeight = 2.0,
        )

        assertEquals(72, layout.pierCount)
        assertEquals(144, layout.slots.size)

        (0..3).forEach { side ->
            val piers = layout.slots.filter { it.side == side && it.layer == 0 }
            assertEquals(18, piers.size)

            val centers = piers.sortedBy { it.pier }.map { it.position }
            centers.zipWithNext().forEach { (left, right) ->
                assertEquals(1.0, left.horizontalDistanceTo(right), 0.000001)
            }
        }

        layout.slots.groupBy { it.side to it.pier }.values.forEach { layers ->
            assertEquals(2, layers.size)
            val sorted = layers.sortedBy { it.layer }
            assertEquals(2.0, sorted[1].position.y - sorted[0].position.y, 0.000001)
        }
    }

    @Test
    fun `live head and supplement tail divide the wall without overlapping slots`() {
        val layout = WallLayout.create(
            tileCount = 144,
            centerX = 0.0,
            centerY = 0.0,
            centerZ = 0.0,
            radialOffset = 10.0,
            tileWidth = 1.0,
            tileHeight = 2.0,
        )

        val live = layout.liveSlotIds(liveTileCount = 128)
        val supplement = layout.supplementSlotIds(liveTileCount = 128)

        assertEquals(128, live.size)
        assertEquals(16, supplement.size)
        assertTrue(live.intersect(supplement.toSet()).isEmpty())
        assertEquals(0, live.first())
        assertEquals(143, supplement.last())
    }

    @Test
    fun `each wall side faces outward so its concealed face is visible from the table`() {
        val layout = WallLayout.create(
            tileCount = 144,
            centerX = 0.0,
            centerY = 0.0,
            centerZ = 0.0,
            radialOffset = 10.0,
            tileWidth = 1.0,
            tileHeight = 2.0,
        )

        assertEquals(-90f, layout.slots.first { it.side == 0 }.yaw) // east
        assertEquals(0f, layout.slots.first { it.side == 1 }.yaw) // south
        assertEquals(90f, layout.slots.first { it.side == 2 }.yaw) // west
        assertEquals(180f, layout.slots.first { it.side == 3 }.yaw) // north
    }

    @Test
    fun `physical wall is face down and stacked by tile thickness`() {
        assertEquals(TileFace.FACE_DOWN, WallRenderer.WALL_TILE_FACE)
        assertEquals(TileConstants.DEPTH.toDouble(), WallRenderer.WALL_LAYER_HEIGHT, 0.000001)
    }

    @Test
    fun `standard wall radius forms a square instead of crossing into a hash`() {
        val radialOffset = WallLayout.squareRadialOffset(tileCount = 144, tileWidth = 1.0)
        val layout = WallLayout.create(
            tileCount = 144,
            centerX = 0.0,
            centerY = 0.0,
            centerZ = 0.0,
            radialOffset = radialOffset,
            tileWidth = 1.0,
            tileHeight = 2.0,
        )

        assertEquals(9.0, radialOffset, 0.000001)
        (0..3).forEach { side ->
            assertEquals(36, layout.slots.count { it.side == side })
        }
        assertTrue(layout.slots.filter { it.side == 0 }.all { it.position.x == radialOffset })
        assertTrue(layout.slots.filter { it.side == 1 }.all { it.position.z == radialOffset })
        assertTrue(
            layout.slots.filter { it.side == 0 }.maxOf { it.position.z } < radialOffset,
            "East and south sides must not cross through each other.",
        )
    }

    @Test
    fun `live wall advances clockwise and supplement wall advances counter-clockwise from opening cut`() {
        val layout = WallLayout.create(
            tileCount = 144,
            centerX = 0.0,
            centerY = 0.0,
            centerZ = 0.0,
            radialOffset = 10.0,
            tileWidth = 1.0,
            tileHeight = 2.0,
        )
        val dice = OpeningDice(listOf(2, 3, 5)) // total 10, dealer 0 selects side 3
        val ordered = layout.slotIdsFromOpening(dice, dealerSeatIndex = 0)

        // Live head is at (side 3, pier 7, top layer)
        val firstLive = layout.slot(ordered.first())!!
        assertEquals(3, firstLive.side)
        assertEquals(7, firstLive.pier)
        assertEquals(1, firstLive.layer)

        // Tail right before opening cut is at (side 3, pier 8)
        val lastSupp = layout.slot(ordered.last())!!
        val secondToLastSupp = layout.slot(ordered[ordered.size - 2])!!
        assertEquals(3, lastSupp.side)
        assertEquals(8, lastSupp.pier)
        assertEquals(0, lastSupp.layer)
        assertEquals(1, secondToLastSupp.layer)

        // Supplement slots are ordered from the cut backwards (counter-clockwise):
        // Pier 8 top & bottom, then Pier 9 top & bottom, then Pier 10 top & bottom...
        val suppIds = ordered.drop(128).chunked(2).reversed().flatten()
        val firstSupp = layout.slot(suppIds[0])!!
        val secondSupp = layout.slot(suppIds[1])!!
        assertEquals(3, firstSupp.side)
        assertEquals(8, firstSupp.pier)
        assertEquals(1, firstSupp.layer)
        assertEquals(0, secondSupp.layer)

        val thirdSupp = layout.slot(suppIds[2])!!
        assertEquals(3, thirdSupp.side)
        assertEquals(9, thirdSupp.pier)
        assertEquals(1, thirdSupp.layer)
    }
}
