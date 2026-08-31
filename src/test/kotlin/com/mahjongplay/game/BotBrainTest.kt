package com.mahjongplay.game

import com.mahjongplay.model.MahjongRule
import com.mahjongplay.model.MahjongTile
import com.mahjongplay.model.ClaimTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BotBrainTest {

    @Test
    fun testDifficultyParsing() {
        assertEquals(BotDifficulty.LOW, BotDifficulty.fromString("low"))
        assertEquals(BotDifficulty.LOW, BotDifficulty.fromString("初級"))
        assertEquals(BotDifficulty.LOW, BotDifficulty.fromString("低"))
        assertEquals(BotDifficulty.MEDIUM, BotDifficulty.fromString("medium"))
        assertEquals(BotDifficulty.MEDIUM, BotDifficulty.fromString("中級"))
        assertEquals(BotDifficulty.MEDIUM, BotDifficulty.fromString("中"))
        assertEquals(BotDifficulty.HIGH, BotDifficulty.fromString("high"))
        assertEquals(BotDifficulty.HIGH, BotDifficulty.fromString("高級"))
        assertEquals(BotDifficulty.HIGH, BotDifficulty.fromString("高"))
        assertEquals(BotDifficulty.MEDIUM, BotDifficulty.fromString("unknown"))
    }

    @Test
    fun testMediumBotPrioritizesTenpai() {
        val game = MahjongGame(rule = MahjongRule(playerCount = 4))
        val bot = MahjongBot(displayName = "TestBot", difficulty = BotDifficulty.MEDIUM)
        bot.game = game

        // Hand: 5 groups almost complete + 1 pair + 1 junk tile
        // M1, M2, M3 (group 1)
        // M4, M5, M6 (group 2)
        // P1, P2, P3 (group 3)
        // S1, S2, S3 (group 4)
        // S4, S5 (waiting on S3, S6 for group 5)
        // EAST, EAST (pair)
        // WHITE_DRAGON (isolated junk tile)
        // Total 17 tiles
        val hand = listOf(
            MahjongTile.M1, MahjongTile.M2, MahjongTile.M3,
            MahjongTile.M4, MahjongTile.M5, MahjongTile.M6,
            MahjongTile.P1, MahjongTile.P2, MahjongTile.P3,
            MahjongTile.S1, MahjongTile.S2, MahjongTile.S3,
            MahjongTile.S4, MahjongTile.S5,
            MahjongTile.EAST, MahjongTile.EAST,
            MahjongTile.WHITE_DRAGON
        )
        bot.hands.addAll(hand)

        // Bot should discard WHITE_DRAGON to enter Tenpai waiting on S3 and S6!
        val discard = BotBrain.decideDiscard(bot, MahjongTile.WHITE_DRAGON, emptyList())
        assertEquals(MahjongTile.WHITE_DRAGON, discard)
        assertTrue(bot.machiIfDiscard(discard).isNotEmpty())
    }

    @Test
    fun testLowBotDiscardsValidCandidate() {
        val bot = MahjongBot(displayName = "LowBot", difficulty = BotDifficulty.LOW)
        bot.hands.addAll(listOf(MahjongTile.M1, MahjongTile.M2, MahjongTile.M3))
        val discard = BotBrain.decideDiscard(bot, MahjongTile.M1, emptyList())
        assertTrue(discard in bot.hands)
    }

    @Test
    fun `medium chii simulation checks tenpai after discarding from the 14 tile concealed hand`() {
        val bot = MahjongBot(displayName = "MediumBot", difficulty = BotDifficulty.MEDIUM)
        bot.hands.addAll(
            listOf(
                MahjongTile.M1, MahjongTile.M2,
                MahjongTile.M4, MahjongTile.M5, MahjongTile.M6,
                MahjongTile.M7, MahjongTile.M8, MahjongTile.M9,
                MahjongTile.P1, MahjongTile.P2, MahjongTile.P3,
                MahjongTile.S1, MahjongTile.S2, MahjongTile.S3,
                MahjongTile.EAST, MahjongTile.EAST,
            )
        )

        val choice = BotBrain.decideChii(
            bot,
            MahjongTile.M3,
            listOf(MahjongTile.M1 to MahjongTile.M2),
            ClaimTarget.LEFT,
        )

        assertEquals(MahjongTile.M1 to MahjongTile.M2, choice)
    }
}
