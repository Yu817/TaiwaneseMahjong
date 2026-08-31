package com.mahjongplay.display

import com.mahjongplay.MahjongPlayPlugin
import com.mahjongplay.game.ActionDisplayOption
import com.mahjongplay.game.BotDifficulty
import com.mahjongplay.model.TaiwanSettlement
import com.mahjongplay.model.Wind
import com.mahjongplay.display.TileConstants.DEPTH
import com.mahjongplay.display.TileConstants.DRAWN_TILE_GAP
import com.mahjongplay.display.TileConstants.HAND_GAP
import com.mahjongplay.display.TileConstants.HEIGHT
import com.mahjongplay.display.TileConstants.PADDING
import com.mahjongplay.display.TileConstants.WIDTH
import com.mahjongplay.game.DrawReason
import com.mahjongplay.game.GameEventListener
import com.mahjongplay.game.MahjongBot
import com.mahjongplay.game.MahjongPlayer
import com.mahjongplay.game.MahjongGame
import com.mahjongplay.game.MahjongPlayerBase
import com.mahjongplay.game.OpeningDiceEvent
import com.mahjongplay.game.SeatWindDrawStartEvent
import com.mahjongplay.game.SeatWindTilePickedEvent
import com.mahjongplay.game.SeatWindTurnPromptEvent
import com.mahjongplay.game.SeatWindDrawCompleteEvent
import com.mahjongplay.game.TileDrawEvent
import com.mahjongplay.game.WallInitializedEvent
import com.mahjongplay.model.ClaimTarget
import com.mahjongplay.model.MahjongGameBehavior
import com.mahjongplay.model.MahjongRound
import com.mahjongplay.model.MahjongTile
import com.mahjongplay.model.ScoreItem
import com.mahjongplay.table.mahjongTableEntityTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.EntityType
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ActionDisplay(
    val textDisplay: TextDisplay? = null,
    val tileDisplays: List<MahjongTileDisplay> = emptyList(),
    val interaction: org.bukkit.entity.Interaction,
    val behavior: MahjongGameBehavior,
    val data: String,
    val ownerUUID: String,
    val subOptions: List<ActionDisplayOption>? = null,
    val layoutSpacing: Double = 1.25,
    val lateralOffset: Double = 0.0,
)

data class AimedTileClick(val index: Int, val confirmed: Boolean)

