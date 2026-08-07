package com.mahjongplay.model

import com.mahjongplay.util.TextFormatting
import net.kyori.adventure.text.Component

/** 台灣麻將的座風／圈風。 */
enum class Wind(
    val tile: MahjongTile,
    val displayName: String,
    val flowerIndex: Int
) : TextFormatting {
    EAST(MahjongTile.EAST, "東", 1),
    SOUTH(MahjongTile.SOUTH, "南", 2),
    WEST(MahjongTile.WEST, "西", 3),
    NORTH(MahjongTile.NORTH, "北", 4);

    override fun toText(): Component = Component.text(displayName)
}
