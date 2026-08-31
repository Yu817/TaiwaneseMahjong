package com.mahjongplay.config

import com.mahjongplay.display.BoardRenderer
import com.mahjongplay.model.MahjongRule
import org.bukkit.configuration.file.FileConfiguration

/**
 * Server-level defaults for newly created tables.
 *
 * Existing tables keep their own values in tables.yml, so changing this file
 * does not silently change a game that is already waiting or in progress.
 */
data class MahjongSettings(
    val defaultGameLength: MahjongRule.GameLength = MahjongRule.GameLength.TWO_WIND,
    val defaultRounds: Int = 16,
    val defaultBotResponseMs: Long = MahjongRule.MIN_BOT_RESPONSE_MS,
    /** Standard Taiwanese Mahjong starting score for each seat. */
    val defaultStartingPoints: Int = 0,
    val defaultBasePoints: Int = 0,
    val defaultPointsPerTai: Int = 1000,
    val defaultMinimumTai: MahjongRule.MinimumTai = MahjongRule.MinimumTai.NONE,
    val defaultThinkingTime: MahjongRule.ThinkingTime = MahjongRule.ThinkingTime.NORMAL,
    val defaultFlowersEnabled: Boolean = true,
    val drawAnimationMs: Long = MahjongRule.DEFAULT_DRAW_ANIMATION_MS,
    val initialDealAnimationMs: Long = MahjongRule.DEFAULT_INITIAL_DEAL_ANIMATION_MS,
    val initialDealGroupPauseMs: Long = MahjongRule.DEFAULT_INITIAL_DEAL_GROUP_PAUSE_MS,
    val openingDiceAnimationMs: Long = MahjongRule.DEFAULT_OPENING_DICE_ANIMATION_MS,
    val teleportPlayersOnStart: Boolean = true,
    val seatDistance: Double = 2.8,
    val tableScale: Float = 1.8f,
    val handDistance: Double = BoardRenderer.DEFAULT_HAND_DISTANCE,
    val wallDistance: Double? = null,
    val economyEnabled: Boolean = true,
    val economyMinBalance: Double = 0.0,
    val economyBankruptcyEnabled: Boolean = true,
    val defaultMoneyMatch: Boolean = true,
) {
    fun createRule(
        gameLength: MahjongRule.GameLength = defaultGameLength,
        roundsOverride: Int? = null,
    ): MahjongRule {
        val rounds = (roundsOverride ?: if (gameLength == defaultGameLength) {
            defaultRounds
        } else {
            gameLength.getRounds(4)
        }).coerceIn(MahjongRule.MIN_ROUNDS, MahjongRule.MAX_ROUNDS)

        return MahjongRule(
            length = gameLength,
            playerCount = 4,
            thinkingTime = defaultThinkingTime,
            startingPoints = defaultStartingPoints,
            minimumTai = defaultMinimumTai,
            roundsToPlay = rounds,
            basePoints = defaultBasePoints,
            pointsPerTai = defaultPointsPerTai,
            flowersEnabled = defaultFlowersEnabled,
            botResponseDelayMs = defaultBotResponseMs,
            moneyMatch = defaultMoneyMatch,
            drawAnimationMs = drawAnimationMs,
            initialDealAnimationMs = initialDealAnimationMs,
            initialDealGroupPauseMs = initialDealGroupPauseMs,
            openingDiceAnimationMs = openingDiceAnimationMs,
        )
    }

    companion object {
        fun from(config: FileConfiguration): MahjongSettings {
            val configuredLength = enumValue(
                config.getString("defaults.game-length"),
                MahjongRule.GameLength.TWO_WIND,
            )
            val configuredMinimumTai = enumValue(
                config.getString("defaults.minimum-tai"),
                MahjongRule.MinimumTai.NONE,
            )
            val configuredThinkingTime = enumValue(
                config.getString("defaults.thinking-time"),
                MahjongRule.ThinkingTime.NORMAL,
            )

            return MahjongSettings(
                defaultGameLength = configuredLength,
                defaultRounds = config.getInt("defaults.rounds", MahjongRule.MAX_ROUNDS)
                    .coerceIn(MahjongRule.MIN_ROUNDS, MahjongRule.MAX_ROUNDS),
                defaultBotResponseMs = config.getLong(
                    "defaults.bot-response-ms",
                    MahjongRule.MIN_BOT_RESPONSE_MS,
                ).coerceIn(MahjongRule.MIN_BOT_RESPONSE_MS, MahjongRule.MAX_BOT_RESPONSE_MS),
                defaultStartingPoints = config.getInt("defaults.starting-points", 0)
                    .coerceIn(0, MahjongRule.MAX_POINTS),
                defaultBasePoints = config.getInt("defaults.base-points", 0)
                    .coerceIn(0, MahjongRule.MAX_POINTS),
                defaultPointsPerTai = config.getInt("defaults.points-per-tai", 1000)
                    .coerceIn(0, MahjongRule.MAX_POINTS),
                defaultMinimumTai = configuredMinimumTai,
                defaultThinkingTime = configuredThinkingTime,
                defaultFlowersEnabled = config.getBoolean("defaults.flowers", true),
                drawAnimationMs = config.getLong(
                    "display.draw-animation-ms",
                    MahjongRule.DEFAULT_DRAW_ANIMATION_MS,
                ).coerceIn(0L, MahjongRule.MAX_DRAW_ANIMATION_MS),
                initialDealAnimationMs = config.getLong(
                    "display.initial-deal-animation-ms",
                    MahjongRule.DEFAULT_INITIAL_DEAL_ANIMATION_MS,
                ).coerceIn(0L, MahjongRule.MAX_DRAW_ANIMATION_MS),
                initialDealGroupPauseMs = config.getLong(
                    "display.initial-deal-group-pause-ms",
                    MahjongRule.DEFAULT_INITIAL_DEAL_GROUP_PAUSE_MS,
                ).coerceIn(0L, MahjongRule.MAX_DRAW_ANIMATION_MS),
                openingDiceAnimationMs = config.getLong(
                    "display.opening-dice-animation-ms",
                    MahjongRule.DEFAULT_OPENING_DICE_ANIMATION_MS,
                ).coerceIn(0L, MahjongRule.MAX_OPENING_DICE_ANIMATION_MS),
                teleportPlayersOnStart = config.getBoolean("teleport.enabled", true),
                seatDistance = config.getDouble("teleport.seat-distance", 2.8).coerceIn(2.0, 8.0),
                tableScale = config.getDouble("display.table-scale", 1.8).toFloat().coerceIn(1.0f, 3.0f),
                handDistance = config.getDouble(
                    "display.hand-distance",
                    BoardRenderer.DEFAULT_HAND_DISTANCE,
                ).coerceIn(1.10, 2.50),
                wallDistance = if (config.contains("display.wall-distance")) {
                    config.getDouble("display.wall-distance").coerceIn(0.80, 2.00)
                } else null,
                economyEnabled = config.getBoolean("economy.enabled", true),
                economyMinBalance = config.getDouble("economy.min-balance", 0.0).coerceAtLeast(0.0),
                economyBankruptcyEnabled = config.getBoolean("economy.bankruptcy-check", true),
                defaultMoneyMatch = config.getBoolean("defaults.money-match", true),
            )
        }

        private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T {
            val normalized = value?.trim()?.uppercase()?.replace('-', '_') ?: return fallback
            return enumValues<T>().firstOrNull { it.name == normalized } ?: fallback
        }
    }
}
