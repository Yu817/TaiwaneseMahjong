package com.mahjongplay.interaction

import com.mahjongplay.game.GameStatus
import com.mahjongplay.game.MahjongGame
import com.mahjongplay.model.MahjongGameBehavior
import com.mahjongplay.game.MahjongPlayer
import com.mahjongplay.table.ChairInteractionResult
import com.mahjongplay.table.MahjongTableManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerAnimationType
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID

class EntityInteractionListener(
    private val gameManager: MahjongTableManager
) : Listener {

    /**
     * A left click can raise several Bukkit events for the same arm swing
     * (animation, interact, and sometimes entity damage). Keep the discard
     * action single-shot while still cancelling all duplicate events.
     */
    private val recentLeftDiscardInputAt = mutableMapOf<UUID, Long>()
    private val recentRightJoinLeaveInputAt = mutableMapOf<UUID, Long>()

    fun clearRecentInput(playerUUID: UUID) {
        recentLeftDiscardInputAt.remove(playerUUID)
        recentRightJoinLeaveInputAt.remove(playerUUID)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val to = event.to ?: return
        val player = event.player
        checkPlayerDistance(player, "離開牌桌過遠")

        if (event.from.yaw == to.yaw && event.from.pitch == to.pitch) return

        val playerUUID = player.uniqueId.toString()
        val session = gameManager.getSessionForPlayer(playerUUID)
        val game = session?.game ?: return
        val renderer = gameManager.getRenderer(game) ?: return
        renderer.refreshDiscardHover(player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        checkPlayerDistance(event.player, "被傳送離開牌桌")
    }

    @EventHandler
    fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        checkPlayerDistance(event.player, "切換世界離開牌桌")
    }

    private fun checkPlayerDistance(player: Player, reason: String = "離開牌桌過遠") {
        val playerUUID = player.uniqueId.toString()
        val session = gameManager.getSessionForPlayer(playerUUID) ?: return
        val maxDist = gameManager.settings.maxQueueDistance
        if (maxDist <= 0.0) return

        val playerLoc = player.location
        val outOfRange = playerLoc.world != session.center.world ||
            playerLoc.distanceSquared(session.center) > maxDist * maxDist

        if (session.game.status == GameStatus.WAITING) {
            if (outOfRange) {
                gameManager.leaveTable(playerUUID)
                runCatching { UUID.fromString(playerUUID) }.getOrNull()?.let { gameManager.releasePlayerFromChairs(it) }
                player.sendMessage(
                    Component.text("[麻將] ", NamedTextColor.GOLD)
                        .append(Component.text("你已離開牌桌過遠（超過 ${maxDist.toInt()} 格），已自動退出列隊。", NamedTextColor.RED))
                )
            }
        } else if (session.game.status == GameStatus.PLAYING) {
            if (outOfRange) {
                val mjPlayer = session.game.players.find { it.uuid == playerUUID } as? com.mahjongplay.game.MahjongPlayer
                if (mjPlayer != null && !mjPlayer.isBotTakeover) {
                    mjPlayer.activateBotTakeover(session.game.rule.defaultBotDifficulty)
                    session.renderer.updateSeatScoreDisplays()
                    session.renderer.updateVisibility(mjPlayer)
                    session.bridge.updateHud()
                    session.bridge.broadcast(
                        Component.text("[麻將] 玩家【${mjPlayer.rawDisplayName}】$reason，已自動切換為 🤖 代打模式！", NamedTextColor.GOLD)
                    )
                    player.sendMessage(
                        Component.text("[麻將] ", NamedTextColor.GOLD)
                            .append(Component.text("你因 $reason，系統已自動為你開啟 🤖 代打模式！(返回牌桌或按 F 鍵可恢復手動)", NamedTextColor.YELLOW))
                    )
                }
            }
        }
    }

    @EventHandler
    fun onInteractBlock(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return

        val block = event.clickedBlock ?: return

        if (event.action == Action.LEFT_CLICK_BLOCK) {
            if (block.type == Material.BARRIER &&
                gameManager.isProtectedBlock(block.location) &&
                handleLeftTileInput(event.player)
            ) {
                event.isCancelled = true
            }
            return
        }

        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        val chairResult = gameManager.handleChairBlockInteraction(event.player, block.location)
            ?: gameManager.handleChairBlockInteraction(event.player, block.location.clone().add(0.0, 1.0, 0.0))
            ?: gameManager.handleChairBlockInteraction(event.player, block.location.clone().add(0.0, -1.0, 0.0))
        if (chairResult != null) {
            event.isCancelled = true
            sendChairResult(event.player, chairResult)
            return
        }

        // 對著麻將桌桌面方塊右鍵點擊：加入／離開麻將桌 或 開啟管理員介面
        val tableSession = gameManager.getTableByTableTopBlock(block.location)
        if (tableSession != null) {
            val isAdmin = event.player.hasPermission("mahjongplay.admin") || event.player.isOp
            if (isAdmin && (event.player.isSneaking || tableSession.game.status != GameStatus.WAITING)) {
                com.mahjongplay.table.MahjongAdminGUI.open(event.player, tableSession, gameManager)
                event.isCancelled = true
                return
            }
            if (tableSession.game.status == GameStatus.WAITING) {
                handleTableJoinLeave(event.player, tableSession)
                event.isCancelled = true
                return
            }
        }

        if (block.type == Material.BARRIER &&
            gameManager.isProtectedBlock(block.location) &&
            handleBarrierTileDiscard(event.player)
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onInteractAir(event: PlayerInteractEvent) {
        if (event.action != Action.LEFT_CLICK_AIR) return
        if (event.hand != EquipmentSlot.HAND) return

        if (handleLeftTileInput(event.player)) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onLeftClickEntity(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        val clickedEntity = event.entity as? Interaction ?: return
        val playerUUID = player.uniqueId.toString()

        val game = gameManager.getGameForPlayer(playerUUID)
        if (game != null) {
            val renderer = gameManager.getRenderer(game)
            if (renderer != null && renderer.isSeatWindInteraction(clickedEntity.uniqueId)) {
                event.isCancelled = true
                renderer.handleSeatWindClick(player, clickedEntity.uniqueId)
                return
            }
            val actionDisplay = renderer?.getActionByInteraction(clickedEntity.uniqueId)
            if (actionDisplay != null && actionDisplay.ownerUUID == playerUUID) {
                event.isCancelled = true
                val mjPlayer = game.realPlayers.find { it.uuid == playerUUID } as? MahjongPlayer
                if (actionDisplay.subOptions != null && actionDisplay.subOptions.isNotEmpty()) {
                    renderer.expandSubMenu(playerUUID, actionDisplay.subOptions)
                    player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, org.bukkit.SoundCategory.PLAYERS, 0.5f, 1.4f)
                } else if (mjPlayer != null) {
                    if (actionDisplay.behavior == MahjongGameBehavior.SKIP) {
                        MahjongSoundHelper.playSkip(player)
                    }
                    mjPlayer.resolveAction(actionDisplay.behavior, actionDisplay.data)
                }
                return
            }
        }

        if (handleLeftTileInput(player, clickedEntity)) {
            event.isCancelled = true
        }
    }

    /**
     * PlayerAnimationEvent is the fallback for left-clicking an Interaction
     * entity, because that click may not produce PlayerInteractEvent.
     */
    @EventHandler(ignoreCancelled = true)
    fun onArmSwing(event: PlayerAnimationEvent) {
        if (event.animationType != PlayerAnimationType.ARM_SWING) return

        if (handleLeftTileInput(event.player)) {
            event.isCancelled = true
        }
    }

    private fun handleTableJoinLeave(player: Player, session: com.mahjongplay.table.MahjongTableSession): Boolean {
        val now = System.currentTimeMillis()
        val prev = recentRightJoinLeaveInputAt[player.uniqueId]
        if (prev != null && now - prev < 250L) {
            return false
        }
        recentRightJoinLeaveInputAt[player.uniqueId] = now

        if (session.game.status != GameStatus.WAITING) {
            player.sendMessage(Component.text("[麻將] 遊戲正在進行中", NamedTextColor.RED))
            return true
        }

        val playerUUID = player.uniqueId.toString()
        val existingSession = gameManager.getSessionForPlayer(playerUUID)
        if (existingSession != null && existingSession.tableId == session.tableId) {
            gameManager.leaveTable(playerUUID)
            player.sendMessage(Component.text("[麻將] 已離開麻將桌", NamedTextColor.YELLOW))
            return true
        }

        if (existingSession != null) {
            player.sendMessage(Component.text("[麻將] 你已經在另一張麻將桌中", NamedTextColor.RED))
            return true
        }
        if (gameManager.joinTable(session.tableId, playerUUID, player.name)) {
            player.sendMessage(Component.text("[麻將] 已加入麻將桌！", NamedTextColor.GREEN))
        } else {
            player.sendMessage(Component.text("[麻將] 無法加入（牌桌已滿）", NamedTextColor.RED))
        }
        return true
    }

    @EventHandler
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        val clickedEntity = event.rightClicked
        val player = event.player
        val playerUUID = player.uniqueId.toString()

        if (clickedEntity.scoreboardTags.contains("taiwanese_mahjong_chair") ||
            clickedEntity.scoreboardTags.contains("taiwanese_mahjong_seat") ||
            (clickedEntity is ArmorStand && clickedEntity.scoreboardTags.any { it.startsWith("taiwanese_mahjong") })
        ) {
            val chairResult = gameManager.handleChairBlockInteraction(player, clickedEntity.location.block.location)
                ?: gameManager.handleChairBlockInteraction(player, clickedEntity.location.clone().add(0.0, -1.0, 0.0).block.location)
                ?: gameManager.handleChairBlockInteraction(player, clickedEntity.location.clone().add(0.0, 1.0, 0.0).block.location)
            if (chairResult != null) {
                event.isCancelled = true
                sendChairResult(player, chairResult)
                return
            }
        }

        if (clickedEntity.scoreboardTags.contains("taiwanese_mahjong_table")) {
            val tableSession = gameManager.getTableByTableEntity(clickedEntity)
            if (tableSession != null) {
                val isAdmin = player.hasPermission("mahjongplay.admin") || player.isOp
                if (isAdmin && (player.isSneaking || tableSession.game.status != GameStatus.WAITING)) {
                    event.isCancelled = true
                    com.mahjongplay.table.MahjongAdminGUI.open(player, tableSession, gameManager)
                    return
                }
                if (tableSession.game.status == GameStatus.WAITING) {
                    event.isCancelled = true
                    handleTableJoinLeave(player, tableSession)
                    return
                }
            }
        }

        if (clickedEntity !is Interaction) return

        val joinSession = gameManager.getTableByJoinInteraction(clickedEntity.uniqueId)
        if (joinSession != null) {
            event.isCancelled = true
            val isAdmin = player.hasPermission("mahjongplay.admin") || player.isOp
            if (isAdmin && (player.isSneaking || joinSession.game.status != GameStatus.WAITING)) {
                com.mahjongplay.table.MahjongAdminGUI.open(player, joinSession, gameManager)
                return
            }
            handleTableJoinLeave(player, joinSession)
            return
        }

        val readySession = gameManager.getTableByReadyInteraction(clickedEntity.uniqueId)
        if (readySession != null) {
            event.isCancelled = true
            if (readySession.game.status != GameStatus.WAITING) return

            val mjPlayer = readySession.game.players.find { it.uuid == playerUUID }
            if (mjPlayer == null) {
                player.sendMessage(Component.text("[麻將] 你不在這張麻將桌中", NamedTextColor.RED))
                return
            }
            val newReady = !mjPlayer.ready
            if (newReady) {
                val error = gameManager.canPlayerReady(readySession, playerUUID)
                if (error != null) {
                    player.sendMessage(Component.text("[麻將] $error", NamedTextColor.RED))
                    return
                }
            }
            readySession.game.readyOrNot(playerUUID, newReady)
            gameManager.updateTableDisplay(readySession)
            player.sendMessage(Component.text("[麻將] ${if (newReady) "已準備 ✓" else "取消準備 ✗"}", if (newReady) NamedTextColor.GREEN else NamedTextColor.YELLOW))
            gameManager.checkAutoStart(readySession)
            return
        }

        val startSession = gameManager.getTableByStartInteraction(clickedEntity.uniqueId)
        if (startSession != null) {
            event.isCancelled = true
            if (startSession.game.status != GameStatus.WAITING) return

            val mjPlayer = startSession.game.players.find { it.uuid == playerUUID }
            if (mjPlayer == null) {
                player.sendMessage(Component.text("[麻將] 你不在這張麻將桌中", NamedTextColor.RED))
                return
            }
            if (!mjPlayer.ready) {
                player.sendMessage(Component.text("[麻將] 請先準備", NamedTextColor.RED))
                return
            }

            val error = gameManager.startGame(startSession)
            if (error != null) {
                player.sendMessage(Component.text("[麻將] $error", NamedTextColor.RED))
            }
            return
        }

        val settingsSession = gameManager.getTableBySettingsInteraction(clickedEntity.uniqueId)
        if (settingsSession != null) {
            event.isCancelled = true
            if (settingsSession.game.status != GameStatus.WAITING) {
                player.sendMessage(Component.text("[麻將] 遊戲進行中不能調整設定", NamedTextColor.RED))
                return
            }
            gameManager.openSettingsMenu(settingsSession, player)
            return
        }

        val settingsTarget = gameManager.getSettingsOptionTarget(clickedEntity.uniqueId)
        if (settingsTarget != null) {
            event.isCancelled = true
            val session = gameManager.getSession(settingsTarget.tableId) ?: return
            gameManager.handleSettingsOption(session, playerUUID, settingsTarget.action)
            return
        }

        val game = gameManager.getGameForPlayer(playerUUID) ?: return
        val mjPlayer = game.realPlayers.find { it.uuid == playerUUID } as? MahjongPlayer ?: return
        val renderer = gameManager.getRenderer(game) ?: return
        if (renderer.isSeatWindInteraction(clickedEntity.uniqueId)) {
            event.isCancelled = true
            renderer.handleSeatWindClick(player, clickedEntity.uniqueId)
            return
        }

        val actionDisplay = renderer.getActionByInteraction(clickedEntity.uniqueId)
        if (actionDisplay != null && actionDisplay.ownerUUID == playerUUID) {
            event.isCancelled = true

            if (actionDisplay.subOptions != null && actionDisplay.subOptions.isNotEmpty()) {
                renderer.expandSubMenu(playerUUID, actionDisplay.subOptions)
                player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, org.bukkit.SoundCategory.PLAYERS, 0.5f, 1.4f)
            } else {
                if (actionDisplay.behavior == MahjongGameBehavior.SKIP) {
                    MahjongSoundHelper.playSkip(player)
                }
                mjPlayer.resolveAction(actionDisplay.behavior, actionDisplay.data)
            }
            return
        }

        if (handleTileDiscard(player, clickedEntity)) {
            event.isCancelled = true
        }
    }

    /**
     * A tabletop Barrier can win the block ray trace before the card's
     * Interaction entity. Re-run an entity-only ray trace so a card still
     * receives the same discard click without weakening chair Barrier checks.
     */
    private fun handleBarrierTileDiscard(player: Player): Boolean {
        val clickedEntity = player.world.rayTraceEntities(
            player.eyeLocation,
            player.eyeLocation.direction,
            8.0,
            0.08,
        ) { entity -> entity is Interaction }?.hitEntity as? Interaction ?: return false

        return handleTileDiscard(player, clickedEntity)
    }

    private fun handleLeftTileInput(player: Player, clickedEntity: Interaction? = null): Boolean {
        val now = System.currentTimeMillis()
        val previous = recentLeftDiscardInputAt[player.uniqueId]
        if (previous != null && now - previous < 150L) {
            return true
        }

        val handled = if (clickedEntity != null) {
            handleTileDiscard(player, clickedEntity)
        } else {
            handleBarrierTileDiscard(player)
        }
        if (handled) {
            recentLeftDiscardInputAt[player.uniqueId] = now
        }
        return handled
    }

    private fun handleTileDiscard(player: Player, clickedEntity: Interaction): Boolean {
        val playerUUID = player.uniqueId.toString()
        val game = gameManager.getGameForPlayer(playerUUID) ?: return false
        val mjPlayer = game.realPlayers.find { it.uuid == playerUUID } as? MahjongPlayer ?: return false
        val renderer = gameManager.getRenderer(game) ?: return false
        val pending = mjPlayer.pendingAction ?: return false

        if (MahjongGameBehavior.DISCARD !in pending.behaviors) return false

        val ownerDisplays = renderer.handOwnerDisplays[mjPlayer.uuid] ?: return false
        val clickedIndex = ownerDisplays.indexOfFirst {
            it.interactionEntity?.uniqueId == clickedEntity.uniqueId
        }
        if (clickedIndex < 0) return false

        val tile = mjPlayer.hands.getOrNull(clickedIndex) ?: return false
        val confirmed = renderer.confirmTileForDiscard(mjPlayer.uuid, clickedIndex)
        if (confirmed) {
            mjPlayer.resolveAction(MahjongGameBehavior.DISCARD, "${tile.code}")
        } else {
            MahjongSoundHelper.playTileSelect(player)
        }
        return true
    }

    @EventHandler
    fun onInteractAtEntity(event: PlayerInteractAtEntityEvent) {
        val clickedEntity = event.rightClicked
        if (clickedEntity.scoreboardTags.contains("taiwanese_mahjong_chair") ||
            clickedEntity.scoreboardTags.contains("taiwanese_mahjong_seat") ||
            (clickedEntity is ArmorStand && clickedEntity.scoreboardTags.any { it.startsWith("taiwanese_mahjong") })
        ) {
            val chairResult = gameManager.handleChairBlockInteraction(event.player, clickedEntity.location.block.location)
                ?: gameManager.handleChairBlockInteraction(event.player, clickedEntity.location.clone().add(0.0, -1.0, 0.0).block.location)
                ?: gameManager.handleChairBlockInteraction(event.player, clickedEntity.location.clone().add(0.0, 1.0, 0.0).block.location)
            if (chairResult != null) {
                event.isCancelled = true
                sendChairResult(event.player, chairResult)
                return
            }
        }

        if (clickedEntity.scoreboardTags.contains("taiwanese_mahjong_table")) {
            val tableSession = gameManager.getTableByTableEntity(clickedEntity)
            if (tableSession != null && tableSession.game.status == GameStatus.WAITING) {
                event.isCancelled = true
                handleTableJoinLeave(event.player, tableSession)
            }
        }
    }

    private fun sendChairResult(player: org.bukkit.entity.Player, result: ChairInteractionResult) {
        val (message, color) = when (result) {
            ChairInteractionResult.SEATED -> "已坐上椅子（按 Shift 可隨時起身走動）。" to NamedTextColor.GREEN
            ChairInteractionResult.OCCUPIED -> "這張椅子已經有人坐了。" to NamedTextColor.RED
            ChairInteractionResult.OTHER_TABLE -> "你已經在另一張麻將桌。" to NamedTextColor.RED
            ChairInteractionResult.TABLE_FULL -> "這張麻將桌已滿。" to NamedTextColor.RED
            ChairInteractionResult.GAME_IN_PROGRESS -> "遊戲進行中，非本桌玩家無法加入。" to NamedTextColor.YELLOW
        }
        player.sendMessage(Component.text("[麻將] $message", color))
    }
}

interface GameRegistry {
    fun getGameForPlayer(uuid: String): MahjongGame?
    fun getRenderer(game: MahjongGame): com.mahjongplay.display.BoardRenderer?
}
