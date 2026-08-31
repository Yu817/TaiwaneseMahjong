package com.mahjongplay.table

import com.mahjongplay.MahjongPlayPlugin
import com.github.shynixn.mccoroutine.bukkit.minecraftDispatcher
import com.mahjongplay.config.MahjongSettings
import com.mahjongplay.display.BoardRenderer
import com.mahjongplay.game.BotDifficulty
import com.mahjongplay.game.GameStatus
import com.mahjongplay.game.MahjongBot
import com.mahjongplay.game.MahjongGame
import com.mahjongplay.interaction.GameRegistry
import com.mahjongplay.interaction.PaperGameBridge
import com.mahjongplay.model.MahjongRule
import com.mahjongplay.model.Wind
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class SettingsOptionTarget(
    val tableId: UUID,
    val action: String,
)

enum class NumericSettingField {
    BASE,
    TAI,
}

data class NumericSettingPrompt(
    val tableId: UUID,
    val field: NumericSettingField,
)

data class NumericSettingInput(
    val prompt: NumericSettingPrompt,
    val raw: String,
)

enum class ChairInteractionResult {
    SEATED,
    OCCUPIED,
    OTHER_TABLE,
    TABLE_FULL,
    GAME_IN_PROGRESS,
}

class MahjongTableManager(var settings: MahjongSettings) : GameRegistry {

    private val tables = ConcurrentHashMap<UUID, MahjongTableSession>()
    private val playerToTable = ConcurrentHashMap<String, UUID>()
    private val joinInteractionToTable = ConcurrentHashMap<UUID, UUID>()
    private val startInteractionToTable = ConcurrentHashMap<UUID, UUID>()
    private val readyInteractionToTable = ConcurrentHashMap<UUID, UUID>()
    private val settingsInteractionToTable = ConcurrentHashMap<UUID, UUID>()
    private val settingsOptionToTarget = ConcurrentHashMap<UUID, SettingsOptionTarget>()
    private val pendingNumericInputs = ConcurrentHashMap<String, NumericSettingPrompt>()
    private val countdownTasks = ConcurrentHashMap<UUID, Int>()
    private val countdownRemaining = ConcurrentHashMap<UUID, Int>()
    private val tableCounter = AtomicInteger(0)
    private val interruptedPlayers = ConcurrentHashMap.newKeySet<String>()
    private val centerInspectingPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private var dataFolder: File? = null
    private var loading = false
    private var displayRepairTaskId: Int? = null
    private var actionButtonRepairTaskId: Int? = null

    private val CIRCLE_PRESETS = listOf(1, 4, 8, 12, 16)
    private val BOT_PRESETS = listOf(1000L, 2000L, 3000L, 4000L, 5000L)

    fun startDisplayRepairTask() {
        if (displayRepairTaskId != null) return
        displayRepairTaskId = Bukkit.getScheduler().runTaskTimer(
            MahjongPlayPlugin.instance,
            Runnable {
                tables.values.forEach { session ->
                    if (!session.center.isChunkLoaded) return@forEach
                    session.table.spawn()
                    // spawn() can replace a non-persistent Interaction with a
                    // new UUID after a chunk reload. Rebind it even while the
                    // table is empty, otherwise visible join text is inert.
                    registerInteractionMappings(session)
                    if (session.game.status == GameStatus.WAITING && session.game.players.isNotEmpty()) {
                        session.table.showActionButtons(buttonAnchorFor(session))
                    }
                }
            },
            100L,
            100L,
        ).taskId
        actionButtonRepairTaskId = Bukkit.getScheduler().runTaskTimer(
            MahjongPlayPlugin.instance,
            Runnable {
                tables.values.forEach { session ->
                    if (!session.center.isChunkLoaded) return@forEach
                    session.renderer.refreshActionButtons()
                    if (session.game.status == GameStatus.WAITING && session.game.players.isNotEmpty()) {
                        val anchor = buttonAnchorFor(session)
                        if (session.table.settingsMenuOpen) {
                            session.table.refreshSettingsMenu(anchor)
                        } else {
                            session.table.showActionButtons(anchor)
                        }
                    }
                    registerInteractionMappings(session)
                }
            },
            1L,
            6L,
        ).taskId
    }

    fun onChunkLoad(chunk: org.bukkit.Chunk) {
        tables.values.filter { it.center.world == chunk.world && it.center.chunk.x == chunk.x && it.center.chunk.z == chunk.z }.forEach { session ->
            session.table.spawn()
            registerInteractionMappings(session)
            if (session.game.status == GameStatus.WAITING && session.game.players.isNotEmpty()) {
                session.table.showActionButtons(buttonAnchorFor(session))
            }
        }
    }

    fun createRule(
        gameLength: MahjongRule.GameLength? = null,
        roundsOverride: Int? = null,
    ): MahjongRule = settings.createRule(gameLength ?: settings.defaultGameLength, roundsOverride)

    fun createTable(
        center: Location,
        creatorUUID: String,
        creatorName: String,
        rule: MahjongRule = createRule(),
    ): MahjongTableSession {
        val game = MahjongGame(
            rule = rule,
            gameCoroutineContext = MahjongPlayPlugin.instance.minecraftDispatcher,
        )
        val renderer = BoardRenderer(game, center, settings.tableScale, settings.handDistance, settings.wallDistance)
        val bridge = PaperGameBridge(game, renderer, this)
        game.listener = bridge

        val modeText = "台麻·${rule.displayLengthText}"
        val existingNums = tables.values.map { it.humanId.substringAfterLast("]").removeSuffix("號桌").toIntOrNull() ?: 0 }.toSet()
        var tableNum = 1
        while (tableNum in existingNums) tableNum++
        val humanId = "[$modeText]${tableNum}號桌"

        val session = MahjongTableSession(
            tableId = game.tableId,
            game = game,
            renderer = renderer,
            bridge = bridge,
            center = center,
            table = MahjongTable(center, modeText, 4, rule.chairsEnabled, settings.tableScale),
            humanId = humanId
        )

        tables[session.tableId] = session
        autoSave()

        return session
    }

    fun registerJoinInteraction(session: MahjongTableSession) {
        if (session.table.joinInteraction == null) return
        registerInteractionMappings(session)
        updateTableDisplay(session)
    }

    fun joinTable(tableId: UUID, playerUUID: String, playerName: String): Boolean {
        if (playerToTable.containsKey(playerUUID)) return false
        val session = tables[tableId] ?: return false

        // 若該玩家先前在對局中離開/斷線被託管，此處無縫接管恢復手動
        if (session.game.status == GameStatus.PLAYING) {
            val existing = session.game.players.find { it.uuid == playerUUID } as? com.mahjongplay.game.MahjongPlayer
            if (existing != null && existing.isBotTakeover) {
                existing.deactivateBotTakeover()
                playerToTable[playerUUID] = tableId
                session.renderer.updateSeatScoreDisplays()
                session.renderer.updateVisibility(existing)
                session.bridge.updateHud()
                val bp = Bukkit.getPlayer(UUID.fromString(playerUUID))
                if (bp != null) {
                    teleportSinglePlayerToSeat(session, existing, bp)
                }
                session.bridge.broadcast(
                    Component.text("[麻將] 玩家【${existing.rawDisplayName}】重新返回牌桌，已解除託管恢復手動操作！", NamedTextColor.GREEN)
                )
                return true
            }
            return false
        }

        if (!session.game.join(playerUUID, playerName)) return false
        playerToTable[playerUUID] = tableId
        if (session.ownerUUID == null) session.ownerUUID = playerUUID
        updateTableDisplay(session)
        return true
    }

    fun getTableByJoinInteraction(interactionUUID: UUID): MahjongTableSession? {
        val tableId = joinInteractionToTable[interactionUUID] ?: return null
        return tables[tableId]
    }

    fun getTableByStartInteraction(interactionUUID: UUID): MahjongTableSession? {
        val tableId = startInteractionToTable[interactionUUID] ?: return null
        return tables[tableId]
    }

    fun getTableByReadyInteraction(interactionUUID: UUID): MahjongTableSession? {
        val tableId = readyInteractionToTable[interactionUUID] ?: return null
        return tables[tableId]
    }

    fun getTableBySettingsInteraction(interactionUUID: UUID): MahjongTableSession? =
        settingsInteractionToTable[interactionUUID]?.let { tables[it] }

    fun getSettingsOptionTarget(interactionUUID: UUID): SettingsOptionTarget? =
        settingsOptionToTarget[interactionUUID]

