package com.mahjongplay.model

import com.mahjongplay.util.TextFormatting
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

/**
 * 台灣麻將規則。
 *
 * 預設採 4 人、16 張手牌、144 張牌、最低一台起胡、每台 1000 分。
 * 不同地區的台麻細節差異很大，因此點數與最低台數保留為桌規設定，方便依牌桌習慣調整。
 */
data class MahjongRule(
    var length: GameLength = GameLength.TWO_WIND,
    var playerCount: Int = 4,
    var thinkingTime: ThinkingTime = ThinkingTime.NORMAL,
    var startingPoints: Int = 16000,
    var minPointsToWin: Int = 0,
    var minimumTai: MinimumTai = MinimumTai.ONE,
    var spectate: Boolean = true,
    var pointsPerTai: Int = 1000,
    var honbaPoints: Int = 100,
    var dealerTsumoMultiplier: Int = 2
) {
    val isTaiwanese: Boolean
        get() = true

    fun toComponents(): List<Component> {
        val enabled = Component.text("開啟", NamedTextColor.GREEN)
        return listOf(
            Component.text("§6§l台灣麻將規則"),
            Component.text(" - 局數: ", NamedTextColor.YELLOW).append(length.toText().color(NamedTextColor.GREEN)),
            Component.text(" - 手牌: ", NamedTextColor.YELLOW).append(Component.text("16 張", NamedTextColor.AQUA)),
            Component.text(" - 起始分數: ", NamedTextColor.YELLOW).append(Component.text("$startingPoints", NamedTextColor.GREEN)),
            Component.text(" - 最低台數: ", NamedTextColor.YELLOW).append(Component.text("${minimumTai.tai} 台", NamedTextColor.GREEN)),
            Component.text(" - 每台: ", NamedTextColor.YELLOW).append(Component.text("$pointsPerTai 分", NamedTextColor.GREEN)),
            Component.text(" - 花牌補牌: ", NamedTextColor.YELLOW).append(enabled),
            Component.text(" - 旁觀: ", NamedTextColor.YELLOW).append(if (spectate) enabled else Component.text("關閉", NamedTextColor.RED))
        )
    }

    companion object {
        const val MAX_POINTS = 200000
        const val MIN_POINTS = 100
    }

    enum class GameLength(
        private val startingWind: Wind,
        val rounds: Int,
        val finalRound: Pair<Wind, Int>,
        val displayText: String
    ) : TextFormatting {
        ONE_GAME(Wind.EAST, 1, Wind.EAST to 0, "一局"),
        EAST(Wind.EAST, 4, Wind.SOUTH to 3, "東風"),
        TWO_WIND(Wind.EAST, 8, Wind.WEST to 3, "半莊");

        fun getStartingRound(): MahjongRound = MahjongRound(wind = startingWind)

        fun getRounds(playerCount: Int): Int = when (this) {
            ONE_GAME -> 1
            else -> (rounds / 4) * playerCount
        }

        fun getFinalRound(playerCount: Int): Pair<Wind, Int> = when (this) {
            ONE_GAME -> finalRound
            else -> finalRound.first to (playerCount - 1)
        }

        override fun toText(): Component = Component.text(displayText)
    }

    enum class MinimumTai(val tai: Int) : TextFormatting {
        NONE(0),
        ONE(1),
        TWO(2),
        FOUR(4);

        override fun toText(): Component = Component.text(tai.toString())
    }

    enum class ThinkingTime(val base: Int, val extra: Int) : TextFormatting {
        VERY_SHORT(3, 5),
        SHORT(5, 10),
        NORMAL(5, 20),
        LONG(60, 0),
        VERY_LONG(300, 0);

        override fun toText(): Component = Component.text("$base + $extra s")
    }
}
