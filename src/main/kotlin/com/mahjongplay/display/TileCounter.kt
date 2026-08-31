package com.mahjongplay.display

import com.mahjongplay.game.MahjongGame
import com.mahjongplay.game.MahjongPlayerBase
import com.mahjongplay.model.MahjongTile

object TileCounter {
    /**
     * 計算本家視角下，某張牌在未知區域（牌牆或其他玩家未公開的手牌中）的剩餘張數。
     *
     * 總張數：花牌各 1 張，一般數牌與字牌各 4 張。
     * 可見牌（已現張數）：
     * 1. 本家自身手牌 (player.hands)
     * 2. 四家打入海底的捨牌 (discardedTiles)
     * 3. 四家已副露的吃、碰、槓面子 (fuuroList)
     * 4. 四家已翻開補花的花牌 (flowerTiles)
     */
    fun countRemainingUnseenTiles(game: MahjongGame, player: MahjongPlayerBase, targetTile: MahjongTile): Int {
        val total = when {
            targetTile.isFlower -> 1
            targetTile == MahjongTile.UNKNOWN -> 0
            else -> 4
        }
        var seen = 0
        seen += player.hands.count { it == targetTile }
        seen += game.seat.sumOf { s -> s.discardedTiles.count { it == targetTile } }
        seen += game.seat.sumOf { s ->
            s.fuuroList.filter { f -> f.isOpen || s.uuid == player.uuid }
                .sumOf { f -> f.tiles.count { it == targetTile } }
        }
        seen += game.seat.sumOf { s -> s.flowerTiles.count { it == targetTile } }
        return (total - seen).coerceAtLeast(0)
    }
}
