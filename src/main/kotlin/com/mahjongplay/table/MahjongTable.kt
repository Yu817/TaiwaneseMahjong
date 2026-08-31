package com.mahjongplay.table

import com.mahjongplay.display.MahjongChairDisplay
import com.mahjongplay.display.MahjongTableDisplay
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import com.mahjongplay.MahjongPlayPlugin
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.EntityType
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.entity.Entity
import java.util.UUID
import kotlin.math.hypot

/**
 * Stable ownership marker for every entity belonging to one table.
 *
 * The table renderer is rebuilt after a plugin reload, so an in-memory UUID
 * registry alone cannot clean up stale entities.  Deriving the marker from
 * the persisted table location lets a new renderer reclaim only its own
 * entities while leaving nearby server/plugin displays untouched.
 */
internal fun mahjongTableEntityTag(center: Location): String {
    val key = "${center.world.uid}:${center.blockX}:${center.blockY}:${center.blockZ}"
    return "taiwanese_mahjong_table_" + UUID.nameUUIDFromBytes(key.toByteArray()).toString().replace("-", "")
}

// In the current Minecraft client TextDisplay glyphs render below the entity
// origin. Place each Interaction box on the visible glyph row and keep a gap
// between rows so aiming at one option cannot select the next option down.
private const val SETTINGS_OPTION_INTERACTION_Y_OFFSET = -0.26
private const val SETTINGS_OPTION_INTERACTION_HEIGHT = 0.20f

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
    val tableScale: Float = MahjongTableDisplay.DEFAULT_SCALE,
) {

    private val entityOwnershipTag = mahjongTableEntityTag(center)
    private val trackedEntityIds = mutableSetOf<UUID>()

    companion object {
        // Chairs and their invisible support blocks are two blocks from the
        // table center. Players must be teleported onto those supports.
        const val CHAIR_DISTANCE = 2.0

        fun obstructedPlacementBlocks(center: Location, chairsEnabled: Boolean): List<Location> {
            val offsets = buildList {
                for (dx in -1..1) for (dz in -1..1) add(dx to dz)
                if (chairsEnabled) addAll(listOf(2 to 0, 0 to -2, -2 to 0, 0 to 2))
            }
            return offsets
                .distinct()
                .map { (dx, dz) ->
                    Location(center.world, (center.blockX + dx).toDouble(), center.blockY.toDouble(), (center.blockZ + dz).toDouble())
                }
                .filter { it.block.type != Material.AIR }
        }
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
    private var publicSettingsTextDisplay: TextDisplay? = null
    private var publicSettingsText: Component? = null
    private var turnTextDisplay: TextDisplay? = null
    private var turnText: Component? = null

    fun spawn() {
        if (!center.isChunkLoaded) return

        val hasTableDisplay = tableDisplay?.entity?.isValid == true
        val hasJoinDisplay = joinTextDisplay?.isValid == true
        val hasJoinInteraction = joinInteraction?.isValid == true
        val hasSeatChairs = !chairsEnabled || (
            seatChairDisplays.size == seatOffsets.size
                && seatChairDisplays.all { it.entity?.isValid == true }
            )
        if (hasTableDisplay && hasJoinDisplay && hasJoinInteraction && hasSeatChairs) {
            tableDisplay?.entity?.let(::rememberOwnedEntity)
            listOfNotNull(joinTextDisplay, joinInteraction).forEach(::rememberOwnedEntity)
            tableDisplay?.normalizeOrientation()
            if (chairsEnabled) {
                seatChairDisplays.forEach {
                    it.spawn()
                    it.entity?.let(::rememberOwnedEntity)
                }
            } else {
                removeSeatSupportBlocks()
                removeSeatChairs()
            }
            repairPublicSettingsDisplay()
            repairTurnDisplay()
            return
        }

        spawnCollisionBlocks()
        if (chairsEnabled) {
            spawnSeatSupportBlocks()
            spawnSeatChairs()
        } else {
            removeSeatSupportBlocks()
            removeSeatChairs()
        }
        if (!hasTableDisplay) {
            tableDisplay?.remove()
            tableDisplay = MahjongTableDisplay(center, scale = tableScale, ownershipTag = entityOwnershipTag).also {
                it.spawn()
                it.entity?.let(::rememberOwnedEntity)
            }
        }

        if (!hasJoinDisplay || !hasJoinInteraction) {
            joinTextDisplay?.remove(); joinTextDisplay = null
            joinInteraction?.remove(); joinInteraction = null
            spawnJoinDisplay()
        }
        repairPublicSettingsDisplay()
        repairTurnDisplay()
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
                seatChairDisplays += MahjongChairDisplay(chairLocation, seat.chairYaw, entityOwnershipTag)
            }
        }

        seatChairDisplays.forEach {
            it.spawn()
            it.entity?.let(::rememberOwnedEntity)
        }
    }

    /**
     * Toggle the visible chairs and their exact barrier click targets.
     * Disabling chairs removes both the display entities and their support
     * barriers, so the old seat cannot remain as an invisible obstacle.
     */
    fun applyChairsEnabled(enabled: Boolean) {
        chairsEnabled = enabled
        if (enabled) {
            spawnSeatSupportBlocks()
            spawnSeatChairs()
        } else {
            releaseAllChairPassengers()
            removeSeatChairs()
            removeSeatSupportBlocks()
        }
    }

    private fun removeSeatChairs() {
        seatChairDisplays.forEach { it.remove() }
        seatChairDisplays.clear()
    }

    private fun removeSeatSupportBlocks() {
        val world = center.world
        val cx = center.blockX
        val cy = center.blockY
        val cz = center.blockZ

        seatOffsets.forEach { seat ->
            val supportLoc = Location(world, (cx + seat.dx).toDouble(), cy.toDouble(), (cz + seat.dz).toDouble())
            if (supportLoc.block.type == Material.BARRIER) {
                supportLoc.block.type = Material.AIR
            }
            placedBlocks.removeIf { sameBlock(it, supportLoc) }
        }
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

    /**
     * Location for the turn beacon at the outside edge of a physical chair.
     * The renderer/game seat order is East, South, West, North, while the
     * legacy chair list is East, North, West, South; map between them so the
     * indicator follows the player and still appears at the correct corner.
     *
     * Keep the beacon just above the table top and move it slightly away from
     * the table.  A beacon at the old low chair height was hidden by the table
     * model, while a full block higher sat in the player's sight line.
     */
    fun turnIndicatorLocation(gameSeatIndex: Int): Location {
        val seat = turnIndicatorSeat(gameSeatIndex)
        val radialLength = hypot(seat.dx.toDouble(), seat.dz.toDouble())
        val radialX = seat.dx / radialLength
        val radialZ = seat.dz / radialLength
        return Location(
            center.world,
            center.blockX + seat.dx + 0.5 + radialX * 0.35,
            center.blockY + 1.02,
            center.blockZ + seat.dz + 0.5 + radialZ * 0.35,
        )
    }

    /** Horizontal tangent used to place the turn beacon as a vertical ring. */
    fun turnIndicatorSideAxis(gameSeatIndex: Int): DoubleArray {
        val seat = turnIndicatorSeat(gameSeatIndex)
        val radialLength = hypot(seat.dx.toDouble(), seat.dz.toDouble())
        val radialX = seat.dx / radialLength
        val radialZ = seat.dz / radialLength
        return doubleArrayOf(-radialZ, radialX)
    }

    private fun turnIndicatorSeat(gameSeatIndex: Int): SeatOffset {
        val gameIndex = Math.floorMod(gameSeatIndex, seatOffsets.size)
        val chairIndex = when (gameIndex) {
            0 -> 0
            1 -> 3
            2 -> 2
            else -> 1
        }
        return seatOffsets[chairIndex]
    }

    fun sitPlayerAtGameSeat(gameSeatIndex: Int, player: Player): Boolean {
        if (!chairsEnabled) return false
        val gameIndex = Math.floorMod(gameSeatIndex, seatOffsets.size)
        val chairIndex = when (gameIndex) {
            0 -> 0 // East
            1 -> 3 // South
            2 -> 2 // West
            else -> 1 // North
        }
        val target = seatChairDisplays.getOrNull(chairIndex) ?: return false
        seatChairDisplays
            .filter { it !== target }
            .forEach { it.releasePlayer(player.uniqueId) }
        return target.sit(player)
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

    fun chairIndexAt(location: Location): Int {
        if (location.world != center.world) return -1
        if (location.blockY !in center.blockY - 1..center.blockY + 1) return -1

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
        val block = location.block
        if (block.type != Material.AIR && block.type != material) return
        block.type = material
        if (placedBlocks.none { sameBlock(it, location) }) {
            placedBlocks += location
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
        rememberOwnedEntity(textDisplay)
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
        rememberOwnedEntity(interaction)
        interaction.isPersistent = false
        interaction.interactionWidth = 2.0f
        interaction.interactionHeight = 0.65f
        interaction.isResponsive = true
        joinInteraction = interaction

        updateJoinDisplay(0, playerCount, waiting = true)
    }

    private fun spawnActionButtons() {
        val world = center.world

        // 垂直置中排列於牌桌正中心軸 (center.x, center.z)，配合 Billboard.CENTER
        // 讓 360 度所有方向的玩家看過去皆為即時面向自己、絕不重疊歪斜
        val readyY = center.blockY + 2.10
        val readyLoc = Location(world, center.x, readyY, center.z)
        val readyTd = world.spawnEntity(readyLoc, EntityType.TEXT_DISPLAY) as TextDisplay
        rememberOwnedEntity(readyTd)
        readyTd.isPersistent = false
        readyTd.billboard = Display.Billboard.CENTER
        readyTd.backgroundColor = Color.fromARGB(175, 0, 90, 0)
        readyTd.brightness = Display.Brightness(15, 15)
        readyTd.isSeeThrough = false
        readyTd.setViewRange(0.6f)
        readyTd.alignment = TextDisplay.TextAlignment.CENTER
        readyTd.text(Component.text(" ✓ 準備 ", NamedTextColor.GREEN).decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false))
        readyTextDisplay = readyTd

        val readyIntLoc = Location(world, center.x, readyY - 0.15, center.z)
        val readyInt = world.spawnEntity(readyIntLoc, EntityType.INTERACTION) as Interaction
        rememberOwnedEntity(readyInt)
        readyInt.isPersistent = false
        readyInt.interactionWidth = 1.2f
        readyInt.interactionHeight = 0.35f
        readyInt.isResponsive = false
        readyInteraction = readyInt

        val startY = center.blockY + 1.70
        val startLoc = Location(world, center.x, startY, center.z)
        val startTd = world.spawnEntity(startLoc, EntityType.TEXT_DISPLAY) as TextDisplay
        rememberOwnedEntity(startTd)
        startTd.isPersistent = false
        startTd.billboard = Display.Billboard.CENTER
        startTd.backgroundColor = Color.fromARGB(175, 140, 70, 0)
        startTd.brightness = Display.Brightness(15, 15)
        startTd.isSeeThrough = false
        startTd.setViewRange(0.6f)
        startTd.alignment = TextDisplay.TextAlignment.CENTER
        startTd.text(Component.text(" ▶ 開始 ", NamedTextColor.GOLD).decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false))
        startTextDisplay = startTd

        val startIntLoc = Location(world, center.x, startY - 0.15, center.z)
        val startInt = world.spawnEntity(startIntLoc, EntityType.INTERACTION) as Interaction
        rememberOwnedEntity(startInt)
        startInt.isPersistent = false
        startInt.interactionWidth = 1.2f
        startInt.interactionHeight = 0.35f
        startInt.isResponsive = false
        startInteraction = startInt

        val settingsY = center.blockY + 1.30
        val settingsLoc = Location(world, center.x, settingsY, center.z)
        val settingsTd = world.spawnEntity(settingsLoc, EntityType.TEXT_DISPLAY) as TextDisplay
        rememberOwnedEntity(settingsTd)
        settingsTd.isPersistent = false
        settingsTd.billboard = Display.Billboard.CENTER
        settingsTd.backgroundColor = Color.fromARGB(175, 0, 75, 115)
        settingsTd.brightness = Display.Brightness(15, 15)
        settingsTd.isSeeThrough = false
        settingsTd.setViewRange(0.6f)
        settingsTd.alignment = TextDisplay.TextAlignment.CENTER
        settingsTd.text(Component.text(" ⚙ 設定 ", NamedTextColor.AQUA).decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false))
        settingsTextDisplay = settingsTd

        val settingsInt = world.spawnEntity(Location(world, center.x, settingsY - 0.15, center.z), EntityType.INTERACTION) as Interaction
        rememberOwnedEntity(settingsInt)
        settingsInt.isPersistent = false
        settingsInt.interactionWidth = 1.2f
        settingsInt.interactionHeight = 0.35f
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
        val readyY = center.blockY + 2.10
        val readyLoc = Location(center.world, center.x, readyY, center.z)
        readyTextDisplay?.apply {
            billboard = Display.Billboard.CENTER
            teleport(readyLoc)
        }
        readyInteraction?.teleport(Location(center.world, center.x, readyY - 0.15, center.z))

        val startY = center.blockY + 1.70
        val startLoc = Location(center.world, center.x, startY, center.z)
        startTextDisplay?.apply {
            billboard = Display.Billboard.CENTER
            teleport(startLoc)
        }
        startInteraction?.teleport(Location(center.world, center.x, startY - 0.15, center.z))

        val settingsY = center.blockY + 1.30
        val settingsLoc = Location(center.world, center.x, settingsY, center.z)
        settingsTextDisplay?.apply {
            billboard = Display.Billboard.CENTER
            teleport(settingsLoc)
        }
        settingsInteraction?.teleport(Location(center.world, center.x, settingsY - 0.15, center.z))

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
                rememberOwnedEntity(this)
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
            rememberOwnedEntity(textDisplay)
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
            rememberOwnedEntity(interaction)
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
        syncFloatingTextVisibility()
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

    /**
     * Keep a read-only settings summary in the world so every nearby player
     * can see the same live rules while the owner edits them in a Dialog.
     */
    fun updatePublicSettingsDisplay(text: Component?) {
        publicSettingsText = text
        if (text == null) {
            publicSettingsTextDisplay?.remove()
            publicSettingsTextDisplay = null
            return
        }

        val display = ensurePublicSettingsDisplay()
        display.text(text)
        display.teleport(publicSettingsDisplayLocation())
    }

    private fun repairPublicSettingsDisplay() {
        val text = publicSettingsText ?: return
        val display = ensurePublicSettingsDisplay()
        display.text(text)
        display.teleport(publicSettingsDisplayLocation())
    }

    private fun ensurePublicSettingsDisplay(): TextDisplay {
        val current = publicSettingsTextDisplay
        if (current?.isValid == true) {
            rememberOwnedEntity(current)
            return current
        }

        return (center.world.spawnEntity(publicSettingsDisplayLocation(), EntityType.TEXT_DISPLAY) as TextDisplay).apply {
            rememberOwnedEntity(this)
            isPersistent = false
            billboard = Display.Billboard.CENTER
            backgroundColor = Color.fromARGB(205, 10, 18, 28)
            brightness = Display.Brightness(15, 15)
            isSeeThrough = false
            setViewRange(0.9f)
            alignment = TextDisplay.TextAlignment.CENTER
            publicSettingsTextDisplay = this
        }
    }

    private fun publicSettingsDisplayLocation(): Location =
        Location(center.world, center.x, center.blockY + 4.65, center.z)

    /**
     * Public read-only turn indicator.  It is deliberately a TextDisplay only;
     * keeping it free of an Interaction entity prevents it from stealing clicks
     * intended for tiles or the table controls.
     */
    fun updateTurnDisplay(text: Component?) {
        turnText = text
        if (text == null) {
            turnTextDisplay?.remove()
            turnTextDisplay = null
            return
        }

        val display = ensureTurnDisplay()
        display.text(text)
        display.teleport(turnDisplayLocation())
        syncFloatingTextVisibility()
    }

    private fun repairTurnDisplay() {
        val text = turnText ?: return
        val display = ensureTurnDisplay()
        display.text(text)
        display.teleport(turnDisplayLocation())
        syncFloatingTextVisibility()
    }

    private fun ensureTurnDisplay(): TextDisplay {
        val current = turnTextDisplay
        if (current?.isValid == true) {
            rememberOwnedEntity(current)
            return current
        }

        return (center.world.spawnEntity(turnDisplayLocation(), EntityType.TEXT_DISPLAY) as TextDisplay).apply {
            rememberOwnedEntity(this)
            isPersistent = false
            billboard = Display.Billboard.CENTER
            backgroundColor = Color.fromARGB(220, 8, 18, 30)
            brightness = Display.Brightness(15, 15)
            isSeeThrough = false
            setViewRange(0.9f)
            alignment = TextDisplay.TextAlignment.CENTER
            turnTextDisplay = this
        }
    }

    private fun turnDisplayLocation(): Location =
        Location(center.world, center.x, center.blockY + 3.65, center.z)


    private var hiddenFromPlayerUUIDs: Set<String> = emptySet()

    fun setHiddenFromPlayers(playerUUIDs: Set<String>) {
        hiddenFromPlayerUUIDs = playerUUIDs
        syncFloatingTextVisibility()
    }

    fun syncFloatingTextVisibility() {
        val plugin = com.mahjongplay.MahjongPlayPlugin.instance
        if (!plugin.isEnabled) return
        val targets = hiddenFromPlayerUUIDs.mapNotNull { uuidStr ->
            runCatching { UUID.fromString(uuidStr) }.getOrNull()?.let { Bukkit.getPlayer(it) }
        }
        val displays = listOfNotNull(joinTextDisplay, turnTextDisplay, publicSettingsTextDisplay)

        targets.forEach { player ->
            displays.forEach { display ->
                runCatching { player.hideEntity(plugin, display) }
            }
        }
        Bukkit.getOnlinePlayers().filter { it !in targets }.forEach { spectator ->
            displays.forEach { display ->
                runCatching { spectator.showEntity(plugin, display) }
            }
        }
    }

    fun revealAllFloatingTexts() {
        val plugin = com.mahjongplay.MahjongPlayPlugin.instance
        if (!plugin.isEnabled) return
        hiddenFromPlayerUUIDs = emptySet()
        val displays = listOfNotNull(joinTextDisplay, turnTextDisplay, publicSettingsTextDisplay)
        Bukkit.getOnlinePlayers().forEach { player ->
            displays.forEach { display ->
                runCatching { player.showEntity(plugin, display) }
            }
        }
    }

    fun revealFloatingTextsTo(player: Player) {
        val plugin = com.mahjongplay.MahjongPlayPlugin.instance
        if (!plugin.isEnabled) return
        val displays = listOfNotNull(joinTextDisplay, turnTextDisplay, publicSettingsTextDisplay)
        displays.forEach { display ->
            runCatching { player.showEntity(plugin, display) }
        }
    }

    fun isProtectedBlock(loc: Location): Boolean =
        placedBlocks.any { sameBlock(it, loc) }

    private fun rememberOwnedEntity(entity: Entity) {
        entity.addScoreboardTag(entityOwnershipTag)
        trackedEntityIds += entity.uniqueId
    }

    fun removeEntities() {
        tableDisplay?.remove()
        tableDisplay = null
        removeSeatChairs()
        joinTextDisplay?.remove(); joinTextDisplay = null
        joinInteraction?.remove(); joinInteraction = null
        publicSettingsTextDisplay?.remove(); publicSettingsTextDisplay = null
        publicSettingsText = null
        turnTextDisplay?.remove(); turnTextDisplay = null
        turnText = null
        hideActionButtons()

        if (center.isChunkLoaded) {
            val ownedIds = trackedEntityIds.toSet()
            center.world.getNearbyEntities(center, 4.5, 3.5, 4.5)
                .filter { entity ->
                    entity.uniqueId in ownedIds ||
                        entity.scoreboardTags.contains(entityOwnershipTag)
                }
                .forEach { it.remove() }
        }
        trackedEntityIds.clear()
    }

    fun destroy() {
        removeEntities()
        placedBlocks.forEach {
            if (it.block.type == Material.BARRIER) it.block.type = Material.AIR
        }
        placedBlocks.clear()
    }
}
