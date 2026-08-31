package com.mahjongplay.table

import com.mahjongplay.MahjongPlayPlugin
import com.mahjongplay.game.BotDifficulty
import com.mahjongplay.game.GameStatus
import com.mahjongplay.game.MahjongPlayer
import com.mahjongplay.interaction.MahjongChatFormat
import com.mahjongplay.model.MahjongGameBehavior
import com.mahjongplay.model.MahjongRule
import com.mahjongplay.stats.MahjongStatsGUI
import com.mahjongplay.stats.MahjongStatsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.UUID

class MahjongCommand(
    private val manager: MahjongTableManager,
    private val statsManager: MahjongStatsManager,
) : CommandExecutor, TabCompleter {

    companion object {
        const val PERM_ADMIN = "mahjongplay.admin"
        const val PERM_CREATE = "mahjongplay.command.create"
        const val PERM_JOIN = "mahjongplay.command.join"
        const val PERM_LEAVE = "mahjongplay.command.leave"
        const val PERM_SETTINGS = "mahjongplay.command.settings"
        const val PERM_STATUS = "mahjongplay.command.status"
        const val PERM_LIST = "mahjongplay.command.list"
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty() || args[0].equals("help", ignoreCase = true)) {
            sendHelp(sender)
            return true
        }

        val sub = args[0].lowercase()
        when (sub) {
            "table", "create" -> if (sender is Player) handleCreate(sender, args) else sender.msgConsoleOnly()
            "join" -> if (sender is Player) handleJoin(sender, args) else sender.msgConsoleOnly()
            "leave", "quit" -> if (sender is Player) handleLeave(sender) else sender.msgConsoleOnly()
            "settings", "setting", "menu", "gui" -> if (sender is Player) handleSettings(sender, args) else sender.msgConsoleOnly()
            "stats", "status", "profile" -> if (sender is Player) handleStatus(sender, args) else sender.msgConsoleOnly()
            "list" -> handleList(sender)
            "auto", "trust", "ai" -> if (sender is Player) handleAuto(sender) else sender.msgConsoleOnly()
            "ready" -> if (sender is Player) handleReady(sender, true) else sender.msgConsoleOnly()
            "unready" -> if (sender is Player) handleReady(sender, false) else sender.msgConsoleOnly()
            "start" -> if (sender is Player) handleStart(sender) else sender.msgConsoleOnly()
            "bot" -> if (sender is Player) handleAddBot(sender, args) else sender.msgConsoleOnly()
            "action" -> if (sender is Player) handleAction(sender, args) else sender.msgConsoleOnly()
            "admin" -> handleAdmin(sender, args)
            "stop", "cancel" -> handleCancel(sender, args.drop(1).toTypedArray())
            "destroy" -> handleDestroy(sender, args.drop(1).toTypedArray())
            "kick" -> handleKick(sender, args.drop(1).toTypedArray())
            "reload" -> handleReload(sender)
            else -> sendHelp(sender)
        }
        return true
    }

    // ==========================================
    // 玩家常用指令
    // ==========================================

    private fun handleCreate(player: Player, args: Array<out String>) {
        if (!player.hasPermission(PERM_CREATE) && !player.hasPermission(PERM_ADMIN)) {
            player.msg("你沒有權限建立麻將桌。", NamedTextColor.RED)
            return
        }

        val rule = manager.createRule()
        val direction = player.location.direction.setY(0).normalize()
        val loc = player.location.clone().add(direction.multiply(4))
        val center = loc.clone()
        center.x = loc.blockX + 0.5
        center.y = loc.blockY.toDouble()
        center.z = loc.blockZ + 0.5

        val session = manager.createTable(center, player.uniqueId.toString(), player.name, rule)
        session.table.spawn()
        manager.registerJoinInteraction(session)
        player.msg("麻將桌已於面前成功建立！【${session.humanId}】", NamedTextColor.GREEN)
        player.msg("右鍵點擊牌桌或輸入 /mahjong settings 可開啟視覺化規則設定選單。", NamedTextColor.YELLOW)
    }

    private fun handleJoin(player: Player, args: Array<out String>) {
        if (!player.hasPermission(PERM_JOIN) && !player.hasPermission(PERM_ADMIN)) {
            player.msg("你沒有權限加入麻將桌。", NamedTextColor.RED)
            return
        }

        val targetSession = if (args.size > 1) {
            val query = args[1]
            manager.getAllSessions().find { it.humanId.contains(query, ignoreCase = true) || it.tableId.toString().startsWith(query) }
        } else {
            manager.getAllSessions().firstOrNull { s -> s.center.world == player.world && s.center.distance(player.location) <= 10.0 }
                ?: manager.getAllSessions().firstOrNull()
        }

        if (targetSession == null) {
            player.msg("目前找不到可加入的麻將桌。", NamedTextColor.RED)
            return
        }

        if (manager.joinTable(targetSession.tableId, player.uniqueId.toString(), player.name)) {
            player.msg("已成功加入麻將桌【${targetSession.humanId}】！", NamedTextColor.GREEN)
        } else {
            player.msg("無法加入牌桌（可能已滿人或您已在對局中）。", NamedTextColor.RED)
        }
    }

    private fun handleLeave(player: Player) {
        if (manager.leaveTable(player.uniqueId.toString())) {
            player.msg("已離開麻將桌。", NamedTextColor.YELLOW)
        } else {
            player.msg("你目前不在任何麻將桌中。", NamedTextColor.RED)
        }
    }

    private fun handleSettings(player: Player, args: Array<out String>) {
        val session = manager.getSessionForPlayer(player.uniqueId.toString())
            ?: manager.getAllSessions().firstOrNull { s -> s.center.world == player.world && s.center.distance(player.location) <= 6.0 }
            ?: manager.getAllSessions().firstOrNull()

        if (session == null) {
            player.msg("目前附近沒有麻將桌。", NamedTextColor.RED)
            return
        }

        MahjongSettingsGUI.open(player, session, manager)
    }

    private fun handleStatus(player: Player, args: Array<out String>) {
        val targetName = args.getOrNull(1)
        val stats = if (targetName != null) {
            statsManager.getStatsByName(targetName) ?: run {
                player.msg("找不到玩家【$targetName】的麻將戰績紀錄！", NamedTextColor.RED)
                return
            }
        } else {
            statsManager.getStats(player.uniqueId.toString(), player.name)
        }
        MahjongStatsGUI.openStats(player, stats)
    }

    private fun handleAuto(player: Player) {
        if (!manager.toggleBotTakeoverForPlayer(player)) {
            player.msg("你目前不在進行中的麻將牌局中。", NamedTextColor.RED)
        }
    }

    private fun handleReady(player: Player, ready: Boolean) {
        val session = manager.getSessionForPlayer(player.uniqueId.toString())
        if (session == null) {
            player.msg("你不在任何麻將桌中。", NamedTextColor.RED)
            return
        }
        if (ready) {
            val error = manager.canPlayerReady(session, player.uniqueId.toString())
            if (error != null) {
                player.msg(error, NamedTextColor.RED)
                return
            }
        }
        session.game.readyOrNot(player.uniqueId.toString(), ready)
        manager.updateTableDisplay(session)
        player.msg(if (ready) "已完成準備 ✓" else "已取消準備 ✗", if (ready) NamedTextColor.GREEN else NamedTextColor.YELLOW)
        manager.checkAutoStart(session)
    }

    private fun handleStart(player: Player) {
        val session = manager.getSessionForPlayer(player.uniqueId.toString())
            ?: manager.getAllSessions().firstOrNull()
        if (session == null) {
            player.msg("目前沒有可用的麻將桌。", NamedTextColor.RED)
            return
        }
        val error = manager.startGame(session)
        if (error != null) player.msg(error, NamedTextColor.RED)
    }

    private fun handleAddBot(player: Player, args: Array<out String>) {
        val session = manager.getSessionForPlayer(player.uniqueId.toString())
            ?: manager.getAllSessions().firstOrNull()
        if (session == null) {
            player.msg("目前沒有可用的麻將桌。", NamedTextColor.RED)
            return
        }
        if (session.game.status != GameStatus.WAITING) {
            player.msg("遊戲已經開始，無法加入機器人。", NamedTextColor.RED)
            return
        }
        if (session.game.players.size >= session.game.rule.playerCount) {
            player.msg("牌桌已滿（4/4）。", NamedTextColor.RED)
            return
        }
        if (!session.game.rule.botsEnabled) {
            player.msg("牌桌目前設定為關閉機器人。請先在牌桌設定中開啟 Bots！", NamedTextColor.RED)
            return
        }
        val difficulty = args.getOrNull(1)?.let { BotDifficulty.fromString(it) } ?: session.game.rule.defaultBotDifficulty
        val botNum = session.game.players.count { !it.isRealPlayer } + 1
        session.game.addBot("Bot$botNum", difficulty)
        manager.updateTableDisplay(session)
        player.msg("已新增機器人 Bot$botNum [${difficulty.displayName}]（${session.game.players.size}/${session.game.rule.playerCount}）", NamedTextColor.GREEN)
        manager.checkAutoStart(session)
    }

    private fun handleList(sender: CommandSender) {
        val sessions = manager.getAllSessions()
        if (sessions.isEmpty()) {
            sender.msg("目前伺服器內沒有任何運作中的麻將桌。", NamedTextColor.YELLOW)
            return
        }
        sender.sendMessage(MahjongChatFormat.DIVIDER)
        sender.sendMessage(Component.text("  🀄 運作中的麻將桌列表：", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
        sessions.forEach { s ->
            val count = s.game.players.size
            val max = s.game.rule.playerCount
            val statusText = if (s.game.status == GameStatus.PLAYING) "對局中" else "等待中"
            val statusColor = if (s.game.status == GameStatus.PLAYING) NamedTextColor.RED else NamedTextColor.GREEN
            val modeText = if (s.game.rule.moneyMatch && !s.game.rule.botsEnabled) "💰金幣局" else "🎮娛樂局"
            sender.sendMessage(
                Component.text("  • ", NamedTextColor.GRAY)
                    .append(Component.text(s.humanId, NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
                    .append(Component.text(" | 玩家: $count/$max | ", NamedTextColor.GRAY))
                    .append(Component.text("[$statusText]", statusColor))
                    .append(Component.text(" | $modeText", NamedTextColor.YELLOW))
            )
        }
        sender.sendMessage(MahjongChatFormat.DIVIDER)
    }

    private fun handleAction(player: Player, args: Array<out String>) {
        if (args.size < 2) return
        val session = manager.getSessionForPlayer(player.uniqueId.toString()) ?: return
        val mjPlayer = session.game.realPlayers.find { it.uuid == player.uniqueId.toString() } as? MahjongPlayer ?: return
        val behaviorName = args[1].uppercase()
        val behavior = runCatching { MahjongGameBehavior.valueOf(behaviorName) }.getOrNull() ?: return
        val data = if (args.size > 2) args.drop(2).joinToString(" ") else ""
        mjPlayer.resolveAction(behavior, data)
    }

    // ==========================================
    // 管理員專用指令
    // ==========================================

    private fun handleAdmin(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            sender.msg("你沒有權限執行管理員指令（需要 $PERM_ADMIN）。", NamedTextColor.RED)
            return
        }

        if (args.size < 2) {
            sendAdminHelp(sender)
            return
        }

        val adminSub = args[1].lowercase()
        val subArgs = args.drop(2).toTypedArray()
        when (adminSub) {
            "stop", "cancel" -> handleCancel(sender, subArgs)
            "destroy" -> handleDestroy(sender, subArgs)
            "kick" -> handleKick(sender, subArgs)
            "reload" -> handleReload(sender)
            "resetstats" -> handleResetStats(sender, subArgs)
            else -> sendAdminHelp(sender)
        }
    }

    private fun handleCancel(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission(PERM_ADMIN) && sender is Player && !manager.isTableOwner(manager.getSessionForPlayer(sender.uniqueId.toString()) ?: return, sender.uniqueId.toString())) {
            sender.msg("只有管理員或該桌桌主可以取消牌局。", NamedTextColor.RED)
            return
        }

        val session = if (args.isNotEmpty()) {
            val query = args.joinToString(" ")
            manager.getSessionByHumanId(query)
                ?: manager.getAllSessions().find { it.tableId.toString().startsWith(query) || it.game.players.any { p -> p.displayName.equals(query, ignoreCase = true) } }
        } else {
            if (sender is Player) manager.getSessionForPlayer(sender.uniqueId.toString()) else manager.getAllSessions().firstOrNull { it.game.status == GameStatus.PLAYING }
        }

        if (session == null) {
            sender.msg("找不到符合條件的進行中牌桌。", NamedTextColor.RED)
            return
        }

        if (session.game.status == GameStatus.WAITING) {
            sender.msg("麻將桌【${session.humanId}】尚未開局，無需取消。", NamedTextColor.YELLOW)
            return
        }

        if (manager.cancelGame(session)) {
            sender.msg("麻將桌【${session.humanId}】的牌局已被強制終止並重設為等待狀態！", NamedTextColor.GREEN)
        } else {
            sender.msg("終止牌局失敗。", NamedTextColor.RED)
        }
    }

    private fun handleDestroy(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            sender.msg("只有管理員可以強制銷毀麻將桌。", NamedTextColor.RED)
            return
        }

        if (args.firstOrNull()?.equals("all", ignoreCase = true) == true) {
            val count = manager.getAllSessions().size
            manager.getAllSessions().toList().forEach { manager.destroyTable(it.tableId) }
            sender.msg("已強制銷毀全服共 $count 張麻將桌！", NamedTextColor.GREEN)
            return
        }

        val query = args.joinToString(" ")
        val session = if (query.isNotEmpty()) {
            manager.getSessionByHumanId(query)
                ?: manager.getAllSessions().find { it.tableId.toString().startsWith(query) }
        } else {
            if (sender is Player) manager.getSessionForPlayer(sender.uniqueId.toString()) else manager.getAllSessions().firstOrNull()
        }

        if (session == null) {
            sender.msg("找不到欲銷毀的麻將桌。", NamedTextColor.RED)
            return
        }

        val name = session.humanId
        manager.destroyTable(session.tableId)
        sender.msg("麻將桌【$name】已成功銷毀。", NamedTextColor.GREEN)
    }

    private fun handleKick(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.msg("用法：/mahjong admin kick <玩家名稱/座位號>", NamedTextColor.RED)
            return
        }

        val targetName = args[0]
        val session = manager.getAllSessions().find { s -> s.game.players.any { it.displayName.equals(targetName, ignoreCase = true) } }
            ?: (if (sender is Player) manager.getSessionForPlayer(sender.uniqueId.toString()) else null)

        if (session == null) {
            sender.msg("找不到包含該玩家的麻將桌。", NamedTextColor.RED)
            return
        }

        val isOwner = sender is Player && manager.isTableOwner(session, sender.uniqueId.toString())
        if (!sender.hasPermission(PERM_ADMIN) && !isOwner) {
            sender.msg("你沒有權限踢出該玩家。", NamedTextColor.RED)
            return
        }

        val index = targetName.toIntOrNull() ?: session.game.players.indexOfFirst { it.displayName.equals(targetName, ignoreCase = true) }
        if (index !in session.game.players.indices) {
            sender.msg("在牌桌中找不到該玩家或座位號。", NamedTextColor.RED)
            return
        }

        val kicked = session.game.players[index]
        session.game.kick(index)
        sender.msg("已將【${kicked.displayName}】移出麻將桌【${session.humanId}】。", NamedTextColor.GREEN)
    }

    private fun handleReload(sender: CommandSender) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            sender.msg("你沒有權限重載設定檔。", NamedTextColor.RED)
            return
        }

        MahjongPlayPlugin.instance.reloadPluginConfig()
        sender.msg("TaiwaneseMahjong 設定檔 config.yml 已成功重載！", NamedTextColor.GREEN)
    }

    private fun handleResetStats(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            sender.msg("你沒有權限重置玩家戰績。", NamedTextColor.RED)
            return
        }

        if (args.isEmpty()) {
            sender.msg("用法：/mahjong admin resetstats <玩家名稱>", NamedTextColor.RED)
            return
        }

        val targetName = args[0]
        val offline = Bukkit.getOfflinePlayer(targetName)
        val uuidStr = offline.uniqueId.toString()
        statsManager.resetStats(uuidStr)
        sender.msg("已重置玩家【$targetName】的麻將歷史戰績紀錄！", NamedTextColor.GREEN)
    }

    // ==========================================
    // 說明與 Tab 補全
    // ==========================================

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage(MahjongChatFormat.DIVIDER)
        sender.sendMessage(Component.text("  🀄【台灣麻將】 指令導覽說明", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
        sender.sendMessage(Component.text("  💡 玩家常用指令：", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))
        sender.sendMessage(Component.text("  • /mahjong table", NamedTextColor.AQUA).append(Component.text(" - 於面前生成麻將桌", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("  • /mahjong join [桌號]", NamedTextColor.AQUA).append(Component.text(" - 加入牌桌（或直接右鍵牌桌）", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("  • /mahjong leave", NamedTextColor.AQUA).append(Component.text(" - 離開目前牌桌", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("  • /mahjong settings", NamedTextColor.AQUA).append(Component.text(" - 開啟規則設定選單 GUI", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("  • /mahjong stats [玩家]", NamedTextColor.AQUA).append(Component.text(" - 查看麻將歷史戰績與段位", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("  • /mahjong auto", NamedTextColor.AQUA).append(Component.text(" - 開啟／取消 🤖 託管代打（或按 F 鍵）", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("  • /mahjong list", NamedTextColor.AQUA).append(Component.text(" - 查看所有運作中的麻將桌", NamedTextColor.GRAY)))

        if (sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(Component.text(" "))
            sender.sendMessage(Component.text("  🛠 管理員專用指令：", NamedTextColor.RED).decorate(TextDecoration.BOLD))
            sender.sendMessage(Component.text("  • /mahjong admin stop [桌號/玩家]", NamedTextColor.LIGHT_PURPLE).append(Component.text(" - 強制終止並重置牌局", NamedTextColor.GRAY)))
            sender.sendMessage(Component.text("  • /mahjong admin destroy [桌號/all]", NamedTextColor.LIGHT_PURPLE).append(Component.text(" - 強制銷毀牌桌實體", NamedTextColor.GRAY)))
            sender.sendMessage(Component.text("  • /mahjong admin kick <玩家>", NamedTextColor.LIGHT_PURPLE).append(Component.text(" - 強制踢出牌桌玩家", NamedTextColor.GRAY)))
            sender.sendMessage(Component.text("  • /mahjong admin reload", NamedTextColor.LIGHT_PURPLE).append(Component.text(" - 重新載入 config.yml", NamedTextColor.GRAY)))
            sender.sendMessage(Component.text("  • /mahjong admin resetstats <玩家>", NamedTextColor.LIGHT_PURPLE).append(Component.text(" - 重置玩家戰績數據", NamedTextColor.GRAY)))
        }
        sender.sendMessage(MahjongChatFormat.DIVIDER)
    }

    private fun sendAdminHelp(sender: CommandSender) {
        sender.sendMessage(MahjongChatFormat.DIVIDER)
        sender.sendMessage(Component.text("  🛠【台灣麻將】 管理員指令列表", NamedTextColor.RED).decorate(TextDecoration.BOLD))
        sender.sendMessage(Component.text("  • /mahjong admin stop [桌號/玩家]", NamedTextColor.YELLOW).append(Component.text(" - 強制終止進行中的牌局", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("  • /mahjong admin destroy [桌號/all]", NamedTextColor.YELLOW).append(Component.text(" - 銷毀牌桌", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("  • /mahjong admin kick <玩家>", NamedTextColor.YELLOW).append(Component.text(" - 踢出玩家", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("  • /mahjong admin reload", NamedTextColor.YELLOW).append(Component.text(" - 重新載入設定檔", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("  • /mahjong admin resetstats <玩家>", NamedTextColor.YELLOW).append(Component.text(" - 重置玩家戰績", NamedTextColor.GRAY)))
        sender.sendMessage(MahjongChatFormat.DIVIDER)
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        val isAdmin = sender.hasPermission(PERM_ADMIN)

        if (args.size == 1) {
            val suggestions = mutableListOf("table", "join", "leave", "settings", "stats", "auto", "list", "help")
            if (isAdmin) {
                suggestions += "admin"
                suggestions += "stop"
                suggestions += "destroy"
                suggestions += "reload"
            }
            return suggestions.filter { it.startsWith(args[0].lowercase()) }
        }

        if (args.size == 2) {
            val first = args[0].lowercase()
            if (first == "admin" && isAdmin) {
                return listOf("stop", "destroy", "kick", "reload", "resetstats")
                    .filter { it.startsWith(args[1].lowercase()) }
            }
            if (first == "stats" || first == "status" || first == "profile") {
                return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
            }
            if (first == "join" || first == "stop" || first == "cancel") {
                return manager.getAllHumanIds().filter { it.startsWith(args[1], ignoreCase = true) }
            }
            if (first == "destroy" && isAdmin) {
                return (listOf("all") + manager.getAllHumanIds()).filter { it.startsWith(args[1], ignoreCase = true) }
            }
        }

        if (args.size == 3 && args[0].lowercase() == "admin" && isAdmin) {
            val adminSub = args[1].lowercase()
            when (adminSub) {
                "stop", "cancel" -> return (manager.getAllHumanIds() + Bukkit.getOnlinePlayers().map { it.name }).filter { it.startsWith(args[2], ignoreCase = true) }
                "destroy" -> return (listOf("all") + manager.getAllHumanIds()).filter { it.startsWith(args[2], ignoreCase = true) }
                "kick", "resetstats" -> return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[2], ignoreCase = true) }
            }
        }

        return emptyList()
    }

    private fun CommandSender.msg(text: String, color: NamedTextColor) {
        sendMessage(MahjongChatFormat.PREFIX.append(Component.text(text, color)))
    }

    private fun CommandSender.msgConsoleOnly() {
        sendMessage("此指令僅限遊戲內玩家使用。")
    }
}
