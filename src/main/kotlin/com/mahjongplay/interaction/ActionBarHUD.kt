package com.mahjongplay.interaction

import com.mahjongplay.game.MahjongGame
import com.mahjongplay.game.MahjongPlayerBase
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import java.util.UUID

/** 台麻 HUD：顯示圈局、牌山、分數、花牌與聽牌。 */
object ActionBarHUD {
    fun sendUpdate(game: MahjongGame) {
        game.realPlayers.forEach { mjPlayer ->
            val player = Bukkit.getPlayer(UUID.fromString(mjPlayer.uuid)) ?: return@forEach
            val seatWind = seatWindOf(game, mjPlayer)
            val flowers = mjPlayer.flowerTiles.joinToString(",") { it.displayName }.ifEmpty { "無" }
            var bar = Component.text(game.round.displayName(), NamedTextColor.GOLD)
                .append(Component.text("|${seatWind.displayName}", NamedTextColor.AQUA))
                .append(Component.text("|牌山${game.wallSize}", NamedTextColor.GREEN))
                .append(Component.text("|${mjPlayer.points}分", NamedTextColor.WHITE))
                .append(Component.text("|花:$flowers", NamedTextColor.LIGHT_PURPLE))

            val previewMachi = mjPlayer.previewMachiTiles.distinct()
            if (previewMachi.isNotEmpty()) {
                bar = bar.append(Component.text("|聽:${previewMachi.joinToString(",") { it.displayName }}", NamedTextColor.YELLOW))
            }
            player.sendActionBar(bar)
        }
    }

    private fun seatWindOf(game: MahjongGame, player: MahjongPlayerBase): com.mahjongplay.model.Wind {
        val seatOrder = List(4) { game.seat[(game.round.round + it) % 4] }
        return com.mahjongplay.model.Wind.entries[seatOrder.indexOf(player).coerceIn(0, 3)]
    }
}
