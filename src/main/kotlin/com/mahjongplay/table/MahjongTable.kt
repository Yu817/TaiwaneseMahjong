package com.mahjongplay.table

import com.mahjongplay.display.MahjongTableDisplay
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.EntityType
import org.bukkit.entity.Interaction
import org.bukkit.entity.TextDisplay

class MahjongTable(val center: Location, val gameLengthText: String = "半庄", val playerCount: Int = 4) {

    private val placedBlocks = mutableListOf<Location>()
    private var tableDisplay: MahjongTableDisplay? = null

    var joinTextDisplay: TextDisplay? = null
        private set
    var joinInteraction: Interaction? = null
        private set
    var startTextDisplay: TextDisplay? = null
        private set
    var startInteraction: Interaction? = null
        private set
    var readyTextDisplay: TextDisplay? = null
        private set
    var readyInteraction: Interaction? = null
        private set

    fun spawn() {
        val hasTableDisplay = tableDisplay?.entity?.isValid == true
        val hasJoinDisplay = joinTextDisplay?.isValid == true
        val hasJoinInteraction = joinInteraction?.isValid == true
        if (hasTableDisplay && hasJoinDisplay && hasJoinInteraction) {
            tableDisplay?.normalizeOrientation()
            return
        }

        spawnCollisionBlocks()
        spawnSeatSlabs()
        clearLegacyTableSurface()

        if (!hasTableDisplay) {
            tableDisplay?.remove()
            tableDisplay = MahjongTableDisplay(center).also { it.spawn() }
        }

        if (!hasJoinDisplay || !hasJoinInteraction) {
            joinTextDisplay?.remove(); joinTextDisplay = null
            joinInteraction?.remove(); joinInteraction = null
            spawnJoinDisplay()
        }
    }

    private fun spawnCollisionBlocks() {
        val world = center.world
        val cx = center.blockX
        val cy = center.blockY
        val cz = center.blockZ

        for (dx in -1..1) {
            for (dz in -1..1) {
                val barrierLoc = Location(world, (cx + dx).toDouble(), cy.toDouble(), (cz + dz).toDouble())
                setTrackedBlock(barrierLoc, Material.BARRIER)
            }
        }
    }

    private fun spawnSeatSlabs() {
        val world = center.world
        val cx = center.blockX
        val cy = center.blockY
        val cz = center.blockZ

        val seatOffsets = listOf(2 to 0, 0 to -2, -2 to 0, 0 to 2)
        seatOffsets.forEach { (dx, dz) ->
            val slabLoc = Location(world, (cx + dx).toDouble(), cy.toDouble(), (cz + dz).toDouble())
            setTrackedBlock(slabLoc, Material.OAK_SLAB)
        }
    }

    private fun setTrackedBlock(location: Location, material: Material) {
        location.block.type = material
        if (placedBlocks.none { sameBlock(it, location) }) {
            placedBlocks += location
        }
    }

    private fun clearLegacyTableSurface() {
        val world = center.world
        val cx = center.blockX
        val cy = center.blockY + 1
        val cz = center.blockZ

        for (dx in -1..1) {
            for (dz in -1..1) {
                val surface = world.getBlockAt(cx + dx, cy, cz + dz)
                if (surface.type == Material.GREEN_CARPET || surface.type == Material.LIGHT_BLUE_CARPET) {
                    surface.type = Material.AIR
                }
            }
        }
    }

    private fun sameBlock(first: Location, second: Location): Boolean =
        first.world == second.world &&
            first.blockX == second.blockX &&
            first.blockY == second.blockY &&
            first.blockZ == second.blockZ

    private fun spawnJoinDisplay() {
        val world = center.world
        val displayLoc = Location(world, center.x, center.blockY + 2.5, center.z)

        val textDisplay = world.spawnEntity(displayLoc, EntityType.TEXT_DISPLAY) as TextDisplay
        textDisplay.isPersistent = false
        textDisplay.billboard = Display.Billboard.CENTER
        textDisplay.backgroundColor = Color.fromARGB(180, 0, 0, 0)
        textDisplay.brightness = Display.Brightness(15, 15)
        textDisplay.isSeeThrough = false
        textDisplay.setViewRange(0.5f)
        textDisplay.alignment = TextDisplay.TextAlignment.CENTER
        joinTextDisplay = textDisplay

        val interactionLoc = Location(world, center.x, center.blockY + 2.2, center.z)
        val interaction = world.spawnEntity(interactionLoc, EntityType.INTERACTION) as Interaction
        interaction.isPersistent = false
        interaction.interactionWidth = 2.0f
        interaction.interactionHeight = 1.0f
        interaction.isResponsive = true
        joinInteraction = interaction

        updateJoinDisplay(0, playerCount, waiting = true)
    }

