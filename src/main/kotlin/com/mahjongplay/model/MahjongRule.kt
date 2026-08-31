package com.mahjongplay.model

import com.mahjongplay.util.TextFormatting
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

/**
 * 台灣麻將規則。
 *
 * 預設採 4 人、16 張手牌、144 張牌、最低一台起胡、每台 1000 籌碼。
 * 每筆支付使用底台制：底 + 台數 × 每台籌碼。
 */
data class MahjongRule(
    var length: GameLength = GameLength.TWO_WIND,
    var playerCount: Int = 4,
    var thinkingTime: ThinkingTime = ThinkingTime.NORMAL,
    var startingPoints: Int = 16_000,
    var minPointsToWin: Int = 0,
    var minimumTai: MinimumTai = MinimumTai.NONE,
    var spectate: Boolean = true,
    // Number of non-dealer-repeat hands. 1 = 1/4 circle, 16 = a full 4-circle game.
    var roundsToPlay: Int = 16,
    var basePoints: Int = 0,
    var pointsPerTai: Int = 1000,
    var flowersEnabled: Boolean = true,
    var chairsEnabled: Boolean = true,
    var botResponseDelayMs: Long = MIN_BOT_RESPONSE_MS,
    /** Normal turn and replacement draw travel time; 0 keeps the flow instant. */
    var drawAnimationMs: Long = DEFAULT_DRAW_ANIMATION_MS,
    /** A faster cadence keeps the 65-tile initial deal from feeling sluggish. */
    var initialDealAnimationMs: Long = DEFAULT_INITIAL_DEAL_ANIMATION_MS,
    var initialDealGroupPauseMs: Long = DEFAULT_INITIAL_DEAL_GROUP_PAUSE_MS,
    /** Three-dice opening roll, including the short settled-result pause. */
    var openingDiceAnimationMs: Long = DEFAULT_OPENING_DICE_ANIMATION_MS,
    /** 開局抓位（抓風）開關 */
    var seatWindDrawEnabled: Boolean = true,
    /** 是否啟用機器人（Bots）補位 */
    var botsEnabled: Boolean = true,
    /** 預設機器人難度 */
    var defaultBotDifficulty: com.mahjongplay.game.BotDifficulty = com.mahjongplay.game.BotDifficulty.MEDIUM,
    /** 旁觀玩家是否可看手牌 */
    var spectatorSeeHands: Boolean = false,
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
            Component.text(" • 起始積分: ", NamedTextColor.YELLOW)
                .append(Component.text("$startingPoints", NamedTextColor.GREEN)),
            Component.text(" • 底／台: ", NamedTextColor.YELLOW)
                .append(Component.text("$basePoints／$pointsPerTai 積分", NamedTextColor.GREEN)),
            Component.text(" • 最低台數: ", NamedTextColor.YELLOW)
                .append(Component.text("${minimumTai.tai} 台", NamedTextColor.GREEN)),
            Component.text(" • 花牌補牌: ", NamedTextColor.YELLOW)
                .append(if (flowersEnabled) enabled else disabled),
            Component.text(" • 椅子: ", NamedTextColor.YELLOW)
                .append(if (chairsEnabled) enabled else disabled),
            Component.text(" • Bots 機器人: ", NamedTextColor.YELLOW)
                .append(if (botsEnabled) Component.text("開啟（${defaultBotDifficulty.displayName}・${botSeconds}秒）", NamedTextColor.GREEN) else disabled),
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
        const val DEFAULT_DRAW_ANIMATION_MS = 240L
        const val DEFAULT_INITIAL_DEAL_ANIMATION_MS = 100L
        const val DEFAULT_INITIAL_DEAL_GROUP_PAUSE_MS = 70L
        const val DEFAULT_OPENING_DICE_ANIMATION_MS = 2200L
        const val MAX_OPENING_DICE_ANIMATION_MS = 5000L
        const val MAX_DRAW_ANIMATION_MS = 2000L
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