    /**
     * Join and sit when a player right-clicks the exact barrier block under a
     * table chair. Entity/display hitboxes are intentionally not involved.
     */
    fun handleChairBlockInteraction(player: Player, blockLocation: Location): ChairInteractionResult? {
        val session = tables.values.firstOrNull { it.table.isChairBlock(blockLocation) } ?: return null
        if (session.table.isChairOccupiedBy(blockLocation, player.uniqueId)) return null

        val playerUUID = player.uniqueId.toString()
        val isPlayerInGame = session.game.players.any { it.uuid == playerUUID }

        // 如果遊戲正在進行中，允許本局參賽玩家重新坐回椅子
        if (session.game.status != GameStatus.WAITING) {
            if (!isPlayerInGame) return ChairInteractionResult.GAME_IN_PROGRESS
            if (session.table.sitAtChair(blockLocation, player)) {
                return ChairInteractionResult.SEATED
            }
            return ChairInteractionResult.OCCUPIED
        }

        val existingSession = getSessionForPlayer(playerUUID)
        if (existingSession != null && existingSession.tableId != session.tableId) {
            return ChairInteractionResult.OTHER_TABLE
        }
        if (!session.table.isChairAvailable(blockLocation, player.uniqueId)) {
            return ChairInteractionResult.OCCUPIED
        }

        var joinedHere = false
        if (existingSession == null) {
            if (!joinTable(session.tableId, playerUUID, player.name)) {
                return ChairInteractionResult.TABLE_FULL
            }
            joinedHere = true
        }

        if (session.table.sitAtChair(blockLocation, player)) {
            return ChairInteractionResult.SEATED
        }

        if (joinedHere) leaveTable(playerUUID)
        return ChairInteractionResult.OCCUPIED
    }

    fun releasePlayerFromChairs(playerUUID: UUID) {
        tables.values.forEach { session ->
            session.table.releasePlayerFromChairs(playerUUID)
        }
    }

    fun minRequiredBalance(rule: com.mahjongplay.model.MahjongRule): Double {
        if (settings.economyMinBalance > 0.0) {
            return settings.economyMinBalance
        }
        val dynamicAmount = (rule.basePoints + rule.pointsPerTai * 16).toDouble()
        return dynamicAmount.coerceAtLeast(0.0)
    }

    fun canPlayerReady(session: MahjongTableSession, playerUUID: String): String? {
        val rule = session.game.rule
        if (rule.moneyMatch && !rule.botsEnabled && settings.economyEnabled) {
            val economy = MahjongPlayPlugin.instance.currentEconomy()
            if (economy == null) {
                return "金幣對戰需要經濟系統：${MahjongPlayPlugin.instance.economyUnavailableReason()}"
            }
            val required = minRequiredBalance(rule)
            if (required > 0.0) {
                val bal = economy.balance(playerUUID)
                if (bal < required) {
                    return "你的餘額不足入場門檻（目前：${economy.format(bal)}，需要：${economy.format(required)}）！"
                }
            }
        }
        return null
    }

    fun startGame(session: MahjongTableSession): String? {
        if (session.game.status != GameStatus.WAITING) return "遊戲已在進行中"
        if (session.game.players.isEmpty()) return "目前沒有玩家"

        val rule = session.game.rule
        if (rule.moneyMatch && !rule.botsEnabled) {
            if (!settings.economyEnabled) {
                return "目前已停用伺服器經濟系統；請在牌桌設定中切換為【休閒娛樂局】或開啟機器人。"
            }
            val economy = MahjongPlayPlugin.instance.currentEconomy()
            if (economy == null) {
                return "金幣對戰需要經濟系統：${MahjongPlayPlugin.instance.economyUnavailableReason()}"
            }
            val required = minRequiredBalance(rule)
            if (required > 0.0) {
                val brokePlayers = session.game.players.filterIsInstance<com.mahjongplay.game.MahjongPlayer>().filter {
                    economy.balance(it.uuid) < required
                }
                if (brokePlayers.isNotEmpty()) {
                    val formattedReq = economy.format(required)
                    val detail = brokePlayers.joinToString("、") { p ->
                        "${p.displayName} (餘額: ${economy.format(economy.balance(p.uuid))})"
                    }
                    return "以下玩家餘額不足入場門檻（需要 $formattedReq）：$detail"
                }
            }
        }

        val unready = session.game.players.filter { it.isRealPlayer && !it.ready }
        if (unready.isNotEmpty()) return "還有玩家未準備：${unready.joinToString { it.displayName }}"

        val pc = session.game.rule.playerCount
        if (session.game.rule.botsEnabled) {
            while (session.game.players.size < pc) {
                val botNum = session.game.players.count { !it.isRealPlayer } + 1
                session.game.addBot("Bot$botNum", session.game.rule.defaultBotDifficulty)
            }
        } else {
            if (session.game.players.size < pc) {
                return "牌桌設定中已關閉 Bots（機器人），需要湊滿 $pc 位真人玩家才能開始！"
            }
        }

        session.table.releaseAllChairPassengers()
        session.table.hideActionButtons()
        startInteractionToTable.values.removeAll { it == session.tableId }
        readyInteractionToTable.values.removeAll { it == session.tableId }

        session.game.start()
        updateTableDisplay(session)
        return null
    }

    fun leaveTable(playerUUID: String): Boolean {
        pendingNumericInputs.remove(playerUUID)
        val tableId = playerToTable[playerUUID] ?: return false
        val session = tables[tableId] ?: return false
        val wasPlaying = session.game.status == GameStatus.PLAYING

        runCatching { UUID.fromString(playerUUID) }.getOrNull()?.let { pUid ->
            if (centerInspectingPlayers.remove(pUid)) {
                Bukkit.getPlayer(pUid)?.let { p ->
                    Bukkit.getOnlinePlayers().filter { it.uniqueId != pUid }.forEach { other ->
                        other.showPlayer(MahjongPlayPlugin.instance, p)
                    }
                    getRenderer(session.game)?.showFloatingCenterTileFor(p)
                }
            }
            getRenderer(session.game)?.clearHoverRemainingFor(playerUUID)
            session.table.releasePlayerFromChairs(pUid)
        }

        session.bridge.hideBarForPlayer(playerUUID)
        runCatching { UUID.fromString(playerUUID) }.getOrNull()?.let { Bukkit.getPlayer(it) }?.let {
            session.table.revealFloatingTextsTo(it)
        }
        playerToTable.remove(playerUUID)

        if (wasPlaying) {
            val leavingPlayer = session.game.players.find { it.uuid == playerUUID } as? com.mahjongplay.game.MahjongPlayer
            if (leavingPlayer != null) {
                leavingPlayer.activateBotTakeover(session.game.rule.defaultBotDifficulty, offline = true)
                session.bridge.broadcast(
                    Component.text("[麻將] 玩家【${leavingPlayer.rawDisplayName}】中途離開，已由 🤖 機器人接管代打！", NamedTextColor.GOLD)
                )
                session.renderer.updateSeatScoreDisplays()
            }
            val remainingRealPlayers = session.game.players.count { it is com.mahjongplay.game.MahjongPlayer && !it.isBotTakeover }
            if (remainingRealPlayers == 0) {
                session.bridge.broadcast(
                    Component.text("[麻將] 牌桌上所有真人玩家皆已離開，牌局結束。", NamedTextColor.GRAY)
                )
                session.game.end()
                session.game.players.clear()
            }
        } else {
            session.game.leave(playerUUID)
            if (session.game.realPlayers.isEmpty()) {
                session.game.players.removeAll { it is MahjongBot }
            }
            if (session.ownerUUID == playerUUID) {
                session.ownerUUID = session.game.realPlayers.firstOrNull()?.uuid
            }
            cancelCountdown(session.tableId)
            updateTableDisplay(session)
        }
        return true
    }

    fun cancelGame(session: MahjongTableSession): Boolean {
        if (session.game.status == GameStatus.WAITING) return false

        cancelCountdown(session.tableId)
        session.game.cancelGame()
        session.bridge.cleanup()
        session.renderer.clearAllDisplays()

        // 移除所有補位機器人，保留真人玩家並重設準備狀態
        val bots = session.game.players.filter { !it.isRealPlayer }
        bots.forEach { bot ->
            session.game.leave(bot.uuid)
            playerToTable.remove(bot.uuid)
        }
        session.game.realPlayers.filterIsInstance<com.mahjongplay.game.MahjongPlayer>().forEach { mjPlayer ->
            mjPlayer.ready = false
            if (mjPlayer.isBotTakeover) {
                mjPlayer.deactivateBotTakeover()
            }
        }

        session.table.updateTurnDisplay(null)
        updateTableDisplay(session)
        session.table.showActionButtons()
        registerJoinInteraction(session)

        session.game.realPlayers.forEach { mjPlayer ->
            val p = runCatching { Bukkit.getPlayer(UUID.fromString(mjPlayer.uuid)) }.getOrNull()
            p?.scoreboard = Bukkit.getScoreboardManager().mainScoreboard
            p?.sendMessage(Component.text("[麻將] 管理員已強制取消並重設當前牌局！", NamedTextColor.RED))
        }
        autoSave()
        return true
    }

