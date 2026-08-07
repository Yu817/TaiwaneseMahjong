package com.mahjongplay.table

import com.mahjongplay.game.GameStatus
import com.mahjongplay.game.MahjongPlayer
import com.mahjongplay.model.MahjongGameBehavior
import com.mahjongplay.model.MahjongRule
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class MahjongCommand(private val manager: MahjongTableManager) : CommandExecutor, TabCompleter {

    companion object {
        private data class CommandPermission(val node: String, val deniedMessage: String)

        private val COMMAND_PERMISSIONS = mapOf(
            "create" to CommandPermission("mahjongplay.command.create", "你沒有權限建立麻將桌"),
            "join" to CommandPermission("mahjongplay.command.join", "你沒有權限加入麻將桌"),
            "leave" to CommandPermission("mahjongplay.command.leave", "你沒有權限離開麻將桌"),
            "ready" to CommandPermission("mahjongplay.command.ready", "你沒有權限準備"),
            "unready" to CommandPermission("mahjongplay.command.unready", "你沒有權限取消準備"),
            "start" to CommandPermission("mahjongplay.command.start", "你沒有權限強制開始遊戲"),
            "bot" to CommandPermission("mahjongplay.command.bot", "你沒有權限新增機器人"),
            "kick" to CommandPermission("mahjongplay.command.kick", "你沒有權限踢出玩家"),
            "destroy" to CommandPermission("mahjongplay.command.destroy", "你沒有權限銷毀麻將桌"),
            "action" to CommandPermission("mahjongplay.command.action", "你沒有權限執行麻將操作"),
            "list" to CommandPermission("mahjongplay.command.list", "你沒有權限查看麻將桌列表"),
            "info" to CommandPermission("mahjongplay.command.info", "你沒有權限查看麻將桌資訊")
        )
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Only players can use this command.")
            return true
        }

        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        val subCommand = args[0].lowercase()
        if (!sender.requireCommandPermission(subCommand)) {
            return true
        }

        when (subCommand) {
            "create" -> handleCreate(sender, args)
            "join" -> handleJoin(sender, args)
            "leave" -> handleLeave(sender)
            "ready" -> handleReady(sender, true)
            "unready" -> handleReady(sender, false)
            "start" -> handleStart(sender)
            "bot" -> handleAddBot(sender)
            "kick" -> handleKick(sender, args)
            "destroy" -> handleDestroy(sender, args)
            "action" -> handleAction(sender, args)
            "list" -> handleList(sender)
            "info" -> handleInfo(sender)
            else -> sendHelp(sender)
        }
        return true
    }

    private fun handleCreate(player: Player, args: Array<out String>) {
        val gameLength = when (args.getOrNull(1)?.lowercase()) {
            "one" -> MahjongRule.GameLength.ONE_GAME
            "east" -> MahjongRule.GameLength.EAST
            "twowind", null -> MahjongRule.GameLength.TWO_WIND
            else -> {
                player.msg("可選模式: one(一局) / east(東風) / twowind(半莊)", NamedTextColor.RED)
                return
            }
        }
        val actualLength = gameLength
        val startingPoints = 16000
        val direction = player.location.direction.setY(0).normalize()
        val loc = player.location.clone().add(direction.multiply(4))
        val center = loc.clone()
        center.x = loc.blockX + 0.5
        center.y = loc.blockY.toDouble()
        center.z = loc.blockZ + 0.5
        val session = manager.createTable(center, player.uniqueId.toString(), player.name, actualLength, startingPoints)
        session.table.spawn()
        manager.registerJoinInteraction(session)
        player.msg("麻將桌已建立！${session.humanId}", NamedTextColor.GREEN)
        player.msg("使用 /mahjong bot 新增機器人，再用 /mahjong start 開始遊戲", NamedTextColor.YELLOW)
    }

    private fun handleJoin(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            val sessions = manager.getAllSessions()
            if (sessions.isEmpty()) {
                player.msg("目前沒有可用的麻將桌", NamedTextColor.RED)
                return
            }
            val first = sessions.first()
            if (manager.joinTable(first.tableId, player.uniqueId.toString(), player.name)) {
                player.msg("已加入麻將桌 ${first.tableId.toString().take(8)}", NamedTextColor.GREEN)
            } else {
                player.msg("無法加入（可能已滿，或你已在遊戲中）", NamedTextColor.RED)
            }
            return
        }

        val tableIdStr = args[1]
        val matchingSession = manager.getAllSessions().find { it.tableId.toString().startsWith(tableIdStr) }
        if (matchingSession == null) {
            player.msg("找不到牌桌：$tableIdStr", NamedTextColor.RED)
            return
        }
        if (manager.joinTable(matchingSession.tableId, player.uniqueId.toString(), player.name)) {
            player.msg("已加入麻將桌", NamedTextColor.GREEN)
        } else {
            player.msg("無法加入（可能已滿，或你已在遊戲中）", NamedTextColor.RED)
        }
    }

    private fun handleLeave(player: Player) {
        if (manager.leaveTable(player.uniqueId.toString())) {
            player.msg("已離開麻將桌", NamedTextColor.YELLOW)
        } else {
            player.msg("你不在任何麻將桌中", NamedTextColor.RED)
        }
    }

    private fun handleReady(player: Player, ready: Boolean) {
        val session = manager.getSessionForPlayer(player.uniqueId.toString())
        if (session == null) {
            player.msg("你不在任何麻將桌中", NamedTextColor.RED)
            return
        }
        session.game.readyOrNot(player.uniqueId.toString(), ready)
        manager.updateTableDisplay(session)
        player.msg(if (ready) "已準備" else "取消準備", NamedTextColor.GREEN)
        manager.checkAutoStart(session)
    }

    private fun handleStart(player: Player) {
        val session = manager.getSessionForPlayer(player.uniqueId.toString())
            ?: manager.getAllSessions().firstOrNull()
        if (session == null) {
            player.msg("目前沒有可用的麻將桌", NamedTextColor.RED)
            return
        }
        if (session.game.players.size != session.game.rule.playerCount) {
            val pc = session.game.rule.playerCount
            player.msg("需要 ${pc} 位玩家才能開始（目前 ${session.game.players.size}/$pc，使用 /mahjong bot 新增機器人）", NamedTextColor.RED)
            return
        }
        if (!session.game.players.all { it.ready }) {
            player.msg("還有玩家未準備", NamedTextColor.RED)
            return
        }
        session.game.start()
    }

    private fun handleAddBot(player: Player) {
        val session = manager.getSessionForPlayer(player.uniqueId.toString())
            ?: manager.getAllSessions().firstOrNull()
        if (session == null) {
            player.msg("目前沒有可用的麻將桌", NamedTextColor.RED)
            return
        }
        if (session.game.status != GameStatus.WAITING) {
            player.msg("遊戲已經開始", NamedTextColor.RED)
            return
        }
        if (session.game.players.size >= session.game.rule.playerCount) {
            player.msg("牌桌已滿", NamedTextColor.RED)
            return
        }
        val botNum = session.game.players.count { !it.isRealPlayer } + 1
        session.game.addBot("Bot$botNum")
        manager.updateTableDisplay(session)
        player.msg("已新增機器人 Bot$botNum（${session.game.players.size}/${session.game.rule.playerCount}）", NamedTextColor.GREEN)
        manager.checkAutoStart(session)
    }

    private fun handleKick(player: Player, args: Array<out String>) {
        val session = manager.getSessionForPlayer(player.uniqueId.toString())
            ?: manager.getAllSessions().firstOrNull()
        if (session == null) {
            player.msg("目前沒有可用的麻將桌", NamedTextColor.RED)
            return
        }
        val index = args.getOrNull(1)?.toIntOrNull()
        if (index == null || index !in session.game.players.indices) {
            player.msg("用法：/mahjong kick <座位號 0-3>", NamedTextColor.RED)
            return
        }
        session.game.kick(index)
        player.msg("已踢出座位 $index", NamedTextColor.YELLOW)
    }

    private fun handleDestroy(player: Player, args: Array<out String>) {
        val humanId = args.drop(1).joinToString(" ")
        val session = if (humanId.isNotEmpty()) {
            manager.getSessionByHumanId(humanId)
        } else {
            manager.getAllSessions().firstOrNull()
        }
        if (session == null) {
            player.msg("找不到麻將桌：$humanId", NamedTextColor.RED)
            return
        }
        val name = session.humanId
        manager.destroyTable(session.tableId)
        player.msg("麻將桌 $name 已銷毀", NamedTextColor.YELLOW)
    }

    private fun handleAction(player: Player, args: Array<out String>) {
        if (args.size < 2) return
        val session = manager.getSessionForPlayer(player.uniqueId.toString()) ?: return
        val mjPlayer = session.game.realPlayers.find { it.uuid == player.uniqueId.toString() } as? MahjongPlayer ?: return

        val behaviorName = args[1].uppercase()
        val behavior = try { MahjongGameBehavior.valueOf(behaviorName) } catch (_: Exception) { return }
        val data = if (args.size > 2) args.drop(2).joinToString(" ") else ""

        mjPlayer.resolveAction(behavior, data)
    }

    private fun handleList(player: Player) {
        val sessions = manager.getAllSessions()
        if (sessions.isEmpty()) {
            player.msg("目前沒有運作中的麻將桌", NamedTextColor.YELLOW)
            return
        }
        player.msg("運作中的麻將桌：", NamedTextColor.GOLD)
        sessions.forEach { session ->
            val count = session.game.players.size
            val pc = session.game.rule.playerCount
            val status = session.game.status
            player.msg("  ${session.humanId} - $count/$pc 位玩家 [$status]", NamedTextColor.AQUA)
        }
    }

    private fun handleInfo(player: Player) {
        val session = manager.getSessionForPlayer(player.uniqueId.toString())
        if (session == null) {
            player.msg("你不在任何麻將桌中", NamedTextColor.RED)
            return
        }
        player.msg("麻將桌 ${session.humanId}", NamedTextColor.GOLD)
        session.game.players.forEachIndexed { i, p ->
            val ready = if (p.ready) "✓" else "✗"
            val type = if (p.isRealPlayer) "玩家" else "機器人"
            player.msg("  $i. ${p.displayName} [$type] $ready", NamedTextColor.AQUA)
        }
        session.game.rule.toComponents().forEach { player.sendMessage(it) }
    }

    private fun sendHelp(player: Player) {
        player.msg("=== 台灣麻將指令 ===", NamedTextColor.GOLD)
        player.msg("/mahjong create [one/east/twowind] - 建立台麻牌桌", NamedTextColor.YELLOW)
        player.msg("/mahjong join [id] - 加入牌桌", NamedTextColor.YELLOW)
        player.msg("/mahjong leave - 離開牌桌", NamedTextColor.YELLOW)
        player.msg("/mahjong ready/unready - 準備/取消準備", NamedTextColor.YELLOW)
        player.msg("/mahjong bot - 新增機器人", NamedTextColor.YELLOW)
        player.msg("/mahjong start - 開始遊戲", NamedTextColor.YELLOW)
        player.msg("/mahjong destroy - 銷毀牌桌", NamedTextColor.YELLOW)
        player.msg("/mahjong info - 查看牌桌規則", NamedTextColor.YELLOW)
        player.msg("/mahjong list - 查看所有牌桌", NamedTextColor.YELLOW)
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("create", "join", "leave", "ready", "unready", "start", "bot", "kick", "destroy", "info", "list")
                .filter { sender.hasCommandPermission(it) }
                .filter { it.startsWith(args[0].lowercase()) }
        }
        if (args.size == 2 && args[0].lowercase() == "create") {
            if (!sender.hasCommandPermission("create")) return emptyList()
            return listOf("one", "east", "twowind")
                .filter { it.startsWith(args[1].lowercase()) }
        }
        if (args[0].lowercase() == "destroy") {
            if (!sender.hasCommandPermission("destroy")) return emptyList()
            val partial = args.drop(1).joinToString(" ")
            return manager.getAllHumanIds().filter { it.startsWith(partial) }
        }
        return emptyList()
    }

    private fun CommandSender.hasCommandPermission(command: String): Boolean {
        val permission = COMMAND_PERMISSIONS[command] ?: return true
        return hasPermission(permission.node)
    }

    private fun Player.requireCommandPermission(command: String): Boolean {
        val permission = COMMAND_PERMISSIONS[command] ?: return true
        if (hasPermission(permission.node)) {
            return true
        }
        msg(permission.deniedMessage, NamedTextColor.RED)
        return false
    }

    private fun Player.msg(text: String, color: NamedTextColor) {
        sendMessage(Component.text("[麻將] ", NamedTextColor.GOLD).append(Component.text(text, color)))
    }
}
