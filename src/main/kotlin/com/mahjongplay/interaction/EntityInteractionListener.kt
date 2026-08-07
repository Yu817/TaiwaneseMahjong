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
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerAnimationType
import org.bukkit.event.player.PlayerMoveEvent
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

    @EventHandler(ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val to = event.to ?: return
        if (event.from.yaw == to.yaw && event.from.pitch == to.pitch) return

        val playerUUID = event.player.uniqueId.toString()
        val game = gameManager.getGameForPlayer(playerUUID) ?: return
        val renderer = gameManager.getRenderer(game) ?: return
        renderer.refreshDiscardHover(event.player)
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
        if (chairResult != null) {
            event.isCancelled = true
            sendChairResult(event.player, chairResult)
            return
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

    @EventHandler
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        val clickedEntity = event.rightClicked
        val player = event.player
        val playerUUID = player.uniqueId.toString()

        if (clickedEntity !is Interaction) return

        val joinSession = gameManager.getTableByJoinInteraction(clickedEntity.uniqueId)
        if (joinSession != null) {
            event.isCancelled = true
            if (joinSession.game.status != GameStatus.WAITING) {
                player.sendMessage(Component.text("[麻將] 遊戲正在進行中", NamedTextColor.RED))
                return
            }

            val existingSession = gameManager.getSessionForPlayer(playerUUID)
            if (existingSession != null && existingSession.tableId == joinSession.tableId) {
                gameManager.leaveTable(playerUUID)
                player.sendMessage(Component.text("[麻將] 已離開麻將桌", NamedTextColor.YELLOW))
                return
            }

            if (existingSession != null) {
                player.sendMessage(Component.text("[麻將] 你已經在另一張麻將桌中", NamedTextColor.RED))
                return
            }
            if (gameManager.joinTable(joinSession.tableId, playerUUID, player.name)) {
                player.sendMessage(Component.text("[麻將] 已加入麻將桌！", NamedTextColor.GREEN))
            } else {
                player.sendMessage(Component.text("[麻將] 無法加入（牌桌已滿）", NamedTextColor.RED))
            }
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
            readySession.game.readyOrNot(playerUUID, newReady)
            gameManager.updateTableDisplay(readySession)
            player.sendMessage(Component.text("[麻將] ${if (newReady) "已準備 ✓" else "取消準備 ✗"}", if (newReady) NamedTextColor.GREEN else NamedTextColor.YELLOW))
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

        val actionDisplay = renderer.getActionByInteraction(clickedEntity.uniqueId)
        if (actionDisplay != null && actionDisplay.ownerUUID == playerUUID) {
            event.isCancelled = true

            if (actionDisplay.subOptions != null && actionDisplay.subOptions.isNotEmpty()) {
                renderer.expandSubMenu(playerUUID, actionDisplay.subOptions)
            } else {
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
        }
        return true
    }

    private fun sendChairResult(player: org.bukkit.entity.Player, result: ChairInteractionResult) {
        val (message, color) = when (result) {
            ChairInteractionResult.SEATED -> "已坐到這張椅子，按 Shift 可起身。" to NamedTextColor.GREEN
            ChairInteractionResult.OCCUPIED -> "這張椅子已經有人坐了。" to NamedTextColor.RED
            ChairInteractionResult.OTHER_TABLE -> "你已經在另一張麻將桌。" to NamedTextColor.RED
            ChairInteractionResult.TABLE_FULL -> "這張麻將桌已滿。" to NamedTextColor.RED
            ChairInteractionResult.GAME_IN_PROGRESS -> "遊戲進行中，不能更換座位。" to NamedTextColor.YELLOW
        }
        player.sendMessage(Component.text("[麻將] $message", color))
    }
}

interface GameRegistry {
    fun getGameForPlayer(uuid: String): MahjongGame?
    fun getRenderer(game: MahjongGame): com.mahjongplay.display.BoardRenderer?
}