    fun destroyTable(tableId: UUID) {
        val session = tables.remove(tableId) ?: return
        cancelCountdown(tableId)
        session.game.end()
        session.renderer.clearAllDisplays()
        session.bridge.cleanup()
        session.table.joinInteraction?.uniqueId?.let { joinInteractionToTable.remove(it) }
        session.table.startInteraction?.uniqueId?.let { startInteractionToTable.remove(it) }
        session.table.readyInteraction?.uniqueId?.let { readyInteractionToTable.remove(it) }
        session.table.settingsInteraction?.uniqueId?.let { settingsInteractionToTable.remove(it) }
        session.table.settingsOptionInteractions.forEach { settingsOptionToTarget.remove(it.uniqueId) }
        session.table.destroy()
        session.game.players.forEach { playerToTable.remove(it.uuid) }
        autoSave()
    }

    fun updateTableDisplay(session: MahjongTableSession) {
        val isWaiting = session.game.status == GameStatus.WAITING
        session.table.gameLengthText = session.game.rule.displayLengthText
        session.table.applyChairsEnabled(session.game.rule.chairsEnabled)
        session.table.updatePublicSettingsDisplay(
            if (isWaiting) publicSettingsPanelText(session) else null
        )
        val playerInfo = session.game.players.map {
            val name = if (it.uuid == session.ownerUUID) "♛ ${it.displayName}" else it.displayName
            name to it.ready
        }
        val buttonAnchor = buttonAnchorFor(session)
        session.table.updateJoinDisplay(
            playerCount = session.game.players.size,
            maxPlayers = session.game.rule.playerCount,
            waiting = isWaiting,
            playerInfo = playerInfo,
            buttonAnchor = buttonAnchor,
        )
        if (!isWaiting) {
            val playingUUIDs = session.game.players.filter { it.isRealPlayer }.map { it.uuid }.toSet()
            session.table.setHiddenFromPlayers(playingUUIDs)
        } else {
            session.table.revealAllFloatingTexts()
        }
        if (isWaiting && session.game.players.isNotEmpty()) {
            session.table.showActionButtons(buttonAnchor)
        }
        session.table.startInteraction?.uniqueId?.let { startInteractionToTable[it] = session.tableId }
        session.table.readyInteraction?.uniqueId?.let { readyInteractionToTable[it] = session.tableId }
        registerInteractionMappings(session)
    }

    fun openSettingsMenu(session: MahjongTableSession, viewer: Player) {
        if (session.game.status != GameStatus.WAITING) return
        MahjongSettingsGUI.open(viewer, session, this)
    }

    private fun createSettingsDialog(session: MahjongTableSession, viewer: Player): Dialog {
        val owner = isTableOwner(session, viewer.uniqueId.toString())
        val rule = session.game.rule
        val inputs = if (owner) {
            listOf(
                DialogInput.text("base", Component.text("底金積分", NamedTextColor.YELLOW))
                    .width(175)
                    .labelVisible(true)
                    .initial(rule.basePoints.toString())
                    .maxLength(7)
                    .build(),
                DialogInput.text("tai", Component.text("每台積分", NamedTextColor.GOLD))
                    .width(175)
                    .labelVisible(true)
                    .initial(rule.pointsPerTai.toString())
                    .maxLength(7)
                    .build(),
            )
        } else {
            emptyList()
        }

        val base = DialogBase.builder(Component.text("⚙ 牌桌設定", NamedTextColor.AQUA))
            .externalTitle(Component.text("麻將牌桌設定", NamedTextColor.AQUA))
            .canCloseWithEscape(true)
            .pause(false)
            .afterAction(DialogBase.DialogAfterAction.CLOSE)
            .body(listOf(DialogBody.plainMessage(settingsDialogSummary(session, viewer), 360)))
            .inputs(inputs)
            .build()

        val closeButton = ActionButton.builder(Component.text("✕ 關閉視窗", NamedTextColor.GRAY))
            .tooltip(Component.text("關閉設定視窗", NamedTextColor.GRAY))
            .width(175)
            .build()

        val type = if (owner) {
            val buttons = mutableListOf<ActionButton>()
            // Row 1: 賽制長度與抓位
            buttons += settingsDialogButton(session, "round_next", "🀄 圈數：${rule.displayCircleText}", NamedTextColor.AQUA, "點擊循環切換局數 (1/4圈 ~ 4圈)")
            buttons += settingsDialogButton(
                session,
                "seat_wind_toggle",
                "🧭 抓風：${if (rule.seatWindDrawEnabled) "開啟 ✓" else "關閉 ✗"}",
                if (rule.seatWindDrawEnabled) NamedTextColor.GOLD else NamedTextColor.DARK_GRAY,
                "點擊切換開局抓風選位流程",
            )

            // Row 2: 機器人開關與難度
            buttons += settingsDialogButton(
                session,
                "bots_toggle",
                "🤖 機器人：${if (rule.botsEnabled) "開啟 ✓" else "關閉 ✗"}",
                if (rule.botsEnabled) NamedTextColor.GREEN else NamedTextColor.RED,
                "點擊開啟或關閉機器人補位",
            )
            buttons += settingsDialogButton(
                session,
                "bot_difficulty_next",
                "🎯 難度：${rule.defaultBotDifficulty.displayName}",
                if (rule.botsEnabled) NamedTextColor.GOLD else NamedTextColor.DARK_GRAY,
                "點擊循環切換機器人難度",
            )

            // Row 3: 機器人速度與旁觀
            buttons += settingsDialogButton(
                session,
                "bot_speed_next",
                "⚡ 速度：${(rule.botResponseDelayMs / 1000L).coerceIn(1L, 5L)}秒",
                if (rule.botsEnabled) NamedTextColor.GREEN else NamedTextColor.DARK_GRAY,
                "點擊循環切換機器人思考速度 (1~5秒)",
            )
            buttons += settingsDialogButton(
                session,
                "spectators_toggle",
                "👁 旁觀：${if (rule.spectatorSeeHands) "允許看牌 ✓" else "隱藏手牌 ✗"}",
                if (rule.spectatorSeeHands) NamedTextColor.BLUE else NamedTextColor.DARK_GRAY,
                "點擊切換旁觀者是否可看所有玩家手牌",
            )

            // Row 4: 麻將規則與場景設施
            buttons += settingsDialogButton(
                session,
                "flowers_toggle",
                "🌸 花牌：${if (rule.flowersEnabled) "開啟 ✓" else "關閉 ✗"}",
                if (rule.flowersEnabled) NamedTextColor.LIGHT_PURPLE else NamedTextColor.DARK_GRAY,
                "點擊切換是否啟用 8 張花牌規則",
            )
            buttons += settingsDialogButton(
                session,
                "chairs_toggle",
                "🪑 椅子：${if (rule.chairsEnabled) "開啟 ✓" else "關閉 ✗"}",
                if (rule.chairsEnabled) NamedTextColor.LIGHT_PURPLE else NamedTextColor.DARK_GRAY,
                "點擊切換是否在牌桌旁生成實體坐椅",
            )

            // Row 5: 數值確認與重設
            buttons += settingsDialogButton(session, "apply_values", "💾 套用底台積分", NamedTextColor.YELLOW, "點擊將上方輸入框的底／台數值套用至牌桌")
            buttons += settingsDialogButton(session, "reset_defaults", "🔄 還原預設設定", NamedTextColor.RED, "點擊將所有牌桌規則還原為預設值")

            DialogType.multiAction(
                buttons,
                closeButton,
                2,
            )
        } else {
            DialogType.notice(closeButton)
        }

        return Dialog.create { factory ->
            factory.empty()
                .base(base)
                .type(type)
        }
    }

    private fun settingsDialogButton(
        session: MahjongTableSession,
        action: String,
        label: String,
        color: NamedTextColor,
        tooltipText: String = "點擊更新牌桌設定",
    ): ActionButton = ActionButton.builder(Component.text(label, color))
        .tooltip(Component.text(tooltipText, NamedTextColor.GRAY))
        .width(175)
        .action(dialogAction(session.tableId, action))
        .build()

