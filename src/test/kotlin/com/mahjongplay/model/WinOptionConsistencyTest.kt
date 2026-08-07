package com.mahjongplay.model

import com.mahjongplay.game.MahjongBot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WinOptionConsistencyTest {
    @Test
    fun `exact seven pin wait remains eligible for tsumo after drawing seven pin`() {
        val player = MahjongBot("wait-test")
        player.fuuroList += sequenceFuuro(MahjongTile.M1, MahjongTile.M2, MahjongTile.M3)
        player.fuuroList += sequenceFuuro(MahjongTile.S1, MahjongTile.S2, MahjongTile.S3)
        player.hands += listOf(
            MahjongTile.P1, MahjongTile.P1, MahjongTile.P1,
            MahjongTile.M5, MahjongTile.M5, MahjongTile.M5,
            MahjongTile.S9, MahjongTile.S9, MahjongTile.S9,
            MahjongTile.P7,
        )

        assertEquals(listOf(MahjongTile.P7), player.machiTiles)

        player.drawTile(MahjongTile.P7)

        assertTrue(TaiwaneseHandEvaluator.canWin(player.hands, player.fuuroList))
        assertTrue(
            player.canWin(
                winningTile = MahjongTile.P7,
                isWinningTileInHands = true,
                rule = MahjongRule(minimumTai = MahjongRule.MinimumTai.ONE),
                roundWind = Wind.EAST,
                seatWind = Wind.WEST,
                isTsumo = true,
            )
        )
    }

    @Test
    fun `drawn seventeen tile hand is not mislabeled as current tenpai`() {
        val player = MahjongBot("preview-test")
        player.hands += listOf(
            MahjongTile.M1, MahjongTile.M2, MahjongTile.M3,
            MahjongTile.M4, MahjongTile.M5, MahjongTile.M6,
            MahjongTile.M7, MahjongTile.M8, MahjongTile.M9,
            MahjongTile.P1, MahjongTile.P2, MahjongTile.P3,
            MahjongTile.S1, MahjongTile.S2, MahjongTile.S3,
            MahjongTile.P7,
            MahjongTile.EAST,
        )

        // A 17-tile turn may have discard choices that lead to tenpai, but it is
        // not itself the 16-tile waiting state shown by the HUD as "聽牌".
        assertTrue(player.machiTiles.isEmpty())
        assertTrue(player.previewMachiTiles.isNotEmpty())
    }

    private fun sequenceFuuro(a: MahjongTile, b: MahjongTile, c: MahjongTile): Fuuro = Fuuro(
        type = MeldType.SEQUENCE,
        tiles = listOf(a, b, c),
        claimTarget = ClaimTarget.LEFT,
        claimTile = c,
    )
}
