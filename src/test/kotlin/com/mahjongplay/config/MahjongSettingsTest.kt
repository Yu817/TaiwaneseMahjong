package com.mahjongplay.config

import com.mahjongplay.model.MahjongRule
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MahjongSettingsTest {
    @Test
    fun `numeric score settings are constrained to safe limits`() {
        val config = YamlConfiguration().apply {
            set("defaults.starting-points", Int.MAX_VALUE)
            set("defaults.base-points", Int.MAX_VALUE)
            set("defaults.points-per-tai", Int.MAX_VALUE)
        }

        val settings = MahjongSettings.from(config)

        assertEquals(MahjongRule.MAX_POINTS, settings.defaultStartingPoints)
        assertEquals(MahjongRule.MAX_POINTS, settings.defaultBasePoints)
        assertEquals(MahjongRule.MAX_POINTS, settings.defaultPointsPerTai)
    }

    @Test
    fun `display hand and wall distance settings default and constrain correctly`() {
        val defaultConfig = YamlConfiguration()
        val defaultSettings = MahjongSettings.from(defaultConfig)
        assertEquals(1.30, defaultSettings.handDistance, 0.001)
        assertNull(defaultSettings.wallDistance)

        val customConfig = YamlConfiguration().apply {
            set("display.hand-distance", 1.45)
            set("display.wall-distance", 1.05)
        }
        val customSettings = MahjongSettings.from(customConfig)
        assertEquals(1.45, customSettings.handDistance, 0.001)
        assertEquals(1.05, customSettings.wallDistance!!, 0.001)
    }

    @Test
    fun `opening dice animation can be disabled and is capped`() {
        val disabled = YamlConfiguration().apply { set("display.opening-dice-animation-ms", -1) }
        val excessive = YamlConfiguration().apply { set("display.opening-dice-animation-ms", Long.MAX_VALUE) }

        assertEquals(0L, MahjongSettings.from(disabled).openingDiceAnimationMs)
        assertEquals(MahjongRule.MAX_OPENING_DICE_ANIMATION_MS, MahjongSettings.from(excessive).openingDiceAnimationMs)
    }
}
