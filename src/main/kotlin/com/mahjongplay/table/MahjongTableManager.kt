package com.mahjongplay.table

import com.mahjongplay.MahjongPlayPlugin
import com.mahjongplay.config.MahjongSettings
import com.mahjongplay.display.BoardRenderer
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

class MahjongTableManager(private val settings: MahjongSettings) : GameRegistry {

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
                        registerInteractionMappings(session)
                    }
                }
            },
            1L,
            6L,
        ).taskId
    }

    fun onChunkLoad(chunk: org.bukkit.Chunk) {
        tables.values.filter { it.center.world == chunk.world && it.center.chunk.x == chunk.x && it.center.chunk.z == chunk.z }.forEach { session ->
            session.table.spawn()
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
        val game = MahjongGame(rule = rule)
        val renderer = BoardRenderer(game, center, settings.tableScale)
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
        // A seated player's camera can still briefly point at the support
        // block. Do not turn that harmless click into another seat message.
        if (session.table.isChairOccupiedBy(blockLocation, player.uniqueId)) return null
        if (session.game.status != GameStatus.WAITING) return ChairInteractionResult.GAME_IN_PROGRESS

        val playerUUID = player.uniqueId.toString()
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

    fun startGame(session: MahjongTableSession): String? {
        if (session.game.status != GameStatus.WAITING) return "遊戲已在進行中"
        if (session.game.players.isEmpty()) return "目前沒有玩家"

        val unready = session.game.players.filter { it.isRealPlayer && !it.ready }
        if (unready.isNotEmpty()) return "還有玩家未準備：${unready.joinToString { it.displayName }}"

        val pc = session.game.rule.playerCount
        while (session.game.players.size < pc) {
            val botNum = session.game.players.count { !it.isRealPlayer } + 1
            session.game.addBot("Bot$botNum")
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

        runCatching { UUID.fromString(playerUUID) }.getOrNull()?.let {
            session.table.releasePlayerFromChairs(it)
        }

        session.bridge.hideBarForPlayer(playerUUID)
        session.game.leave(playerUUID)
        playerToTable.remove(playerUUID)

        if (wasPlaying) {
            session.game.players.forEach { playerToTable.remove(it.uuid) }
            session.game.players.clear()
        }

        if (session.game.realPlayers.isEmpty()) {
            session.game.players.removeAll { it is MahjongBot }
        }

        if (session.ownerUUID == playerUUID) {
            session.ownerUUID = session.game.realPlayers.firstOrNull()?.uuid
        }

        cancelCountdown(session.tableId)
        updateTableDisplay(session)
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
        if (isWaiting && session.game.players.isNotEmpty()) {
            session.table.showActionButtons(buttonAnchor)
        }
        session.table.startInteraction?.uniqueId?.let { startInteractionToTable[it] = session.tableId }
        session.table.readyInteraction?.uniqueId?.let { readyInteractionToTable[it] = session.tableId }
        registerInteractionMappings(session)
    }

    fun openSettingsMenu(session: MahjongTableSession, viewer: Player) {
        if (session.game.status != GameStatus.WAITING) return
        viewer.showDialog(createSettingsDialog(session, viewer))
    }

    private fun createSettingsDialog(session: MahjongTableSession, viewer: Player): Dialog {
        val owner = isTableOwner(session, viewer.uniqueId.toString())
        val rule = session.game.rule
        val inputs = if (owner) {
            listOf(
                DialogInput.text("base", Component.text("底分", NamedTextColor.YELLOW))
                    .width(240)
                    .labelVisible(true)
                    .initial(rule.basePoints.toString())
                    .maxLength(6)
                    .build(),
                DialogInput.text("tai", Component.text("每台分數", NamedTextColor.GOLD))
                    .width(240)
                    .labelVisible(true)
                    .initial(rule.pointsPerTai.toString())
                    .maxLength(6)
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

        val closeButton = ActionButton.builder(Component.text("關閉", NamedTextColor.GRAY))
            .tooltip(Component.text("關閉設定視窗", NamedTextColor.GRAY))
            .width(180)
            .build()

        val type = if (owner) {
            DialogType.multiAction(
                listOf(
                    settingsDialogButton(session, "round_next", "圈數：${rule.displayCircleText}", NamedTextColor.AQUA),
                    settingsDialogButton(
                        session,
                        "bot_next",
                        "Bot：${(rule.botResponseDelayMs / 1000L).coerceIn(1L, 5L)}秒",
                        NamedTextColor.GREEN,
                    ),
                    settingsDialogButton(
                        session,
                        "flowers_toggle",
                        "花牌：${if (rule.flowersEnabled) "開啟" else "關閉"}",
                        NamedTextColor.LIGHT_PURPLE,
                    ),
                    settingsDialogButton(
                        session,
                        "chairs_toggle",
                        "椅子：${if (rule.chairsEnabled) "開啟" else "關閉"}",
                        NamedTextColor.LIGHT_PURPLE,
                    ),
                    settingsDialogButton(session, "apply_values", "套用設定", NamedTextColor.YELLOW),
                ),
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
    ): ActionButton = ActionButton.builder(Component.text(label, color))
        .tooltip(Component.text("點擊後更新牌桌設定", NamedTextColor.GRAY))
        .width(180)
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
            "bot_next" -> updated.botResponseDelayMs = nextPreset(updated.botResponseDelayMs, BOT_PRESETS)
            "flowers_toggle" -> updated.flowersEnabled = !updated.flowersEnabled
            "chairs_toggle" -> updated.chairsEnabled = !updated.chairsEnabled
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
                            "[麻將] 底分與每台分數必須是 0～${MahjongRule.MAX_POINTS} 的整數。",
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
        val flowers = if (rule.flowersEnabled) "開啟" else "關閉"
        val chairs = if (rule.chairsEnabled) "開啟" else "關閉"
        val botSeconds = (rule.botResponseDelayMs / 1000L).coerceIn(1L, 5L)
        val hint = if (isTableOwner(session, viewer.uniqueId.toString())) {
            "桌主可用按鈕切換；底／台請在欄位輸入後按套用。"
        } else {
            "目前為查看模式，只有桌主可以修改。"
        }
        return Component.text("桌主：$ownerName", NamedTextColor.GOLD)
            .append(Component.newline())
            .append(Component.text("圈數：${rule.displayCircleText}  •  Bot：${botSeconds}秒", NamedTextColor.GREEN))
            .append(Component.newline())
            .append(Component.text("底／台：${rule.basePoints}／${rule.pointsPerTai}分  •  花牌：$flowers", NamedTextColor.YELLOW))
            .append(Component.newline())
            .append(Component.text("椅子：$chairs", NamedTextColor.LIGHT_PURPLE))
            .append(Component.newline())
            .append(Component.text(hint, NamedTextColor.GRAY))
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
        return Component.text("⚙ 牌桌設定", NamedTextColor.AQUA)
            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
            .append(Component.newline())
            .append(Component.text("桌主：$ownerName", NamedTextColor.GOLD))
            .append(Component.newline())
            .append(Component.text("圈數：${rule.displayCircleText}  •  Bot：${botSeconds}秒", NamedTextColor.GREEN))
            .append(Component.newline())
            .append(Component.text("底／台：${rule.basePoints}／${rule.pointsPerTai}分  •  花牌：$flowers", NamedTextColor.YELLOW))
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
            "bot_next" -> updated.botResponseDelayMs = nextPreset(updated.botResponseDelayMs, BOT_PRESETS)
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
            settingsMenuOptions(session.game.rule.chairsEnabled),
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
        return Component.text("⚙ 牌桌設定", NamedTextColor.AQUA)
            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
            .append(Component.newline())
            .append(Component.text("桌主：$ownerName", NamedTextColor.GOLD))
            .append(Component.newline())
            .append(Component.text("圈數：${rule.displayCircleText}  •  Bot：${botSeconds}秒", NamedTextColor.GREEN))
            .append(Component.newline())
            .append(Component.text("底／台：${rule.basePoints}／${rule.pointsPerTai}分  •  花牌：$flowers", NamedTextColor.YELLOW))
            .append(Component.newline())
            .append(Component.text("椅子：$chairs", NamedTextColor.LIGHT_PURPLE))
            .append(Component.newline())
            .append(Component.text("桌主可點擊按鈕修改；底／台請依聊天提示輸入", NamedTextColor.GRAY))
    }

    private fun settingsMenuOptions(chairsEnabled: Boolean): List<SettingsMenuOption> = listOf(
        SettingsMenuOption("round_next", "圈數 ▶", NamedTextColor.AQUA, 0, 0),
        SettingsMenuOption("bot_next", "Bot ▶", NamedTextColor.GREEN, 1, 0),
        SettingsMenuOption("base_input", "底 輸入", NamedTextColor.YELLOW, 2, 0),
        SettingsMenuOption("tai_input", "台 輸入", NamedTextColor.GOLD, 3, 0),
        SettingsMenuOption("flowers_toggle", "✿ 切換花牌", NamedTextColor.LIGHT_PURPLE, 4, 0),
        SettingsMenuOption("chairs_toggle", "椅子 ${if (chairsEnabled) "開啟" else "關閉"}", NamedTextColor.LIGHT_PURPLE, 5, 0),
        SettingsMenuOption("close", "✕ 關閉", NamedTextColor.GRAY, 6, 0),
    )

    private fun beginNumericInput(
        session: MahjongTableSession,
        playerUUID: String,
        field: NumericSettingField,
    ) {
        pendingNumericInputs[playerUUID] = NumericSettingPrompt(session.tableId, field)
        val label = if (field == NumericSettingField.BASE) "底分" else "每台分數"
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
                "底分"
            }
            NumericSettingField.TAI -> {
                updated.pointsPerTai = value
                "每台分數"
            }
        }
        session.game.changeRules(updated)
        updateTableDisplay(session)
        refreshSettingsMenu(session)
        player.sendMessage(Component.text("[麻將] $label 已設為 $value 分；其他玩家需要重新準備。", NamedTextColor.GREEN))
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

            val windIndex = (Wind.entries.size - ((game.round.round + seatIndex) % Wind.entries.size)) % Wind.entries.size
            val wind = Wind.entries[windIndex]
            player.sendMessage(
                Component.text("[麻將] ", NamedTextColor.GOLD)
                    .append(Component.text("座位已安排：${wind.displayName}家", NamedTextColor.AQUA))
                    .append(Component.text("，你的牌就在面前。", NamedTextColor.GREEN))
            )
        }
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
                    session.game.start()
                    updateTableDisplay(session)
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

    fun getAllHumanIds(): List<String> = tables.values.map { it.humanId }

    fun getSessionByHumanId(humanId: String): MahjongTableSession? =
        tables.values.find { it.humanId == humanId }

    fun shutdown() {
        displayRepairTaskId?.let { Bukkit.getScheduler().cancelTask(it) }
        displayRepairTaskId = null
        actionButtonRepairTaskId?.let { Bukkit.getScheduler().cancelTask(it) }
        actionButtonRepairTaskId = null
        tables.values.forEach { session ->
            if (session.game.status == GameStatus.PLAYING) {
                session.game.end()
            }
            session.renderer.clearAllDisplays()
            session.bridge.cleanup()
            session.table.removeEntities()
            session.game.players.forEach { playerToTable.remove(it.uuid) }
        }
        tables.clear()
        joinInteractionToTable.clear()
        startInteractionToTable.clear()
        readyInteractionToTable.clear()
        settingsInteractionToTable.clear()
        settingsOptionToTarget.clear()
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
                "honbaPoints" to session.game.rule.honbaPoints,
                "dealerTsumoMultiplier" to session.game.rule.dealerTsumoMultiplier,
                "flowersEnabled" to session.game.rule.flowersEnabled,
                "botResponseDelayMs" to session.game.rule.botResponseDelayMs,
                "chairsEnabled" to session.game.rule.chairsEnabled,
            )
            if (session.game.status == GameStatus.PLAYING) {
                map["playingPlayers"] = session.game.realPlayers.map { it.uuid }
            }
            map
        }
        config.set("tables", tableList)
        config.save(file)
    }

    private fun autoSave() {
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
            val startingPoints = (map["startingPoints"] as? Number)?.toInt() ?: 16000
            val gameLength = try { MahjongRule.GameLength.valueOf(gameLengthName) } catch (_: Exception) { MahjongRule.GameLength.TWO_WIND }
            val roundsToPlay = (map["roundsToPlay"] as? Number)?.toInt()
            val rule = createRule(gameLength, roundsToPlay).apply {
                this.startingPoints = (map["startingPoints"] as? Number)?.toInt() ?: startingPoints
                this.basePoints = (map["basePoints"] as? Number)?.toInt() ?: this.basePoints
                this.pointsPerTai = (map["pointsPerTai"] as? Number)?.toInt() ?: this.pointsPerTai
                this.honbaPoints = (map["honbaPoints"] as? Number)?.toInt() ?: this.honbaPoints
                this.dealerTsumoMultiplier = (map["dealerTsumoMultiplier"] as? Number)?.toInt()
                    ?: this.dealerTsumoMultiplier
                this.flowersEnabled = map["flowersEnabled"] as? Boolean ?: this.flowersEnabled
                this.chairsEnabled = map["chairsEnabled"] as? Boolean ?: this.chairsEnabled
                this.botResponseDelayMs = ((map["botResponseDelayMs"] as? Number)?.toLong()
                    ?: this.botResponseDelayMs)
                    .coerceIn(MahjongRule.MIN_BOT_RESPONSE_MS, MahjongRule.MAX_BOT_RESPONSE_MS)
                // Taiwanese tables in this project allow a valid hand to win
                // even when its calculated tai is zero.  Migrate old saved
                // tables that still contain minimumTai: ONE.
                this.minimumTai = MahjongRule.MinimumTai.NONE
                this.thinkingTime = (map["thinkingTime"] as? String)?.let {
                    runCatching { MahjongRule.ThinkingTime.valueOf(it) }.getOrNull()
                } ?: this.thinkingTime
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
