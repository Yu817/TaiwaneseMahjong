package com.mahjongplay.model

/** 台麻的圈、局與連莊本場。 */
data class MahjongRound(
    var wind: Wind = Wind.EAST,
    var round: Int = 0,
    var honba: Int = 0
) {
    private var spentRounds = 0

    fun nextRound(playerCount: Int = 4) {
        val nextRound = (round + 1) % playerCount
        honba = 0
        if (nextRound == 0) {
            wind = Wind.entries[(wind.ordinal + 1) % Wind.entries.size]
        }
        round = nextRound
        spentRounds++
    }

    fun isAllLast(rule: MahjongRule): Boolean = (spentRounds + 1) >= rule.roundsToPlay

    fun displayName(): String = "${wind.displayName}${round + 1}局"
}
