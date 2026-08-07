package com.mahjongplay.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaiwaneseMahjongRulesTest {
    @Test
    fun `taiwanese wall has 144 tiles and eight single flowers`() {
        val wall = MahjongTile.taiwaneseWall

        assertEquals(144, wall.size)
        MahjongTile.basicTiles.forEach { assertEquals(4, wall.count { tile -> tile == it }) }
        MahjongTile.flowerTiles.forEach { assertEquals(1, wall.count { tile -> tile == it }) }
    }

    @Test
    fun `sixteen tile hand wins with five groups and a pair`() {
        val winningHand = listOf(
            MahjongTile.M1, MahjongTile.M2, MahjongTile.M3,
            MahjongTile.M4, MahjongTile.M5, MahjongTile.M6,
            MahjongTile.M7, MahjongTile.M8, MahjongTile.M9,
            MahjongTile.P1, MahjongTile.P2, MahjongTile.P3,
            MahjongTile.S1, MahjongTile.S2, MahjongTile.S3,
            MahjongTile.EAST, MahjongTile.EAST
        )

        val shapes = TaiwaneseHandEvaluator.findWinningShapes(winningHand, emptyList())

        assertTrue(shapes.isNotEmpty())
        assertTrue(shapes.all { it.concealedGroups.size == 5 && it.pair.size == 2 })
    }

    @Test
    fun `open meld reduces concealed hand to four groups and a pair`() {
        val openPon = Fuuro(
            type = MeldType.TRIPLET,
            tiles = listOf(MahjongTile.RED_DRAGON, MahjongTile.RED_DRAGON, MahjongTile.RED_DRAGON),
            claimTarget = ClaimTarget.LEFT,
            claimTile = MahjongTile.RED_DRAGON
        )
        val concealed = listOf(
            MahjongTile.M1, MahjongTile.M2, MahjongTile.M3,
            MahjongTile.M4, MahjongTile.M5, MahjongTile.M6,
            MahjongTile.P1, MahjongTile.P2, MahjongTile.P3,
            MahjongTile.S1, MahjongTile.S2, MahjongTile.S3,
            MahjongTile.EAST, MahjongTile.EAST
        )

        assertTrue(TaiwaneseHandEvaluator.canWin(concealed, listOf(openPon)))
    }

    @Test
    fun `clean one suit is scored in tai instead of han`() {
        val winningHand = listOf(
            MahjongTile.M1, MahjongTile.M1, MahjongTile.M1,
            MahjongTile.M2, MahjongTile.M2, MahjongTile.M2,
            MahjongTile.M3, MahjongTile.M4, MahjongTile.M5,
            MahjongTile.M6, MahjongTile.M7, MahjongTile.M8,
            MahjongTile.M9, MahjongTile.M9, MahjongTile.M9,
            MahjongTile.M5, MahjongTile.M5
        )
        val shape = TaiwaneseHandEvaluator.findWinningShapes(winningHand, emptyList()).first()
        val settlement = TaiwaneseScorer.score(
            displayName = "test",
            uuid = "test",
            isRealPlayer = false,
            botCode = MahjongTile.UNKNOWN.code,
            concealedTiles = winningHand,
            fuuroList = emptyList(),
            flowers = emptyList(),
            shape = shape,
            winningTile = MahjongTile.M5,
            isTsumo = false,
            seatWind = Wind.EAST,
            roundWind = Wind.SOUTH,
            pointsPerTai = 1000
        )

        assertTrue(settlement.taiList.any { it.name == "清一色" && it.tai == 8 })
        assertEquals(9, settlement.tai)
        assertEquals(9000, settlement.score)
    }
}
