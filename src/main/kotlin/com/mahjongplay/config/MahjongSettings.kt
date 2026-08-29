package com.mahjongplay.config

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
    val defaultStartingPoints: Int = 16000,
    val defaultBasePoints: Int = 0,
    val defaultPointsPerTai: Int = 1000,
    val defaultMinimumTai: MahjongRule.MinimumTai = MahjongRule.MinimumTai.NONE,
    val defaultThinkingTime: MahjongRule.ThinkingTime = MahjongRule.ThinkingTime.NORMAL,
    val defaultHonbaPoints: Int = 100,
    val defaultDealerTsumoMultiplier: Int = 2,
    val defaultFlowersEnabled: Boolean = true,
    val teleportPlayersOnStart: Boolean = true,
    val seatDistance: Double = 2.8,
    val tableScale: Float = 1.8f,
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
            honbaPoints = defaultHonbaPoints,
            dealerTsumoMultiplier = defaultDealerTsumoMultiplier,
            flowersEnabled = defaultFlowersEnabled,
            botResponseDelayMs = defaultBotResponseMs,
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
                defaultStartingPoints = config.getInt("defaults.starting-points", 16000).coerceAtLeast(0),
                defaultBasePoints = config.getInt("defaults.base-points", 0).coerceAtLeast(0),
                defaultPointsPerTai = config.getInt("defaults.points-per-tai", 1000).coerceAtLeast(0),
                defaultMinimumTai = configuredMinimumTai,
                defaultThinkingTime = configuredThinkingTime,
                defaultHonbaPoints = config.getInt("defaults.honba-points", 100).coerceAtLeast(0),
                defaultDealerTsumoMultiplier = config.getInt("defaults.dealer-tsumo-multiplier", 2)
                    .coerceAtLeast(1),
                defaultFlowersEnabled = config.getBoolean("defaults.flowers", true),
                teleportPlayersOnStart = config.getBoolean("teleport.enabled", true),
                seatDistance = config.getDouble("teleport.seat-distance", 2.8).coerceIn(2.0, 8.0),
                tableScale = config.getDouble("display.table-scale", 1.8).toFloat().coerceIn(1.0f, 3.0f),
            )
        }

        private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T {
            val normalized = value?.trim()?.uppercase()?.replace('-', '_') ?: return fallback
            return enumValues<T>().firstOrNull { it.name == normalized } ?: fallback
        }
    }
}
