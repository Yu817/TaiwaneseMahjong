package com.mahjongplay.game

/** 台灣底台制：和牌底台金額，加上莊家作為付款者應負擔的台數。 */
object TaiwanPayment {
    fun calculate(
        settlementScore: Int,
        dealerLiabilityTai: Int,
        pointsPerTai: Int,
    ): Int {
        val liability = saturatedMultiply(
            dealerLiabilityTai.coerceAtLeast(0).toLong(),
            pointsPerTai.coerceAtLeast(0).toLong(),
        )
        val payment = saturatedAdd(settlementScore.coerceAtLeast(0).toLong(), liability)
        return payment.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun saturatedAdd(first: Long, second: Long): Long =
        if (first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second

    private fun saturatedMultiply(first: Long, second: Long): Long = when {
        first == 0L || second == 0L -> 0L
        first > Long.MAX_VALUE / second -> Long.MAX_VALUE
        else -> first * second
    }
}
