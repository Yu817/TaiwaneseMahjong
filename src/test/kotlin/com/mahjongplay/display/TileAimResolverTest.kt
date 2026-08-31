package com.mahjongplay.display

import org.bukkit.util.Vector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileAimResolverTest {
    @Test
    fun `hand hitboxes do not overlap adjacent tiles`() {
        assertTrue(TileConstants.INTERACTION_WIDTH < TileConstants.WIDTH + TileConstants.HAND_GAP)
    }

    @Test
    fun `ray selects the nearest tile center`() {
        val spacing = (TileConstants.WIDTH + TileConstants.HAND_GAP).toDouble()
        val centers = listOf(
            Vector(-spacing, 0.0, 2.0),
            Vector(0.0, 0.0, 2.0),
            Vector(spacing, 0.0, 2.0),
        )

        assertEquals(
            1,
            TileAimResolver.findOnHandPlane(
                origin = Vector(0.0, 0.0, 0.0),
                direction = Vector(0.0, 0.0, 1.0),
                planePoint = centers.first(),
                planeNormal = Vector(0.0, 0.0, 1.0),
                horizontalAxis = Vector(1.0, 0.0, 0.0),
                centers = centers,
                maxDistance = 8.0,
                tileHalfWidth = TileConstants.WIDTH / 2.0,
                verticalTolerance = 0.09,
            ),
        )
    }

    @Test
    fun `angled ray selects tile from its hand-plane intersection`() {
        val spacing = (TileConstants.WIDTH + TileConstants.HAND_GAP).toDouble()
        val centers = listOf(
            Vector(-spacing, 0.0, 2.0),
            Vector(0.0, 0.0, 2.0),
            Vector(spacing, 0.0, 2.0),
        )

        assertEquals(
            2,
            TileAimResolver.findOnHandPlane(
                origin = Vector(1.0, 0.0, 0.0),
                direction = Vector(spacing - 1.0, 0.0, 2.0),
                planePoint = centers.first(),
                planeNormal = Vector(0.0, 0.0, 1.0),
                horizontalAxis = Vector(1.0, 0.0, 0.0),
                centers = centers,
                maxDistance = 8.0,
                tileHalfWidth = TileConstants.WIDTH / 2.0,
                verticalTolerance = 0.09,
            ),
        )
    }

    @Test
    fun `gap between adjacent tiles is a dead zone`() {
        val spacing = (TileConstants.WIDTH + TileConstants.HAND_GAP).toDouble()
        val centers = listOf(
            Vector(0.0, 0.0, 2.0),
            Vector(spacing, 0.0, 2.0),
        )

        assertNull(
            TileAimResolver.findOnHandPlane(
                origin = Vector(spacing / 2.0, 0.0, 0.0),
                direction = Vector(0.0, 0.0, 1.0),
                planePoint = centers.first(),
                planeNormal = Vector(0.0, 0.0, 1.0),
                horizontalAxis = Vector(1.0, 0.0, 0.0),
                centers = centers,
                maxDistance = 8.0,
                tileHalfWidth = TileConstants.WIDTH / 2.0,
                verticalTolerance = 0.09,
            )
        )
    }

    @Test
    fun `upper part of a standing tile remains selectable`() {
        assertEquals(
            0,
            TileAimResolver.findOnHandPlane(
                origin = Vector(0.0, 0.07, 0.0),
                direction = Vector(0.0, 0.0, 1.0),
                planePoint = Vector(0.0, 0.0, 2.0),
                planeNormal = Vector(0.0, 0.0, 1.0),
                horizontalAxis = Vector(1.0, 0.0, 0.0),
                centers = listOf(Vector(0.0, 0.0, 2.0)),
                maxDistance = 8.0,
                tileHalfWidth = TileConstants.WIDTH / 2.0,
                verticalTolerance = 0.09,
            )
        )
    }

    @Test
    fun `ray parallel to hand plane selects nothing`() {
        assertNull(
            TileAimResolver.findOnHandPlane(
                origin = Vector(0.0, 0.0, 0.0),
                direction = Vector(1.0, 0.0, 0.0),
                planePoint = Vector(0.0, 0.0, 2.0),
                planeNormal = Vector(0.0, 0.0, 1.0),
                horizontalAxis = Vector(1.0, 0.0, 0.0),
                centers = listOf(Vector(0.0, 0.0, 2.0)),
                maxDistance = 8.0,
                tileHalfWidth = TileConstants.WIDTH / 2.0,
                verticalTolerance = 0.09,
            )
        )
    }

    @Test
    fun `hand plane beyond interaction range selects nothing`() {
        assertNull(
            TileAimResolver.findOnHandPlane(
                origin = Vector(0.0, 0.0, 0.0),
                direction = Vector(0.0, 0.0, 1.0),
                planePoint = Vector(0.0, 0.0, 9.0),
                planeNormal = Vector(0.0, 0.0, 1.0),
                horizontalAxis = Vector(1.0, 0.0, 0.0),
                centers = listOf(Vector(0.0, 0.0, 9.0)),
                maxDistance = 8.0,
                tileHalfWidth = TileConstants.WIDTH / 2.0,
                verticalTolerance = 0.09,
            )
        )
    }

    @Test
    fun `hover must remain stable before becoming selected`() {
        val stabilizer = AimStabilizer(stabilityMillis = 100L)

        assertNull(stabilizer.update(4, nowMillis = 1_000L))
        assertNull(stabilizer.update(4, nowMillis = 1_099L))
        assertEquals(4, stabilizer.update(4, nowMillis = 1_100L))
        assertNull(stabilizer.update(5, nowMillis = 1_101L))
    }
}
