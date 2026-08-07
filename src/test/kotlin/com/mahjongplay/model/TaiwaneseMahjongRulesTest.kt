package com.mahjongplay.model

import com.mahjongplay.game.MahjongBot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaiwaneseMahjongRulesTest {
    @Test
    fun `circle labels cover quick and full game lengths`() {
        assertEquals("1/4圈", MahjongRule(roundsToPlay = 1).displayCircleText)
        assertEquals("1圈", MahjongRule(roundsToPlay = 4).displayCircleText)
        assertEquals("4圈", MahjongRule(roundsToPlay = 16).displayCircleText)
    }

    @Test
    fun `taiwanese wall has 144 tiles and eight single flowers`() {
        val wall = MahjongTile.taiwaneseWall

        assertEquals(144, wall.size)
        MahjongTile.basicTiles.forEach { assertEquals(4, wall.count { tile -> tile == it }) }
        MahjongTile.flowerTiles.forEach { assertEquals(1, wall.count { tile -> tile == it }) }
    }

    @Test
    fun `normal wall excludes flowers`() {
        assertEquals(136, MahjongTile.normalWall.size)
        assertTrue(MahjongTile.normalWall.none { it.isFlower })
        MahjongTile.basicTiles.forEach { assertEquals(4, MahjongTile.normalWall.count { tile -> tile == it }) }
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
        val winningHand = cleanOneSuitHand()
        val settlement = score(
            winningHand = winningHand,
            winningTile = MahjongTile.M5,
            seatWind = Wind.WEST,
        )

        assertTrue(settlement.taiList.any { it.name == "清一色" && it.tai == 8 })
        assertEquals(9, settlement.tai)
        assertEquals(9000, settlement.score)
    }

    @Test
    fun `base points are added before tai points`() {
        val settlement = score(
            winningHand = cleanOneSuitHand(),
            winningTile = MahjongTile.M5,
            seatWind = Wind.WEST,
            pointsPerTai = 10,
            basePoints = 30,
        )

        assertEquals(9, settlement.tai)
        assertEquals(120, settlement.score)
    }

    @Test
    fun `dealer repeat and pull are counted as tai`() {
        val settlement = score(
            winningHand = cleanOneSuitHand(),
            winningTile = MahjongTile.M5,
            seatWind = Wind.EAST,
            context = TaiwanWinContext(dealerRepeat = 2),
        )

        assertTrue(settlement.taiList.any { it.name == "莊家" && it.tai == 1 })
        assertTrue(settlement.taiList.any { it.name == "連莊×2" && it.tai == 2 })
        assertTrue(settlement.taiList.any { it.name == "拉莊×2" && it.tai == 2 })
        assertEquals(14, settlement.tai)
    }

    @Test
    fun `menzen tsumo scores the common three tai combination`() {
        val settlement = score(
            winningHand = cleanOneSuitHand(),
            winningTile = MahjongTile.M5,
            isTsumo = true,
            seatWind = Wind.WEST,
        )

        assertTrue(settlement.taiList.any { it.name == "門清" && it.tai == 1 })
        assertTrue(settlement.taiList.any { it.name == "自摸" && it.tai == 1 })
        assertTrue(settlement.taiList.any { it.name == "不求人" && it.tai == 1 })
    }

    @Test
    fun `five concealed triplets are detected including closed kans`() {
        val winningHand = fiveTripletHand()
        val settlement = score(
            winningHand = winningHand,
            winningTile = MahjongTile.RED_DRAGON,
            isTsumo = true,
            seatWind = Wind.WEST,
        )

        assertTrue(settlement.taiList.any { it.name == "五暗刻" && it.tai == 8 })
        assertFalse(settlement.taiList.any { it.name == "四暗刻" })
        assertFalse(settlement.taiList.any { it.name == "三暗刻" })
    }

    @Test
    fun `triplet completed by ron is not counted as concealed`() {
        val winningHand = fiveTripletHand()
        val settlement = score(
            winningHand = winningHand,
            winningTile = MahjongTile.RED_DRAGON,
            isTsumo = false,
            seatWind = Wind.WEST,
        )

        assertTrue(settlement.taiList.any { it.name == "四暗刻" && it.tai == 5 })
        assertFalse(settlement.taiList.any { it.name == "五暗刻" })
    }

    @Test
    fun `single wait is derived from the actual pre win hand`() {
        val player = MahjongBot("wait-test")
        player.hands += listOf(
            MahjongTile.M1, MahjongTile.M2, MahjongTile.M3,
            MahjongTile.M4, MahjongTile.M5, MahjongTile.M6,
            MahjongTile.M7, MahjongTile.M8, MahjongTile.M9,
            MahjongTile.P1, MahjongTile.P2, MahjongTile.P3,
            MahjongTile.S1, MahjongTile.S2, MahjongTile.S3,
            MahjongTile.EAST,
        )

        val settlement = player.calcTaiwanSettlementForWin(
            winningTile = MahjongTile.EAST,
            isWinningTileInHands = false,
            rule = MahjongRule(minimumTai = MahjongRule.MinimumTai.NONE),
            roundWind = Wind.SOUTH,
            seatWind = Wind.WEST,
            isTsumo = false,
        )

        assertTrue(settlement.taiList.any { it.name == "獨聽" && it.tai == 1 })
    }

    @Test
    fun `last tile kong and first turn bonuses are represented in settlement`() {
        val settlement = score(
            winningHand = cleanOneSuitHand(),
            winningTile = MahjongTile.M5,
            isTsumo = true,
            seatWind = Wind.EAST,
            context = TaiwanWinContext(
                dealerRepeat = 1,
                isSingleWait = true,
                isLastLiveTile = true,
                isKongReplacement = true,
                isHeavenlyHand = true,
            ),
        )

        assertTrue(settlement.taiList.any { it.name == "獨聽" && it.tai == 1 })
        assertTrue(settlement.taiList.any { it.name == "海底撈月" && it.tai == 1 })
        assertTrue(settlement.taiList.any { it.name == "槓上開花" && it.tai == 1 })
        assertTrue(settlement.taiList.any { it.name == "天胡" && it.tai == 16 })
    }

    @Test
    fun `robbing kong and human hand bonuses are available for ron`() {
        val settlement = score(
            winningHand = cleanOneSuitHand(),
            winningTile = MahjongTile.M5,
            isTsumo = false,
            seatWind = Wind.WEST,
            context = TaiwanWinContext(
                isRobbingKong = true,
                isHumanHand = true,
            ),
        )

        assertTrue(settlement.taiList.any { it.name == "搶槓" && it.tai == 1 })
        assertTrue(settlement.taiList.any { it.name == "人胡" && it.tai == 8 })
    }

    private fun cleanOneSuitHand(): List<MahjongTile> = listOf(
        MahjongTile.M1, MahjongTile.M1, MahjongTile.M1,
        MahjongTile.M2, MahjongTile.M2, MahjongTile.M2,
        MahjongTile.M3, MahjongTile.M4, MahjongTile.M5,
        MahjongTile.M6, MahjongTile.M7, MahjongTile.M8,
        MahjongTile.M9, MahjongTile.M9, MahjongTile.M9,
        MahjongTile.M5, MahjongTile.M5,
    )

    private fun fiveTripletHand(): List<MahjongTile> = listOf(
        MahjongTile.EAST, MahjongTile.EAST, MahjongTile.EAST,
        MahjongTile.SOUTH, MahjongTile.SOUTH, MahjongTile.SOUTH,
        MahjongTile.WEST, MahjongTile.WEST, MahjongTile.WEST,
        MahjongTile.WHITE_DRAGON, MahjongTile.WHITE_DRAGON, MahjongTile.WHITE_DRAGON,
        MahjongTile.RED_DRAGON, MahjongTile.RED_DRAGON, MahjongTile.RED_DRAGON,
        MahjongTile.M5, MahjongTile.M5,
    )

    private fun score(
        winningHand: List<MahjongTile>,
        winningTile: MahjongTile,
        isTsumo: Boolean = false,
        seatWind: Wind = Wind.WEST,
        roundWind: Wind = Wind.SOUTH,
        pointsPerTai: Int = 1000,
        basePoints: Int = 0,
        context: TaiwanWinContext = TaiwanWinContext(),
    ): TaiwanSettlement {
        val shape = TaiwaneseHandEvaluator.findWinningShapes(winningHand, emptyList()).first()
        return TaiwaneseScorer.score(
            displayName = "test",
            uuid = "test",
            isRealPlayer = false,
            botCode = MahjongTile.UNKNOWN.code,
            concealedTiles = winningHand,
            fuuroList = emptyList(),
            flowers = emptyList(),
            shape = shape,
            winningTile = winningTile,
            isTsumo = isTsumo,
            seatWind = seatWind,
            roundWind = roundWind,
            pointsPerTai = pointsPerTai,
            basePoints = basePoints,
            context = context,
        )
    }
}
