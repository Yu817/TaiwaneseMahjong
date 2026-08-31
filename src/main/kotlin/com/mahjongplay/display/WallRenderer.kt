package com.mahjongplay.display

import com.mahjongplay.game.DrawSource
import com.mahjongplay.game.MahjongGame
import com.mahjongplay.game.TileDrawEvent
import com.mahjongplay.model.MahjongTile
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.ArrayDeque
import java.util.UUID

/** Destination for the brief concealed/owner-visible draw animation. */
data class DrawFlightTarget(
    val location: Location,
    val yaw: Float,
    val face: TileFace = TileFace.STANDING,
)

/**
 * Bukkit-side representation of the concealed wall.
 *
 * Game logic owns all tile identities and wall mutations. This class only
 * maps those mutations to stable display slots, so rendering cannot ever
 * create an extra tile or influence which tile is drawn.
 */
class WallRenderer(
    private val game: MahjongGame,
    private val tableCenter: Location,
    private val bottomTileY: Double,
    private val drawDestination: (TileDrawEvent) -> DrawFlightTarget,
    private val showToAllViewers: (MahjongTileDisplay) -> Unit,
    private val wallDistance: Double? = null,
    private val ownershipTag: String? = null,
) {
    companion object {
        /** Concealed wall tiles lie flat with their faces against the table. */
        internal val WALL_TILE_FACE = TileFace.FACE_DOWN
        /** Two wall layers are separated by the thickness of a flat tile. */
        internal val WALL_LAYER_HEIGHT = TileConstants.DEPTH.toDouble()
    }

    private var layout: WallLayout? = null
    private val liveSlots = ArrayDeque<Int>()
    private val supplementSlots = ArrayDeque<Int>()
    private val wallDisplays = mutableMapOf<Int, MahjongTileDisplay>()
    private val activeFlights = mutableMapOf<Long, List<MahjongTileDisplay>>()

    fun initialize(event: com.mahjongplay.game.WallInitializedEvent) {
        cancelAndClear()

        val radialOffset = wallDistance ?: WallLayout.squareRadialOffset(
            tileCount = event.tileCount,
            tileWidth = TileConstants.WIDTH.toDouble(),
        )
        val newLayout = WallLayout.create(
            tileCount = event.tileCount,
            centerX = tableCenter.x,
            centerY = bottomTileY,
            centerZ = tableCenter.z,
            // Keep perpendicular sides outside each other's span so the wall
            // is a square rather than two crossing lines.
            radialOffset = radialOffset,
            tileWidth = TileConstants.WIDTH.toDouble(),
            // A face-down wall is stacked by tile thickness, not the height
            // used by upright hand tiles.
            tileHeight = WALL_LAYER_HEIGHT,
        )
        layout = newLayout
        val orderedIds = event.openingDice?.let {
            newLayout.slotIdsFromOpening(it.dice, it.dealerSeatIndex)
        } ?: newLayout.slots.map { it.id }
        orderedIds.take(event.liveWallSize).forEach(liveSlots::addLast)
        val suppIds = orderedIds.drop(event.liveWallSize)
        suppIds.chunked(2).reversed().flatten().forEach(supplementSlots::addLast)

        newLayout.slots.forEach { slot ->
            val display = MahjongTileDisplay(
                slotLocation(slot), MahjongTile.UNKNOWN, WALL_TILE_FACE, slot.yaw,
                ownershipTag = ownershipTag,
            )
            display.spawn()
            showToAllViewers(display)
            wallDisplays[slot.id] = display
        }
    }

    fun beginDraw(event: TileDrawEvent) {
        val currentLayout = layout ?: return
        val sourceId = when (event.source) {
            DrawSource.LIVE_HEAD -> liveSlots.takeFirstOrNull()
            DrawSource.SUPPLEMENT_TAIL -> {
                val deadWallSlot = supplementSlots.takeFirstOrNull()
                if (deadWallSlot != null) {
                    // The rules move one tile from the live-wall tail into
                    // the head side of the dead wall after every supplement
                    // draw while live tiles remain. Keep that physical slot
                    // in the wall display and only remove the tile drawn from
                    // the dead-wall tail; the queue append preserves the next
                    // tail order without creating phantom question-mark tiles.
                    if (event.supplementRefilledFromLiveWall) {
                        takeSupplementFromLiveSlots()?.let(supplementSlots::addLast)
                    }
                    deadWallSlot
                } else {
                    takeSupplementFromLiveSlots()
                }
            }
        } ?: return
        val concealedFlight = wallDisplays.remove(sourceId) ?: return
        val sourceSlot = currentLayout.slot(sourceId) ?: return
        val sourceLocation = concealedFlight.entity?.location?.clone() ?: slotLocation(sourceSlot)

        val destination = drawDestination(event)
        val durationTicks = (event.animationMillis / 50L).toInt().coerceIn(0, 59)
        moveWithAnimation(concealedFlight, destination, durationTicks)
        showToAllViewers(concealedFlight)

        val owner = playerFor(event.playerUUID)
        val ownerFlight = owner?.let { player ->
            concealedFlight.hideTo(player)
            MahjongTileDisplay(
                sourceLocation, event.tile, WALL_TILE_FACE, sourceSlot.yaw,
                ownershipTag = ownershipTag,
            ).also { display ->
                display.spawn()
                display.showTo(player)
                moveWithAnimation(display, destination, durationTicks)
            }
        }

        activeFlights[event.sequence] = listOfNotNull(concealedFlight, ownerFlight)
    }

    fun completeDraw(event: TileDrawEvent) {
        activeFlights.remove(event.sequence)?.forEach(MahjongTileDisplay::remove)
    }

    fun isTrackedDisplay(entityId: UUID): Boolean =
        wallDisplays.values.any { it.entity?.uniqueId == entityId } ||
            activeFlights.values.flatten().any { it.entity?.uniqueId == entityId }

    fun showPublicDisplaysTo(player: Player) {
        wallDisplays.values.forEach { it.showTo(player) }
        // Flights are intentionally omitted.  They are transient and may
        // include an owner-only copy of the drawn tile; the next normal draw
        // or a fresh wall sync will reconcile them without leaking private
        // information to a joining spectator.
    }

    fun cancelAndClear() {
        wallDisplays.values.forEach(MahjongTileDisplay::remove)
        wallDisplays.clear()
        activeFlights.values.flatten().forEach(MahjongTileDisplay::remove)
        activeFlights.clear()
        liveSlots.clear()
        supplementSlots.clear()
        layout = null
    }

    private fun moveWithAnimation(display: MahjongTileDisplay, target: DrawFlightTarget, durationTicks: Int) {
        display.entity?.setTeleportDuration(durationTicks)
        display.updatePosition(target.location, target.yaw, target.face)
    }

    private fun moveImmediately(display: MahjongTileDisplay, location: Location, yaw: Float, face: TileFace) {
        display.entity?.setTeleportDuration(0)
        display.updatePosition(location, yaw, face)
    }

    private fun slotLocation(slot: WallSlot): Location =
        Location(tableCenter.world, slot.position.x, slot.position.y, slot.position.z)

    private fun playerFor(uuid: String): Player? = runCatching {
        Bukkit.getPlayer(UUID.fromString(uuid))
    }.getOrNull()

    private fun ArrayDeque<Int>.takeFirstOrNull(): Int? = if (isEmpty()) null else removeFirst()

    private fun takeSupplementFromLiveSlots(): Int? {
        if (liveSlots.isEmpty()) return null
        if (liveSlots.size == 1) return liveSlots.removeLast()
        val bottomSlotId = liveSlots.last()
        val topSlotId = liveSlots.elementAt(liveSlots.size - 2)
        val bottomSlot = layout?.slot(bottomSlotId)
        val topSlot = layout?.slot(topSlotId)
        return if (bottomSlot != null && topSlot != null &&
            bottomSlot.side == topSlot.side && bottomSlot.pier == topSlot.pier &&
            topSlot.layer == 1 && bottomSlot.layer == 0
        ) {
            val b = liveSlots.removeLast()
            val t = liveSlots.removeLast()
            liveSlots.addLast(b)
            t
        } else {
            liveSlots.removeLast()
        }
    }
}
