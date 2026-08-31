package com.mahjongplay.stats

import com.mahjongplay.MahjongPlayPlugin
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent

class MahjongStatsListener(private val statsManager: MahjongStatsManager) : Listener {

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val view = event.view
        val titleText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(view.title())

        if (titleText.contains("麻將個人戰績統計") || titleText.contains("全服麻將雀神排行榜") || titleText.contains("麻將近期歷史戰績")) {
            event.isCancelled = true
            val rawSlot = event.rawSlot
            if (rawSlot !in 0 until 54) return

            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, 1.2f)
            val stats = statsManager.getStats(player.uniqueId.toString(), player.name)

            if (titleText.contains("麻將個人戰績統計")) {
                when (rawSlot) {
                    30 -> { // 點擊查看歷史戰績按鈕
                        MahjongStatsGUI.openHistory(player, stats)
                    }
                    32 -> { // 點擊查看全服排行榜按鈕
                        val top = statsManager.getLeaderboard(10)
                        MahjongStatsGUI.openLeaderboard(player, top)
                    }
                    45 -> { // 重新整理
                        MahjongStatsGUI.openStats(player, stats)
                    }
                    53 -> { // 關閉
                        player.closeInventory()
                    }
                }
            } else if (titleText.contains("麻將近期歷史戰績")) {
                when (rawSlot) {
                    45 -> { // 重新整理歷史紀錄
                        MahjongStatsGUI.openHistory(player, stats)
                    }
                    49 -> { // 返回個人戰績主面板
                        MahjongStatsGUI.openStats(player, stats)
                    }
                    53 -> { // 關閉
                        player.closeInventory()
                    }
                }
            } else if (titleText.contains("全服麻將雀神排行榜")) {
                when (rawSlot) {
                    49 -> { // 返回個人戰績主面板
                        MahjongStatsGUI.openStats(player, stats)
                    }
                    53 -> { // 關閉
                        player.closeInventory()
                    }
                }
            }
        }
    }
}
