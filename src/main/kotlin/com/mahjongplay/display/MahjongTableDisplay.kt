package com.mahjongplay.display

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.joml.AxisAngle4f
import org.joml.Matrix4f
import org.joml.Quaternionf

class MahjongTableDisplay(
    private val center: Location,
    var yaw: Float = DEFAULT_YAW,
    val scale: Float = DEFAULT_SCALE,
    private val ownershipTag: String? = null,
) {
    var entity: ItemDisplay? = null
        private set

    fun spawn(): ItemDisplay {
        entity?.takeIf { it.isValid }?.let {
            normalizeOrientation(it)
            return it
        }
        entity?.remove()

        val spawnLoc = displayLocation(center)
        if (spawnLoc.isChunkLoaded) {
            val existing = center.world.getNearbyEntities(spawnLoc, 1.0, 1.0, 1.0)
                .filterIsInstance<ItemDisplay>()
                .filter { it.scoreboardTags.contains("taiwanese_mahjong_table") }
            if (existing.isNotEmpty()) {
                val primary = existing.first()
                existing.drop(1).forEach { it.remove() }
                ownershipTag?.let(primary::addScoreboardTag)
                normalizeOrientation(primary)
                applyTransform(primary, yaw)
                entity = primary
                return primary
            }
        }

        val display = center.world.spawnEntity(spawnLoc, EntityType.ITEM_DISPLAY) as ItemDisplay
        // Keep the table entity across chunk saves.  The manager also repairs it
        // periodically because other server cleanup/reload routines may remove
        // display entities without touching the join text.
        display.isPersistent = true
        display.addScoreboardTag("taiwanese_mahjong_table")
        ownershipTag?.let(display::addScoreboardTag)
        display.setViewRange(1.0f)
        display.setVisibleByDefault(true)
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE)
        display.setItemStack(createTableItem())
        normalizeOrientation(display)
        applyTransform(display, yaw)

        entity = display
        return display
    }

    fun updateTransform(newCenter: Location = center, newYaw: Float = yaw) {
        yaw = newYaw
        entity?.let { display ->
            display.teleport(displayLocation(newCenter))
            normalizeOrientation(display)
            applyTransform(display, newYaw)
        }
    }

    /**
     * The command's center Location can retain the creator's camera pitch/yaw.
     * An ItemDisplay uses that entity rotation in addition to its transformation
     * matrix, which can make the otherwise-horizontal table appear tilted.
     */
    fun normalizeOrientation(display: ItemDisplay? = entity) {
        display?.setRotation(0f, 0f)
    }

    fun remove() {
        entity?.remove()
        entity = null
        val loc = displayLocation(center)
        if (loc.isChunkLoaded) {
            center.world.getNearbyEntities(loc, 1.0, 1.0, 1.0)
                .filterIsInstance<ItemDisplay>()
                .filter {
                    (ownershipTag?.let(it.scoreboardTags::contains) == true) ||
                        (it.scoreboardTags.contains("taiwanese_mahjong_table") &&
                            it.location.distanceSquared(loc) < 0.01)
                }
                .forEach { it.remove() }
        }
    }

    private fun displayLocation(base: Location): Location =
        Location(
            base.world,
            base.x,
            base.y + originYOffset(scale),
            base.z,
            0f,
            0f,
        )

    private fun applyTransform(display: ItemDisplay, displayYaw: Float) {
        val rotation = Quaternionf(
            AxisAngle4f(
                Math.toRadians(displayYaw.toDouble()).toFloat(),
                0f,
                1f,
                0f,
            )
        )
        display.setTransformationMatrix(
            Matrix4f()
                .rotate(rotation)
                .scale(scale)
        )
    }

    @Suppress("UnstableApiUsage")
    private fun createTableItem(): ItemStack {
        val item = ItemStack(Material.PAPER)
        item.setData(
            DataComponentTypes.CUSTOM_MODEL_DATA,
            CustomModelData.customModelData()
                .addFloat(MahjongModelData.TABLE.toFloat())
                .build()
        )
        // Keep the legacy integer component as well for clients routed through
        // ViaVersion/older paper model override formats.
        val meta = item.itemMeta
        meta.setCustomModelData(MahjongModelData.TABLE)
        item.itemMeta = meta
        return item
    }

    companion object {
        const val DEFAULT_SCALE = 1.8f
        const val DEFAULT_YAW = 0f

        fun originYOffset(scale: Float): Double = 0.5 * scale.toDouble()
        fun greenTopOffset(scale: Float): Double = 0.5625 * scale.toDouble()
    }
}
