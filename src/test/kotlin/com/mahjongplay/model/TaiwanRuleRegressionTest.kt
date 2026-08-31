package com.mahjongplay.model

import com.mahjongplay.game.MahjongBot
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaiwanRuleRegressionTest {
    @Test
    fun `passed win blocks ron and tsumo until cleared`() {
        val player = MahjongBot("water-test")
        player.hands += winningHand().dropLast(1)

        player.markPassedWin()
        assertFalse(
            player.canWin(
                winningTile = MahjongTile.M9,
                isWinningTileInHands = false,
                rule = MahjongRule(minimumTai = MahjongRule.MinimumTai.NONE),
                roundWind = Wind.EAST,
                seatWind = Wind.SOUTH,
                isTsumo = false,
            )
        )

        player.clearPassedWin()
        assertTrue(
            player.canWin(
                winningTile = MahjongTile.M9,
                isWinningTileInHands = false,
                rule = MahjongRule(minimumTai = MahjongRule.MinimumTai.NONE),
                roundWind = Wind.EAST,
                seatWind = Wind.SOUTH,
                isTsumo = false,
            )
        )
    }

    @Test
    fun `flower replacement win scores gang replacement bonus`() {
        val settlement = score(
            isTsumo = true,
            context = TaiwanWinContext(isFlowerReplacement = true),
        )
        assertTrue(settlement.taiList.any { it.name == "槓上開花" && it.tai == 1 })
    }

    @Test
    fun `heavenly hand does not duplicate closed self draw`() {
        val settlement = score(
            isTsumo = true,
            seatWind = Wind.EAST,
            context = TaiwanWinContext(isHeavenlyHand = true),
        )
        assertTrue(settlement.taiList.any { it.name == "天胡" && it.tai == 24 })
        assertFalse(
            settlement.taiList.any {
                it.name == "門清" ||
                    it.name == "自摸" ||
                    it.name == "門清自摸" ||
                    it.name == "槓上開花"
            }
        )
    }

    @Test
    fun `only matching seat flowers count individually`() {
        val hand = winningHand()
        val shape = TaiwaneseHandEvaluator.findWinningShapes(hand, emptyList()).first()
        val settlement = TaiwaneseScorer.score(
            displayName = "test",
            uuid = "test",
            isRealPlayer = false,
            botCode = MahjongTile.UNKNOWN.code,
            concealedTiles = hand,
            fuuroList = emptyList(),
            flowers = listOf(
                MahjongTile.FLOWER_1,
                MahjongTile.FLOWER_2,
                MahjongTile.FLOWER_5,
            ),
            shape = shape,
            winningTile = MahjongTile.M9,
            isTsumo = false,
            seatWind = Wind.EAST,
            roundWind = Wind.SOUTH,
            pointsPerTai = 1000,
        )

        val doorFlowers = settlement.taiList.filter { it.name.startsWith("門花") }
        assertTrue(doorFlowers.size == 2)
        assertFalse(settlement.taiList.any { it.name == "門花(夏)" })
    }

    @Test
    fun `score multiplication cannot wrap into a negative number`() {
        val settlement = scoreWithPoints(Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, settlement.score)
    }

    private fun score(
        isTsumo: Boolean = false,
        seatWind: Wind = Wind.SOUTH,
        context: TaiwanWinContext = TaiwanWinContext(),
    ): TaiwanSettlement {
        val hand = winningHand()
        val shape = TaiwaneseHandEvaluator.findWinningShapes(hand, emptyList()).first()
        return TaiwaneseScorer.score(
            displayName = "test",
            uuid = "test",
            isRealPlayer = false,
            botCode = MahjongTile.UNKNOWN.code,
            concealedTiles = hand,
            fuuroList = emptyList(),
            flowers = emptyList(),
            shape = shape,
            winningTile = MahjongTile.M9,
            isTsumo = isTsumo,
            seatWind = seatWind,
            roundWind = Wind.EAST,
            pointsPerTai = 1000,
            context = context,
        )
    }

    private fun scoreWithPoints(basePoints: Int, pointsPerTai: Int): TaiwanSettlement {
        val hand = winningHand()
        val shape = TaiwaneseHandEvaluator.findWinningShapes(hand, emptyList()).first()
        return TaiwaneseScorer.score(
            displayName = "test",
            uuid = "test",
            isRealPlayer = false,
            botCode = MahjongTile.UNKNOWN.code,
            concealedTiles = hand,
            fuuroList = emptyList(),
            flowers = emptyList(),
            shape = shape,
            winningTile = MahjongTile.M9,
            isTsumo = true,
            seatWind = Wind.EAST,
            roundWind = Wind.EAST,
            pointsPerTai = pointsPerTai,
            basePoints = basePoints,
        )
    }

    private fun winningHand(): List<MahjongTile> = listOf(
        MahjongTile.M1, MahjongTile.M2, MahjongTile.M3,
        MahjongTile.M1, MahjongTile.M2, MahjongTile.M3,
        MahjongTile.M4, MahjongTile.M5, MahjongTile.M6,
        MahjongTile.M4, MahjongTile.M5, MahjongTile.M6,
        MahjongTile.M7, MahjongTile.M8, MahjongTile.M9,
        MahjongTile.M9, MahjongTile.M9,
    )
}
