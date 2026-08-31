package com.mahjongplay.game

import com.mahjongplay.model.MahjongRule
import com.mahjongplay.model.MahjongTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeatWindDrawTest {

    @Test
    fun testStarterIndexCalculation() {
        val playerCount = 4
        // dice total 5 -> 5 - 1 = 4 -> 4 % 4 = 0 (self / temporary east)
        assertEquals(0, (5 - 1) % playerCount)
        // dice total 6 -> 6 - 1 = 5 -> 5 % 4 = 1 (south / shimonoya)
        assertEquals(1, (6 - 1) % playerCount)
        // dice total 7 -> 7 - 1 = 6 -> 6 % 4 = 2 (west / toimen)
        assertEquals(2, (7 - 1) % playerCount)
        // dice total 8 -> 8 - 1 = 7 -> 7 % 4 = 3 (north / kamicha)
        assertEquals(3, (8 - 1) % playerCount)
        // dice total 11 -> 11 - 1 = 10 -> 10 % 4 = 2 (west)
        assertEquals(2, (11 - 1) % playerCount)
    }

    @Test
    fun testPickOrderIsCounterClockwise() {
        val playerCount = 4
        val starterIndex = 2
        val pickOrder = (0 until playerCount).map { (starterIndex + it) % playerCount }
        assertEquals(listOf(2, 3, 0, 1), pickOrder)
    }

    @Test
    fun testSeatingPermutationLogic() {
        val p1 = MahjongBot(displayName = "P1")
        val p2 = MahjongBot(displayName = "P2")
        val p3 = MahjongBot(displayName = "P3")
        val p4 = MahjongBot(displayName = "P4")

        // Suppose P3 got EAST, P1 got SOUTH, P4 got WEST, P2 got NORTH
        val assigned = mapOf(
            MahjongTile.EAST to p3,
            MahjongTile.SOUTH to p1,
            MahjongTile.WEST to p4,
            MahjongTile.NORTH to p2
        )

        val newSeatOrder = listOf(
            assigned[MahjongTile.EAST]!!,
            assigned[MahjongTile.SOUTH]!!,
            assigned[MahjongTile.WEST]!!,
            assigned[MahjongTile.NORTH]!!
        )

        assertEquals("P3", newSeatOrder[0].displayName)
        assertEquals("P1", newSeatOrder[1].displayName)
        assertEquals("P4", newSeatOrder[2].displayName)
        assertEquals("P2", newSeatOrder[3].displayName)
    }

    @Test
    fun testRuleDefaults() {
        val rule = MahjongRule()
        assertTrue(rule.seatWindDrawEnabled)
    }

    @Test
    fun testSeatWindCalculations() {
        val game = MahjongGame().apply {
            join("uuid-1", "Player1")
            join("uuid-2", "Player2")
            join("uuid-3", "Player3")
            join("uuid-4", "Player4")
        }
        game.seat = game.players.toMutableList()

        // Round 0 (East 1):
        // seat[0] is East, seat[1] is South, seat[2] is West, seat[3] is North
        assertEquals(com.mahjongplay.model.Wind.EAST, com.mahjongplay.interaction.ActionBarHUD.seatWindOf(game, game.seat[0]))
        assertEquals(com.mahjongplay.model.Wind.SOUTH, com.mahjongplay.interaction.ActionBarHUD.seatWindOf(game, game.seat[1]))
        assertEquals(com.mahjongplay.model.Wind.WEST, com.mahjongplay.interaction.ActionBarHUD.seatWindOf(game, game.seat[2]))
        assertEquals(com.mahjongplay.model.Wind.NORTH, com.mahjongplay.interaction.ActionBarHUD.seatWindOf(game, game.seat[3]))

        // Round 1 (East 2):
        game.round.round = 1
        // seat[1] is East (dealer), seat[2] is South, seat[3] is West, seat[0] is North
        assertEquals(com.mahjongplay.model.Wind.NORTH, com.mahjongplay.interaction.ActionBarHUD.seatWindOf(game, game.seat[0]))
        assertEquals(com.mahjongplay.model.Wind.EAST, com.mahjongplay.interaction.ActionBarHUD.seatWindOf(game, game.seat[1]))
        assertEquals(com.mahjongplay.model.Wind.SOUTH, com.mahjongplay.interaction.ActionBarHUD.seatWindOf(game, game.seat[2]))
        assertEquals(com.mahjongplay.model.Wind.WEST, com.mahjongplay.interaction.ActionBarHUD.seatWindOf(game, game.seat[3]))
    }
}
