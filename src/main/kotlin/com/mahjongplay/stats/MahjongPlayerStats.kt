package com.mahjongplay.stats

import java.text.SimpleDateFormat
import java.util.Date

data class MatchLogItem(
    val timestamp: Long,
    val rank: Int,
    val scoreDelta: Int,
    val rpDelta: Int = 0,
    val taiWon: Int = 0,
    val summary: String = "",
) {
    fun formattedDate(): String =
        SimpleDateFormat("yyyy/MM/dd HH:mm").format(Date(timestamp))
}

enum class RankTier(
    val minRP: Int,
    val nextTierMinRP: Int,
    val displayName: String,
    val badge: String,
) {
    NINE_DAN(3500, 3500, "🌟 九段・雀皇", "🌟"),
    EIGHT_DAN(3000, 3500, "🌟 八段・雀神", "🌟"),
    SEVEN_DAN(2600, 3000, "👑 七段・雀聖 ★★★", "👑"),
    SIX_DAN(2200, 2600, "👑 六段・雀聖 ★★", "👑"),
    FIVE_DAN(1800, 2200, "👑 五段・雀聖 ★", "👑"),
    FOUR_DAN(1500, 1800, "🏆 四段・雀豪 ★★★", "🏆"),
    THREE_DAN(1200, 1500, "🏆 三段・雀豪 ★★", "🏆"),
    TWO_DAN(900, 1200, "🏆 二段・雀豪 ★", "🏆"),
    ONE_DAN(600, 900, "🀄 初段・雀傑", "🀄"),
    ADEPT_3(400, 600, "🀄 雀士 ★★★", "🀄"),
    ADEPT_2(250, 400, "🀄 雀士 ★★", "🀄"),
    ADEPT_1(100, 250, "🀄 雀士 ★", "🀄"),
    NOVICE(0, 100, "🌱 雀生 (入門)", "🌱");

    companion object {
        fun fromRP(rp: Int): RankTier = when {
            rp >= 3500 -> NINE_DAN
            rp >= 3000 -> EIGHT_DAN
            rp >= 2600 -> SEVEN_DAN
            rp >= 2200 -> SIX_DAN
            rp >= 1800 -> FIVE_DAN
            rp >= 1500 -> FOUR_DAN
            rp >= 1200 -> THREE_DAN
            rp >= 900 -> TWO_DAN
            rp >= 600 -> ONE_DAN
            rp >= 400 -> ADEPT_3
            rp >= 250 -> ADEPT_2
            rp >= 100 -> ADEPT_1
            else -> NOVICE
        }
    }
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
    var ratingPoints: Int = 1000,
    var highestRatingPoints: Int = 1000,
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

    val rankTier: RankTier
        get() = RankTier.fromRP(ratingPoints)

    val rankTitle: String
        get() = rankTier.displayName

    val rankProgressPercent: Double
        get() {
            val tier = rankTier
            if (tier.nextTierMinRP <= tier.minRP) return 100.0
            val earned = (ratingPoints - tier.minRP).coerceAtLeast(0)
            val span = tier.nextTierMinRP - tier.minRP
            return ((earned.toDouble() / span) * 100.0).coerceIn(0.0, 100.0)
        }
}
