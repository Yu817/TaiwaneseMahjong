package com.mahjongplay.interaction

import com.mahjongplay.game.MahjongGame
import com.mahjongplay.game.MahjongPlayerBase
import com.mahjongplay.model.Wind
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import java.util.UUID

/** Compact, readable status bar shown to each real player. */
object ActionBarHUD {
    fun sendUpdate(game: MahjongGame) {
        game.realPlayers.forEach { mjPlayer ->
            val player = Bukkit.getPlayer(UUID.fromString(mjPlayer.uuid)) ?: return@forEach
            val seatWind = seatWindOf(game, mjPlayer)
            val current = game.currentPlayer

            var bar = Component.text("麻將", NamedTextColor.GOLD)
                .decorate(TextDecoration.BOLD)
                .append(Component.text("  •  ${game.round.displayName()}", NamedTextColor.AQUA))
                .append(Component.text("  •  ${seatWind.displayName}家", NamedTextColor.LIGHT_PURPLE))
                .append(Component.text("  •  剩餘牌 ${game.wallSize}", NamedTextColor.GREEN))
                .append(Component.text("  •  ${mjPlayer.points}分", NamedTextColor.WHITE))

            if (game.round.honba > 0) {
                bar = bar.append(Component.text("  •  連${game.round.honba}", NamedTextColor.YELLOW))
            }
            if (game.rule.flowersEnabled) {
                bar = bar.append(Component.text("  •  花 ${mjPlayer.flowerTiles.size}", NamedTextColor.LIGHT_PURPLE))
            }

            val status = when {
                mjPlayer.actionOptions.isNotEmpty() -> {
                    val labels = mjPlayer.actionOptions
                        .take(4)
                        .joinToString("／") { it.label }
                    Component.text("  │  請選擇：$labels", NamedTextColor.YELLOW)
                        .decorate(TextDecoration.BOLD)
                }
                current?.uuid == mjPlayer.uuid ->
                    Component.text("  │  ▶ 輪到你", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                current != null ->
                    Component.text("  │  等待 ${current.displayName}", NamedTextColor.GRAY)
                else -> Component.text("  │  準備發牌", NamedTextColor.GRAY)
            }
            bar = bar.append(status)

            // Only show the waits for the player's CURRENT 16-tile waiting hand.
            // previewMachiTiles also includes waits that would become available
            // after discarding one tile from a 17-tile drawn hand. Calling that
            // generic preview "聽牌" was misleading: it could show 7筒 even
            // though the current 17 tiles had not actually won on 7筒.
            val exactMachi = mjPlayer.machiTiles.distinct().take(6)
            if (exactMachi.isNotEmpty()) {
                bar = bar.append(
                    Component.text("  │  聽牌 ${exactMachi.joinToString("、") { machiDisplayName(it) }}", NamedTextColor.YELLOW)
                )
            }
            player.sendActionBar(bar)
        }
    }

    fun seatWindOf(game: MahjongGame, player: MahjongPlayerBase): Wind {
        val index = game.seat.indexOf(player)
        if (index < 0) return Wind.EAST
        val seatOrderIndex = (4 - ((game.round.round + index) % 4)) % 4
        return Wind.entries[seatOrderIndex]
    }

    /** Use Arabic numerals for numbered tiles so every client font shows them. */
    private fun machiDisplayName(tile: com.mahjongplay.model.MahjongTile): String = when (tile.suit) {
        com.mahjongplay.model.TileSuit.MAN -> "${tile.number}萬"
        com.mahjongplay.model.TileSuit.PIN -> "${tile.number}筒"
        com.mahjongplay.model.TileSuit.SOU -> "${tile.number}索"
        else -> tile.displayName
    }
}
