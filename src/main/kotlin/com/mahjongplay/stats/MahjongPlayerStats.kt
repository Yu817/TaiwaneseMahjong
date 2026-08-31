package com.mahjongplay.stats

import java.text.SimpleDateFormat
import java.util.Date

data class MatchLogItem(
    val timestamp: Long,
    val rank: Int,
    val scoreDelta: Int,
    val taiWon: Int,
    val summary: String,
) {
    fun formattedDate(): String =
        SimpleDateFormat("yyyy/MM/dd HH:mm").format(Date(timestamp))
}

data class MahjongPlayerStats(
    val uuid: String,
    var playerName: String,
    var totalMatches: Int = 0,
    var firstPlaces: Int = 0,
    var secondPlaces: Int = 0,
    var thirdPlaces: Int = 0,
    var fourthPlaces: Int = 0,
    var totalNetScore: Int = 0,
    var totalHands: Int = 0,
    var tsumoCount: Int = 0,
    var ronCount: Int = 0,
    var dealInCount: Int = 0,
    var totalTaiWon: Int = 0,
    var maxTaiInHand: Int = 0,
    var maxTaiHandName: String = "無",
    var maxMatchScore: Int = 0,
    var maxDealerStreak: Int = 0,
    val recentHistory: MutableList<MatchLogItem> = mutableListOf(),
) {
    val totalWins: Int
        get() = tsumoCount + ronCount

    val winRate: Double
        get() = if (totalMatches > 0) (firstPlaces.toDouble() / totalMatches) * 100.0 else 0.0

    val top2Rate: Double
        get() = if (totalMatches > 0) ((firstPlaces + secondPlaces).toDouble() / totalMatches) * 100.0 else 0.0

    val fourthPlaceRate: Double
        get() = if (totalMatches > 0) (fourthPlaces.toDouble() / totalMatches) * 100.0 else 0.0

    val averagePlacement: Double
        get() = if (totalMatches > 0) {
            (firstPlaces * 1 + secondPlaces * 2 + thirdPlaces * 3 + fourthPlaces * 4).toDouble() / totalMatches
        } else 0.0

    val winHandRate: Double
        get() = if (totalHands > 0) (totalWins.toDouble() / totalHands) * 100.0 else 0.0

    val dealInRate: Double
        get() = if (totalHands > 0) (dealInCount.toDouble() / totalHands) * 100.0 else 0.0

    val tsumoRate: Double
        get() = if (totalWins > 0) (tsumoCount.toDouble() / totalWins) * 100.0 else 0.0

    val averageTai: Double
        get() = if (totalWins > 0) totalTaiWon.toDouble() / totalWins else 0.0

    val rankTitle: String
        get() = when {
            totalNetScore >= 400000 -> "🌟 七段・雀神"
            totalNetScore >= 200000 -> "👑 六段・雀聖"
            totalNetScore >= 100000 -> "👑 五段・雀豪"
            totalNetScore >= 60000 -> "🏆 四段・雀傑"
            totalNetScore >= 30000 -> "🏆 三段・雀傑"
            totalNetScore >= 10000 -> "🀄 二段・雀士"
            totalNetScore >= 0 -> "🀄 初段・雀士"
            else -> "🌱 雀生 (入門)"
        }
}
