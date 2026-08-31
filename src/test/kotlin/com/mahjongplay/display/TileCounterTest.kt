package com.mahjongplay.display

import com.mahjongplay.game.MahjongGame
import com.mahjongplay.game.MahjongPlayerBase
import com.mahjongplay.model.ClaimTarget
import com.mahjongplay.model.Fuuro
import com.mahjongplay.model.MahjongRule
import com.mahjongplay.model.MahjongTile
import com.mahjongplay.model.MeldType
import kotlin.test.Test
import kotlin.test.assertEquals

class TileCounterTest {

    private class TestPlayer(
        override val uuid: String,
        override val displayName: String = uuid
    ) : MahjongPlayerBase() {
        override val isRealPlayer = true
    }

    @Test
    fun testRemainingCountAccountsForHandDiscardsAndMelds() {
        val p1 = TestPlayer("p1")
        val p2 = TestPlayer("p2")
        val p3 = TestPlayer("p3")
        val p4 = TestPlayer("p4")

        val game = MahjongGame(rule = MahjongRule(playerCount = 4))
        game.seat = mutableListOf(p1, p2, p3, p4)

        // p1 holds 1 M5
        p1.hands.add(MahjongTile.M5)
        assertEquals(3, TileCounter.countRemainingUnseenTiles(game, p1, MahjongTile.M5))

        // p2 discards 1 M5
        p2.discardedTiles.add(MahjongTile.M5)
        assertEquals(2, TileCounter.countRemainingUnseenTiles(game, p1, MahjongTile.M5))

        // p3 has a Chii containing M5
        p3.fuuroList.add(
            Fuuro(
                type = MeldType.SEQUENCE,
                tiles = listOf(MahjongTile.M4, MahjongTile.M5, MahjongTile.M6),
                claimTarget = ClaimTarget.LEFT,
                claimTile = MahjongTile.M5
            )
        )
        assertEquals(1, TileCounter.countRemainingUnseenTiles(game, p1, MahjongTile.M5))

        // p4 also discards 1 M5
        p4.discardedTiles.add(MahjongTile.M5)
        assertEquals(0, TileCounter.countRemainingUnseenTiles(game, p1, MahjongTile.M5))
    }

    @Test
    fun testFlowerTileHasOneTotalCopy() {
        val p1 = TestPlayer("p1")
        val game = MahjongGame(rule = MahjongRule(playerCount = 4))
        game.seat = mutableListOf(p1)

        // Initially unseen flower
        assertEquals(1, TileCounter.countRemainingUnseenTiles(game, p1, MahjongTile.FLOWER_1))

        // p1 draws or exposes flower
        p1.flowerTiles.add(MahjongTile.FLOWER_1)
        assertEquals(0, TileCounter.countRemainingUnseenTiles(game, p1, MahjongTile.FLOWER_1))
    }
}
