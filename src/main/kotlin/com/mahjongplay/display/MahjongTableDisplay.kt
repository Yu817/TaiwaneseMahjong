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
                .filter { it.scoreboardTags.contains("taiwanese_mahjong_table") }
                .forEach { it.remove() }
        }
    }

    private fun displayLocation(base: Location): Location =
        Location(
            base.world,
            base.x,
            base.y + TABLE_ORIGIN_Y_OFFSET,
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
                .scale(TABLE_SCALE)
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
        // The source model spans -8..24 pixels around Minecraft's 8-pixel model pivot.
        // Scaling the two-block-wide model by 1.5 makes it cover the existing 3x3 table.
        const val TABLE_SCALE = 1.5f

        // Source Y coordinates are 0..10 around the 8-pixel pivot. At 1.5 scale the
        // model extends -0.75..0.1875 blocks from the display origin, so +0.75 places
        // the feet at center.blockY and the tabletop at approximately +0.9375.
        const val TABLE_ORIGIN_Y_OFFSET = 0.75f
        const val DEFAULT_YAW = 0f
    }
}
