package com.mahjongplay.table

import com.mahjongplay.MahjongPlayPlugin
import com.mahjongplay.game.BotDifficulty
import com.mahjongplay.game.GameStatus
import com.mahjongplay.game.MahjongBot
import com.mahjongplay.game.MahjongPlayer
import com.mahjongplay.interaction.MahjongChatFormat
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.UUID

object MahjongAdminGUI {

    const val TITLE_PREFIX = "🛠 牌桌管理員控制台 #"

    private fun Component.noItalic(): Component =
        this.decoration(TextDecoration.ITALIC, false)

    fun open(player: Player, session: MahjongTableSession, manager: MahjongTableManager) {
        if (!player.hasPermission("mahjongplay.admin") && !player.isOp) {
            player.sendMessage(MahjongChatFormat.error("你沒有權限開啟管理員控制台。"))
            return
        }

        val title = "$TITLE_PREFIX${session.humanId}"
        val inv: Inventory = Bukkit.createInventory(
            null,
            54,
            Component.text(title, NamedTextColor.DARK_RED).decorate(TextDecoration.BOLD).noItalic(),
        )

        // 1. 邊框與背景填色
        val grayBorder = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" ", NamedTextColor.GRAY))
        val redBorder = createItem(Material.RED_STAINED_GLASS_PANE, Component.text(" ", NamedTextColor.RED))
        for (i in 0 until 54) {
            inv.setItem(i, grayBorder)
        }
        val cornerSlots = listOf(0, 8, 45, 53)
        cornerSlots.forEach { inv.setItem(it, redBorder) }

        // 2. Slot 4: 牌桌總覽資訊
        val statusText = if (session.game.status == GameStatus.PLAYING) "§c🔴 對局進行中" else "§a🟢 等待玩家中"
        val realCount = session.game.realPlayers.size
        val botCount = session.game.players.count { !it.isRealPlayer }
        val rule = session.game.rule
        val ownerName = session.ownerUUID?.let { uuid ->
            session.game.players.find { it.uuid == uuid }?.displayName ?: runCatching { Bukkit.getOfflinePlayer(UUID.fromString(uuid)).name }.getOrNull()
        } ?: "無 (系統桌)"

        val overviewLore = listOf(
            Component.text("§7牌桌 ID: §b#${session.humanId} §8(${session.tableId})").noItalic(),
            Component.text("§7當前狀態: $statusText").noItalic(),
            Component.text("§7所在位置: §e${session.center.world.name} §8(§f${session.center.blockX}, ${session.center.blockY}, ${session.center.blockZ}§8)").noItalic(),
            Component.text("§7牌桌房主: §6$ownerName").noItalic(),
            Component.text("§7玩家人數: §e$realCount 真人 §8/ §b$botCount 機器人 §8(上限 ${rule.playerCount} 人)").noItalic(),
            Component.text("§7賽制規則: §f${rule.displayCircleText} §8︳§6${rule.basePoints} 底 / ${rule.pointsPerTai} 台").noItalic(),
            Component.text("§7金流模式: ${if (rule.moneyMatch) "§e💰 真金對戰局" else "§a🎮 休閒娛樂局"}").noItalic(),
        )
        inv.setItem(4, createItem(Material.BEACON, Component.text("🀄【麻將牌桌核心狀態】", NamedTextColor.GOLD).decorate(TextDecoration.BOLD), overviewLore))

        // Row 2: 核心全域管理 (Slots 10, 12, 14, 16)
        // Slot 10: 強制終止牌局
        val stopLore = if (session.game.status == GameStatus.PLAYING) {
            listOf(
                Component.text("§c強制中斷當前正在進行的牌局。").noItalic(),
                Component.text("§7• 安全重置對局狀態並退回等待階段").noItalic(),
                Component.text("§7• 清理所有對局實體與牌牆").noItalic(),
                Component.text("§e▶ 點擊立即終止牌局").noItalic(),
            )
        } else {
            listOf(
                Component.text("§7目前牌桌處於等待中，無需終止。").noItalic(),
            )
        }
        inv.setItem(10, createItem(
            if (session.game.status == GameStatus.PLAYING) Material.BARRIER else Material.STRUCTURE_VOID,
            Component.text("🛑 強制終止牌局", if (session.game.status == GameStatus.PLAYING) NamedTextColor.RED else NamedTextColor.GRAY).decorate(TextDecoration.BOLD),
            stopLore,
        ))

        // Slot 12: 強制銷毀牌桌
        val destroyLore = listOf(
            Component.text("§4徹底銷毀此張麻將牌桌。").noItalic(),
            Component.text("§c警告：此操作不可復原！").noItalic(),
            Component.text("§7• 刪除所有實體、椅子與方塊").noItalic(),
            Component.text("§7• 移除存檔記錄並將玩家移出").noItalic(),
            Component.text("§e▶ 點擊確認銷毀牌桌").noItalic(),
        )
        inv.setItem(12, createItem(Material.LAVA_BUCKET, Component.text("💣 強制銷毀牌桌", NamedTextColor.DARK_RED).decorate(TextDecoration.BOLD), destroyLore))

        // Slot 14: 規則設定選單
        val settingsLore = listOf(
            Component.text("§b以管理員權限開啟規則設定 GUI。").noItalic(),
            Component.text("§7• 可調整底台分、圈數、時間限制").noItalic(),
            Component.text("§7• 可開啟/關閉真金模式與 Bot 參數").noItalic(),
            Component.text("§e▶ 點擊開啟設定選單").noItalic(),
        )
        inv.setItem(14, createItem(Material.COMPARATOR, Component.text("⚙ 調整牌桌規則", NamedTextColor.AQUA).decorate(TextDecoration.BOLD), settingsLore))

        // Slot 16: 修復與重整實體
        val repairLore = listOf(
            Component.text("§a重新生成並刷新所有牌桌實體。").noItalic(),
            Component.text("§7• 重整 3D 浮字體與按鈕互動箱").noItalic(),
            Component.text("§7• 修復因區塊重載可能發生的顯示問題").noItalic(),
            Component.text("§e▶ 點擊執行全桌刷新").noItalic(),
        )
        inv.setItem(16, createItem(Material.ANVIL, Component.text("🔄 刷新牌桌實體", NamedTextColor.GREEN).decorate(TextDecoration.BOLD), repairLore))

        // Row 3: 輔助操作 (Slots 19, 21, 23, 25)
        // Slot 19: 補入機器人
        val addBotLore = listOf(
            Component.text("§b為牌桌新增一位電腦機器人。").noItalic(),
            Component.text("§7• 僅在等待階段且未額滿時有效").noItalic(),
            Component.text("§e▶ 點擊加入機器人").noItalic(),
        )
        inv.setItem(19, createItem(Material.IRON_INGOT, Component.text("🤖 補入電腦機器人", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD), addBotLore))

        // Slot 21: 清除所有機器人
        val clearBotsLore = listOf(
            Component.text("§c移除牌桌內的所有電腦機器人。").noItalic(),
            Component.text("§7• 僅保留在座的真人玩家").noItalic(),
            Component.text("§e▶ 點擊清除所有 Bot").noItalic(),
        )
        inv.setItem(21, createItem(Material.SHEARS, Component.text("🧹 清除所有機器人", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD), clearBotsLore))

        // Slot 23: 強制開始對局
        val startLore = listOf(
            Component.text("§a強制啟動本桌牌局。").noItalic(),
            Component.text("§7• 若人數不足會自動補足 Bot 並開局").noItalic(),
            Component.text("§e▶ 點擊強制開局").noItalic(),
        )
        inv.setItem(23, createItem(Material.EMERALD, Component.text("🚀 強制啟動牌局", NamedTextColor.GREEN).decorate(TextDecoration.BOLD), startLore))

        // Slot 25: 強制切換旁觀看牌
        val seeHands = session.game.rule.spectatorSeeHands
        val spectatorLore = listOf(
            Component.text("§7控制旁觀玩家是否可看到全場玩家的手牌。").noItalic(),
            Component.text("§7• 當前狀態：${if (seeHands) "§a✔ 允許看牌 (明牌旁觀)" else "§c✗ 隱藏手牌 (防偷看作弊)"}").noItalic(),
            Component.text("§7• §e支援遊戲中即時切換§7，切換後立即重繪全場視角！").noItalic(),
            Component.text(" "),
            Component.text("§e▶ 點擊立即切換看牌狀態").noItalic(),
        )
        inv.setItem(25, createItem(
            if (seeHands) Material.ENDER_EYE else Material.ENDER_PEARL,
            Component.text(if (seeHands) "👁 旁觀看牌：§a允許看牌" else "👁 旁觀看牌：§c隱藏手牌", if (seeHands) NamedTextColor.AQUA else NamedTextColor.GRAY).decorate(TextDecoration.BOLD),
            spectatorLore,
        ))

        // Row 4: 玩家座位管理 (Slots 28, 30, 32, 34)
        val windNames = listOf("東風位 (Seat 0)", "南風位 (Seat 1)", "西風位 (Seat 2)", "北風位 (Seat 3)")
        val seatSlots = listOf(28, 30, 32, 34)

        for (seatIdx in 0..3) {
            val slot = seatSlots[seatIdx]
            val playerBase = session.game.players.getOrNull(seatIdx)
            if (playerBase == null) {
                val emptyLore = listOf(
                    Component.text("§7座位：${windNames[seatIdx]}").noItalic(),
                    Component.text("§8目前無人入座").noItalic(),
                )
                inv.setItem(slot, createItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, Component.text("🀄【空座位 $seatIdx】", NamedTextColor.DARK_GRAY), emptyLore))
            } else if (playerBase is MahjongPlayer) {
                val bp = Bukkit.getPlayer(UUID.fromString(playerBase.uuid))
                val isOnline = bp != null && bp.isOnline
                val isTakeover = playerBase.isBotTakeover
                val playerLore = listOf(
                    Component.text("§7風位：§6${windNames[seatIdx]}").noItalic(),
                    Component.text("§7類型：§a真人玩家").noItalic(),
                    Component.text("§7狀態：${if (isOnline) "§a在線" else "§c離線"} §8︳${if (playerBase.ready) "§a已準備 ✓" else "§7未準備 ✗"}").noItalic(),
                    Component.text("§7代打：${if (isTakeover) "§e🤖 代打模式中" else "§b手動操作中"}").noItalic(),
                    Component.text("§7目前積分：§e${playerBase.points} 分").noItalic(),
                    Component.text(" "),
                    Component.text("§e[左鍵點擊] §c踢出該玩家 (Kick)").noItalic(),
                    Component.text("§b[右鍵點擊] §e切換代打模式 (Takeover)").noItalic(),
                )
                val head = createPlayerHead(playerBase.rawDisplayName, playerBase.uuid, Component.text("👤 ${playerBase.rawDisplayName}", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD), playerLore)
                inv.setItem(slot, head)
            } else if (playerBase is MahjongBot) {
                val botLore = listOf(
                    Component.text("§7風位：§6${windNames[seatIdx]}").noItalic(),
                    Component.text("§7類型：§b電腦機器人 (Bot)").noItalic(),
                    Component.text("§7難度：§e${playerBase.difficulty.displayName}").noItalic(),
                    Component.text("§7目前積分：§e${playerBase.points} 分").noItalic(),
                    Component.text(" "),
                    Component.text("§e[左鍵點擊] §c踢出此機器人").noItalic(),
                )
                inv.setItem(slot, createItem(Material.IRON_BLOCK, Component.text("🤖 ${playerBase.displayName}", NamedTextColor.AQUA).decorate(TextDecoration.BOLD), botLore))
            }
        }

        // Slot 49: 關閉選單
        inv.setItem(49, createItem(Material.BARRIER, Component.text("✖ 關閉管理面板", NamedTextColor.RED).decorate(TextDecoration.BOLD)))

        player.openInventory(inv)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.6f, 1.2f)
    }

    private fun createItem(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(name.decoration(TextDecoration.ITALIC, false))
        if (lore.isNotEmpty()) {
            meta.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
        }
        item.itemMeta = meta
        return item
    }

    private fun createPlayerHead(playerName: String, uuidStr: String, name: Component, lore: List<Component>): ItemStack {
        val item = ItemStack(Material.PLAYER_HEAD)
        val meta = item.itemMeta as? SkullMeta ?: return item
        runCatching {
            meta.owningPlayer = Bukkit.getOfflinePlayer(UUID.fromString(uuidStr))
        }
        meta.displayName(name.decoration(TextDecoration.ITALIC, false))
        meta.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
        item.itemMeta = meta
        return item
    }

    class Listener(private val manager: MahjongTableManager) : org.bukkit.event.Listener {

        @EventHandler
        fun onInventoryClick(event: InventoryClickEvent) {
            val titleComponent = event.view.title()
            val plainTitle = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(titleComponent)
            if (!plainTitle.contains(TITLE_PREFIX)) return

            event.isCancelled = true
            val player = event.whoClicked as? Player ?: return
            val rawSlot = event.rawSlot
            if (rawSlot !in 0 until 54) return

            if (!player.hasPermission("mahjongplay.admin") && !player.isOp) {
                player.sendMessage(MahjongChatFormat.error("你沒有管理員權限。"))
                player.closeInventory()
                return
            }

            val humanId = plainTitle.substringAfter(TITLE_PREFIX).trim()
            val session = manager.getSessionByHumanId(humanId)
            if (session == null) {
                player.sendMessage(MahjongChatFormat.error("找不到該麻將桌。"))
                player.closeInventory()
                return
            }

            when (rawSlot) {
                10 -> { // 強制終止牌局
                    if (session.game.status == GameStatus.PLAYING) {
                        manager.cancelGame(session)
                        player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f)
                        player.sendMessage(MahjongChatFormat.success("已成功強制終止並重置牌局【#$humanId】！"))
                        MahjongAdminGUI.open(player, session, manager)
                    } else {
                        player.sendMessage(MahjongChatFormat.warn("牌桌目前處於等待中，無需終止。"))
                    }
                }
                12 -> { // 強制銷毀牌桌
                    player.closeInventory()
                    manager.destroyTable(session.tableId)
                    player.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.2f)
                    player.sendMessage(MahjongChatFormat.success("麻將桌【#$humanId】已徹底銷毀！"))
                }
                14 -> { // 開啟規則設定選單
                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
                    MahjongSettingsGUI.open(player, session, manager)
                }
                16 -> { // 刷新牌桌實體
                    session.table.spawn()
                    manager.registerJoinInteraction(session)
                    manager.updateTableDisplay(session)
                    player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 0.7f, 1.5f)
                    player.sendMessage(MahjongChatFormat.success("已成功重新生成並刷新牌桌【#$humanId】的所有實體！"))
                    MahjongAdminGUI.open(player, session, manager)
                }
                19 -> { // 補入機器人
                    if (session.game.status != GameStatus.WAITING) {
                        player.sendMessage(MahjongChatFormat.warn("遊戲進行中，無法新增機器人。"))
                        return
                    }
                    if (session.game.players.size >= session.game.rule.playerCount) {
                        player.sendMessage(MahjongChatFormat.warn("牌桌已滿（${session.game.rule.playerCount}/${session.game.rule.playerCount}）。"))
                        return
                    }
                    val botNum = session.game.players.count { !it.isRealPlayer } + 1
                    session.game.addBot("Bot$botNum", session.game.rule.defaultBotDifficulty)
                    manager.updateTableDisplay(session)
                    manager.checkAutoStart(session)
                    player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f)
                    player.sendMessage(MahjongChatFormat.success("已成功為牌桌【#$humanId】新增機器人 Bot$botNum！"))
                    MahjongAdminGUI.open(player, session, manager)
                }
                21 -> { // 清除機器人
                    if (session.game.status != GameStatus.WAITING) {
                        player.sendMessage(MahjongChatFormat.warn("遊戲進行中，無法直接清除機器人（請先終止牌局）。"))
                        return
                    }
                    val bots = session.game.players.filter { !it.isRealPlayer }
                    bots.forEach { bot ->
                        manager.leaveTable(bot.uuid)
                    }
                    manager.updateTableDisplay(session)
                    player.playSound(player.location, Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.8f, 1.0f)
                    player.sendMessage(MahjongChatFormat.success("已清除牌桌【#$humanId】內的所有機器人！"))
                    MahjongAdminGUI.open(player, session, manager)
                }
                23 -> { // 強制開局
                    if (session.game.status != GameStatus.WAITING) {
                        player.sendMessage(MahjongChatFormat.warn("牌局已在進行中。"))
                        return
                    }
                    val needed = session.game.rule.playerCount - session.game.players.size
                    for (i in 1..needed) {
                        val botNum = session.game.players.count { !it.isRealPlayer } + 1
                        session.game.addBot("Bot$botNum", session.game.rule.defaultBotDifficulty)
                    }
                    session.game.players.forEach { it.ready = true }
                    val err = manager.startGame(session)
                    if (err != null) {
                        player.sendMessage(MahjongChatFormat.error(err))
                    } else {
                        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f)
                        player.sendMessage(MahjongChatFormat.success("已強制啟動牌局【#$humanId】！"))
                    }
                    MahjongAdminGUI.open(player, session, manager)
                }
                25 -> { // 強制切換旁觀看牌 (遊戲中即時生效)
                    val newSeeHands = !session.game.rule.spectatorSeeHands
                    session.game.rule.spectatorSeeHands = newSeeHands
                    manager.autoSave()
                    if (session.game.status == GameStatus.PLAYING) {
                        session.game.players.forEach { session.renderer.updateVisibility(it) }
                    }
                    manager.updateTableDisplay(session)
                    val stateText = if (newSeeHands) "§a允許看牌 (明牌旁觀)" else "§c隱藏手牌 (防作弊)"
                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
                    player.sendMessage(MahjongChatFormat.success("已將牌桌【#$humanId】的旁觀模式切換為：$stateText"))
                    MahjongAdminGUI.open(player, session, manager)
                }
                28, 30, 32, 34 -> { // 座位玩家管理
                    val seatIdx = when (rawSlot) {
                        28 -> 0
                        30 -> 1
                        32 -> 2
                        34 -> 3
                        else -> -1
                    }
                    val targetPlayer = session.game.players.getOrNull(seatIdx)
                    if (targetPlayer == null) {
                        player.sendMessage(MahjongChatFormat.warn("該座位目前沒有玩家。"))
                        return
                    }

                    if (event.isRightClick && targetPlayer is MahjongPlayer) {
                        // 右鍵：切換代打模式
                        if (session.game.status == GameStatus.PLAYING) {
                            if (!targetPlayer.isBotTakeover) {
                                targetPlayer.activateBotTakeover(session.game.rule.defaultBotDifficulty)
                                player.sendMessage(MahjongChatFormat.success("已將玩家【${targetPlayer.rawDisplayName}】切換為 🤖 代打模式！"))
                            } else {
                                targetPlayer.deactivateBotTakeover()
                                player.sendMessage(MahjongChatFormat.success("已將玩家【${targetPlayer.rawDisplayName}】解除代打模式！"))
                            }
                            session.renderer.updateSeatScoreDisplays()
                            session.renderer.updateVisibility(targetPlayer)
                            session.bridge.updateHud()
                            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
                            MahjongAdminGUI.open(player, session, manager)
                        } else {
                            player.sendMessage(MahjongChatFormat.warn("代打模式僅在對局中生效。"))
                        }
                    } else {
                        // 左鍵：踢出玩家
                        val name = targetPlayer.displayName
                        manager.leaveTable(targetPlayer.uuid)
                        runCatching { UUID.fromString(targetPlayer.uuid) }.getOrNull()?.let { manager.releasePlayerFromChairs(it) }
                        player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 0.8f, 1.2f)
                        player.sendMessage(MahjongChatFormat.success("已將【$name】移出牌桌【#$humanId】！"))
                        MahjongAdminGUI.open(player, session, manager)
                    }
                }
                49 -> { // 關閉
                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, 1.0f)
                    player.closeInventory()
                }
            }
        }
    }
}
