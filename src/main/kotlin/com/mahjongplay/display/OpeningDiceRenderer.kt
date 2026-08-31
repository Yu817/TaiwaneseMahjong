package com.mahjongplay.display

import com.mahjongplay.MahjongPlayPlugin
import com.mahjongplay.game.OpeningDiceEvent
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.EntityType
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import org.joml.AxisAngle4f
import org.joml.Matrix4f
import org.joml.Quaternionf
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Three small physical dice that roll at the centre before the deal starts. */
class OpeningDiceRenderer(
    private val center: Location,
    private val surfaceY: () -> Double,
    private val ownershipTag: String? = null,
) {
    private val displays = mutableListOf<ItemDisplay>()
    private var animationTask: BukkitTask? = null

    fun begin(event: OpeningDiceEvent) {
        cancelAndClear()
        val durationTicks = (event.animationMillis / 50L).toInt().coerceAtLeast(1)
        event.dice.values.forEachIndexed { index, _ ->
            val angle = index * (2.0 * PI / 3.0) - PI / 2.0
            val location = center.clone().apply {
                x += kotlin.math.cos(angle) * 0.16
                z += kotlin.math.sin(angle) * 0.16
                y = surfaceY() + 0.13
            }
            val display = center.world.spawnEntity(location, EntityType.ITEM_DISPLAY) as ItemDisplay
            display.isPersistent = false
            ownershipTag?.let(display::addScoreboardTag)
            display.setViewRange(1.0f)
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE)
            display.setItemStack(createDiceItem())
            displays += display
        }

        center.world.playSound(center, Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, SoundCategory.BLOCKS, 0.9f, 1.35f)
        var tick = 0
        animationTask = Bukkit.getScheduler().runTaskTimer(
            MahjongPlayPlugin.instance,
            Runnable {
                val progress = (tick.toFloat() / durationTicks).coerceIn(0f, 1f)
                displays.forEachIndexed { index, display ->
                    if (progress < SETTLE_PROGRESS) {
                        val phase = tick * 0.82f + index * 2.1f
                        val radius = 0.19 * (1.0 - progress / SETTLE_PROGRESS * 0.48)
                        val angle = index * (2.0 * PI / 3.0) + tick * 0.22
                        display.teleport(center.clone().apply {
                            x += kotlin.math.cos(angle) * radius
                            z += kotlin.math.sin(angle) * radius
                            y = surfaceY() + 0.10 + abs(sin(phase.toDouble())) * 0.20 * (1.0 - progress)
                        })
                        display.setTransformationMatrix(
                            Matrix4f()
                                .rotateX(phase)
                                .rotateY(phase * 1.31f)
                                .rotateZ(phase * 0.77f)
                                .scale(DICE_SCALE),
                        )
                    } else {
                        settle(display, event.dice.values[index], index)
                    }
                }
                tick++
                if (tick > durationTicks) animationTask?.cancel()
            },
            0L,
            1L,
        )
    }

    fun complete(event: OpeningDiceEvent) {
        animationTask?.cancel()
        animationTask = null
        displays.forEachIndexed { index, display -> settle(display, event.dice.values[index], index) }
        center.world.playSound(center, Sound.BLOCK_STONE_BUTTON_CLICK_ON, SoundCategory.BLOCKS, 1.0f, 1.65f)
        displays.forEach(ItemDisplay::remove)
        displays.clear()
    }

    fun isTrackedDisplay(id: java.util.UUID): Boolean = displays.any { it.uniqueId == id }

    fun cancelAndClear() {
        animationTask?.cancel()
        animationTask = null
        displays.forEach(ItemDisplay::remove)
        displays.clear()
    }

    private fun settle(display: ItemDisplay, value: Int, index: Int) {
        val angle = index * (2.0 * PI / 3.0) - PI / 2.0
        display.teleport(center.clone().apply {
            x += kotlin.math.cos(angle) * 0.14
            z += kotlin.math.sin(angle) * 0.14
            y = surfaceY() + 0.095
        })
        display.setTransformationMatrix(Matrix4f().rotate(topFaceRotation(value)).scale(DICE_SCALE))
    }

    /** Model faces: east=1, up=2, north=3, south=4, down=5, west=6. */
    private fun topFaceRotation(value: Int): Quaternionf = when (value) {
        1 -> Quaternionf(AxisAngle4f((PI / 2).toFloat(), 0f, 0f, 1f))
        2 -> Quaternionf()
        3 -> Quaternionf(AxisAngle4f((PI / 2).toFloat(), 1f, 0f, 0f))
        4 -> Quaternionf(AxisAngle4f((-PI / 2).toFloat(), 1f, 0f, 0f))
        5 -> Quaternionf(AxisAngle4f(PI.toFloat(), 1f, 0f, 0f))
        else -> Quaternionf(AxisAngle4f((-PI / 2).toFloat(), 0f, 0f, 1f))
    }

    @Suppress("UnstableApiUsage")
    private fun createDiceItem(): ItemStack {
        val item = ItemStack(Material.PAPER)
        item.setData(
            DataComponentTypes.CUSTOM_MODEL_DATA,
            CustomModelData.customModelData().addFloat(MahjongModelData.DICE_WHITE.toFloat()).build(),
        )
        item.itemMeta = item.itemMeta.apply { setCustomModelData(MahjongModelData.DICE_WHITE) }
        return item
    }

    companion object {
        private const val DICE_SCALE = 0.18f
        private const val SETTLE_PROGRESS = 0.68f
    }
}
