package com.mahjongplay.game

/** The standard Taiwanese opening roll: three dice thrown by the dealer. */
data class OpeningDice(val values: List<Int>) {
    init {
        require(values.size == DICE_COUNT) { "Taiwanese mahjong uses exactly three opening dice." }
        require(values.all { it in 1..6 }) { "Every die must be between one and six." }
    }

    val total: Int = values.sum()

    /**
     * Remainders 1/2/3/0 select dealer/right/opposite/left.  Seat indices in
     * the game advance counter-clockwise, hence subtraction from the dealer.
     */
    fun selectedWall(dealerSeatIndex: Int): Int =
        Math.floorMod(dealerSeatIndex - (total - 1), PLAYER_COUNT)

    companion object {
        const val DICE_COUNT = 3
        private const val PLAYER_COUNT = 4

        fun roll(nextValue: () -> Int): OpeningDice =
            OpeningDice(List(DICE_COUNT) { nextValue() })
    }
}

data class OpeningDiceEvent(
    val dice: OpeningDice,
    val dealerSeatIndex: Int,
    val animationMillis: Long,
)
