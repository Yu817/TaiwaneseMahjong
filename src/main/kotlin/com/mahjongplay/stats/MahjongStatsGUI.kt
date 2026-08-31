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
    const val HISTORY_GUI_TITLE = "📜 麻將近期歷史戰績"

    private fun Component.noItalic(): Component =
        this.decoration(TextDecoration.ITALIC, false)

    private fun renderProgressBar(percent: Double, totalBars: Int = 10): String {
        val filled = ((percent / 100.0) * totalBars).toInt().coerceIn(0, totalBars)
        val empty = totalBars - filled
        return "■".repeat(filled) + "□".repeat(empty)
    }

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
                Component.text("段位稱號: ", NamedTextColor.GRAY).append(Component.text(stats.rankTitle, NamedTextColor.YELLOW).decorate(TextDecoration.BOLD)),
                Component.text("天梯雀力: ", NamedTextColor.GRAY).append(Component.text("${stats.ratingPoints} RP", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
                    .append(Component.text(" (最高 ${stats.highestRatingPoints})", NamedTextColor.DARK_GRAY)),
                Component.text("總對局數: ", NamedTextColor.GRAY).append(Component.text("${stats.totalMatches} 場", NamedTextColor.WHITE)),
                Component.text("累積積分盈虧: ", NamedTextColor.GRAY).append(
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
        val progressBar = renderProgressBar(stats.rankProgressPercent, 10)
        val nextRankText = if (stats.rankTier.nextTierMinRP > stats.rankTier.minRP) {
            "距下一段位還需 ${stats.rankTier.nextTierMinRP - stats.ratingPoints} RP"
        } else {
            "已達最高段位頂點"
        }

        val rankItem = createItem(
            Material.NETHER_STAR,
            Component.text("⭐【雀力段位與評級】", NamedTextColor.LIGHT_PURPLE).decorate(TextDecoration.BOLD),
            listOf(
                Component.text("目前段位: ", NamedTextColor.GRAY).append(Component.text(stats.rankTitle, NamedTextColor.YELLOW).decorate(TextDecoration.BOLD)),
                Component.text("天梯雀力: ", NamedTextColor.GRAY).append(Component.text("${stats.ratingPoints} RP", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
                    .append(Component.text(" (生涯最高: ${stats.highestRatingPoints} RP)", NamedTextColor.DARK_GRAY)),
                Component.text("升段進度: ", NamedTextColor.GRAY).append(Component.text("[$progressBar] ${String.format("%.1f", stats.rankProgressPercent)}%", NamedTextColor.GREEN)),
                Component.text("  ↳ ", NamedTextColor.DARK_GRAY).append(Component.text(nextRankText, NamedTextColor.GRAY)),
                Component.text("──────────────────", NamedTextColor.DARK_GRAY),
                Component.text("• 3500+ RP: 🌟 九段・雀皇", NamedTextColor.GOLD),
                Component.text("• 3000+ RP: 🌟 八段・雀神", NamedTextColor.GOLD),
                Component.text("• 2600+ RP: 👑 七段・雀聖 ★★★", NamedTextColor.LIGHT_PURPLE),
                Component.text("• 2200+ RP: 👑 六段・雀聖 ★★", NamedTextColor.LIGHT_PURPLE),
                Component.text("• 1800+ RP: 👑 五段・雀聖 ★", NamedTextColor.LIGHT_PURPLE),
                Component.text("• 1500+ RP: 🏆 四段・雀豪 ★★★", NamedTextColor.AQUA),
                Component.text("• 1200+ RP: 🏆 三段・雀豪 ★★", NamedTextColor.AQUA),
                Component.text("• 900+ RP:  🏆 二段・雀豪 ★", NamedTextColor.AQUA),
                Component.text("• 600+ RP:  🀄 初段・雀傑", NamedTextColor.GREEN),
                Component.text("• 400+ RP:  🀄 雀士 ★★★", NamedTextColor.YELLOW),
                Component.text("• 250+ RP:  🀄 雀士 ★★", NamedTextColor.YELLOW),
                Component.text("• 100+ RP:  🀄 雀士 ★", NamedTextColor.YELLOW),
                Component.text("• 0+ RP:    🌱 雀生 (入門)", NamedTextColor.WHITE),
                Component.text("──────────────────", NamedTextColor.DARK_GRAY),
                Component.text("💡 雀力依據 4人真人對局順位馬點結算，", NamedTextColor.DARK_AQUA),
                Component.text("   不受底台設定影響，徹底杜絕刷分洗分！", NamedTextColor.DARK_AQUA),
            )
        )
        inv.setItem(25, rankItem)

        // 7. 獨立歷史戰績按鈕 (Slot 30)
        val historyBtn = createItem(
            Material.WRITTEN_BOOK,
            Component.text("📜【歷史對局戰績】", NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
            listOf(
                Component.text("點擊開啟獨立的【歷史戰績明細選單】", NamedTextColor.YELLOW),
                Component.text("• 已記錄場次: ", NamedTextColor.GRAY).append(Component.text("${stats.recentHistory.size} 場", NamedTextColor.WHITE)),
                Component.text("• 可查閱近期每一場的名次、雀力增減與得失分", NamedTextColor.GRAY),
                Component.text("──────────────────", NamedTextColor.DARK_GRAY),
                Component.text("👉 點擊進入查閱歷史戰績明細", NamedTextColor.GREEN).decorate(TextDecoration.BOLD),
            )
        )
        inv.setItem(30, historyBtn)

        // 8. 全服雀神排行榜按鈕 (Slot 32)
        val leaderboardBtn = createItem(
            Material.GOLD_BLOCK,
            Component.text("🏆【全服雀神排行榜】", NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
            listOf(
                Component.text("點擊查看全伺服器【天梯雀力 Top 10】榮譽榜！", NamedTextColor.YELLOW),
                Component.text("──────────────────", NamedTextColor.DARK_GRAY),
                Component.text("👉 點擊進入查看全服排行", NamedTextColor.GREEN).decorate(TextDecoration.BOLD),
            )
        )
        inv.setItem(32, leaderboardBtn)

        // 9. 底部導航按鈕
        val refreshBtn = createItem(Material.COMPASS, Component.text("🔄 重新整理", NamedTextColor.AQUA).decorate(TextDecoration.BOLD), listOf(Component.text("點擊更新最新戰績數據", NamedTextColor.GRAY)))
        inv.setItem(45, refreshBtn)

        val closeBtn = createItem(Material.BARRIER, Component.text("✕ 關閉選單", NamedTextColor.RED).decorate(TextDecoration.BOLD), listOf(Component.text("點擊關閉戰績介面", NamedTextColor.GRAY)))
        inv.setItem(53, closeBtn)

        player.openInventory(inv)
    }

    fun openHistory(player: Player, stats: MahjongPlayerStats) {
        val inv = Bukkit.createInventory(null, 54, Component.text(HISTORY_GUI_TITLE, NamedTextColor.GOLD).decorate(TextDecoration.BOLD).noItalic())

        val grayBorder = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" ", NamedTextColor.GRAY))
        val goldBorder = createItem(Material.ORANGE_STAINED_GLASS_PANE, Component.text(" ", NamedTextColor.GOLD))

        for (i in 0 until 54) {
            inv.setItem(i, grayBorder)
        }
        val borderSlots = listOf(0, 8, 9, 17, 36, 44, 45, 53)
        borderSlots.forEach { inv.setItem(it, goldBorder) }

        // Top Summary (Slot 4)
        val summaryItem = createItem(
            Material.BOOKSHELF,
            Component.text("📜【玩家：${stats.playerName} 的歷史對局紀錄】", NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
            listOf(
                Component.text("• 當前段位: ", NamedTextColor.GRAY).append(Component.text(stats.rankTitle, NamedTextColor.YELLOW).decorate(TextDecoration.BOLD)),
                Component.text("• 天梯雀力: ", NamedTextColor.GRAY).append(Component.text("${stats.ratingPoints} RP", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)),
                Component.text("• 累計總場次: ", NamedTextColor.GRAY).append(Component.text("${stats.totalMatches} 場", NamedTextColor.WHITE)),
                Component.text("• 已收錄近期: ", NamedTextColor.GRAY).append(Component.text("${stats.recentHistory.size} 場紀錄 (最多 21 場)", NamedTextColor.AQUA)),
            )
        )
        inv.setItem(4, summaryItem)

        // 21 slots for logs: rows 1..3: 10..16, 19..25, 28..34
        val displaySlots = listOf(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
        )

        if (stats.recentHistory.isEmpty()) {
            val emptyLog = createItem(
                Material.PAPER,
                Component.text("暫無歷史對局紀錄", NamedTextColor.DARK_GRAY).decorate(TextDecoration.BOLD),
                listOf(Component.text("多進行幾場麻將對局來累計歷史戰績吧！", NamedTextColor.GRAY))
            )
            inv.setItem(22, emptyLog)
        } else {
            stats.recentHistory.take(displaySlots.size).forEachIndexed { index, log ->
                val slot = displaySlots[index]
                val (mat, rankName, rankColor) = when (log.rank) {
                    1 -> Triple(Material.GOLD_INGOT, "🥇 一位 (冠軍)", NamedTextColor.YELLOW)
                    2 -> Triple(Material.IRON_INGOT, "🥈 二位 (亞軍)", NamedTextColor.WHITE)
                    3 -> Triple(Material.COPPER_INGOT, "🥉 三位 (季軍)", NamedTextColor.GOLD)
                    else -> Triple(Material.COAL, "💥 四位 (殿軍)", NamedTextColor.RED)
                }
                val deltaColor = if (log.scoreDelta >= 0) NamedTextColor.GREEN else NamedTextColor.RED
                val deltaSign = if (log.scoreDelta >= 0) "+${log.scoreDelta}" else "${log.scoreDelta}"
                val rpColor = if (log.rpDelta >= 0) NamedTextColor.GREEN else NamedTextColor.RED
                val rpSign = if (log.rpDelta >= 0) "+${log.rpDelta}" else "${log.rpDelta}"

                val logItem = createItem(
                    mat,
                    Component.text("第 ${index + 1} 場：$rankName", rankColor).decorate(TextDecoration.BOLD),
                    listOf(
                        Component.text("• 對局時間: ", NamedTextColor.GRAY).append(Component.text(log.formattedDate(), NamedTextColor.WHITE)),
                        Component.text("• 最終順位: ", NamedTextColor.GRAY).append(Component.text("第 ${log.rank} 名", rankColor).decorate(TextDecoration.BOLD)),
                        Component.text("• 雀力變動: ", NamedTextColor.GRAY).append(Component.text("$rpSign RP", rpColor).decorate(TextDecoration.BOLD)),
                        Component.text("• 積分盈虧: ", NamedTextColor.GRAY).append(Component.text("$deltaSign 積分", deltaColor)),
                    )
                )
                inv.setItem(slot, logItem)
            }
        }

        // Navigation Buttons
        val refreshBtn = createItem(Material.COMPASS, Component.text("🔄 重新整理", NamedTextColor.AQUA).decorate(TextDecoration.BOLD), listOf(Component.text("點擊更新最新戰績數據", NamedTextColor.GRAY)))
        inv.setItem(45, refreshBtn)

        val backBtn = createItem(Material.ARROW, Component.text("◀ 返回個人戰績", NamedTextColor.GREEN).decorate(TextDecoration.BOLD), listOf(Component.text("點擊返回個人戰績統計主面版", NamedTextColor.GRAY)))
        inv.setItem(49, backBtn)

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
            Component.text("🏆【全服麻將雀神榮譽天梯榜】", NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
            listOf(
                Component.text("展示全伺服器【天梯雀力 RP】前 10 名頂尖雀友！", NamedTextColor.YELLOW),
                Component.text("排位評級完全取決於個人技術與對局實力，杜絕洗分。", NamedTextColor.GRAY)
            )
        )
        inv.setItem(4, banner)

        val displaySlots = listOf(22, 20, 24, 28, 29, 30, 31, 32, 33, 34)

        topPlayers.take(10).forEachIndexed { index, stats ->
            val rank = index + 1
            val slot = displaySlots.getOrElse(index) { 28 + index }
            val rankTitle = when (rank) {
                1 -> "👑 冠軍・雀神第 1 名"
                2 -> "🥈 亞軍・雀神第 2 名"
                3 -> "🥉 季軍・雀神第 3 名"
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
                    Component.text("天梯雀力: ", NamedTextColor.GRAY).append(Component.text("${stats.ratingPoints} RP", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)),
                    Component.text("完賽場數: ", NamedTextColor.GRAY).append(Component.text("${stats.totalMatches} 場", NamedTextColor.WHITE)),
                    Component.text("勝率 (一位率): ", NamedTextColor.GRAY).append(Component.text("${String.format("%.1f", stats.winRate)}%", NamedTextColor.GREEN)),
                    Component.text("放銃率: ", NamedTextColor.GRAY).append(Component.text("${String.format("%.1f", stats.dealInRate)}%", NamedTextColor.RED)),
                ).map { it.noItalic() })
                head.itemMeta = headMeta
            }
            inv.setItem(slot, head)
        }

        val backBtn = createItem(Material.ARROW, Component.text("◀ 返回個人戰績", NamedTextColor.GREEN).decorate(TextDecoration.BOLD), listOf(Component.text("點擊返回個人戰績統計主面版", NamedTextColor.GRAY)))
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