    private fun spawnActionButtons() {
        val world = center.world
        val btnY = center.blockY + 1.8

        val readyLoc = Location(world, center.x - 0.5, btnY, center.z)
        val readyTd = world.spawnEntity(readyLoc, EntityType.TEXT_DISPLAY) as TextDisplay
        readyTd.isPersistent = false
        readyTd.billboard = Display.Billboard.CENTER
        readyTd.backgroundColor = Color.fromARGB(160, 0, 80, 0)
        readyTd.brightness = Display.Brightness(15, 15)
        readyTd.isSeeThrough = false
        readyTd.setViewRange(0.4f)
        readyTd.alignment = TextDisplay.TextAlignment.CENTER
        readyTd.text(Component.text(" ✓ 準備 ", NamedTextColor.GREEN))
        readyTextDisplay = readyTd

        val readyIntLoc = Location(world, center.x - 0.5, btnY - 0.15, center.z)
        val readyInt = world.spawnEntity(readyIntLoc, EntityType.INTERACTION) as Interaction
        readyInt.isPersistent = false
        readyInt.interactionWidth = 0.8f
        readyInt.interactionHeight = 0.4f
        readyInt.isResponsive = false
        readyInteraction = readyInt

        val startLoc = Location(world, center.x + 0.5, btnY, center.z)
        val startTd = world.spawnEntity(startLoc, EntityType.TEXT_DISPLAY) as TextDisplay
        startTd.isPersistent = false
        startTd.billboard = Display.Billboard.CENTER
        startTd.backgroundColor = Color.fromARGB(160, 120, 60, 0)
        startTd.brightness = Display.Brightness(15, 15)
        startTd.isSeeThrough = false
        startTd.setViewRange(0.4f)
        startTd.alignment = TextDisplay.TextAlignment.CENTER
        startTd.text(Component.text(" ▶ 開始 ", NamedTextColor.GOLD))
        startTextDisplay = startTd

        val startIntLoc = Location(world, center.x + 0.5, btnY - 0.15, center.z)
        val startInt = world.spawnEntity(startIntLoc, EntityType.INTERACTION) as Interaction
        startInt.isPersistent = false
        startInt.interactionWidth = 0.8f
        startInt.interactionHeight = 0.4f
        startInt.isResponsive = false
        startInteraction = startInt
    }

    fun hideActionButtons() {
        startTextDisplay?.remove(); startTextDisplay = null
        startInteraction?.remove(); startInteraction = null
        readyTextDisplay?.remove(); readyTextDisplay = null
        readyInteraction?.remove(); readyInteraction = null
    }

    fun showActionButtons() {
        if (startTextDisplay == null) spawnActionButtons()
    }

    fun updateJoinDisplay(
        playerCount: Int,
        maxPlayers: Int,
        waiting: Boolean,
        playerInfo: List<Pair<String, Boolean>> = emptyList(),
    ) {
        val textDisplay = joinTextDisplay ?: return
        if (waiting) {
            if (playerCount > 0) showActionButtons() else hideActionButtons()
            var text = Component.text("🀄 麻將 ", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
                .append(Component.text("[$gameLengthText]", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, false))
                .append(Component.newline())
                .append(Component.text("右鍵點擊加入／離開", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, false))
                .append(Component.newline())
                .append(Component.text("$playerCount/$maxPlayers 位玩家", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, false))

            playerInfo.forEach { (name, ready) ->
                val readyMark = if (ready) " ✓" else " ✗"
                val color = if (ready) NamedTextColor.GREEN else NamedTextColor.GRAY
                text = text.append(Component.newline())
                    .append(Component.text("$name$readyMark", color).decoration(TextDecoration.BOLD, false))
            }
            textDisplay.text(text)
        } else {
            textDisplay.text(
                Component.text("🀄 麻將 ", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
                    .append(Component.text("[$gameLengthText]", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, false))
                    .append(Component.newline())
                    .append(Component.text("遊戲進行中", NamedTextColor.RED).decoration(TextDecoration.BOLD, false))
            )
        }
    }

    fun showCountdown(seconds: Int) {
        val textDisplay = joinTextDisplay ?: return
        textDisplay.text(
            Component.text("🀄 麻將 ", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
                .append(Component.text("[$gameLengthText]", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, false))
                .append(Component.newline())
                .append(Component.text("${seconds} 秒後開始……", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, false))
        )
    }

    fun isProtectedBlock(loc: Location): Boolean =
        placedBlocks.any { sameBlock(it, loc) }

    fun removeEntities() {
        tableDisplay?.remove()
        tableDisplay = null
        joinTextDisplay?.remove(); joinTextDisplay = null
        joinInteraction?.remove(); joinInteraction = null
        hideActionButtons()
    }

    fun destroy() {
        removeEntities()
        placedBlocks.forEach { it.block.type = Material.AIR }
        placedBlocks.clear()
        clearLegacyTableSurface()
    }
}