    private fun dialogAction(tableId: UUID, action: String): DialogAction = DialogAction.customClick(
        DialogActionCallback { response, audience ->
            if (audience is Player) {
                Bukkit.getScheduler().runTask(
                    MahjongPlayPlugin.instance,
                    Runnable { handleSettingsDialogAction(tableId, audience, action, response) },
                )
            }
        },
        ClickCallback.Options.builder().uses(1).build(),
    )

    private fun handleSettingsDialogAction(
        tableId: UUID,
        player: Player,
        action: String,
        response: DialogResponseView,
    ) {
        val session = tables[tableId] ?: return
        if (session.game.status != GameStatus.WAITING) {
            player.sendMessage(Component.text("[麻將] 遊戲已開始，不能調整設定。", NamedTextColor.RED))
            return
        }
        if (!isTableOwner(session, player.uniqueId.toString())) {
            player.sendMessage(Component.text("[麻將] 只有桌主可以調整設定。", NamedTextColor.RED))
            return
        }

        val updated = session.game.rule.copy()
        when (action) {
            "round_next" -> updated.roundsToPlay = nextPreset(updated.roundsToPlay, CIRCLE_PRESETS)
            "bots_toggle" -> updated.botsEnabled = !updated.botsEnabled
            "bot_difficulty_next" -> {
                val allDiffs = BotDifficulty.entries
                val nextIdx = (allDiffs.indexOf(updated.defaultBotDifficulty) + 1) % allDiffs.size
                updated.defaultBotDifficulty = allDiffs[nextIdx]
                session.game.players.filterIsInstance<com.mahjongplay.game.MahjongBot>().forEach { it.difficulty = updated.defaultBotDifficulty }
            }
            "bot_speed_next", "bot_next" -> updated.botResponseDelayMs = nextPreset(updated.botResponseDelayMs, BOT_PRESETS)
            "flowers_toggle" -> updated.flowersEnabled = !updated.flowersEnabled
            "chairs_toggle" -> updated.chairsEnabled = !updated.chairsEnabled
            "seat_wind_toggle" -> updated.seatWindDrawEnabled = !updated.seatWindDrawEnabled
            "spectators_toggle" -> {
                updated.spectatorSeeHands = !updated.spectatorSeeHands
                session.game.players.forEach { session.renderer.updateVisibility(it) }
            }
            "reset_defaults" -> {
                val defaultRule = settings.createRule()
                updated.roundsToPlay = defaultRule.roundsToPlay
                updated.botsEnabled = defaultRule.botsEnabled
                updated.defaultBotDifficulty = defaultRule.defaultBotDifficulty
                updated.botResponseDelayMs = defaultRule.botResponseDelayMs
                updated.flowersEnabled = defaultRule.flowersEnabled
                updated.chairsEnabled = defaultRule.chairsEnabled
                updated.seatWindDrawEnabled = defaultRule.seatWindDrawEnabled
                updated.spectatorSeeHands = defaultRule.spectatorSeeHands
                updated.basePoints = defaultRule.basePoints
                updated.pointsPerTai = defaultRule.pointsPerTai
            }
            "apply_values" -> {
                val base = response.getText("base")
                    ?.trim()
                    ?.replace(",", "")
                    ?.toIntOrNull()
                val tai = response.getText("tai")
                    ?.trim()
                    ?.replace(",", "")
                    ?.toIntOrNull()
                if (base == null || tai == null || base !in 0..MahjongRule.MAX_POINTS || tai !in 0..MahjongRule.MAX_POINTS) {
                    player.sendMessage(
                        Component.text(
                            "[麻將] 底金與每台積分必須是 0～${MahjongRule.MAX_POINTS} 的整數。",
                            NamedTextColor.RED,
                        )
                    )
                    reopenSettingsDialog(session, player)
                    return
                }
                updated.basePoints = base
                updated.pointsPerTai = tai
            }
            else -> return
        }

        session.game.changeRules(updated)
        updateTableDisplay(session)
        autoSave()
        player.sendMessage(Component.text("[麻將] 設定已更新，其他玩家需要重新準備。", NamedTextColor.GREEN))
        reopenSettingsDialog(session, player)
    }

    private fun reopenSettingsDialog(session: MahjongTableSession, player: Player) {
        Bukkit.getScheduler().runTaskLater(
            MahjongPlayPlugin.instance,
            Runnable {
                if (player.isOnline && tables[session.tableId] === session && session.game.status == GameStatus.WAITING) {
                    player.showDialog(createSettingsDialog(session, player))
                }
            },
            1L,
        )
    }

