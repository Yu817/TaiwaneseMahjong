package com.mahjongplay.stats

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.UUID

object MahjongStatsGUI {

    const val STATS_GUI_TITLE = "🀄 麻將個人戰績統計"
    const val LEADERBOARD_GUI_TITLE = "🏆 全服麻將雀神排行榜"

    private fun Component.noItalic(): Component =
        this.decoration(TextDecoration.ITALIC, false)

    fun openStats(player: Player, stats: MahjongPlayerStats) {
        val inv = Bukkit.createInventory(null, 54, Component.text(STATS_GUI_TITLE, NamedTextColor.DARK_AQUA).decorate(TextDecoration.BOLD).noItalic())

        // 1. Fill background with glass panes
        val grayBorder = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" ", NamedTextColor.GRAY))
        val cyanBorder = createItem(Material.CYAN_STAINED_GLASS_PANE, Component.text(" ", NamedTextColor.AQUA))

        for (i in 0 until 54) {
            inv.setItem(i, grayBorder)
        }
        val cyanSlots = listOf(0, 8, 9, 17, 36, 44, 45, 53)
        cyanSlots.forEach { inv.setItem(it, cyanBorder) }

        // 2. Player Head (Slot 4)
        val head = ItemStack(Material.PLAYER_HEAD)
        val headMeta = head.itemMeta as? SkullMeta
        if (headMeta != null) {
            headMeta.owningPlayer = Bukkit.getOfflinePlayer(runCatching { UUID.fromString(stats.uuid) }.getOrNull() ?: player.uniqueId)
            headMeta.displayName(Component.text("👤 玩家：${stats.playerName}", NamedTextColor.GOLD).decorate(TextDecoration.BOLD).noItalic())
            headMeta.lore(listOf(
                Component.text("段位稱號: ", NamedTextColor.GRAY).append(Component.text(stats.rankTitle, NamedTextColor.YELLOW)),
                Component.text("總對局數: ", NamedTextColor.GRAY).append(Component.text("${stats.totalMatches} 場", NamedTextColor.WHITE)),
                Component.text("總累積積分: ", NamedTextColor.GRAY).append(
                    Component.text(
                        if (stats.totalNetScore >= 0) "+${stats.totalNetScore}" else "${stats.totalNetScore}",
                        if (stats.totalNetScore >= 0) NamedTextColor.GREEN else NamedTextColor.RED,
                    ).decorate(TextDecoration.BOLD)
                ),
            ).map { it.noItalic() })
            head.itemMeta = headMeta
        }
        inv.setItem(4, head)

        // 3. 對局總覽 (Slot 19)
        val overview = createItem(
            Material.GOLD_INGOT,
            Component.text("📊【對局總覽】", NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
            listOf(
                Component.text("• 總對局場數: ", NamedTextColor.GRAY).append(Component.text("${stats.totalMatches} 場", NamedTextColor.WHITE)),
                Component.text("• 總勝率 (一位率): ", NamedTextColor.GRAY).append(Component.text("${String.format("%.1f", stats.winRate)}%", NamedTextColor.YELLOW)),
                Component.text("• 前二率 (連對率): ", NamedTextColor.GRAY).append(Component.text("${String.format("%.1f", stats.top2Rate)}%", NamedTextColor.AQUA)),
                Component.text("• 總積分盈虧: ", NamedTextColor.GRAY).append(
                    Component.text(
                        if (stats.totalNetScore >= 0) "+${stats.totalNetScore}" else "${stats.totalNetScore}",
                        if (stats.totalNetScore >= 0) NamedTextColor.GREEN else NamedTextColor.RED,
                    )
                ),
                Component.text("• 單場最高獲利: ", NamedTextColor.GRAY).append(Component.text("+${stats.maxMatchScore} 積分", NamedTextColor.GREEN)),
            )
        )
        inv.setItem(19, overview)

        // 4. 順位分佈 (Slot 21)
        val placement = createItem(
            Material.EMERALD,
            Component.text("🥇【順位分佈】", NamedTextColor.GREEN).decorate(TextDecoration.BOLD),
            listOf(
                Component.text("• 🥇 一位: ", NamedTextColor.YELLOW).append(Component.text("${stats.firstPlaces} 次 (${String.format("%.1f", stats.winRate)}%)", NamedTextColor.WHITE)),
                Component.text("• 🥈 二位: ", NamedTextColor.GRAY).append(Component.text("${stats.secondPlaces} 次 (${String.format("%.1f", if (stats.totalMatches > 0) (stats.secondPlaces.toDouble() / stats.totalMatches) * 100 else 0.0)}%)", NamedTextColor.WHITE)),
                Component.text("• 🥉 三位: ", NamedTextColor.GOLD).append(Component.text("${stats.thirdPlaces} 次 (${String.format("%.1f", if (stats.totalMatches > 0) (stats.thirdPlaces.toDouble() / stats.totalMatches) * 100 else 0.0)}%)", NamedTextColor.WHITE)),
                Component.text("• 🎖️ 四位: ", NamedTextColor.RED).append(Component.text("${stats.fourthPlaces} 次 (${String.format("%.1f", stats.fourthPlaceRate)}%)", NamedTextColor.WHITE)),
                Component.text("• ⭐ 平均順位: ", NamedTextColor.AQUA).append(Component.text("${String.format("%.2f", stats.averagePlacement)} 位", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD)),
            )
        )
        inv.setItem(21, placement)

        // 5. 和牌與放銃 (Slot 23)
        val winDealIn = createItem(
            Material.TARGET,
            Component.text("🎯【和牌與放銃數據】", NamedTextColor.RED).decorate(TextDecoration.BOLD),
            listOf(
                Component.text("• 總摸打局數: ", NamedTextColor.GRAY).append(Component.text("${stats.totalHands} 局", NamedTextColor.WHITE)),
                Component.text("• 和牌率: ", NamedTextColor.GRAY).append(Component.text("${String.format("%.1f", stats.winHandRate)}% (${stats.totalWins} 次)", NamedTextColor.GREEN)),
                Component.text("• 自摸率: ", NamedTextColor.GRAY).append(Component.text("${String.format("%.1f", stats.tsumoRate)}% (${stats.tsumoCount} 次)", NamedTextColor.YELLOW)),
                Component.text("• 榮和(抓胡): ", NamedTextColor.GRAY).append(Component.text("${stats.ronCount} 次", NamedTextColor.WHITE)),
                Component.text("• 放銃率 (銃率): ", NamedTextColor.GRAY).append(Component.text("${String.format("%.1f", stats.dealInRate)}% (${stats.dealInCount} 次)", NamedTextColor.RED)),
                Component.text("• 平均每局胡牌台數: ", NamedTextColor.GRAY).append(Component.text("${String.format("%.1f", stats.averageTai)} 台", NamedTextColor.AQUA)),
                Component.text("• 生涯最高牌型: ", NamedTextColor.GRAY).append(Component.text("${stats.maxTaiInHand} 台 [${stats.maxTaiHandName}]", NamedTextColor.GOLD)),
            )
        )
        inv.setItem(23, winDealIn)

        // 6. 段位與進度 (Slot 25)
        val rankItem = createItem(
            Material.NETHER_STAR,
            Component.text("⭐【雀力段位】", NamedTextColor.LIGHT_PURPLE).decorate(TextDecoration.BOLD),
            listOf(
                Component.text("目前稱號: ", NamedTextColor.GRAY).append(Component.text(stats.rankTitle, NamedTextColor.YELLOW).decorate(TextDecoration.BOLD)),
                Component.text("累計積分: ", NamedTextColor.GRAY).append(Component.text("${stats.totalNetScore} 積分", NamedTextColor.WHITE)),
                Component.text("──────────────", NamedTextColor.DARK_GRAY),
                Component.text("• 400,000分+: 🌟 七段・雀神", NamedTextColor.GOLD),
                Component.text("• 200,000分+: 👑 六段・雀聖", NamedTextColor.LIGHT_PURPLE),
                Component.text("• 100,000分+: 👑 五段・雀豪", NamedTextColor.AQUA),
                Component.text("• 60,000分+:  🏆 四段・雀傑", NamedTextColor.GREEN),
                Component.text("• 30,000分+:  🏆 三段・雀傑", NamedTextColor.GREEN),
                Component.text("• 10,000分+:  🀄 二段・雀士", NamedTextColor.YELLOW),
                Component.text("• 0分+:       🀄 初段・雀士", NamedTextColor.WHITE),
            )
        )
        inv.setItem(25, rankItem)

        // 7. 近期戰績紀錄 (Slots 37 ~ 43)
        val historyTitle = createItem(
            Material.BOOKSHELF,
            Component.text("📜【近期戰績紀錄】", NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
            listOf(Component.text("展示最近完賽的對局紀錄", NamedTextColor.GRAY))
        )
        inv.setItem(36, historyTitle)

        if (stats.recentHistory.isEmpty()) {
            val emptyLog = createItem(Material.PAPER, Component.text("暫無歷史戰績", NamedTextColor.DARK_GRAY), listOf(Component.text("多進行幾場麻將對局來累計戰績吧！", NamedTextColor.GRAY)))
            inv.setItem(40, emptyLog)
        } else {
            stats.recentHistory.take(6).forEachIndexed { index, log ->
                val slot = 37 + index
                val rankColor = when (log.rank) {
                    1 -> NamedTextColor.YELLOW
                    2 -> NamedTextColor.GRAY
                    3 -> NamedTextColor.GOLD
                    else -> NamedTextColor.RED
                }
                val deltaColor = if (log.scoreDelta >= 0) NamedTextColor.GREEN else NamedTextColor.RED
                val deltaSign = if (log.scoreDelta >= 0) "+${log.scoreDelta}" else "${log.scoreDelta}"
                val logItem = createItem(
                    Material.WRITTEN_BOOK,
                    Component.text("第 ${index + 1} 場: 第 ${log.rank} 名", rankColor).decorate(TextDecoration.BOLD),
                    listOf(
                        Component.text("• 時間: ", NamedTextColor.GRAY).append(Component.text(log.formattedDate(), NamedTextColor.WHITE)),
                        Component.text("• 最終名次: ", NamedTextColor.GRAY).append(Component.text("第 ${log.rank} 名", rankColor)),
                        Component.text("• 積分得失: ", NamedTextColor.GRAY).append(Component.text("$deltaSign 積分", deltaColor)),
                    )
                )
                inv.setItem(slot, logItem)
            }
        }

        // 8. 底部導航按鈕
        val refreshBtn = createItem(Material.COMPASS, Component.text("🔄 重新整理", NamedTextColor.AQUA).decorate(TextDecoration.BOLD), listOf(Component.text("點擊更新最新戰績數據", NamedTextColor.GRAY)))
        inv.setItem(45, refreshBtn)

        val leaderboardBtn = createItem(Material.GOLD_BLOCK, Component.text("🏆 查看全服雀神榜", NamedTextColor.GOLD).decorate(TextDecoration.BOLD), listOf(Component.text("點擊查看伺服器積分排行榜 Top 10！", NamedTextColor.YELLOW)))
        inv.setItem(49, leaderboardBtn)

        val closeBtn = createItem(Material.BARRIER, Component.text("✕ 關閉選單", NamedTextColor.RED).decorate(TextDecoration.BOLD), listOf(Component.text("點擊關閉戰績介面", NamedTextColor.GRAY)))
        inv.setItem(53, closeBtn)

        player.openInventory(inv)
    }

    fun openLeaderboard(player: Player, topPlayers: List<MahjongPlayerStats>) {
        val inv = Bukkit.createInventory(null, 54, Component.text(LEADERBOARD_GUI_TITLE, NamedTextColor.GOLD).decorate(TextDecoration.BOLD).noItalic())

        val grayBorder = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" ", NamedTextColor.GRAY))
        val goldBorder = createItem(Material.YELLOW_STAINED_GLASS_PANE, Component.text(" ", NamedTextColor.GOLD))

        for (i in 0 until 54) {
            inv.setItem(i, grayBorder)
        }
        val goldSlots = listOf(0, 8, 9, 17, 36, 44, 45, 53)
        goldSlots.forEach { inv.setItem(it, goldBorder) }

        val banner = createItem(
            Material.BEACON,
            Component.text("🏆【全服麻將雀神榮譽榜】", NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
            listOf(Component.text("伺服器累積淨積分最高的前 10 位雀友！", NamedTextColor.YELLOW))
        )
        inv.setItem(4, banner)

        // Slots for Top 10:
        // Top 3 in row 2: 20 (Rank 2), 22 (Rank 1), 24 (Rank 3)
        // Top 4~10 in row 4: 28, 29, 30, 31, 32, 33, 34
        val displaySlots = listOf(22, 20, 24, 28, 29, 30, 31, 32, 33, 34)

        topPlayers.take(10).forEachIndexed { index, stats ->
            val rank = index + 1
            val slot = displaySlots.getOrElse(index) { 28 + index }
            val rankTitle = when (rank) {
                1 -> "👑 冠軍・雀神第 1 名"
                2 -> "🥈 亞軍・雀聖第 2 名"
                3 -> "🥉 季軍・雀聖第 3 名"
                else -> "第 $rank 名"
            }
            val titleColor = when (rank) {
                1 -> NamedTextColor.GOLD
                2 -> NamedTextColor.WHITE
                3 -> NamedTextColor.YELLOW
                else -> NamedTextColor.AQUA
            }

            val head = ItemStack(Material.PLAYER_HEAD)
            val headMeta = head.itemMeta as? SkullMeta
            if (headMeta != null) {
                headMeta.owningPlayer = Bukkit.getOfflinePlayer(runCatching { UUID.fromString(stats.uuid) }.getOrNull() ?: player.uniqueId)
                headMeta.displayName(Component.text(rankTitle, titleColor).decorate(TextDecoration.BOLD).noItalic())
                headMeta.lore(listOf(
                    Component.text("玩家名稱: ", NamedTextColor.GRAY).append(Component.text(stats.playerName, NamedTextColor.WHITE).decorate(TextDecoration.BOLD)),
                    Component.text("段位稱號: ", NamedTextColor.GRAY).append(Component.text(stats.rankTitle, NamedTextColor.YELLOW)),
                    Component.text("總累計積分: ", NamedTextColor.GRAY).append(Component.text("+${stats.totalNetScore} 積分", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)),
                    Component.text("完賽場數: ", NamedTextColor.GRAY).append(Component.text("${stats.totalMatches} 場", NamedTextColor.WHITE)),
                    Component.text("勝率 (一位率): ", NamedTextColor.GRAY).append(Component.text("${String.format("%.1f", stats.winRate)}%", NamedTextColor.YELLOW)),
                ).map { it.noItalic() })
                head.itemMeta = headMeta
            }
            inv.setItem(slot, head)
        }

        val backBtn = createItem(Material.ARROW, Component.text("◀ 返回個人戰績", NamedTextColor.GREEN).decorate(TextDecoration.BOLD), listOf(Component.text("點擊返回個人戰績統計面版", NamedTextColor.GRAY)))
        inv.setItem(49, backBtn)

        val closeBtn = createItem(Material.BARRIER, Component.text("✕ 關閉選單", NamedTextColor.RED).decorate(TextDecoration.BOLD), listOf(Component.text("點擊關閉排行榜介面", NamedTextColor.GRAY)))
        inv.setItem(53, closeBtn)

        player.openInventory(inv)
    }

    private fun createItem(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(name.noItalic())
        if (lore.isNotEmpty()) meta.lore(lore.map { it.noItalic() })
        item.itemMeta = meta
        return item
    }
}