class BoardRenderer(
    val game: MahjongGame,
    val tableCenter: Location,
    val tableScale: Float = MahjongTableDisplay.DEFAULT_SCALE,
    val handDistance: Double = DEFAULT_HAND_DISTANCE,
    val wallDistance: Double? = null,
) : GameEventListener {

    val handDisplays = ConcurrentHashMap<String, MutableList<MahjongTileDisplay>>()
    val handOwnerDisplays = ConcurrentHashMap<String, MutableList<MahjongTileDisplay>>()
    private val discardDisplays = ConcurrentHashMap<String, MutableList<MahjongTileDisplay>>()
    private val fuuroDisplays = ConcurrentHashMap<String, MutableList<MahjongTileDisplay>>()
    val ankanOwnerDisplays = ConcurrentHashMap<String, MutableList<MahjongTileDisplay>>()
    val ankanHiddenDisplays = ConcurrentHashMap<String, MutableList<MahjongTileDisplay>>()
    private val flowerDisplays = ConcurrentHashMap<String, MutableList<MahjongTileDisplay>>()
    private val seatScoreDisplays = mutableListOf<TextDisplay>()
    private var floatingCenterDisplay: MahjongTileDisplay? = null
    var roundSettlementDisplay: TextDisplay? = null
    private val selectedTileIndices = ConcurrentHashMap<String, Int>()
    private val aimStabilizers = ConcurrentHashMap<String, AimStabilizer>()
    private val actionDisplays = ConcurrentHashMap<String, MutableList<ActionDisplay>>()
    private val highlightedDiscards = ConcurrentHashMap<String, MutableList<MahjongTileDisplay>>()
    private val hoverRemainingDisplays = ConcurrentHashMap<String, TextDisplay>()
    val seatWindDrawRenderer by lazy {
        SeatWindDrawRenderer(
            center = tableCenter,
            surfaceY = { flatTileY },
            tableScale = tableScale,
            showToAllViewers = ::showToAllViewers,
            ownershipTag = entityOwnershipTag,
        )
    }

    private val wallRenderer by lazy {
        WallRenderer(
            game = game,
            tableCenter = tableCenter,
            bottomTileY = flatTileY,
            drawDestination = ::drawFlightTarget,
            showToAllViewers = ::showToAllViewers,
            wallDistance = wallDistance,
            ownershipTag = entityOwnershipTag,
        )
    }
    private val openingDiceRenderer by lazy { OpeningDiceRenderer(tableCenter, ::surfaceY, entityOwnershipTag) }

    companion object {
        const val DEFAULT_HAND_DISTANCE = 1.30
        private const val RAISE_OFFSET = 0.12
        // 手牌整列微調向左（修正先前向右偏 0.10 導致歪斜問題）
        private const val HAND_LEFT_OFFSET = 0.05
        private const val DISCARD_HOVER_DISTANCE = 8.0
        private const val DISCARD_HOVER_VERTICAL_TOLERANCE = 0.09
        private const val DISCARD_HOVER_STABILITY_MS = 100L
        private const val ACTION_BUTTON_MIN_SPACING = 0.55
        private const val ACTION_BUTTON_GAP = 0.15
    }

    private val world: World get() = tableCenter.world
    private val entityOwnershipTag: String = mahjongTableEntityTag(tableCenter)

    private val surfaceY: Double get() = tableCenter.blockY + MahjongTableDisplay.greenTopOffset(tableScale)
    private val standingTileY: Double get() = surfaceY + HEIGHT / 2.0
    private val flatTileY: Double get() = surfaceY + DEPTH / 2.0
    val handRadialOffset: Double get() = handDistance
    private val furoCornerEdge: Double get() = tableScale * (1.34 / 1.5)
    private val furoRadialOffset: Double get() = tableScale * (1.32 / 1.5)

    // Face points toward center so player behind the tile sees it
    private fun physicalSeatIndex(seatIndex: Int): Int = seatIndex

    private fun seatYaw(seatIndex: Int): Float = when (physicalSeatIndex(seatIndex)) {
        0 -> 90f    // East: face toward -X (center)
        1 -> 0f     // South: face toward -Z (center)
        2 -> -90f   // West: face toward +X (center)
        else -> 180f // North: face toward +Z (center)
    }

    private fun seatDirection(seatIndex: Int): DoubleArray = when (physicalSeatIndex(seatIndex)) {
        0 -> doubleArrayOf(1.0, 0.0)
        1 -> doubleArrayOf(0.0, 1.0)
        2 -> doubleArrayOf(-1.0, 0.0)
        else -> doubleArrayOf(0.0, -1.0)
    }

    // Left-to-right direction from each player's perspective
    private fun seatPerpendicular(seatIndex: Int): DoubleArray = when (physicalSeatIndex(seatIndex)) {
        0 -> doubleArrayOf(0.0, 1.0)    // East: +Z to -Z (south to north)
        1 -> doubleArrayOf(-1.0, 0.0)   // South: -X to +X (west to east)
        2 -> doubleArrayOf(0.0, -1.0)   // West: -Z to +Z (north to south)
        else -> doubleArrayOf(1.0, 0.0)  // North: +X to -X (east to west)
    }

    // Action options are placed along the player's screen-horizontal axis.
    // Falling back to the seat axis keeps the layout stable if the player is
    // temporarily offline or exactly at the table center.
    private fun actionButtonBasis(player: Player?, seatIndex: Int): DoubleArray {
        val fallbackDir = seatDirection(seatIndex)
        val fallbackPerp = seatPerpendicular(seatIndex)
        if (player == null || player.world != world) {
            return doubleArrayOf(fallbackDir[0], fallbackDir[1], fallbackPerp[0], fallbackPerp[1])
        }

        val dx = player.location.x - tableCenter.x
        val dz = player.location.z - tableCenter.z
        val length = kotlin.math.hypot(dx, dz)
        if (length < 0.01) {
            return doubleArrayOf(fallbackDir[0], fallbackDir[1], fallbackPerp[0], fallbackPerp[1])
        }

        val radialX = dx / length
        val radialZ = dz / length
        return doubleArrayOf(radialX, radialZ, -radialZ, radialX)
    }

    private fun actionButtonSpacing(options: List<ActionDisplayOption>): Double {
        val longestLabelWidth = options.maxOfOrNull { option ->
            option.label.codePoints().count().toDouble() * 0.08 + 0.25
        } ?: 0.0
        return maxOf(ACTION_BUTTON_MIN_SPACING, longestLabelWidth + ACTION_BUTTON_GAP)
    }

    override fun onRoundStart(game: MahjongGame, round: MahjongRound) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            clearAllDisplays()
            spawnSeatScoreDisplays()
        })
    }

    override fun onWallInitialized(event: WallInitializedEvent) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            wallRenderer.initialize(event)
        })
    }

    private val activeBukkitPlayers: List<Player>
        get() = game.realPlayers.mapNotNull { runCatching { Bukkit.getPlayer(UUID.fromString(it.uuid)) }.getOrNull() }

    override fun onSeatWindDrawStarted(event: SeatWindDrawStartEvent) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            openingDiceRenderer.begin(OpeningDiceEvent(event.dice, event.starterSeatIndex, 2000L), activeBukkitPlayers)
            seatWindDrawRenderer.begin(event, game)
        })
    }

    override fun onSeatWindTurnPrompt(event: SeatWindTurnPromptEvent) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            seatWindDrawRenderer.promptPicker(event, game)
        })
    }

    override fun onSeatWindTilePicked(event: SeatWindTilePickedEvent) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            seatWindDrawRenderer.onTilePicked(event, game)
        })
    }

    override fun onSeatWindDrawCompleted(event: SeatWindDrawCompleteEvent) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            seatWindDrawRenderer.onCompleted(event, game)
            MahjongPlayPlugin.instance.tableManager.reseatPlayers(game)
        })
    }

    override fun onOpeningDiceStarted(event: OpeningDiceEvent) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            openingDiceRenderer.begin(event, activeBukkitPlayers)
        })
    }

    override fun onOpeningDiceCompleted(event: OpeningDiceEvent) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            openingDiceRenderer.complete(event, activeBukkitPlayers)
        })
    }

    override fun onTileDrawStarted(event: TileDrawEvent) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            wallRenderer.beginDraw(event)
        })
    }

    override fun onTileDrawCompleted(event: TileDrawEvent) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            wallRenderer.completeDraw(event)
        })
    }

    override fun onHandsUpdated(player: MahjongPlayerBase) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            renderHands(player)
            if (player.flowerTiles.isNotEmpty()) renderFlowers(player)
        })
    }

    override fun onTileDiscarded(player: MahjongPlayerBase, tile: MahjongTile) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            renderDiscards(player)
            spawnFloatingCenterTile(tile)
        })
    }

    override fun onPon(player: MahjongPlayerBase, claimedTile: MahjongTile, from: MahjongPlayerBase) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            renderFuuro(player)
            renderDiscards(from)
        })
    }

    override fun onChii(player: MahjongPlayerBase, claimedTile: MahjongTile, from: MahjongPlayerBase) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            renderFuuro(player)
            renderDiscards(from)
        })
    }

    override fun onKan(player: MahjongPlayerBase, tile: MahjongTile, kanType: String, from: MahjongPlayerBase?) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            renderFuuro(player)
            if (from != null) renderDiscards(from)
        })
    }

    override fun onTsumo(player: MahjongPlayerBase, tile: MahjongTile, settlement: TaiwanSettlement) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            updateSeatScoreDisplays()
            val taiStrs = settlement.taiList.map { "${it.name}${it.tai}台" }
            showRoundSettlement(
                title = "🎉【自摸胡牌！】",
                subtitle = "胡牌者：${player.displayName}   胡牌：${tile.displayName}",
                taiDetails = taiStrs,
                scoreInfo = "合計 ${settlement.tai} 台，每家支付 ${settlement.score} 積分",
            )
        })
    }

    override fun onRon(winners: List<MahjongPlayerBase>, loser: MahjongPlayerBase, tile: MahjongTile, settlements: List<TaiwanSettlement>) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            updateSeatScoreDisplays()
            val winnerNames = winners.joinToString { it.displayName }
            val taiStrs = settlements.flatMap { s -> s.taiList.map { "${it.name}${it.tai}台" } }
            val totalScore = settlements.sumOf { it.score }
            showRoundSettlement(
                title = "🎉【榮和胡牌！】",
                subtitle = "胡牌者：$winnerNames   放銃者：${loser.displayName}",
                taiDetails = taiStrs,
                scoreInfo = "放銃支付合計 $totalScore 積分",
            )
        })
    }

    fun showRoundSettlement(title: String, subtitle: String, taiDetails: List<String>, scoreInfo: String) {
        roundSettlementDisplay?.remove()
        val loc = Location(tableCenter.world, tableCenter.x, flatTileY + 1.25, tableCenter.z)
        val td = tableCenter.world.spawnEntity(loc, EntityType.TEXT_DISPLAY) as TextDisplay
        td.isPersistent = false
        td.addScoreboardTag(entityOwnershipTag)
        td.billboard = Display.Billboard.CENTER
        td.backgroundColor = Color.fromARGB(225, 12, 18, 28)
        td.brightness = Display.Brightness(15, 15)
        td.isShadowed = true
        td.setViewRange(1.2f)
        td.alignment = TextDisplay.TextAlignment.CENTER

        var comp = Component.text(title, NamedTextColor.GOLD).decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false)
            .append(Component.newline())
            .append(Component.text(subtitle, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
        if (taiDetails.isNotEmpty()) {
            comp = comp.append(Component.newline())
                .append(Component.text("【台數】 ", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false))
                .append(Component.text(taiDetails.joinToString("、"), NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
        }
        if (scoreInfo.isNotEmpty()) {
            comp = comp.append(Component.newline())
                .append(Component.text(scoreInfo, NamedTextColor.GREEN).decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false))
        }

        td.text(comp)
        roundSettlementDisplay = td
    }

    override fun onScoreSettlement(settlement: com.mahjongplay.model.ScoreSettlement) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            updateSeatScoreDisplays()
        })
    }

    override fun onGameEnd(game: MahjongGame, scoreList: List<ScoreItem>) {
        val clearTask = Runnable { clearAllDisplays() }
        if (MahjongPlayPlugin.instance.isEnabled) {
            Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, clearTask)
        } else {
            clearTask.run()
        }
    }

    fun renderHands(player: MahjongPlayerBase) {
        val seatIndex = game.seat.indexOf(player)
        if (seatIndex < 0) return

        selectedTileIndices.remove(player.uuid)
        aimStabilizers.remove(player.uuid)
        unhighlightDiscards(player.uuid)

        val backList = handDisplays.getOrPut(player.uuid) { mutableListOf() }
        val ownerList = handOwnerDisplays.getOrPut(player.uuid) { mutableListOf() }

        val currentHands = player.hands.toList()
        val tileCount = currentHands.size
        val dir = seatDirection(seatIndex)
        val perp = seatPerpendicular(seatIndex)
        val dirOffset = handRadialOffset
        val totalWidth = tileCount * WIDTH + (tileCount - 1) * HAND_GAP
        val startOffset = totalWidth / 2.0 + HAND_LEFT_OFFSET
        val yaw = seatYaw(seatIndex)
        val isRealPlayer = player.isRealPlayer

        val waitingHandSize = 16 - player.fuuroList.size * 3
        val drawnHandSize = waitingHandSize + 1
        val showGap = player.justDrewTile && tileCount == drawnHandSize

        currentHands.forEachIndexed { index, tile ->
            val isLast = index == tileCount - 1 && showGap
            val tileOffset = index * (WIDTH + HAND_GAP) + if (isLast) DRAWN_TILE_GAP else 0.0

            val x = tableCenter.x + dir[0] * dirOffset + perp[0] * (startOffset - tileOffset)
            val z = tableCenter.z + dir[1] * dirOffset + perp[1] * (startOffset - tileOffset)
            val loc = Location(world, x, standingTileY, z)

            if (index < backList.size) {
                backList[index].updatePosition(loc, yaw, TileFace.STANDING)
                ownerList[index].updateTile(tile)
                ownerList[index].updatePosition(loc.clone(), yaw, TileFace.STANDING)
                ownerList[index].entity?.teleportDuration = 4
            } else {
                val backDisplay = MahjongTileDisplay(loc, MahjongTile.UNKNOWN, TileFace.STANDING, yaw, ownershipTag = entityOwnershipTag)
                backDisplay.spawn()
                backList += backDisplay

                val ownerDisplay = MahjongTileDisplay(loc.clone(), tile, TileFace.STANDING, yaw, interactive = isRealPlayer, ownershipTag = entityOwnershipTag)
                ownerDisplay.spawn()
                ownerDisplay.entity?.teleportDuration = 4
                ownerList += ownerDisplay
            }
        }

        while (backList.size > tileCount) {
            backList.removeLast().remove()
            ownerList.removeLast().remove()
        }

        updateVisibility(player)
    }

    fun findAimedTileIndex(player: Player): Int? {
        val playerUUID = player.uniqueId.toString()
        val seatIndex = game.seat.indexOfFirst { it.uuid == playerUUID }
        if (seatIndex < 0) return null
        val displays = handOwnerDisplays[playerUUID] ?: return null
        val indexedCenters = displays.mapIndexedNotNull { index, display ->
            display.interactionEntity?.location?.toVector()?.let { index to it }
        }
        if (indexedCenters.isEmpty()) return null
        val normal = seatDirection(seatIndex)
        val horizontal = seatPerpendicular(seatIndex)
        val eye = player.eyeLocation
        val resolvedIndex = TileAimResolver.findOnHandPlane(
            origin = eye.toVector(),
            direction = eye.direction,
            planePoint = indexedCenters.first().second,
            planeNormal = org.bukkit.util.Vector(normal[0], 0.0, normal[1]),
            horizontalAxis = org.bukkit.util.Vector(horizontal[0], 0.0, horizontal[1]),
            centers = indexedCenters.map { it.second },
            maxDistance = DISCARD_HOVER_DISTANCE,
            tileHalfWidth = WIDTH / 2.0,
            verticalTolerance = DISCARD_HOVER_VERTICAL_TOLERANCE,
        ) ?: return null
        return indexedCenters[resolvedIndex].first
    }


    fun refreshDiscardHover(player: Player) = updateDiscardAim(player)

    fun refreshActionButtons() = updateActionButtons()

    fun revealHands(player: MahjongPlayerBase) = renderRevealedHands(player)
    fun clickAimedTileForDiscard(player: Player): AimedTileClick? {
        val playerUUID = player.uniqueId.toString()
        val aimedIndex = findAimedTileIndex(player) ?: return null
        val confirmed = selectedTileIndices[playerUUID] == aimedIndex
        if (confirmed) {
            previewTileForDiscard(playerUUID, null)
        } else {
            previewTileForDiscard(playerUUID, aimedIndex)
        }
        return AimedTileClick(aimedIndex, confirmed)
    }

    /** Preview only; the caller must use confirmTileForDiscard to discard. */
    fun updateDiscardAim(player: Player) {
        val playerUUID = player.uniqueId.toString()
        val seatIndex = game.seat.indexOfFirst { it.uuid == playerUUID }
        if (seatIndex < 0) return

        val candidate = findAimedTileIndex(player)
        val stabilizer = aimStabilizers.getOrPut(playerUUID) {
            AimStabilizer(DISCARD_HOVER_STABILITY_MS)
        }
        val hoveredIndex = stabilizer.update(candidate, System.currentTimeMillis())
        previewTileForDiscard(playerUUID, hoveredIndex)
    }

    fun previewTileForDiscard(playerUUID: String, tileIndex: Int?) {
        val gamePlayer = game.seat.find { it.uuid == playerUUID } ?: return
        val current = selectedTileIndices[playerUUID]
        if (current == tileIndex) return

        if (current != null) {
            shiftSingleTile(playerUUID, current, -RAISE_OFFSET)
        }
        if (tileIndex != null) {
            shiftSingleTile(playerUUID, tileIndex, RAISE_OFFSET)
            selectedTileIndices[playerUUID] = tileIndex
            val tile = gamePlayer.hands.getOrNull(tileIndex)
            if (tile != null) {
                highlightDiscards(playerUUID, tile)
            } else {
                unhighlightDiscards(playerUUID)
            }
        } else {
            selectedTileIndices.remove(playerUUID)
            unhighlightDiscards(playerUUID)
        }
        updateHoverRemainingDisplay(playerUUID, tileIndex)
        sendActionBarPreview(gamePlayer, tileIndex)
    }

    fun confirmTileForDiscard(playerUUID: String, clickedIndex: Int): Boolean {
        val currentlySelected = selectedTileIndices[playerUUID]
        if (currentlySelected == clickedIndex) {
            selectedTileIndices.remove(playerUUID)
            shiftSingleTile(playerUUID, clickedIndex, -RAISE_OFFSET)
            unhighlightDiscards(playerUUID)
            updateHoverRemainingDisplay(playerUUID, null)
            sendActionBarPreview(game.seat.first { it.uuid == playerUUID }, null)
            return true
        }
        previewTileForDiscard(playerUUID, clickedIndex)
        return false
    }

    private fun shiftSingleTile(playerUUID: String, tileIndex: Int, deltaY: Double) {
        val displays = handOwnerDisplays[playerUUID] ?: return
        val display = displays.getOrNull(tileIndex) ?: return
        display.entity?.let { e ->
            e.teleportDuration = 4
            val loc = e.location.clone()
            loc.y += deltaY
            e.teleport(loc)
        }
    }

    fun spawnActionOptions(playerUUID: String, options: List<ActionDisplayOption>) {
        clearActionOptions(playerUUID)
        if (options.isEmpty()) return

        val player = game.seat.find { it.uuid == playerUUID } ?: return
        val seatIndex = game.seat.indexOf(player)
        if (seatIndex < 0) return

        spawnActionButtons(playerUUID, seatIndex, options)
    }

    private fun estimateButtonWidth(option: ActionDisplayOption, scale: Float = 0.80f): Double {
        if (option.tiles.isNotEmpty()) {
            val tileW = TileConstants.WIDTH.toDouble()
            val tileCount = option.tiles.size
            val span = tileCount * tileW + (tileCount - 1) * 0.015
            val labelW = if (option.label.isNotEmpty()) estimateTextWidth(option.label, scale) else 0.0
            return maxOf(span + 0.08, labelW, 0.48)
        }
        return estimateTextWidth(option.label, scale)
    }

    private fun estimateTextWidth(label: String, scale: Float = 0.80f): Double {
        var cjk = 0
        var ascii = 0
        for (cp in label.codePoints()) {
            if (cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x3000..0x303F || cp in 0xFF00..0xFFEF) {
                cjk++
            } else {
                ascii++
            }
        }
        val rawWidth = (cjk * 0.28 + ascii * 0.15 + 0.20) * (scale / 0.80f)
        return rawWidth.coerceAtLeast(0.52)
    }

    private fun calculateButtonOffsets(widths: List<Double>, gap: Double): List<Double> {
        if (widths.isEmpty()) return emptyList()
        val totalSpan = widths.sum() + (widths.size - 1) * gap
        val offsets = mutableListOf<Double>()
        var currentCenter = totalSpan / 2.0 - widths[0] / 2.0
        offsets.add(currentCenter)
        for (i in 1 until widths.size) {
            currentCenter -= (widths[i - 1] / 2.0 + gap + widths[i] / 2.0)
            offsets.add(currentCenter)
        }
        return offsets
    }

    private fun spawnActionButtons(playerUUID: String, seatIndex: Int, options: List<ActionDisplayOption>) {
        clearActionOptions(playerUUID)
        if (options.isEmpty()) return

        val ownerBukkit = Bukkit.getPlayer(UUID.fromString(playerUUID))
        val basis = actionButtonBasis(ownerBukkit, seatIndex)
        val dirOffset = handRadialOffset + 0.05
        val actionY = surfaceY + HEIGHT + 0.48
        val buttonScale = 0.80f
        val buttonGap = 0.18

        val widths = options.map { estimateButtonWidth(it, buttonScale) }
        val offsets = calculateButtonOffsets(widths, buttonGap)

        val displays = mutableListOf<ActionDisplay>()

        options.forEachIndexed { index, option ->
            val lateralOffset = offsets[index]
            val btnWidth = widths[index]
            val x = tableCenter.x + basis[0] * dirOffset + basis[2] * lateralOffset
            val z = tableCenter.z + basis[1] * dirOffset + basis[3] * lateralOffset

            val textDisplay: TextDisplay? = if (option.label.isNotEmpty()) {
                val textY = if (option.tiles.isNotEmpty()) actionY + 0.16 else actionY
                val tLoc = Location(world, x, textY, z)
                val td = world.spawnEntity(tLoc, EntityType.TEXT_DISPLAY) as TextDisplay
                td.isPersistent = false
                td.addScoreboardTag(entityOwnershipTag)
                td.billboard = Display.Billboard.CENTER
                td.backgroundColor = Color.fromARGB(210, 16, 20, 28)
                td.brightness = Display.Brightness(15, 15)
                td.isShadowed = true
                td.text(
                    Component.text(" ${option.label} ", option.color).decorate(TextDecoration.BOLD)
                )
                td.alignment = TextDisplay.TextAlignment.CENTER
                td.setViewRange(0.5f)
                td.setVisibleByDefault(false)

                val scaleMatrix = org.joml.Matrix4f().scale(buttonScale)
                td.setTransformationMatrix(scaleMatrix)
                ownerBukkit?.let { p -> p.showEntity(MahjongPlayPlugin.instance, td) }
                td
            } else null

            val tileDisplays = mutableListOf<MahjongTileDisplay>()
            if (option.tiles.isNotEmpty()) {
                val tileCount = option.tiles.size
                val tileW = TileConstants.WIDTH.toDouble()
                val tileGap = 0.015
                val totalSpan = tileCount * tileW + (tileCount - 1) * tileGap
                val startPerp = -totalSpan / 2.0 + tileW / 2.0
                val tileYaw = seatYaw(seatIndex)

                option.tiles.forEachIndexed { tIndex, tile ->
                    val perpOffset = startPerp + tIndex * (tileW + tileGap)
                    val tx = x + basis[2] * perpOffset
                    val tz = z + basis[3] * perpOffset
                    val tLoc = Location(world, tx, actionY, tz)

                    val display = MahjongTileDisplay(tLoc, tile, TileFace.STANDING, tileYaw, interactive = false, ownershipTag = entityOwnershipTag)
                    display.spawn()
                    ownerBukkit?.let { display.showTo(it) }
                    tileDisplays += display
                }
            }

            val interactionWidth = btnWidth.toFloat().coerceIn(0.48f, 1.8f)
            val interactionHeight = if (option.tiles.isNotEmpty() && option.label.isNotEmpty()) 0.45f else 0.35f
            val interactionY = if (option.tiles.isNotEmpty() && option.label.isNotEmpty()) actionY - 0.08 else actionY - 0.16
            val interaction = world.spawnEntity(
                Location(world, x, interactionY, z),
                EntityType.INTERACTION
            ) as org.bukkit.entity.Interaction
            interaction.isPersistent = false
            interaction.addScoreboardTag(entityOwnershipTag)
            interaction.interactionWidth = interactionWidth
            interaction.interactionHeight = interactionHeight
            interaction.isResponsive = false

            displays += ActionDisplay(
                textDisplay = textDisplay,
                tileDisplays = tileDisplays,
                interaction = interaction,
                behavior = option.behavior,
                data = option.data,
                ownerUUID = playerUUID,
                subOptions = option.subOptions,
                lateralOffset = lateralOffset,
            )
        }

        actionDisplays[playerUUID] = displays
    }

    fun updateActionButtons() {
        actionDisplays.forEach { (playerUUID, displays) ->
            val gamePlayer = game.seat.find { it.uuid == playerUUID } ?: return@forEach
            val seatIndex = game.seat.indexOf(gamePlayer)
            if (seatIndex < 0 || displays.isEmpty()) return@forEach

            val ownerBukkit = runCatching { Bukkit.getPlayer(UUID.fromString(playerUUID)) }.getOrNull()
                ?: return@forEach
            val basis = actionButtonBasis(ownerBukkit, seatIndex)
            val dirOffset = handRadialOffset + 0.05
            val actionY = surfaceY + HEIGHT + 0.48

            displays.forEach { action ->
                if (!action.interaction.isValid) return@forEach
                val x = tableCenter.x + basis[0] * dirOffset + basis[2] * action.lateralOffset
                val z = tableCenter.z + basis[1] * dirOffset + basis[3] * action.lateralOffset

                action.textDisplay?.let { td ->
                    if (td.isValid) {
                        td.billboard = Display.Billboard.CENTER
                        val textY = if (action.tileDisplays.isNotEmpty()) actionY + 0.16 else actionY
                        td.teleport(Location(world, x, textY, z))
                    }
                }

                if (action.tileDisplays.isNotEmpty()) {
                    val tileCount = action.tileDisplays.size
                    val tileW = TileConstants.WIDTH.toDouble()
                    val tileGap = 0.015
                    val totalSpan = tileCount * tileW + (tileCount - 1) * tileGap
                    val startPerp = -totalSpan / 2.0 + tileW / 2.0
                    val tileYaw = seatYaw(seatIndex)

                    action.tileDisplays.forEachIndexed { tIndex, tileDisplay ->
                        val perpOffset = startPerp + tIndex * (tileW + tileGap)
                        val tx = x + basis[2] * perpOffset
                        val tz = z + basis[3] * perpOffset
                        val tLoc = Location(world, tx, actionY, tz)
                        tileDisplay.updatePosition(tLoc, tileYaw, TileFace.STANDING)
                    }
                }

                val interactionY = if (action.tileDisplays.isNotEmpty() && action.textDisplay != null) actionY - 0.08 else actionY - 0.16
                action.interaction.teleport(Location(world, x, interactionY, z))
            }
        }
    }

    fun expandSubMenu(playerUUID: String, subOptions: List<ActionDisplayOption>) {
        val player = game.seat.find { it.uuid == playerUUID } ?: return
        val seatIndex = game.seat.indexOf(player)
        if (seatIndex < 0) return

        val withSkip = subOptions + ActionDisplayOption(MahjongGameBehavior.SKIP, "跳過", "", NamedTextColor.GRAY)
        spawnActionButtons(playerUUID, seatIndex, withSkip)
    }

    fun clearActionOptions(playerUUID: String) {
        actionDisplays.remove(playerUUID)?.forEach { ad ->
            ad.textDisplay?.remove()
            ad.tileDisplays.forEach { it.remove() }
            ad.interaction.remove()
        }
    }

    fun getActionByInteraction(interactionUUID: UUID): ActionDisplay? {
        return actionDisplays.values.flatten().find { it.interaction.uniqueId == interactionUUID }
    }

    fun renderDiscards(player: MahjongPlayerBase) {
        val seatIndex = game.seat.indexOf(player)
        if (seatIndex < 0) return

        val existing = discardDisplays.getOrPut(player.uuid) { mutableListOf() }
        val tiles = player.discardedTilesForDisplay.toList()

        val canAppend = tiles.size >= existing.size
                && existing.indices.all { existing[it].tile == tiles[it] }

        if (canAppend && existing.size == tiles.size) return

        val dir = seatDirection(seatIndex)
        val perp = seatPerpendicular(seatIndex)
        val halfSixTiles = WIDTH * 6 / 2.0
        val paddingFromCenter = halfSixTiles + HEIGHT / 2.0 + HEIGHT / 4.0
        val basicOffset = halfSixTiles - WIDTH / 2.0
        val yaw = seatYaw(seatIndex)
        val startX = tableCenter.x + dir[0] * paddingFromCenter + perp[0] * basicOffset
        val startZ = tableCenter.z + dir[1] * paddingFromCenter + perp[1] * basicOffset

        if (canAppend) {
            val startCol = existing.size % 6
            var leftEdge = startCol * (WIDTH + PADDING).toDouble()
            for (index in existing.size until tiles.size) {
                val tile = tiles[index]
                val row = index / 6
                val col = index % 6
                if (col == 0) leftEdge = 0.0

                val centerPerp = leftEdge + WIDTH / 2.0 - WIDTH / 2.0
                val rowOffset = row * (HEIGHT + PADDING)
                val x = startX - perp[0] * centerPerp + dir[0] * rowOffset
                val z = startZ - perp[1] * centerPerp + dir[1] * rowOffset
                val loc = Location(world, x, flatTileY, z)
                leftEdge += WIDTH + PADDING

                val display = MahjongTileDisplay(loc, tile, TileFace.FACE_UP, yaw, ownershipTag = entityOwnershipTag)
                display.spawn()
                showToAllViewers(display)
                existing += display
            }
        } else {
            val oldDisplays = existing.toList()
            existing.clear()

            var leftEdge = 0.0
            tiles.forEachIndexed { index, tile ->
                val row = index / 6
                val col = index % 6
                if (col == 0) leftEdge = 0.0

                val centerPerp = leftEdge + WIDTH / 2.0 - WIDTH / 2.0
                val rowOffset = row * (HEIGHT + PADDING)
                val x = startX - perp[0] * centerPerp + dir[0] * rowOffset
                val z = startZ - perp[1] * centerPerp + dir[1] * rowOffset
                val loc = Location(world, x, flatTileY, z)
                leftEdge += WIDTH + PADDING

                val display = MahjongTileDisplay(loc, tile, TileFace.FACE_UP, yaw, ownershipTag = entityOwnershipTag)
                display.spawn()
                showToAllViewers(display)
                existing += display
            }

            oldDisplays.forEach { it.remove() }
        }
    }

    fun renderFuuro(player: MahjongPlayerBase) {
        val seatIndex = game.seat.indexOf(player)
        if (seatIndex < 0) return

        val existing = fuuroDisplays.getOrPut(player.uuid) { mutableListOf() }
        val oldDisplays = existing.toList()
        existing.clear()

        val ownerAnkanExisting = ankanOwnerDisplays.getOrPut(player.uuid) { mutableListOf() }
        val oldOwnerAnkan = ownerAnkanExisting.toList()
        ownerAnkanExisting.clear()

        val hiddenAnkanExisting = ankanHiddenDisplays.getOrPut(player.uuid) { mutableListOf() }
        val oldHiddenAnkan = hiddenAnkanExisting.toList()
        hiddenAnkanExisting.clear()

        val dir = seatDirection(seatIndex)
        val perp = seatPerpendicular(seatIndex)
        val halfTable = furoCornerEdge
        val yaw = seatYaw(seatIndex)
        val tileGap = 0.0

        val fuuroDirOffset = furoRadialOffset
        var curX = tableCenter.x + dir[0] * fuuroDirOffset - perp[0] * halfTable
        var curZ = tableCenter.z + dir[1] * fuuroDirOffset - perp[1] * halfTable

        var tileCount = 0
        var lastWasClaimTile = false

        val fuuroList = player.fuuroList.toList()
        fuuroList.forEach { fuuro ->
            val isAnkan = fuuro.isKong && !fuuro.isOpen
            val isKakan = fuuro.isAddedKong

            if (isAnkan) {
                val tiles = fuuro.tiles
                val ownerBukkit = Bukkit.getPlayer(UUID.fromString(player.uuid))
                val otherBukkitPlayers = game.players
                    .filter { it.uuid != player.uuid }
                    .mapNotNull { Bukkit.getPlayer(UUID.fromString(it.uuid)) }
                val spectators = if (game.rule.spectate) {
                    Bukkit.getOnlinePlayers().filter { op ->
                        game.players.none { it.uuid == op.uniqueId.toString() }
                    }
                } else emptyList()

                tiles.forEach { tile ->
                    val stepSize = if (tileCount == 0) WIDTH / 2.0 + tileGap / 2.0
                        else if (lastWasClaimTile) (HEIGHT + WIDTH) / 2.0 + tileGap
                        else WIDTH.toDouble() + tileGap

                    curX += perp[0] * stepSize
                    curZ += perp[1] * stepSize

                    val loc = Location(world, curX, flatTileY, curZ)

                    // 暗槓對他人與普通旁觀者顯示扣牌（FACE_DOWN, UNKNOWN）
                    val hiddenDisplay = MahjongTileDisplay(loc, MahjongTile.UNKNOWN, TileFace.FACE_DOWN, yaw, ownershipTag = entityOwnershipTag)
                    hiddenDisplay.spawn()
                    otherBukkitPlayers.forEach { hiddenDisplay.showTo(it) }
                    if (!game.rule.spectatorSeeHands) {
                        spectators.forEach { hiddenDisplay.showTo(it) }
                    }
                    hiddenAnkanExisting += hiddenDisplay

                    // 暗槓對本人顯示自己暗槓的牌面（FACE_UP，正面可見）
                    val ownerDisplay = MahjongTileDisplay(loc.clone(), tile, TileFace.FACE_UP, yaw, ownershipTag = entityOwnershipTag)
                    ownerDisplay.spawn()
                    ownerBukkit?.let { ownerDisplay.showTo(it) }
                    if (game.rule.spectatorSeeHands) {
                        spectators.forEach { ownerDisplay.showTo(it) }
                    }
                    ownerAnkanExisting += ownerDisplay

                    lastWasClaimTile = false
                    tileCount++
                }
            } else {
                val sortedTiles = if (isKakan) fuuro.tiles.toMutableList()
                    else fuuro.tiles.sortedByDescending { it.sortOrder }.toMutableList()

                val kakanTile = if (isKakan) sortedTiles.removeLast() else null

                sortedTiles.remove(fuuro.claimTile)
                val claimIndex = when (fuuro.claimTarget) {
                    ClaimTarget.RIGHT -> { sortedTiles.add(0, fuuro.claimTile); 0 }
                    ClaimTarget.ACROSS -> { sortedTiles.add(1, fuuro.claimTile); 1 }
                    ClaimTarget.LEFT -> { sortedTiles.add(fuuro.claimTile); sortedTiles.size - 1 }
                    else -> -1
                }

                var claimTileX = 0.0
                var claimTileZ = 0.0

                sortedTiles.forEachIndexed { idx, tile ->
                    val isClaimTile = idx == claimIndex

                    val stepSize = when {
                        tileCount == 0 -> {
                            if (isClaimTile) HEIGHT / 2.0 + tileGap / 2.0
                            else WIDTH / 2.0 + tileGap / 2.0
                        }
                        isClaimTile || lastWasClaimTile -> (HEIGHT + WIDTH) / 2.0 + tileGap
                        else -> WIDTH.toDouble() + tileGap
                    }

                    curX += perp[0] * stepSize
                    curZ += perp[1] * stepSize

                    val halfGap = (HEIGHT - WIDTH) / 2.0
                    val posX = if (isClaimTile) curX + dir[0] * halfGap else curX
                    val posZ = if (isClaimTile) curZ + dir[1] * halfGap else curZ

                    val tileYaw = if (isClaimTile) yaw + 90f else yaw

                    val loc = Location(world, posX, flatTileY, posZ)
                    val display = MahjongTileDisplay(loc, tile, TileFace.FACE_UP, tileYaw, ownershipTag = entityOwnershipTag)
                    display.spawn()
                    showToAllViewers(display)
                    existing += display

                    if (isClaimTile) {
                        claimTileX = posX
                        claimTileZ = posZ
                    }
                    lastWasClaimTile = isClaimTile
                    tileCount++
                }

                kakanTile?.let { tile ->
                    val kakanX = claimTileX - dir[0] * (WIDTH.toDouble() + tileGap)
                    val kakanZ = claimTileZ - dir[1] * (WIDTH.toDouble() + tileGap)
                    val loc = Location(world, kakanX, flatTileY, kakanZ)
                    val display = MahjongTileDisplay(loc, tile, TileFace.FACE_UP, yaw + 90f, ownershipTag = entityOwnershipTag)
                    display.spawn()
                    showToAllViewers(display)
                    existing += display
                }
            }

            curX += perp[0] * PADDING
            curZ += perp[1] * PADDING
        }

        oldDisplays.forEach { it.remove() }
        oldOwnerAnkan.forEach { it.remove() }
        oldHiddenAnkan.forEach { it.remove() }
    }

    fun renderFlowers(player: MahjongPlayerBase) {
        val seatIndex = game.seat.indexOf(player)
        if (seatIndex < 0) return

        val existing = flowerDisplays.getOrPut(player.uuid) { mutableListOf() }
        val flowers = player.flowerTiles.toList()

        if (flowers.isEmpty()) {
            existing.forEach { it.remove() }
            existing.clear()
            return
        }

        val oldDisplays = existing.toList()
        existing.clear()

        val dir = seatDirection(seatIndex)
        val perp = seatPerpendicular(seatIndex)
        val yaw = seatYaw(seatIndex)

        // 放置在手牌與牌牆中間的空隙（向手牌方向微調 2.4cm，徹底避開牌牆外緣）
        val wallDist = wallDistance ?: 1.0125
        val wallOuterEdge = wallDist + HEIGHT / 2.0
        val handFrontEdge = handRadialOffset - DEPTH / 2.0
        val flowerDirOffset = 1.180
        val totalWidth = flowers.size * WIDTH + (flowers.size - 1) * PADDING
        val startOffset = totalWidth / 2.0

        // 正面朝上、字體正向面對玩家，在手牌與牌牆間居中水平左右肩並肩排列
        flowers.forEachIndexed { index, tile ->
            val tileOffset = index * (WIDTH + PADDING)
            val perpOffset = startOffset - tileOffset - WIDTH / 2.0
            val x = tableCenter.x + dir[0] * flowerDirOffset + perp[0] * perpOffset
            val z = tableCenter.z + dir[1] * flowerDirOffset + perp[1] * perpOffset
            val loc = Location(world, x, flatTileY, z)

            val display = MahjongTileDisplay(loc, tile, TileFace.FACE_UP, yaw, ownershipTag = entityOwnershipTag)
            display.spawn()
            showToAllViewers(display)
            existing += display
        }

        oldDisplays.forEach { it.remove() }
    }

    fun updateSeatScoreDisplays() {
        spawnSeatScoreDisplays()
    }

    private fun spawnSeatScoreDisplays() {
        clearSeatScoreDisplays()
        if (game.seat.isEmpty()) return

        game.seat.forEachIndexed { seatIndex, player ->
            val dir = seatDirection(seatIndex)
            val distance = 2.85
            val x = tableCenter.x + dir[0] * distance
            val z = tableCenter.z + dir[1] * distance
            val loc = Location(world, x, surfaceY + 1.25, z)

            val textDisplay = world.spawnEntity(loc, EntityType.TEXT_DISPLAY) as TextDisplay
            textDisplay.isPersistent = false
            textDisplay.addScoreboardTag(entityOwnershipTag)
            textDisplay.billboard = Display.Billboard.CENTER
            textDisplay.backgroundColor = Color.fromARGB(195, 10, 16, 26)
            textDisplay.brightness = Display.Brightness(15, 15)
            textDisplay.isSeeThrough = false
            textDisplay.isShadowed = true
            textDisplay.setViewRange(0.85f)
            textDisplay.alignment = TextDisplay.TextAlignment.CENTER

            val scaleMatrix = org.joml.Matrix4f().scale(0.80f)
            textDisplay.setTransformationMatrix(scaleMatrix)

            val seatWind = Wind.entries.getOrNull(seatIndex) ?: Wind.EAST
            val pts = player.points
            val ptsText = if (pts > 0) "+$pts" else "$pts"
            val ptsColor = when {
                pts > 0 -> NamedTextColor.GREEN
                pts < 0 -> NamedTextColor.RED
                else -> NamedTextColor.WHITE
            }

            val headerComponent = when {
                player is MahjongBot -> {
                    val diffColor = when (player.difficulty) {
                        BotDifficulty.LOW -> NamedTextColor.GRAY
                        BotDifficulty.MEDIUM -> NamedTextColor.YELLOW
                        BotDifficulty.HIGH -> NamedTextColor.LIGHT_PURPLE
                    }
                    Component.text("【${seatWind.displayName}家】", NamedTextColor.AQUA)
                        .append(Component.text("🤖 ${player.displayName}", NamedTextColor.GOLD))
                        .append(Component.text(" [${player.difficulty.displayName}]", diffColor))
                }
                player is MahjongPlayer && player.isBotTakeover -> {
                    val diffColor = when (player.botDifficulty) {
                        BotDifficulty.LOW -> NamedTextColor.GRAY
                        BotDifficulty.MEDIUM -> NamedTextColor.YELLOW
                        BotDifficulty.HIGH -> NamedTextColor.LIGHT_PURPLE
                    }
                    Component.text("【${seatWind.displayName}家】", NamedTextColor.AQUA)
                        .append(Component.text("🤖 ${player.rawDisplayName} [代打]", NamedTextColor.GOLD))
                        .append(Component.text(" [${player.botDifficulty.displayName}]", diffColor))
                }
                else -> {
                    Component.text("【${seatWind.displayName}家】", NamedTextColor.AQUA)
                        .append(Component.text(player.displayName, NamedTextColor.WHITE))
                }
            }

            val scoreComponent = Component.text("積分: ", NamedTextColor.GRAY)
                .append(Component.text(ptsText, ptsColor).decorate(TextDecoration.BOLD))

            textDisplay.text(headerComponent.append(Component.newline()).append(scoreComponent))
            seatScoreDisplays += textDisplay
        }
    }

    private fun clearSeatScoreDisplays() {
        seatScoreDisplays.forEach { it.remove() }
        seatScoreDisplays.clear()
    }

    private fun showToAllViewers(display: MahjongTileDisplay) {
        game.players.forEach { p ->
            val bp = Bukkit.getPlayer(UUID.fromString(p.uuid))
            if (bp != null) display.showTo(bp)
        }
        Bukkit.getOnlinePlayers()
            .filter { op -> game.players.none { it.uuid == op.uniqueId.toString() } }
            .forEach { display.showTo(it) }
    }

    /**
     * Reconcile this renderer's per-player visibility after a join/rejoin.
     * Display entities are deliberately hidden by default, so showing only
     * newly-created entities is insufficient: a spectator joining mid-round
     * would otherwise see an empty wall and discard area.  Keep public
     * displays public, while applying the same hand-front policy used during
     * normal rendering.
     */
    fun syncPlayerVisibility(viewer: Player) {
        if (!viewer.isOnline) return

        wallRenderer.showPublicDisplaysTo(viewer)
        discardDisplays.values.flatten().forEach { it.showTo(viewer) }
        fuuroDisplays.values.flatten().forEach { it.showTo(viewer) }
        flowerDisplays.values.flatten().forEach { it.showTo(viewer) }
        revealedHandDisplays.values.flatten().forEach { it.showTo(viewer) }
        seatScoreDisplays.forEach { display ->
            runCatching { viewer.showEntity(MahjongPlayPlugin.instance, display) }
        }
        roundSettlementDisplay?.let { display ->
            runCatching { viewer.showEntity(MahjongPlayPlugin.instance, display) }
        }
        floatingCenterDisplay?.showTo(viewer)

        val viewerUUID = viewer.uniqueId.toString()
        val isParticipant = game.players.any { it.uuid == viewerUUID }
        val isSpectator = !isParticipant && game.rule.spectate
        game.seat.forEach { seatPlayer ->
            val isOwner = seatPlayer.uuid == viewerUUID
            val canSeeFront = isOwner || (isSpectator && game.rule.spectatorSeeHands)
            handDisplays[seatPlayer.uuid].orEmpty().forEach { display ->
                if (isOwner) display.hideTo(viewer) else display.showTo(viewer)
            }
            handOwnerDisplays[seatPlayer.uuid].orEmpty().forEach { display ->
                if (canSeeFront) display.showTo(viewer) else display.hideTo(viewer)
            }
            ankanOwnerDisplays[seatPlayer.uuid].orEmpty().forEach { display ->
                if (canSeeFront) display.showTo(viewer) else display.hideTo(viewer)
            }
            ankanHiddenDisplays[seatPlayer.uuid].orEmpty().forEach { display ->
                if (!canSeeFront) display.showTo(viewer) else display.hideTo(viewer)
            }
        }

        // Action labels/tiles are private prompts.  The Interaction hitboxes
        // remain server-side and are validated by owner UUID in the listener.
        actionDisplays[viewerUUID].orEmpty().forEach { action ->
            action.textDisplay?.let { display ->
                runCatching { viewer.showEntity(MahjongPlayPlugin.instance, display) }
            }
            action.tileDisplays.forEach { it.showTo(viewer) }
        }
    }

    private fun drawFlightTarget(event: TileDrawEvent): DrawFlightTarget {
        val player = game.seat.find { it.uuid == event.playerUUID }
            ?: return DrawFlightTarget(Location(world, tableCenter.x, standingTileY, tableCenter.z), 0f)
        val seatIndex = game.seat.indexOf(player)
        if (seatIndex < 0) return DrawFlightTarget(Location(world, tableCenter.x, standingTileY, tableCenter.z), 0f)

        val dir = seatDirection(seatIndex)
        val perp = seatPerpendicular(seatIndex)
        val yaw = seatYaw(seatIndex)

        // 摸到花牌直接順暢飛往手牌前方空隙的花牌區平鋪定位
        if (event.tile.isFlower) {
            val flowerDirOffset = 1.180
            val flowerIndex = player.flowerTiles.size
            val totalWidth = (flowerIndex + 1) * WIDTH + flowerIndex * PADDING
            val startOffset = totalWidth / 2.0
            val perpOffset = startOffset - flowerIndex * (WIDTH + PADDING) - WIDTH / 2.0
            val x = tableCenter.x + dir[0] * flowerDirOffset + perp[0] * perpOffset
            val z = tableCenter.z + dir[1] * flowerDirOffset + perp[1] * perpOffset
            return DrawFlightTarget(Location(world, x, flatTileY, z), yaw, TileFace.FACE_UP)
        }

        val tileCount = event.handSizeBeforeDraw + 1
        val totalWidth = tileCount * WIDTH + (tileCount - 1) * HAND_GAP
        val startOffset = totalWidth / 2.0 + HAND_LEFT_OFFSET
        val isDrawnTile = player.justDrewTile &&
            tileCount == 17 - player.fuuroList.size * 3 &&
            event.reason != DrawReason.INITIAL_DEAL
        val tileOffset = (tileCount - 1) * (WIDTH + HAND_GAP) + if (isDrawnTile) DRAWN_TILE_GAP else 0.0
        val x = tableCenter.x + dir[0] * handRadialOffset + perp[0] * (startOffset - tileOffset)
        val z = tableCenter.z + dir[1] * handRadialOffset + perp[1] * (startOffset - tileOffset)
        return DrawFlightTarget(Location(world, x, standingTileY, z), yaw, TileFace.STANDING)
    }

    fun updateVisibility(player: MahjongPlayerBase) {
        val backDisplays = handDisplays[player.uuid] ?: return
        val ownerOnlyDisplays = handOwnerDisplays[player.uuid] ?: return
        val ownerBukkit = Bukkit.getPlayer(UUID.fromString(player.uuid))

        val otherBukkitPlayers = game.players
            .filter { it.uuid != player.uuid }
            .mapNotNull { Bukkit.getPlayer(UUID.fromString(it.uuid)) }

        val spectators = if (game.rule.spectate) {
            Bukkit.getOnlinePlayers().filter { op ->
                game.players.none { it.uuid == op.uniqueId.toString() }
            }
        } else emptyList()

        backDisplays.forEach { display ->
            ownerBukkit?.let { display.hideTo(it) }
            otherBukkitPlayers.forEach { display.showTo(it) }
            if (game.rule.spectatorSeeHands) {
                spectators.forEach { display.hideTo(it) }
            } else {
                spectators.forEach { display.showTo(it) }
            }
        }

        ownerOnlyDisplays.forEach { display ->
            ownerBukkit?.let { display.showTo(it) }
            otherBukkitPlayers.forEach { display.hideTo(it) }
            if (game.rule.spectatorSeeHands) {
                spectators.forEach { display.showTo(it) }
            } else {
                spectators.forEach { display.hideTo(it) }
            }
        }

        val ankanOwners = ankanOwnerDisplays[player.uuid].orEmpty()
        val ankanHiddens = ankanHiddenDisplays[player.uuid].orEmpty()

        ankanHiddens.forEach { display ->
            ownerBukkit?.let { display.hideTo(it) }
            otherBukkitPlayers.forEach { display.showTo(it) }
            if (game.rule.spectatorSeeHands) {
                spectators.forEach { display.hideTo(it) }
            } else {
                spectators.forEach { display.showTo(it) }
            }
        }

        ankanOwners.forEach { display ->
            ownerBukkit?.let { display.showTo(it) }
            otherBukkitPlayers.forEach { display.hideTo(it) }
            if (game.rule.spectatorSeeHands) {
                spectators.forEach { display.showTo(it) }
            } else {
                spectators.forEach { display.showTo(it) }
            }
        }
    }

    fun renderRevealedHands(player: MahjongPlayerBase) {
        val seatIndex = game.seat.indexOf(player)
        if (seatIndex < 0) return

        handDisplays[player.uuid]?.forEach { it.remove() }
        handDisplays[player.uuid]?.clear()
        handOwnerDisplays[player.uuid]?.forEach { it.remove() }
        handOwnerDisplays[player.uuid]?.clear()

        val dir = seatDirection(seatIndex)
        val perp = seatPerpendicular(seatIndex)
        val dirOffset = handRadialOffset
        val yaw = seatYaw(seatIndex)
        val currentHands = player.hands.toList()
        val tileCount = currentHands.size
        val totalWidth = tileCount * WIDTH + (tileCount - 1) * HAND_GAP
        val startOffset = totalWidth / 2.0 + HAND_LEFT_OFFSET

        val revealed = mutableListOf<MahjongTileDisplay>()

        currentHands.forEachIndexed { index, tile ->
            val tileOffset = index * (WIDTH + HAND_GAP)
            val x = tableCenter.x + dir[0] * dirOffset + perp[0] * (startOffset - tileOffset)
            val z = tableCenter.z + dir[1] * dirOffset + perp[1] * (startOffset - tileOffset)
            val loc = Location(world, x, flatTileY, z)

            val display = MahjongTileDisplay(loc, tile, TileFace.FACE_UP, yaw, ownershipTag = entityOwnershipTag)
            display.spawn()
            showToAllViewers(display)
            revealed += display
        }

        revealedHandDisplays[player.uuid] = revealed
    }

    private val revealedHandDisplays = ConcurrentHashMap<String, MutableList<MahjongTileDisplay>>()

    fun clearAllDisplays() {
        roundSettlementDisplay?.remove()
        roundSettlementDisplay = null
        openingDiceRenderer.cancelAndClear()
        seatWindDrawRenderer.cancelAndClear()
        wallRenderer.cancelAndClear()
        game.seat.forEach { unhighlightDiscards(it.uuid) }
        handDisplays.values.flatten().forEach { it.remove() }
        handDisplays.clear()
        handOwnerDisplays.values.flatten().forEach { it.remove() }
        handOwnerDisplays.clear()
        revealedHandDisplays.values.flatten().forEach { it.remove() }
        revealedHandDisplays.clear()
        selectedTileIndices.clear()
        aimStabilizers.clear()
        discardDisplays.values.flatten().forEach { it.remove() }
        discardDisplays.clear()
        fuuroDisplays.values.flatten().forEach { it.remove() }
        fuuroDisplays.clear()
        ankanOwnerDisplays.values.flatten().forEach { it.remove() }
        ankanOwnerDisplays.clear()
        ankanHiddenDisplays.values.flatten().forEach { it.remove() }
        ankanHiddenDisplays.clear()
        flowerDisplays.values.flatten().forEach { it.remove() }
        flowerDisplays.clear()
        actionDisplays.values.flatten().forEach { ad ->
            ad.textDisplay?.remove()
            ad.tileDisplays.forEach { it.remove() }
            ad.interaction.remove()
        }
        actionDisplays.clear()
        hoverRemainingDisplays.values.forEach { it.remove() }
        hoverRemainingDisplays.clear()
        clearFloatingCenterTile()
        clearSeatScoreDisplays()
        removeOrphanedUnknownBackDisplays()
    }

    /**
     * Hand backs are the only displays that use the question-mark model, and
     * they must always be tracked by [handDisplays]. A plugin reload or an
     * interrupted render can otherwise leave an untracked ItemDisplay lying
     * on the table indefinitely. Keep tracked, standing hand backs; remove
     * only untracked question-mark displays inside this table's bounds.
     */
    private fun removeOrphanedUnknownBackDisplays() {
        val trackedIds = handDisplays.values
            .asSequence()
            .flatten()
            .mapNotNull { it.entity?.uniqueId }
            .toSet()
        val unknownModelData = MahjongModelData.TILE_BASE + MahjongTile.UNKNOWN.code
        val scanRadius = tableScale * 2.25 + 0.5
        var removed = 0

        world.getNearbyEntities(tableCenter, scanRadius, 2.0, scanRadius)
            .filterIsInstance<ItemDisplay>()
            .filter { display ->
                display.uniqueId !in trackedIds &&
                    !wallRenderer.isTrackedDisplay(display.uniqueId) &&
                    !openingDiceRenderer.isTrackedDisplay(display.uniqueId) &&
                    // Only reclaim displays that this table created.  The
                    // UNKNOWN model-data value is shared data and cannot be
                    // used as an ownership test; without this tag check a
                    // nearby table/other plugin could lose its hand backs.
                    display.scoreboardTags.contains(entityOwnershipTag) &&
                    display.itemStack.itemMeta.customModelData == unknownModelData
            }
            .forEach {
                it.remove()
                removed++
            }

        if (removed > 0) {
            MahjongPlayPlugin.instance.logger.info("Removed $removed orphaned hidden-hand display(s) near a mahjong table.")
        }
    }

    fun clearHoverRemainingFor(playerUUID: String) {
        hoverRemainingDisplays.remove(playerUUID)?.remove()
    }

    private fun updateHoverRemainingDisplay(playerUUID: String, tileIndex: Int?) {
        val oldDisplay = hoverRemainingDisplays.remove(playerUUID)
        oldDisplay?.remove()

        if (tileIndex == null) return
        val gamePlayer = game.seat.find { it.uuid == playerUUID } ?: return
        val tile = gamePlayer.hands.getOrNull(tileIndex) ?: return
        val displays = handOwnerDisplays[playerUUID] ?: return
        val tileDisplay = displays.getOrNull(tileIndex) ?: return
        val tileEntity = tileDisplay.entity ?: return

        val ownerBukkit = runCatching { Bukkit.getPlayer(UUID.fromString(playerUUID)) }.getOrNull() ?: return
        val rem = TileCounter.countRemainingUnseenTiles(game, gamePlayer, tile)
        val remColor = when (rem) {
            0 -> NamedTextColor.RED
            1 -> NamedTextColor.YELLOW
            else -> NamedTextColor.GREEN
        }

        val tileLoc = tileEntity.location
        // 浮字顯示於手牌正上方約 8 公分處
        val badgeLoc = Location(world, tileLoc.x, tileLoc.y + HEIGHT + 0.08, tileLoc.z)

        val textDisplay = world.spawnEntity(badgeLoc, EntityType.TEXT_DISPLAY) as TextDisplay
        textDisplay.isPersistent = false
        textDisplay.addScoreboardTag(entityOwnershipTag)
        textDisplay.billboard = Display.Billboard.CENTER
        textDisplay.backgroundColor = Color.fromARGB(210, 16, 20, 28)
        textDisplay.brightness = Display.Brightness(15, 15)
        textDisplay.isShadowed = true
        textDisplay.text(
            Component.text("餘 ", NamedTextColor.GRAY)
                .append(Component.text("$rem", remColor).decorate(TextDecoration.BOLD))
        )
        textDisplay.alignment = TextDisplay.TextAlignment.CENTER
        textDisplay.setViewRange(0.5f)
        textDisplay.setVisibleByDefault(false)

        val scaleMatrix = org.joml.Matrix4f().scale(0.65f)
        textDisplay.setTransformationMatrix(scaleMatrix)
        ownerBukkit.showEntity(MahjongPlayPlugin.instance, textDisplay)

        hoverRemainingDisplays[playerUUID] = textDisplay
    }

    private fun sendActionBarPreview(player: MahjongPlayerBase, tileIndex: Int?) {
        val bukkitPlayer = Bukkit.getPlayer(UUID.fromString(player.uuid)) ?: return
        if (tileIndex == null) {
            bukkitPlayer.sendActionBar(Component.empty())
            return
        }
        val tile = player.hands.getOrNull(tileIndex) ?: return
        val rem = TileCounter.countRemainingUnseenTiles(game, player, tile)
        val remColor = when (rem) {
            0 -> NamedTextColor.RED
            1 -> NamedTextColor.YELLOW
            else -> NamedTextColor.GREEN
        }
        bukkitPlayer.sendActionBar(
            Component.text("已選取：", NamedTextColor.GRAY)
                .append(Component.text(tile.displayName, NamedTextColor.GOLD))
                .append(Component.text(" 【餘 ", NamedTextColor.DARK_GRAY))
                .append(Component.text("$rem", remColor).decorate(TextDecoration.BOLD))
                .append(Component.text("】", NamedTextColor.DARK_GRAY))
                .append(Component.text("（再次點擊出牌）", NamedTextColor.YELLOW))
        )
    }

    private fun spawnFloatingCenterTile(tile: MahjongTile) {
        clearFloatingCenterTile()
        // 放大中央出牌提示並設置向四周玩家面向 (BILLBOARD CENTER)，高度稍作提升
        val loc = Location(world, tableCenter.x, surfaceY + HEIGHT + 0.45, tableCenter.z)
        val display = MahjongTileDisplay(loc, tile, TileFace.STANDING, 0f, ownershipTag = entityOwnershipTag)
        display.spawn()
        display.entity?.let { entity ->
            val matrix = org.joml.Matrix4f().scale(0.42f)
            entity.setTransformationMatrix(matrix)
            entity.billboard = Display.Billboard.CENTER
        }
        showToAllViewers(display)

        // 若有玩家正在 Shift 俯瞰牌桌中央捨牌區，將此中央提示懸浮牌對其隱藏，避免遮擋視線
        runCatching { MahjongPlayPlugin.instance.tableManager }.getOrNull()?.let { mgr ->
            game.realPlayers.forEach { mjPlayer ->
                val uuid = runCatching { UUID.fromString(mjPlayer.uuid) }.getOrNull() ?: return@forEach
                if (mgr.isPlayerInspectingCenter(uuid)) {
                    Bukkit.getPlayer(uuid)?.let { display.hideTo(it) }
                }
            }
        }

        floatingCenterDisplay = display
    }

    private fun clearFloatingCenterTile() {
        floatingCenterDisplay?.remove()
        floatingCenterDisplay = null
    }

    fun hideFloatingCenterTileFor(player: Player) {
        floatingCenterDisplay?.hideTo(player)
    }

    fun showFloatingCenterTileFor(player: Player) {
        floatingCenterDisplay?.showTo(player)
    }

    fun handleSeatWindClick(player: org.bukkit.entity.Player, entityUUID: java.util.UUID): Boolean =
        seatWindDrawRenderer.handleInteractionClick(player, entityUUID)

    fun isSeatWindInteraction(entityUUID: java.util.UUID): Boolean =
        seatWindDrawRenderer.isWindTileInteraction(entityUUID)

    fun highlightDiscards(playerUUID: String, tile: MahjongTile) {
        unhighlightDiscards(playerUUID)
        val matched = mutableListOf<MahjongTileDisplay>()
        matched += discardDisplays.values.flatten().filter { it.tile == tile }
        handOwnerDisplays[playerUUID]?.filter { it.tile == tile }?.let { matched += it }
        matched += fuuroDisplays.values.flatten().filter { it.tile == tile }
        ankanOwnerDisplays[playerUUID]?.filter { it.tile == tile }?.let { matched += it }

        val player = Bukkit.getPlayer(UUID.fromString(playerUUID)) ?: return
        matched.forEach { display ->
            display.entity?.let {
                runCatching {
                    MahjongPlayPlugin.instance.glowingEntities.setGlowing(it, player, org.bukkit.ChatColor.GOLD)
                }
            }
        }
        highlightedDiscards[playerUUID] = matched
    }

    fun unhighlightDiscards(playerUUID: String) {
        val highlighted = highlightedDiscards.remove(playerUUID) ?: return
        val player = Bukkit.getPlayer(UUID.fromString(playerUUID)) ?: return
        highlighted.forEach { display ->
            display.entity?.let {
                runCatching {
                    MahjongPlayPlugin.instance.glowingEntities.unsetGlowing(it, player)
                }
            }
        }
    }
}
