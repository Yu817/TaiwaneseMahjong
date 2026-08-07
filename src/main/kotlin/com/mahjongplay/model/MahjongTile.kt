package com.mahjongplay.model

import com.mahjongplay.util.TextFormatting
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

/**
 * 台灣麻將使用的牌。
 *
 * 台灣麻將使用 144 張牌：34 種基本牌各四張，以及春夏秋冬、梅蘭竹菊各一張。
 * 紅寶牌、寶牌指示牌與拔北不是台灣麻將規則的一部分，因此不會出現在牌山中。
 */
enum class MahjongTile : TextFormatting {
    M1, M2, M3, M4, M5, M6, M7, M8, M9,
    P1, P2, P3, P4, P5, P6, P7, P8, P9,
    S1, S2, S3, S4, S5, S6, S7, S8, S9,

    EAST,
    SOUTH,
    WEST,
    NORTH,

    WHITE_DRAGON,
    GREEN_DRAGON,
    RED_DRAGON,

    FLOWER_1,
    FLOWER_2,
    FLOWER_3,
    FLOWER_4,
    FLOWER_5,
    FLOWER_6,
    FLOWER_7,
    FLOWER_8,

    UNKNOWN;

    val code: Int = ordinal

    val isFlower: Boolean
        get() = this in FLOWER_1..FLOWER_8

    val isHonor: Boolean
        get() = this in EAST..RED_DRAGON

    val isNumbered: Boolean
        get() = this in M1..S9

    val suit: TileSuit
        get() = when {
            this in M1..M9 -> TileSuit.MAN
            this in P1..P9 -> TileSuit.PIN
            this in S1..S9 -> TileSuit.SOU
            isHonor -> TileSuit.HONOR
            isFlower -> TileSuit.FLOWER
            else -> TileSuit.UNKNOWN
        }

    val number: Int
        get() = when {
            this in M1..M9 -> ordinal - M1.ordinal + 1
            this in P1..P9 -> ordinal - P1.ordinal + 1
            this in S1..S9 -> ordinal - S1.ordinal + 1
            else -> 0
        }

    val isTerminal: Boolean
        get() = isNumbered && (number == 1 || number == 9)

    val isTerminalOrHonor: Boolean
        get() = isTerminal || isHonor

    val sortOrder: Int
        get() = code

    val modelPath: String
        get() = "mahjongcraft:item/mahjong_tile/mahjong_tile_${name.lowercase()}"

    val displayName: String
        get() = when (this) {
            M1 -> "一萬"; M2 -> "二萬"; M3 -> "三萬"; M4 -> "四萬"; M5 -> "五萬"
            M6 -> "六萬"; M7 -> "七萬"; M8 -> "八萬"; M9 -> "九萬"
            P1 -> "一筒"; P2 -> "二筒"; P3 -> "三筒"; P4 -> "四筒"; P5 -> "五筒"
            P6 -> "六筒"; P7 -> "七筒"; P8 -> "八筒"; P9 -> "九筒"
            S1 -> "一索"; S2 -> "二索"; S3 -> "三索"; S4 -> "四索"; S5 -> "五索"
            S6 -> "六索"; S7 -> "七索"; S8 -> "八索"; S9 -> "九索"
            EAST -> "東"; SOUTH -> "南"; WEST -> "西"; NORTH -> "北"
            WHITE_DRAGON -> "白"; GREEN_DRAGON -> "發"; RED_DRAGON -> "中"
            FLOWER_1 -> "春"; FLOWER_2 -> "夏"; FLOWER_3 -> "秋"; FLOWER_4 -> "冬"
            FLOWER_5 -> "梅"; FLOWER_6 -> "蘭"; FLOWER_7 -> "竹"; FLOWER_8 -> "菊"
            UNKNOWN -> "?"
        }

    val previousTile: MahjongTile
        get() = when (suit) {
            TileSuit.MAN -> if (this == M1) M9 else entries[code - 1]
            TileSuit.PIN -> if (this == P1) P9 else entries[code - 1]
            TileSuit.SOU -> if (this == S1) S9 else entries[code - 1]
            TileSuit.HONOR -> when (this) {
                EAST -> NORTH
                SOUTH -> EAST
                WEST -> SOUTH
                NORTH -> WEST
                WHITE_DRAGON -> RED_DRAGON
                GREEN_DRAGON -> WHITE_DRAGON
                RED_DRAGON -> GREEN_DRAGON
                else -> UNKNOWN
            }
            else -> UNKNOWN
        }

    val nextTile: MahjongTile
        get() = when (suit) {
            TileSuit.MAN -> if (this == M9) M1 else entries[code + 1]
            TileSuit.PIN -> if (this == P9) P1 else entries[code + 1]
            TileSuit.SOU -> if (this == S9) S1 else entries[code + 1]
            TileSuit.HONOR -> when (this) {
                EAST -> SOUTH
                SOUTH -> WEST
                WEST -> NORTH
                NORTH -> EAST
                WHITE_DRAGON -> GREEN_DRAGON
                GREEN_DRAGON -> RED_DRAGON
                RED_DRAGON -> WHITE_DRAGON
                else -> UNKNOWN
            }
            else -> UNKNOWN
        }

    override fun toText(): Component {
        val color = when {
            isFlower -> NamedTextColor.LIGHT_PURPLE
            isHonor -> NamedTextColor.AQUA
            else -> NamedTextColor.WHITE
        }
        return Component.text(displayName, color)
    }

    companion object {
        val basicTiles: List<MahjongTile> = entries.filter { it.isNumbered || it.isHonor }
        val flowerTiles: List<MahjongTile> = entries.filter { it.isFlower }

        /** 完整 144 張台麻牌山。花牌各一張，基本牌各四張。 */
        val taiwaneseWall: List<MahjongTile> = buildList {
            basicTiles.forEach { tile -> repeat(4) { add(tile) } }
            addAll(flowerTiles)
        }

        /** 舊版名稱保留給外部整合，但內容已經是台麻牌山。 */
        val normalWall: List<MahjongTile> = taiwaneseWall

        fun random(): MahjongTile = basicTiles.random()
    }
}

enum class TileSuit {
    MAN, PIN, SOU, HONOR, FLOWER, UNKNOWN
}
