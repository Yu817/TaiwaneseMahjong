package com.mahjongplay.display

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.EntityType
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * One visual chair at a Mahjong table seat.
 *
 * The model is bundled in the project's own ItemsAdder namespace. The chair
 * is an ItemDisplay rather than an ItemsAdder furniture entity so a table can
 * own and repair all of its display entities in the same way as the table and
 * tile displays.
 */
class MahjongChairDisplay(
    private val location: Location,
    private val yaw: Float,
) {
    var entity: ItemDisplay? = null
        private set
    private var seatEntity: ArmorStand? = null

    fun spawn(): ItemDisplay {
        entity?.takeIf { it.isValid }?.let {
            normalizeOrientation(it)
            ensureSeatEntity()
            return it
        }
        entity?.remove()

        val display = location.world.spawnEntity(location.clone(), EntityType.ITEM_DISPLAY) as ItemDisplay
        display.isPersistent = true
        display.addScoreboardTag("taiwanese_mahjong_chair")
        display.setViewRange(1.0f)
        display.setVisibleByDefault(true)
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE)
        display.setItemStack(createChairItem())
        normalizeOrientation(display)

        entity = display
        ensureSeatEntity()
        return display
    }

    fun isAvailableFor(playerUUID: UUID): Boolean {
        val occupant = seatEntity?.passengers?.firstOrNull { it is Player } ?: return true
        return occupant.uniqueId == playerUUID
    }

    fun isOccupiedBy(playerUUID: UUID): Boolean =
        seatEntity?.passengers?.any { it is Player && it.uniqueId == playerUUID } == true

    fun sit(player: Player): Boolean {
        val seat = ensureSeatEntity()
        val occupant = seat.passengers.firstOrNull { it is Player }
        if (occupant != null && occupant.uniqueId != player.uniqueId) return false
        if (occupant?.uniqueId == player.uniqueId) {
            return true
        }

        player.leaveVehicle()
        player.setRotation(yaw, 0f)
        return seat.addPassenger(player)
    }

    fun releasePlayer(playerUUID: UUID) {
        seatEntity?.passengers
            ?.filter { it.uniqueId == playerUUID }
            ?.forEach {
                it.leaveVehicle()
                seatEntity?.removePassenger(it)
            }
    }

    fun releaseAllPassengers() {
        seatEntity?.passengers?.toList()?.forEach {
            it.leaveVehicle()
            seatEntity?.removePassenger(it)
        }
    }

    fun remove() {
        releaseAllPassengers()
        seatEntity?.remove()
        seatEntity = null
        entity?.remove()
        entity = null
    }

    private fun normalizeOrientation(display: ItemDisplay) {
        display.setRotation(yaw, 0f)
    }

    private fun ensureSeatEntity(): ArmorStand {
        seatEntity?.takeIf { it.isValid }?.let {
            it.teleport(seatLocation())
            it.setRotation(yaw, 0f)
            return it
        }
        seatEntity?.remove()

        val seat = location.world.spawnEntity(seatLocation(), EntityType.ARMOR_STAND) as ArmorStand
        seat.isPersistent = false
        seat.isInvisible = true
        seat.isInvulnerable = true
        seat.isSilent = true
        seat.isCollidable = false
        seat.setGravity(false)
        seat.setMarker(true)
        seat.setBasePlate(false)
        seat.setArms(false)
        seat.setRotation(yaw, 0f)
        seat.addScoreboardTag("taiwanese_mahjong_seat")
        seatEntity = seat
        return seat
    }

    private fun seatLocation(): Location = location.clone().add(0.0, SEAT_Y_OFFSET, 0.0)

    @Suppress("UnstableApiUsage")
    private fun createChairItem(): ItemStack {
        val item = ItemStack(Material.PAPER)
        item.setData(
            DataComponentTypes.CUSTOM_MODEL_DATA,
            CustomModelData.customModelData()
                .addFloat(MahjongModelData.CHAIR.toFloat())
                .build()
        )
        // Keep the legacy integer component for clients routed through
        // ViaVersion/older paper model override formats.
        val meta = item.itemMeta
        meta.setCustomModelData(MahjongModelData.CHAIR)
        item.itemMeta = meta
        return item
    }

    companion object {
        // Matches the table model's item-pivot convention and places the
        // imported chair's seat close to the top of the invisible support block.
        const val ORIGIN_Y_OFFSET = 0.75
        const val SEAT_Y_OFFSET = 0.35
    }
}
