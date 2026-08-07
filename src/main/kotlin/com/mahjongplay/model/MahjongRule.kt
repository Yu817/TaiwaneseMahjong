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
    // Number of non-dealer-repeat hands. 1 = 1/4 circle, 16 = a full 4-circle game.
    var roundsToPlay: Int = 16,
    var basePoints: Int = 0,
    var pointsPerTai: Int = 1000,
    var honbaPoints: Int = 100,
    var dealerTsumoMultiplier: Int = 2,
    var flowersEnabled: Boolean = true,
    var chairsEnabled: Boolean = true,
    var botResponseDelayMs: Long = MIN_BOT_RESPONSE_MS,
) {
    val isTaiwanese: Boolean
        get() = true

    val displayCircleText: String
        get() = when (roundsToPlay) {
            1 -> "1/4圈"
            2 -> "1/2圈"
            4 -> "1圈"
            8 -> "2圈"
            12 -> "3圈"
            16 -> "4圈"
            else -> if (roundsToPlay < 4) "${roundsToPlay}/4圈" else "${roundsToPlay / 4}圈"
        }

    // Kept as a compatibility name for the table header and saved-table code.
    val displayLengthText: String
        get() = displayCircleText

    fun toComponents(): List<Component> {
        val enabled = Component.text("開啟", NamedTextColor.GREEN)
        val disabled = Component.text("關閉", NamedTextColor.RED)
        val botSeconds = (botResponseDelayMs / 1000L).coerceIn(1L, 5L)
        return listOf(
            Component.text("台灣麻將規則", NamedTextColor.GOLD),
            Component.text(" • 圈數: ", NamedTextColor.YELLOW)
                .append(Component.text("$displayCircleText（連莊另計）", NamedTextColor.GREEN)),
            Component.text(" • 手牌: ", NamedTextColor.YELLOW)
                .append(Component.text("16 張／${playerCount} 人", NamedTextColor.AQUA)),
            Component.text(" • 起始分數: ", NamedTextColor.YELLOW)
                .append(Component.text("$startingPoints", NamedTextColor.GREEN)),
            Component.text(" • 底／台: ", NamedTextColor.YELLOW)
                .append(Component.text("$basePoints／$pointsPerTai 分", NamedTextColor.GREEN)),
            Component.text(" • 最低台數: ", NamedTextColor.YELLOW)
                .append(Component.text("${minimumTai.tai} 台", NamedTextColor.GREEN)),
            Component.text(" • 花牌補牌: ", NamedTextColor.YELLOW)
                .append(if (flowersEnabled) enabled else disabled),
            Component.text(" • 椅子: ", NamedTextColor.YELLOW)
                .append(if (chairsEnabled) enabled else disabled),
            Component.text(" • Bot 反應: ", NamedTextColor.YELLOW)
                .append(Component.text("${botSeconds} 秒", NamedTextColor.AQUA)),
            Component.text(" • 旁觀: ", NamedTextColor.YELLOW)
                .append(if (spectate) enabled else disabled)
        )
    }

    companion object {
        const val MAX_POINTS = 200000
        const val MIN_POINTS = 100
        const val MIN_ROUNDS = 1
        const val MAX_ROUNDS = 16
        const val MIN_BOT_RESPONSE_MS = 1000L
        const val MAX_BOT_RESPONSE_MS = 5000L
    }

    enum class GameLength(
        private val startingWind: Wind,
        val rounds: Int,
        val finalRound: Pair<Wind, Int>,
        val displayText: String
    ) : TextFormatting {
        ONE_GAME(Wind.EAST, 1, Wind.EAST to 0, "一局"),
        EAST(Wind.EAST, 4, Wind.SOUTH to 3, "東風"),
        // Legacy enum name kept for existing tables/configs; it now means a
        // standard Taiwanese full game: East/South/West/North, four circles.
        TWO_WIND(Wind.EAST, 16, Wind.NORTH to 3, "一將");

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
