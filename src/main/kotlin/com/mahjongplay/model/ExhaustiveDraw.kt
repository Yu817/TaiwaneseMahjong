package com.mahjongplay.model

import com.mahjongplay.util.TextFormatting
import net.kyori.adventure.text.Component

/** 台灣麻將的牌山耗盡流局原因。 */
enum class ExhaustiveDraw(
    private val displayName: String
) : TextFormatting {
    NORMAL("牌山耗盡");

    override fun toText(): Component = Component.text(displayName)
}