    private fun settingsDialogSummary(session: MahjongTableSession, viewer: Player): Component {
        val rule = session.game.rule
        val ownerName = session.game.players
            .firstOrNull { it.uuid == session.ownerUUID }
            ?.displayName
            ?: "尚未加入"
        val isOwner = isTableOwner(session, viewer.uniqueId.toString())

        val botSeconds = (rule.botResponseDelayMs / 1000L).coerceIn(1L, 5L)
        val botsText = if (rule.botsEnabled) "開啟（${rule.defaultBotDifficulty.displayName}・${botSeconds}秒）" else "關閉"
        val flowersText = if (rule.flowersEnabled) "開啟" else "關閉"
        val chairsText = if (rule.chairsEnabled) "開啟" else "關閉"
        val seatWindText = if (rule.seatWindDrawEnabled) "開啟" else "關閉"
        val spectatorText = if (rule.spectatorSeeHands) "看牌" else "隱藏"

        val hint = if (isOwner) {
            "💡 提示：點擊按鈕可直接切換；若修改底金或每台，請輸入後按【💾 套用底台積分】。"
        } else {
            "🔒 目前為檢視模式（僅桌主可變更設定）。"
        }

        return Component.text("👑 桌主：", NamedTextColor.GOLD)
            .append(Component.text(ownerName, NamedTextColor.YELLOW))
            .append(Component.text("   🀄 圈數：", NamedTextColor.AQUA))
            .append(Component.text(rule.displayCircleText, NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("💰 底／台：", NamedTextColor.GOLD))
            .append(Component.text("${rule.basePoints} 底 / ${rule.pointsPerTai} 台", NamedTextColor.GREEN))
            .append(Component.text("   🌸 花牌：", NamedTextColor.LIGHT_PURPLE))
            .append(Component.text(flowersText, if (rule.flowersEnabled) NamedTextColor.GREEN else NamedTextColor.RED))
            .append(Component.newline())
            .append(Component.text("🤖 Bots：", NamedTextColor.BLUE))
            .append(Component.text(botsText, if (rule.botsEnabled) NamedTextColor.GREEN else NamedTextColor.RED))
            .append(Component.newline())
            .append(Component.text("🧭 抓風：$seatWindText  •  🪑 椅子：$chairsText  •  👁 旁觀：$spectatorText", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text(hint, if (isOwner) NamedTextColor.AQUA else NamedTextColor.GRAY))
    }

    private fun publicSettingsPanelText(session: MahjongTableSession): Component {
        val rule = session.game.rule
        val ownerName = session.game.players
            .firstOrNull { it.uuid == session.ownerUUID }
            ?.displayName
            ?: "尚未加入"
        val flowers = if (rule.flowersEnabled) "開啟" else "關閉"
        val chairs = if (rule.chairsEnabled) "開啟" else "關閉"
        val botSeconds = (rule.botResponseDelayMs / 1000L).coerceIn(1L, 5L)
        val botsText = if (rule.botsEnabled) "開啟（${rule.defaultBotDifficulty.displayName}・${botSeconds}秒）" else "關閉"
        return Component.text("⚙ 牌桌設定", NamedTextColor.AQUA)
            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
            .append(Component.newline())
            .append(Component.text("桌主：$ownerName", NamedTextColor.GOLD))
            .append(Component.newline())
            .append(Component.text("圈數：${rule.displayCircleText}  •  Bots：$botsText", NamedTextColor.GREEN))
            .append(Component.newline())
            .append(Component.text("底／台：${rule.basePoints}／${rule.pointsPerTai} 積分  •  花牌：$flowers", NamedTextColor.YELLOW))
            .append(Component.newline())
            .append(Component.text("椅子：$chairs", NamedTextColor.LIGHT_PURPLE))
    }

    fun isTableOwner(session: MahjongTableSession, playerUUID: String): Boolean =
        session.ownerUUID == playerUUID

    fun handleSettingsOption(session: MahjongTableSession, playerUUID: String, action: String) {
        if (session.game.status != GameStatus.WAITING) return
        if (session.ownerUUID != playerUUID) {
            Bukkit.getPlayer(UUID.fromString(playerUUID))?.sendMessage(
                Component.text("[麻將] 只有桌主可以調整牌桌設定；目前設定對所有人公開。", NamedTextColor.RED)
            )
            return
        }

        if (action == "close") {
            session.table.hideSettingsMenu()
            registerInteractionMappings(session)
            return
        }

        val updated = session.game.rule.copy()
        when (action) {
            "round_next" -> updated.roundsToPlay = nextPreset(updated.roundsToPlay, CIRCLE_PRESETS)
            "bots_toggle" -> updated.botsEnabled = !updated.botsEnabled
            "bot_difficulty_next" -> {
                val allDiffs = BotDifficulty.entries
                val nextIdx = (allDiffs.indexOf(updated.defaultBotDifficulty) + 1) % allDiffs.size
                updated.defaultBotDifficulty = allDiffs[nextIdx]
                session.game.players.filterIsInstance<com.mahjongplay.game.MahjongBot>().forEach { it.difficulty = updated.defaultBotDifficulty }
            }
            "bot_speed_next", "bot_next" -> updated.botResponseDelayMs = nextPreset(updated.botResponseDelayMs, BOT_PRESETS)
            "base_input" -> {
                beginNumericInput(session, playerUUID, NumericSettingField.BASE)
                return
            }
            "tai_input" -> {
                beginNumericInput(session, playerUUID, NumericSettingField.TAI)
                return
            }
            "flowers_toggle" -> updated.flowersEnabled = !updated.flowersEnabled
            "chairs_toggle" -> updated.chairsEnabled = !updated.chairsEnabled
            "seat_wind_toggle" -> updated.seatWindDrawEnabled = !updated.seatWindDrawEnabled
            "spectators_toggle" -> {
                updated.spectatorSeeHands = !updated.spectatorSeeHands
                session.game.players.forEach { session.renderer.updateVisibility(it) }
            }
            else -> return
        }

        session.game.changeRules(updated)
        updateTableDisplay(session)
        refreshSettingsMenu(session)
        Bukkit.getPlayer(UUID.fromString(playerUUID))?.sendMessage(
            Component.text("[麻將] 設定已更新，其他玩家需要重新準備。", NamedTextColor.GREEN)
        )
    }

    private fun refreshSettingsMenu(session: MahjongTableSession) {
        if (!session.table.settingsMenuOpen) return
        session.table.showSettingsMenu(
            settingsPanelText(session),
            settingsMenuOptions(session.game.rule),
            buttonAnchorFor(session),
        )
        registerInteractionMappings(session)
    }

    private fun settingsPanelText(session: MahjongTableSession): Component {
        val rule = session.game.rule
        val ownerName = session.game.players
            .firstOrNull { it.uuid == session.ownerUUID }
            ?.displayName
            ?: "尚未加入"
        val flowers = if (rule.flowersEnabled) "開啟" else "關閉"
        val chairs = if (rule.chairsEnabled) "開啟" else "關閉"
        val botSeconds = (rule.botResponseDelayMs / 1000L).coerceIn(1L, 5L)
        val botsText = if (rule.botsEnabled) "開啟（${rule.defaultBotDifficulty.displayName}・${botSeconds}秒）" else "關閉"
        return Component.text("⚙ 牌桌設定", NamedTextColor.AQUA)
            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
            .append(Component.newline())
            .append(Component.text("桌主：$ownerName", NamedTextColor.GOLD))
            .append(Component.newline())
            .append(Component.text("圈數：${rule.displayCircleText}  •  Bots：$botsText", NamedTextColor.GREEN))
            .append(Component.newline())
            .append(Component.text("底／台：${rule.basePoints}／${rule.pointsPerTai} 積分  •  花牌：$flowers", NamedTextColor.YELLOW))
            .append(Component.newline())
            .append(Component.text("椅子：$chairs", NamedTextColor.LIGHT_PURPLE))
            .append(Component.newline())
            .append(Component.text("桌主可點擊按鈕修改；底／台請依聊天提示輸入", NamedTextColor.GRAY))
    }

    private fun settingsMenuOptions(rule: MahjongRule): List<SettingsMenuOption> {
        val list = mutableListOf<SettingsMenuOption>()
        var slot = 0
        list += SettingsMenuOption("round_next", "圈數 ▶", NamedTextColor.AQUA, slot++, 0)
        list += SettingsMenuOption(
            "bots_toggle",
            "Bots ${if (rule.botsEnabled) "開啟" else "關閉"}",
            if (rule.botsEnabled) NamedTextColor.GREEN else NamedTextColor.RED,
            slot++,
            0
        )
        if (rule.botsEnabled) {
            list += SettingsMenuOption(
                "bot_difficulty_next",
                "難度 ${rule.defaultBotDifficulty.displayName}",
                NamedTextColor.GOLD,
                slot++,
                0
            )
            list += SettingsMenuOption(
                "bot_speed_next",
                "速度 ${(rule.botResponseDelayMs / 1000L).coerceIn(1L, 5L)}秒",
                NamedTextColor.GREEN,
                slot++,
                0
            )
        }
        list += SettingsMenuOption("base_input", "底 輸入", NamedTextColor.YELLOW, slot++, 0)
        list += SettingsMenuOption("tai_input", "台 輸入", NamedTextColor.GOLD, slot++, 0)
        list += SettingsMenuOption("flowers_toggle", "✿ 花牌 ${if (rule.flowersEnabled) "開" else "關"}", NamedTextColor.LIGHT_PURPLE, slot++, 0)
        list += SettingsMenuOption("chairs_toggle", "椅子 ${if (rule.chairsEnabled) "開" else "關"}", NamedTextColor.LIGHT_PURPLE, slot++, 0)
        list += SettingsMenuOption("seat_wind_toggle", "抓風 ${if (rule.seatWindDrawEnabled) "開" else "關"}", NamedTextColor.GOLD, slot++, 0)
        list += SettingsMenuOption("spectators_toggle", "旁觀看牌 ${if (rule.spectatorSeeHands) "開" else "關"}", NamedTextColor.BLUE, slot++, 0)
        list += SettingsMenuOption("close", "✕ 關閉", NamedTextColor.GRAY, slot++, 0)
        return list
    }

    private fun beginNumericInput(
        session: MahjongTableSession,
        playerUUID: String,
        field: NumericSettingField,
    ) {
        pendingNumericInputs[playerUUID] = NumericSettingPrompt(session.tableId, field)
        val label = if (field == NumericSettingField.BASE) "底金積分" else "每台積分"
        Bukkit.getPlayer(UUID.fromString(playerUUID))?.sendMessage(
            Component.text(
                "[麻將] 請在聊天輸入$label（0～${MahjongRule.MAX_POINTS} 的整數）；輸入「取消」可取消。",
                NamedTextColor.AQUA,
            )
        )
    }

    /** Consume the next chat message only when this player has an active prompt. */
    fun consumeNumericInput(playerUUID: String, raw: String): NumericSettingInput? =
        pendingNumericInputs.remove(playerUUID)?.let { NumericSettingInput(it, raw.trim()) }

    /** Apply a consumed prompt on the Bukkit main thread. */
    fun applyNumericInput(playerUUID: String, input: NumericSettingInput) {
        val player = Bukkit.getPlayer(UUID.fromString(playerUUID)) ?: return
        val session = tables[input.prompt.tableId]
        if (session == null || session.game.status != GameStatus.WAITING) {
            player.sendMessage(Component.text("[麻將] 這張牌桌目前不能修改設定。", NamedTextColor.RED))
            return
        }
        if (session.ownerUUID != playerUUID) {
            player.sendMessage(Component.text("[麻將] 只有桌主可以修改設定。", NamedTextColor.RED))
            return
        }

        if (input.raw.equals("取消", ignoreCase = true) || input.raw.equals("cancel", ignoreCase = true)) {
            player.sendMessage(Component.text("[麻將] 已取消輸入。", NamedTextColor.YELLOW))
            return
        }

        val value = input.raw.replace(",", "").toIntOrNull()
        if (value == null || value !in 0..MahjongRule.MAX_POINTS) {
            player.sendMessage(
                Component.text(
                    "[麻將] 請輸入 0～${MahjongRule.MAX_POINTS} 的整數；輸入「取消」可取消。",
                    NamedTextColor.RED,
                )
            )
            return
        }

        val updated = session.game.rule.copy()
        val label = when (input.prompt.field) {
            NumericSettingField.BASE -> {
                updated.basePoints = value
                "底金積分"
            }
            NumericSettingField.TAI -> {
                updated.pointsPerTai = value
                "每台積分"
            }
        }
        session.game.changeRules(updated)
        updateTableDisplay(session)
        refreshSettingsMenu(session)
        player.sendMessage(Component.text("[麻將] $label 已設為 $value；其他玩家需要重新準備。", NamedTextColor.GREEN))
    }

    fun clearPendingNumericInput(playerUUID: String) {
        pendingNumericInputs.remove(playerUUID)
    }

    private fun <T : Comparable<T>> nextPreset(value: T, presets: List<T>): T {
        val index = presets.indexOf(value).takeIf { it >= 0 } ?: -1
        return presets[(index + 1) % presets.size]
    }

    private fun registerInteractionMappings(session: MahjongTableSession) {
        val tableId = session.tableId
        joinInteractionToTable.entries.removeIf { it.value == tableId }
        startInteractionToTable.entries.removeIf { it.value == tableId }
        readyInteractionToTable.entries.removeIf { it.value == tableId }
        settingsInteractionToTable.entries.removeIf { it.value == tableId }
        settingsOptionToTarget.entries.removeIf { it.value.tableId == tableId }

        session.table.joinInteraction?.uniqueId?.let { joinInteractionToTable[it] = tableId }
        session.table.startInteraction?.uniqueId?.let { startInteractionToTable[it] = tableId }
        session.table.readyInteraction?.uniqueId?.let { readyInteractionToTable[it] = tableId }
        session.table.settingsInteraction?.uniqueId?.let { settingsInteractionToTable[it] = tableId }
        session.table.settingsOptionInteractions.forEach { interaction ->
            val action = session.table.settingsOptionAction(interaction.uniqueId) ?: return@forEach
            settingsOptionToTarget[interaction.uniqueId] = SettingsOptionTarget(tableId, action)
        }
    }

    private fun buttonAnchorFor(session: MahjongTableSession): Location? =
        session.game.realPlayers.asSequence()
            .mapNotNull { player ->
                runCatching { Bukkit.getPlayer(UUID.fromString(player.uuid))?.location }.getOrNull()
            }
            .firstOrNull()

    /**
     * Put real players at the same physical sides used by BoardRenderer.
     * Physical seat indices are East, South, West, North.  The game advances
     * from each index to the previous one for counter-clockwise play.
     */
    fun teleportPlayersToSeats(game: MahjongGame) {
        if (!settings.teleportPlayersOnStart) return
        val session = tables[game.tableId] ?: return
        val center = session.center
        // The chair support blocks are exactly two blocks from the table
        // center. The old 2.8-block point was outside those supports and left
        // players standing over air.
        val seatDistance = settings.seatDistance.coerceAtMost(MahjongTable.CHAIR_DISTANCE)
        val directions = listOf(
            doubleArrayOf(1.0, 0.0),
            doubleArrayOf(0.0, 1.0),
            doubleArrayOf(-1.0, 0.0),
            doubleArrayOf(0.0, -1.0),
        )

        game.seat.forEachIndexed { seatIndex, mjPlayer ->
            val player = Bukkit.getPlayer(UUID.fromString(mjPlayer.uuid)) ?: return@forEachIndexed
            val direction = directions[seatIndex % directions.size]
            val location = center.clone().apply {
                x += direction[0] * seatDistance
                y = center.blockY + 1.0
                z += direction[1] * seatDistance
                setDirection(center.toVector().subtract(toVector()))
            }
            player.leaveVehicle()
            player.teleport(location)

            if (game.rule.chairsEnabled) {
                Bukkit.getScheduler().runTaskLater(MahjongPlayPlugin.instance, Runnable {
                    if (player.isValid && !player.isInsideVehicle) {
                        session.table.sitPlayerAtGameSeat(seatIndex, player)
                    }
                }, 2L)
            }

            val windIndex = (Wind.entries.size - ((game.round.round + seatIndex) % Wind.entries.size)) % Wind.entries.size
            val wind = Wind.entries[windIndex]
            player.sendMessage(
                Component.text("[麻將] ", NamedTextColor.GOLD)
                    .append(Component.text("座位已安排：${wind.displayName}家", NamedTextColor.AQUA))
                    .append(Component.text("，你的牌就在面前（按 Shift 可起身走動）。", NamedTextColor.GREEN))
            )
        }
    }

    fun getSeatLocation(game: MahjongGame, playerUUID: String): Location? {
        val session = tables[game.tableId] ?: return null
        val seatIndex = game.seat.indexOfFirst { it.uuid == playerUUID }
        if (seatIndex < 0) return null
        val center = session.center
        val seatDistance = settings.seatDistance.coerceAtMost(MahjongTable.CHAIR_DISTANCE)
        val directions = listOf(
            doubleArrayOf(1.0, 0.0),
            doubleArrayOf(0.0, 1.0),
            doubleArrayOf(-1.0, 0.0),
            doubleArrayOf(0.0, -1.0),
        )
        val direction = directions[seatIndex % directions.size]
        return center.clone().apply {
            x += direction[0] * seatDistance
            y = center.blockY + 1.0
            z += direction[1] * seatDistance
            val lookTarget = center.clone().add(0.0, 0.85, 0.0)
            setDirection(lookTarget.toVector().subtract(toVector()))
        }
    }

    fun getTableCenterInspectionLocation(game: MahjongGame, playerUUID: String): Location? {
        val session = tables[game.tableId] ?: return null
        val seatIndex = game.seat.indexOfFirst { it.uuid == playerUUID }
        if (seatIndex < 0) return null
        val center = session.center
        val directions = listOf(
            doubleArrayOf(1.0, 0.0),
            doubleArrayOf(0.0, 1.0),
            doubleArrayOf(-1.0, 0.0),
            doubleArrayOf(0.0, -1.0),
        )
        val direction = directions[seatIndex % directions.size]
        val camX = center.x + direction[0] * 0.70
        val camY = center.blockY + 1.50
        val camZ = center.z + direction[1] * 0.70

        val eyePos = org.bukkit.util.Vector(camX, camY, camZ)
        val targetPos = org.bukkit.util.Vector(center.x, center.blockY + 0.82, center.z)
        val dirVector = targetPos.subtract(eyePos)

        return Location(center.world, camX, camY, camZ).apply {
            setDirection(dirVector)
        }
    }

    fun isPlayerInspectingCenter(uuid: UUID): Boolean = centerInspectingPlayers.contains(uuid)

    fun handlePlayerToggleSneak(player: Player, isSneaking: Boolean) {
        val uuid = player.uniqueId.toString()
        val game = getGameForPlayer(uuid)
        if (game == null || game.status != GameStatus.PLAYING) {
            if (isSneaking) {
                releasePlayerFromChairs(player.uniqueId)
            }
            return
        }

        if (isSneaking) {
            val inspectLoc = getTableCenterInspectionLocation(game, uuid) ?: return
            centerInspectingPlayers.add(player.uniqueId)
            player.teleport(inspectLoc)
            // 將玩家對其他玩家隱藏，其他玩家看牌桌時不會看到有玩家浮在桌子中央
            Bukkit.getOnlinePlayers().filter { it.uniqueId != player.uniqueId }.forEach { other ->
                other.hidePlayer(MahjongPlayPlugin.instance, player)
            }
            getRenderer(game)?.hideFloatingCenterTileFor(player)
            player.sendActionBar(
                Component.text("🔍 正在俯瞰牌桌中央捨牌（放開 Shift 返回手牌視角）", NamedTextColor.AQUA)
            )
        } else {
            if (centerInspectingPlayers.remove(player.uniqueId)) {
                val seatLoc = getSeatLocation(game, uuid) ?: return
                player.teleport(seatLoc)
                // 恢復玩家對其他玩家可見性
                Bukkit.getOnlinePlayers().filter { it.uniqueId != player.uniqueId }.forEach { other ->
                    other.showPlayer(MahjongPlayPlugin.instance, player)
                }
                getRenderer(game)?.showFloatingCenterTileFor(player)
                player.sendActionBar(
                    Component.text("🀄 已返回手牌視角", NamedTextColor.GREEN)
                )
            }
        }
    }

    fun resetCenterInspection(game: MahjongGame) {
        val renderer = getRenderer(game)
        game.realPlayers.forEach { mjPlayer ->
            val uuid = runCatching { UUID.fromString(mjPlayer.uuid) }.getOrNull() ?: return@forEach
            if (centerInspectingPlayers.remove(uuid)) {
                val player = Bukkit.getPlayer(uuid) ?: return@forEach
                val seatLoc = getSeatLocation(game, mjPlayer.uuid) ?: return@forEach
                player.teleport(seatLoc)
                Bukkit.getOnlinePlayers().filter { it.uniqueId != uuid }.forEach { other ->
                    other.showPlayer(MahjongPlayPlugin.instance, player)
                }
                renderer?.showFloatingCenterTileFor(player)
            }
        }
    }

    fun reseatPlayers(game: MahjongGame) {
        val session = tables[game.tableId] ?: return
        game.realPlayers.forEach { mjPlayer ->
            val uuid = runCatching { UUID.fromString(mjPlayer.uuid) }.getOrNull() ?: return@forEach
            val player = Bukkit.getPlayer(uuid) ?: return@forEach
            val newSeatLoc = getSeatLocation(game, mjPlayer.uuid) ?: return@forEach
            player.teleport(newSeatLoc)
            val seatIndex = game.seat.indexOfFirst { it.uuid == mjPlayer.uuid }
            val windName = com.mahjongplay.model.Wind.entries.getOrNull(seatIndex)?.displayName ?: ""
            player.sendMessage(
                Component.text("[麻將] ", NamedTextColor.GOLD)
                    .append(Component.text("您已就任【${windName}家】座位！", NamedTextColor.GREEN))
            )
        }
        updateTableDisplay(session)
    }

    fun checkAutoStart(session: MahjongTableSession) {
        cancelCountdown(session.tableId)

        val pc = session.game.rule.playerCount
        if (session.game.status != GameStatus.WAITING) return
        if (session.game.players.size != pc) return
        if (!session.game.players.all { it.ready }) return

        countdownRemaining[session.tableId] = 3
        val taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(MahjongPlayPlugin.instance, {
            val remaining = countdownRemaining[session.tableId] ?: return@scheduleSyncRepeatingTask
            if (remaining > 0) {
                session.table.showCountdown(remaining)
                session.game.players.forEach { mjp ->
                    Bukkit.getPlayer(UUID.fromString(mjp.uuid))?.sendActionBar(
                        Component.text("遊戲將在 ${remaining} 秒後開始……", NamedTextColor.GOLD)
                    )
                }
                countdownRemaining[session.tableId] = remaining - 1
            } else {
                cancelCountdown(session.tableId)
                if (session.game.status == GameStatus.WAITING
                    && session.game.players.size == pc
                    && session.game.players.all { it.ready }
                ) {
                    val error = startGame(session)
                    if (error != null) {
                        session.game.realPlayers.forEach { mjp ->
                            Bukkit.getPlayer(UUID.fromString(mjp.uuid))?.sendMessage(
                                Component.text("[麻將] $error", NamedTextColor.RED),
                            )
                        }
                        updateTableDisplay(session)
                    }
                }
            }
        }, 0L, 20L)
        countdownTasks[session.tableId] = taskId
    }

    fun cancelCountdown(tableId: UUID) {
        countdownTasks.remove(tableId)?.let { Bukkit.getScheduler().cancelTask(it) }
        countdownRemaining.remove(tableId)
    }

    fun getSessionForPlayer(playerUUID: String): MahjongTableSession? {
        val tableId = playerToTable[playerUUID] ?: return null
        return tables[tableId]
    }

    fun getSession(tableId: UUID): MahjongTableSession? = tables[tableId]

    fun getAllSessions(): Collection<MahjongTableSession> = tables.values

    fun economyEnabled(): Boolean = settings.economyEnabled

    fun getAllHumanIds(): List<String> = tables.values.map { it.humanId }

    fun getSessionByHumanId(humanId: String): MahjongTableSession? =
        tables.values.find { it.humanId == humanId }

    fun shutdown() {
        displayRepairTaskId?.let { runCatching { Bukkit.getScheduler().cancelTask(it) } }
        displayRepairTaskId = null
        actionButtonRepairTaskId?.let { runCatching { Bukkit.getScheduler().cancelTask(it) } }
        actionButtonRepairTaskId = null
        // Countdown callbacks can otherwise race a PlugMan reload and try to
        // start a table after its entities have already been removed.
        countdownTasks.keys.toList().forEach(::cancelCountdown)
        tables.values.forEach { session ->
            runCatching {
                if (session.game.status == GameStatus.PLAYING) {
                    session.game.end()
                }
                session.renderer.clearAllDisplays()
                session.bridge.cleanup()
                session.table.removeEntities()
                session.game.players.forEach { playerToTable.remove(it.uuid) }
            }
        }
        tables.clear()
        joinInteractionToTable.clear()
        startInteractionToTable.clear()
        readyInteractionToTable.clear()
        settingsInteractionToTable.clear()
        settingsOptionToTarget.clear()
        pendingNumericInputs.clear()
        centerInspectingPlayers.clear()
        interruptedPlayers.clear()
        playerToTable.clear()
    }

    fun saveTables(dataFolder: File) {
        this.dataFolder = dataFolder
        val file = File(dataFolder, "tables.yml")
        val config = YamlConfiguration()
        val tableList = tables.values.map { session ->
            val map = mutableMapOf<String, Any>(
                "world" to session.center.world.name,
                "x" to session.center.blockX,
                "y" to session.center.blockY,
                "z" to session.center.blockZ,
                "gameLength" to session.game.rule.length.name,
                "playerCount" to session.game.rule.playerCount,
                "startingPoints" to session.game.rule.startingPoints,
                "roundsToPlay" to session.game.rule.roundsToPlay,
                "basePoints" to session.game.rule.basePoints,
                "pointsPerTai" to session.game.rule.pointsPerTai,
                "minimumTai" to session.game.rule.minimumTai.name,
                "thinkingTime" to session.game.rule.thinkingTime.name,
                "flowersEnabled" to session.game.rule.flowersEnabled,
                "botResponseDelayMs" to session.game.rule.botResponseDelayMs,
                "drawAnimationMs" to session.game.rule.drawAnimationMs,
                "initialDealAnimationMs" to session.game.rule.initialDealAnimationMs,
                "initialDealGroupPauseMs" to session.game.rule.initialDealGroupPauseMs,
                "openingDiceAnimationMs" to session.game.rule.openingDiceAnimationMs,
                "chairsEnabled" to session.game.rule.chairsEnabled,
                "botsEnabled" to session.game.rule.botsEnabled,
                "defaultBotDifficulty" to session.game.rule.defaultBotDifficulty.name,
                "seatWindDrawEnabled" to session.game.rule.seatWindDrawEnabled,
                "spectatorSeeHands" to session.game.rule.spectatorSeeHands,
            )
            if (session.game.status == GameStatus.PLAYING) {
                map["playingPlayers"] = session.game.realPlayers.map { it.uuid }
            }
            map
        }
        config.set("tables", tableList)
        config.save(file)
    }

    fun autoSave() {
        if (loading) return
        val folder = dataFolder ?: return
        saveTables(folder)
    }

    fun loadTables(dataFolder: File) {
        this.dataFolder = dataFolder
        removeOrphanedPersistentDisplays()
        loading = true
        val file = File(dataFolder, "tables.yml")
        if (!file.exists()) {
            loading = false
            return
        }
        val config = YamlConfiguration.loadConfiguration(file)
        val tableList = config.getMapList("tables")
        tableList.forEach { map ->
            val worldName = map["world"] as? String ?: return@forEach
            val world = Bukkit.getWorld(worldName) ?: return@forEach
            val x = (map["x"] as? Number)?.toInt() ?: return@forEach
            val y = (map["y"] as? Number)?.toInt() ?: return@forEach
            val z = (map["z"] as? Number)?.toInt() ?: return@forEach
            val gameLengthName = map["gameLength"] as? String ?: "TWO_WIND"
            val startingPoints = (map["startingPoints"] as? Number)?.toInt() ?: 0
            val gameLength = try { MahjongRule.GameLength.valueOf(gameLengthName) } catch (_: Exception) { MahjongRule.GameLength.TWO_WIND }
            val roundsToPlay = (map["roundsToPlay"] as? Number)?.toInt()
            val rule = createRule(gameLength, roundsToPlay).apply {
                this.startingPoints = ((map["startingPoints"] as? Number)?.toInt() ?: startingPoints)
                    .coerceIn(0, MahjongRule.MAX_POINTS)
                this.basePoints = ((map["basePoints"] as? Number)?.toInt() ?: this.basePoints)
                    .coerceIn(0, MahjongRule.MAX_POINTS)
                this.pointsPerTai = ((map["pointsPerTai"] as? Number)?.toInt() ?: this.pointsPerTai)
                    .coerceIn(0, MahjongRule.MAX_POINTS)
                this.flowersEnabled = map["flowersEnabled"] as? Boolean ?: this.flowersEnabled
                this.chairsEnabled = map["chairsEnabled"] as? Boolean ?: this.chairsEnabled
                this.botResponseDelayMs = ((map["botResponseDelayMs"] as? Number)?.toLong()
                    ?: this.botResponseDelayMs)
                    .coerceIn(MahjongRule.MIN_BOT_RESPONSE_MS, MahjongRule.MAX_BOT_RESPONSE_MS)
                this.drawAnimationMs = ((map["drawAnimationMs"] as? Number)?.toLong()
                    ?: this.drawAnimationMs)
                    .coerceIn(0L, MahjongRule.MAX_DRAW_ANIMATION_MS)
                this.initialDealAnimationMs = ((map["initialDealAnimationMs"] as? Number)?.toLong()
                    ?: this.initialDealAnimationMs)
                    .coerceIn(0L, MahjongRule.MAX_DRAW_ANIMATION_MS)
                this.initialDealGroupPauseMs = ((map["initialDealGroupPauseMs"] as? Number)?.toLong()
                    ?: this.initialDealGroupPauseMs)
                    .coerceIn(0L, MahjongRule.MAX_DRAW_ANIMATION_MS)
                this.openingDiceAnimationMs = ((map["openingDiceAnimationMs"] as? Number)?.toLong()
                    ?: this.openingDiceAnimationMs)
                    .coerceIn(0L, MahjongRule.MAX_OPENING_DICE_ANIMATION_MS)
                this.minimumTai = (map["minimumTai"] as? String)?.let {
                    runCatching { MahjongRule.MinimumTai.valueOf(it) }.getOrNull()
                } ?: this.minimumTai
                this.thinkingTime = (map["thinkingTime"] as? String)?.let {
                    runCatching { MahjongRule.ThinkingTime.valueOf(it) }.getOrNull()
                } ?: this.thinkingTime
                this.botsEnabled = map["botsEnabled"] as? Boolean ?: this.botsEnabled
                this.defaultBotDifficulty = (map["defaultBotDifficulty"] as? String)?.let {
                    runCatching { BotDifficulty.valueOf(it) }.getOrNull()
                } ?: this.defaultBotDifficulty
                this.seatWindDrawEnabled = map["seatWindDrawEnabled"] as? Boolean ?: this.seatWindDrawEnabled
                this.spectatorSeeHands = map["spectatorSeeHands"] as? Boolean ?: this.spectatorSeeHands
            }

            @Suppress("UNCHECKED_CAST")
            val playingPlayers = map["playingPlayers"] as? List<String> ?: emptyList()
            playingPlayers.forEach { interruptedPlayers.add(it) }

            val center = Location(world, x + 0.5, y.toDouble(), z + 0.5)
            val session = createTable(center, "", "", rule)
            session.table.spawn()
            registerJoinInteraction(session)
        }
        loading = false
    }

    /**
     * Table and chair ItemDisplays are persistent so they survive chunk saves.
     * If a table was destroyed while the plugin reference was unavailable, the
     * entity can outlive the saved table. Remove only entities carrying this
     * plugin's private tags before rebuilding tables from tables.yml.
     */
    private fun removeOrphanedPersistentDisplays() {
        var removed = 0
        Bukkit.getWorlds().forEach { world ->
            world.getEntitiesByClass(ItemDisplay::class.java)
                .filter { entity ->
                    entity.scoreboardTags.contains("taiwanese_mahjong_table") ||
                        entity.scoreboardTags.contains("taiwanese_mahjong_chair")
                }
                .forEach { entity ->
                    entity.remove()
                    removed++
                }

            world.getEntitiesByClass(ArmorStand::class.java)
                .filter { entity -> entity.scoreboardTags.contains("taiwanese_mahjong_seat") }
                .forEach { entity ->
                    entity.remove()
                    removed++
                }
        }

        if (removed > 0) {
            MahjongPlayPlugin.instance.logger.info(
                "Removed $removed stale TaiwaneseMahjong display entities before loading tables."
            )
        }
    }

    fun teleportSinglePlayerToSeat(session: MahjongTableSession, player: com.mahjongplay.game.MahjongPlayer, bukkitPlayer: Player) {
        val seatIndex = session.game.seat.indexOf(player)
        if (seatIndex < 0) return
        val directions = listOf(
            doubleArrayOf(1.0, 0.0),
            doubleArrayOf(0.0, 1.0),
            doubleArrayOf(-1.0, 0.0),
            doubleArrayOf(0.0, -1.0),
        )
        val direction = directions[seatIndex % directions.size]
        val seatDistance = settings.seatDistance.coerceAtMost(MahjongTable.CHAIR_DISTANCE)
        val location = session.center.clone().apply {
            x += direction[0] * seatDistance
            y = session.center.blockY + 1.0
            z += direction[1] * seatDistance
            setDirection(session.center.toVector().subtract(toVector()))
        }
        bukkitPlayer.leaveVehicle()
        bukkitPlayer.teleport(location)
    }

    fun rejoinIfBotTakeover(player: Player) {
        val uuid = player.uniqueId.toString()
        val session = tables.values.find { s ->
            s.game.status == GameStatus.PLAYING && s.game.players.any { it.uuid == uuid && it is com.mahjongplay.game.MahjongPlayer && it.isBotTakeover }
        } ?: return

        val mjPlayer = session.game.players.find { it.uuid == uuid } as? com.mahjongplay.game.MahjongPlayer ?: return
        mjPlayer.deactivateBotTakeover()
        playerToTable[uuid] = session.tableId
        session.renderer.updateSeatScoreDisplays()
        session.renderer.updateVisibility(mjPlayer)
        session.bridge.updateHud()
        teleportSinglePlayerToSeat(session, mjPlayer, player)
        session.bridge.broadcast(
            Component.text("[麻將] 玩家【${mjPlayer.rawDisplayName}】重新連線回到牌桌，已解除託管恢復手動操作！", NamedTextColor.GREEN)
        )
    }

    fun toggleBotTakeoverForPlayer(player: Player): Boolean {
        val uuid = player.uniqueId.toString()
        val session = tables.values.find { s ->
            s.game.status == GameStatus.PLAYING && s.game.players.any { it.uuid == uuid }
        } ?: return false

        val mjPlayer = session.game.players.find { it.uuid == uuid } as? com.mahjongplay.game.MahjongPlayer ?: return false
        if (!mjPlayer.isBotTakeover) {
            mjPlayer.activateBotTakeover(session.game.rule.defaultBotDifficulty)
            player.sendMessage(Component.text("[麻將] 🤖 已開啟託管代打模式！(再按一次 F 可解除託管)", NamedTextColor.YELLOW))
            session.bridge.broadcast(
                Component.text("[麻將] 玩家【${mjPlayer.rawDisplayName}】已切換為 🤖 託管代打模式！", NamedTextColor.GOLD)
            )
        } else {
            mjPlayer.deactivateBotTakeover()
            player.sendMessage(Component.text("[麻將] 🀄 已解除託管代打，恢復手動操作！", NamedTextColor.GREEN))
            session.bridge.broadcast(
                Component.text("[麻將] 玩家【${mjPlayer.rawDisplayName}】已解除託管恢復手動操作！", NamedTextColor.GREEN)
            )
        }
        session.renderer.updateSeatScoreDisplays()
        session.renderer.updateVisibility(mjPlayer)
        session.bridge.updateHud()
        return true
    }

    fun isProtectedBlock(loc: org.bukkit.Location): Boolean {
        return tables.values.any { it.table.isProtectedBlock(loc) }
    }

    fun notifyIfInterrupted(playerUUID: String, playerBukkit: org.bukkit.entity.Player) {
        if (interruptedPlayers.remove(playerUUID)) {
            Bukkit.getScheduler().runTaskLater(MahjongPlayPlugin.instance, Runnable {
                playerBukkit.sendMessage(
                    Component.text("[麻將] ", NamedTextColor.GOLD)
                        .append(Component.text("你之前的牌局因伺服器重啟而中斷，很抱歉！", NamedTextColor.YELLOW))
                )
            }, 40L)
        }
    }

    override fun getGameForPlayer(uuid: String): MahjongGame? =
        getSessionForPlayer(uuid)?.game

    override fun getRenderer(game: MahjongGame): BoardRenderer? =
        tables[game.tableId]?.renderer
}

data class MahjongTableSession(
    val tableId: UUID,
    val game: MahjongGame,
    val renderer: BoardRenderer,
    val bridge: PaperGameBridge,
    val center: Location,
    val table: MahjongTable,
    val humanId: String,
    var ownerUUID: String? = null,
)
