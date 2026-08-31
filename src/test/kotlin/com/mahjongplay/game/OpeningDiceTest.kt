package com.mahjongplay.game

import kotlin.test.Test
import kotlin.test.assertEquals

class OpeningDiceTest {
    @Test
    fun `taiwan opening uses exactly three six-sided dice`() {
        val values = ArrayDeque(listOf(6, 1, 4))

        val roll = OpeningDice.roll { values.removeFirst() }

        assertEquals(listOf(6, 1, 4), roll.values)
        assertEquals(11, roll.total)
    }

    @Test
    fun `dice total selects self right opposite or left wall from dealer`() {
        val dealerSeat = 2

        assertEquals(2, OpeningDice(listOf(1, 4, 4)).selectedWall(dealerSeat)) // 9: self
        assertEquals(1, OpeningDice(listOf(2, 4, 4)).selectedWall(dealerSeat)) // 10: right
        assertEquals(0, OpeningDice(listOf(3, 4, 4)).selectedWall(dealerSeat)) // 11: opposite
        assertEquals(3, OpeningDice(listOf(4, 4, 4)).selectedWall(dealerSeat)) // 12: left
    }
}
