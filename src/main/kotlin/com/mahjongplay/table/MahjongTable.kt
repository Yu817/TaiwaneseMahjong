package com.mahjongplay.table

import com.mahjongplay.display.MahjongChairDisplay
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
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import java.util.UUID
import kotlin.math.hypot

// TextDisplay's visible glyphs sit above its entity origin. Keep each
// Interaction box on the glyph row rather than below it, where the previous
// box could be hit while the player was aiming at the next button.
private const val SETTINGS_OPTION_INTERACTION_Y_OFFSET = 0.10
private const val SETTINGS_OPTION_INTERACTION_HEIGHT = 0.30f

data class SettingsMenuOption(
    val action: String,
    val label: String,
    val color: NamedTextColor,
    val row: Int,
    val column: Int,
)

class MahjongTable(
    val center: Location,
    var gameLengthText: String = "4圈",
    val playerCount: Int = 4,
    var chairsEnabled: Boolean = true,
) {

    companion object {
        // Chairs and their invisible support blocks are two blocks from the
        // table center. Players must be teleported onto those supports.
        const val CHAIR_DISTANCE = 2.0
    }

    private val placedBlocks = mutableListOf<Location>()
    private val seatChairDisplays = mutableListOf<MahjongChairDisplay>()
    private var tableDisplay: MahjongTableDisplay? = null

    private data class SeatOffset(val dx: Int, val dz: Int, val chairYaw: Float)

    private val seatOffsets = listOf(
        SeatOffset(2, 0, 90f),
        SeatOffset(0, -2, 0f),
        SeatOffset(-2, 0, -90f),
        SeatOffset(0, 2, 180f),
    )

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
    var settingsInteraction: Interaction? = null
        private set
    val settingsOptionInteractions: List<Interaction>
        get() = settingsOptionInteractionList

    fun settingsOptionAction(entityUUID: UUID): String? = settingsOptionActions[entityUUID]

    val settingsMenuOpen: Boolean
        get() = settingsMenuTextDisplay?.isValid == true
    private var settingsMenuTextDisplay: TextDisplay? = null
    private val settingsOptionTextDisplays = mutableListOf<TextDisplay>()
    private val settingsOptionInteractionList = mutableListOf<Interaction>()
    private val settingsOptionActions = mutableMapOf<UUID, String>()
    private val settingsOptionLayouts = mutableListOf<Pair<Int, Int>>()
    private var actionButtonAnchor: Location? = null
    private var mainDisplayText: Component = Component.empty()

    fun spawn() {
        val hasTableDisplay = tableDisplay?.entity?.isValid == true
        val hasJoinDisplay = joinTextDisplay?.isValid == true
        val hasJoinInteraction = joinInteraction?.isValid == true
        val hasSeatChairs = !chairsEnabled || (
            seatChairDisplays.size == seatOffsets.size
                && seatChairDisplays.all { it.entity?.isValid == true }
            )
        if (hasTableDisplay && hasJoinDisplay && hasJoinInteraction && hasSeatChairs) {
            tableDisplay?.normalizeOrientation()
            if (chairsEnabled) {
                seatChairDisplays.forEach { it.spawn() }
            } else {
                removeSeatChairs()
            }
            return
        }

        spawnCollisionBlocks()
        spawnSeatSupportBlocks()
        if (chairsEnabled) spawnSeatChairs() else removeSeatChairs()
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

    /**
     * Keep the seat positions solid without leaving a visible vanilla slab.
     * The barrier also migrates existing tables that were created with OAK_SLAB.
     */
    private fun spawnSeatSupportBlocks() {
        val world = center.world
        val cx = center.blockX
        val cy = center.blockY
        val cz = center.blockZ

        seatOffsets.forEach { seat ->
            val supportLoc = Location(world, (cx + seat.dx).toDouble(), cy.toDouble(), (cz + seat.dz).toDouble())
            setTrackedBlock(supportLoc, Material.BARRIER)
        }
    }

    private fun spawnSeatChairs() {
        val world = center.world
        val cx = center.blockX
        val cz = center.blockZ

        if (seatChairDisplays.size != seatOffsets.size) {
            seatChairDisplays.forEach { it.remove() }
            seatChairDisplays.clear()
            seatOffsets.forEach { seat ->
                val chairLocation = Location(
                    world,
                    cx + seat.dx + 0.5,
                    center.blockY + MahjongChairDisplay.ORIGIN_Y_OFFSET,
                    cz + seat.dz + 0.5,
                )
                seatChairDisplays += MahjongChairDisplay(chairLocation, seat.chairYaw)
            }
        }

        seatChairDisplays.forEach { it.spawn() }
    }

    /**
     * Toggle the visible chairs and their exact barrier click targets.
     * Invisible support blocks remain in place so disabling chairs cannot make
     * the table edge unsafe, but they are no longer treated as seats.
     */
    fun applyChairsEnabled(enabled: Boolean) {
        chairsEnabled = enabled
        if (enabled) {
            spawnSeatSupportBlocks()
            spawnSeatChairs()
        } else {
            releaseAllChairPassengers()
            removeSeatChairs()
        }
    }

    private fun removeSeatChairs() {
        seatChairDisplays.forEach { it.remove() }
        seatChairDisplays.clear()
    }

    /**
     * Chairs are selected by their invisible barrier support block. This
     * keeps the click target tied to the exact block the player is looking at
     * instead of relying on an ItemDisplay or a large entity hitbox.
     */
    fun isChairBlock(location: Location): Boolean = chairsEnabled && chairIndexAt(location) >= 0

    fun isChairAvailable(blockLocation: Location, playerUUID: UUID): Boolean {
        if (!chairsEnabled) return false
        val index = chairIndexAt(blockLocation)
        return index >= 0 && seatChairDisplays.getOrNull(index)?.isAvailableFor(playerUUID) == true
    }

    fun isChairOccupiedBy(blockLocation: Location, playerUUID: UUID): Boolean {
        if (!chairsEnabled) return false
        val index = chairIndexAt(blockLocation)
        return index >= 0 && seatChairDisplays.getOrNull(index)?.isOccupiedBy(playerUUID) == true
    }

    fun sitAtChair(blockLocation: Location, player: Player): Boolean {
        if (!chairsEnabled) return false
        val index = chairIndexAt(blockLocation)
        val target = seatChairDisplays.getOrNull(index) ?: return false
        if (!target.isAvailableFor(player.uniqueId)) return false

        seatChairDisplays
            .filter { it !== target }
            .forEach { it.releasePlayer(player.uniqueId) }
        return target.sit(player)
    }

    private fun chairIndexAt(location: Location): Int {
        if (location.block.type != Material.BARRIER) return -1
        if (location.world != center.world || location.blockY != center.blockY) return -1

        return seatOffsets.indexOfFirst { seat ->
            location.blockX == center.blockX + seat.dx &&
                location.blockZ == center.blockZ + seat.dz
        }
    }

    fun releasePlayerFromChairs(playerUUID: UUID) {
        seatChairDisplays.forEach { it.releasePlayer(playerUUID) }
    }

    fun releaseAllChairPassengers() {
        seatChairDisplays.forEach { it.releaseAllPassengers() }
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
        textDisplay.backgroundColor = mainDisplayBackground()
        textDisplay.brightness = Display.Brightness(15, 15)
        textDisplay.isSeeThrough = false
        textDisplay.setViewRange(0.5f)
        textDisplay.alignment = TextDisplay.TextAlignment.CENTER
        joinTextDisplay = textDisplay

        val interactionLoc = Location(world, center.x, center.blockY + 2.55, center.z)
        val interaction = world.spawnEntity(interactionLoc, EntityType.INTERACTION) as Interaction
        interaction.isPersistent = false
        interaction.interactionWidth = 2.0f
        interaction.interactionHeight = 0.65f
        interaction.isResponsive = true
        joinInteraction = interaction

        updateJoinDisplay(0, playerCount, waiting = true)
    }

    private fun spawnActionButtons() {
        val world = center.world
        val btnY = center.blockY + 1.8

        val readyLoc = actionButtonLocation(btnY, -0.5)
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

        val readyIntLoc = actionButtonLocation(btnY - 0.15, -0.5)
        val readyInt = world.spawnEntity(readyIntLoc, EntityType.INTERACTION) as Interaction
        readyInt.isPersistent = false
        readyInt.interactionWidth = 0.8f
        readyInt.interactionHeight = 0.4f
        readyInt.isResponsive = false
        readyInteraction = readyInt

        val startLoc = actionButtonLocation(btnY, 0.5)
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

        val startIntLoc = actionButtonLocation(btnY - 0.15, 0.5)
        val startInt = world.spawnEntity(startIntLoc, EntityType.INTERACTION) as Interaction
        startInt.isPersistent = false
        startInt.interactionWidth = 0.8f
        startInt.interactionHeight = 0.4f
        startInt.isResponsive = false
        startInteraction = startInt

        // The join display is a three-line text block. Keep this button in its
        // own row below the player-count line instead of sharing its baseline.
        val settingsLoc = actionButtonLocation(btnY + 0.35, 0.0)
        val settingsTd = world.spawnEntity(settingsLoc, EntityType.TEXT_DISPLAY) as TextDisplay
        settingsTd.isPersistent = false
        settingsTd.billboard = Display.Billboard.CENTER
        settingsTd.backgroundColor = Color.fromARGB(160, 0, 70, 100)
        settingsTd.brightness = Display.Brightness(15, 15)
        settingsTd.isSeeThrough = false
        settingsTd.setViewRange(0.5f)
        settingsTd.alignment = TextDisplay.TextAlignment.CENTER
        settingsTd.text(Component.text(" ⚙ 設定 ", NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
        settingsTextDisplay = settingsTd

        val settingsInt = world.spawnEntity(settingsLoc.clone().add(0.0, -0.15, 0.0), EntityType.INTERACTION) as Interaction
        settingsInt.isPersistent = false
        settingsInt.interactionWidth = 1.0f
        settingsInt.interactionHeight = 0.4f
        settingsInt.isResponsive = false
        settingsInteraction = settingsInt
    }

    private var settingsTextDisplay: TextDisplay? = null

    private fun actionButtonLocation(y: Double, sideOffset: Double): Location {
        val axis = actionButtonAxis()
        return Location(
            center.world,
            center.x + axis[0] * sideOffset,
            y,
            center.z + axis[1] * sideOffset,
        )
    }

    /**
     * Return the screen-left/screen-right axis for a player standing around the
     * table.  The buttons stay beside each other when viewed from that player,
     * instead of being locked to the world's X axis.
     */
    private fun actionButtonAxis(): DoubleArray {
        val anchor = actionButtonAnchor ?: return doubleArrayOf(1.0, 0.0)
        if (anchor.world != center.world) return doubleArrayOf(1.0, 0.0)

        val dx = anchor.x - center.x
        val dz = anchor.z - center.z
        val distance = hypot(dx, dz)
        if (distance < 0.01) return doubleArrayOf(1.0, 0.0)

        val radialX = dx / distance
        val radialZ = dz / distance
        return doubleArrayOf(-radialZ, radialX)
    }

    private fun updateActionButtonPositions() {
        val btnY = center.blockY + 1.8
        readyTextDisplay?.apply {
            billboard = Display.Billboard.CENTER
            teleport(actionButtonLocation(btnY, -0.5))
        }
        readyInteraction?.teleport(actionButtonLocation(btnY - 0.15, -0.5))
        startTextDisplay?.apply {
            billboard = Display.Billboard.CENTER
            teleport(actionButtonLocation(btnY, 0.5))
        }
        startInteraction?.teleport(actionButtonLocation(btnY - 0.15, 0.5))
        settingsTextDisplay?.apply {
            billboard = Display.Billboard.CENTER
            teleport(actionButtonLocation(btnY + 0.35, 0.0))
        }
        settingsInteraction?.teleport(actionButtonLocation(btnY + 0.20, 0.0))
        updateSettingsMenuPositions()
    }

    private fun removeActionButtonsOnly() {
        startTextDisplay?.remove(); startTextDisplay = null
        startInteraction?.remove(); startInteraction = null
        readyTextDisplay?.remove(); readyTextDisplay = null
        readyInteraction?.remove(); readyInteraction = null
        settingsTextDisplay?.remove(); settingsTextDisplay = null
        settingsInteraction?.remove(); settingsInteraction = null
    }

    fun hideActionButtons() {
        hideSettingsMenu(restoreActionButtons = false)
        removeActionButtonsOnly()
        actionButtonAnchor = null
    }

    fun showActionButtons(anchor: Location? = null) {
        anchor?.let { actionButtonAnchor = it.clone() }
        // Settings is a modal panel. Do not let the repair/update tasks spawn
        // the normal ready/start/settings row over the panel.
        if (settingsMenuOpen) return
        if (startTextDisplay == null) spawnActionButtons() else updateActionButtonPositions()
    }

    /**
     * Reposition the open settings panel around the current player every
     * refresh tick without restoring the hidden normal action row.
     */
    fun refreshSettingsMenu(anchor: Location? = null) {
        if (!settingsMenuOpen) return
        anchor?.let { actionButtonAnchor = it.clone() }
        updateSettingsMenuPositions()
    }

    fun showSettingsMenu(
        text: Component,
        options: List<SettingsMenuOption>,
        anchor: Location? = null,
    ) {
        anchor?.let { actionButtonAnchor = it.clone() }
        // Hide the normal table information and action row while the modal is
        // open. Otherwise both layers occupy the same vertical space.
        hideMainDisplayForSettings()
        removeActionButtonsOnly()
        joinInteraction?.teleport(hiddenJoinInteractionLocation())

        if (settingsMenuTextDisplay == null || settingsMenuTextDisplay?.isValid != true) {
            val displayLoc = Location(center.world, center.x, center.blockY + 4.65, center.z)
            settingsMenuTextDisplay = (center.world.spawnEntity(displayLoc, EntityType.TEXT_DISPLAY) as TextDisplay).apply {
                isPersistent = false
                billboard = Display.Billboard.CENTER
                backgroundColor = Color.fromARGB(205, 10, 18, 28)
                brightness = Display.Brightness(15, 15)
                isSeeThrough = false
                setViewRange(0.9f)
                alignment = TextDisplay.TextAlignment.CENTER
            }
        }
        settingsMenuTextDisplay?.text(text)
        clearSettingsOptions()

        // Keep a visible gap between rows so the interaction boxes cannot
        // steal clicks from the neighbouring option.
        val optionY = center.blockY + 4.15
        options.forEach { option ->
            val loc = settingsOptionLocation(optionY, option.row, option.column)
            val textDisplay = center.world.spawnEntity(loc, EntityType.TEXT_DISPLAY) as TextDisplay
            textDisplay.isPersistent = false
            textDisplay.billboard = Display.Billboard.CENTER
            textDisplay.backgroundColor = Color.fromARGB(175, 25, 35, 45)
            textDisplay.brightness = Display.Brightness(15, 15)
            textDisplay.isSeeThrough = false
            textDisplay.setViewRange(0.85f)
            textDisplay.alignment = TextDisplay.TextAlignment.CENTER
            textDisplay.text(Component.text(" ${option.label} ", option.color))
            settingsOptionTextDisplays += textDisplay
            settingsOptionLayouts += option.row to option.column

            val interaction = center.world.spawnEntity(
                loc.clone().add(0.0, SETTINGS_OPTION_INTERACTION_Y_OFFSET, 0.0),
                EntityType.INTERACTION,
            ) as Interaction
            interaction.isPersistent = false
            interaction.interactionWidth = when (option.action) {
                "flowers_toggle" -> 1.30f
                "chairs_toggle" -> 1.20f
                "close" -> 0.90f
                else -> 1.00f
            }
            interaction.interactionHeight = SETTINGS_OPTION_INTERACTION_HEIGHT
            interaction.isResponsive = false
            settingsOptionInteractionList += interaction
            settingsOptionActions[interaction.uniqueId] = option.action
        }
    }

    fun hideSettingsMenu(restoreActionButtons: Boolean = true) {
        val wasOpen = settingsMenuTextDisplay?.isValid == true || settingsOptionTextDisplays.isNotEmpty()
        settingsMenuTextDisplay?.remove()
        settingsMenuTextDisplay = null
        clearSettingsOptions()
        restoreMainDisplayAfterSettings()
        joinInteraction?.teleport(joinInteractionLocation())
        if (restoreActionButtons && wasOpen) showActionButtons()
    }

    private fun clearSettingsOptions() {
        settingsOptionTextDisplays.forEach { it.remove() }
        settingsOptionTextDisplays.clear()
        settingsOptionInteractionList.forEach { it.remove() }
        settingsOptionInteractionList.clear()
        settingsOptionActions.clear()
        settingsOptionLayouts.clear()
    }

    private fun updateSettingsMenuPositions() {
        if (!settingsMenuOpen) return
        val optionY = center.blockY + 4.15
        settingsOptionTextDisplays.forEachIndexed { index, display ->
            val layout = settingsOptionLayouts.getOrNull(index) ?: return@forEachIndexed
            val loc = settingsOptionLocation(optionY, layout.first, layout.second)
            display.teleport(loc)
            settingsOptionInteractionList.getOrNull(index)?.teleport(
                loc.clone().add(0.0, SETTINGS_OPTION_INTERACTION_Y_OFFSET, 0.0)
            )
        }
        settingsMenuTextDisplay?.teleport(Location(center.world, center.x, center.blockY + 4.65, center.z))
    }

    private fun settingsOptionLocation(y: Double, row: Int, column: Int): Location {
        val axis = actionButtonAxis()
        val rowY = y - row * 0.42
        val offset = column * 1.05
        return Location(
            center.world,
            center.x + axis[0] * offset,
            rowY,
            center.z + axis[1] * offset,
        )
    }

    private fun mainDisplayBackground(): Color = Color.fromARGB(180, 0, 0, 0)

    private fun updateMainDisplay(text: Component) {
        mainDisplayText = text
        if (settingsMenuOpen) {
            hideMainDisplayForSettings()
        } else {
            joinTextDisplay?.apply {
                backgroundColor = mainDisplayBackground()
                this.text(text)
            }
        }
    }

    private fun hideMainDisplayForSettings() {
        joinTextDisplay?.apply {
            text(Component.empty())
            backgroundColor = Color.fromARGB(0, 0, 0, 0)
        }
    }

    private fun restoreMainDisplayAfterSettings() {
        joinTextDisplay?.apply {
            backgroundColor = mainDisplayBackground()
            text(mainDisplayText)
        }
    }

    private fun joinInteractionLocation(): Location =
        Location(center.world, center.x, center.blockY + 2.55, center.z)

    private fun hiddenJoinInteractionLocation(): Location =
        Location(center.world, center.x, center.blockY + 20.0, center.z)

    fun updateJoinDisplay(
        playerCount: Int,
        maxPlayers: Int,
        waiting: Boolean,
        playerInfo: List<Pair<String, Boolean>> = emptyList(),
        buttonAnchor: Location? = null,
    ) {
        buttonAnchor?.let { actionButtonAnchor = it.clone() }
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
            updateMainDisplay(text)
        } else {
            updateMainDisplay(
                Component.text("🀄 麻將 ", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
                    .append(Component.text("[$gameLengthText]", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, false))
                    .append(Component.newline())
                    .append(Component.text("遊戲進行中", NamedTextColor.RED).decoration(TextDecoration.BOLD, false))
            )
        }
    }

    fun showCountdown(seconds: Int) {
        if (joinTextDisplay == null) return
        updateMainDisplay(
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
        removeSeatChairs()
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
