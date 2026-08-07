package com.mahjongplay.model

/** 一組已完成的面子，用於規則判斷與台數計算。 */
data class HandGroup(
    val type: MeldType,
    val tiles: List<MahjongTile>,
    val isOpen: Boolean
) {
    val representative: MahjongTile
        get() = tiles.first()
}

data class HandShape(
    val concealedGroups: List<HandGroup>,
    val pair: List<MahjongTile>
) {
    val groups: List<HandGroup>
        get() = concealedGroups

    val allTriplets: Boolean
        get() = groups.all { it.type != MeldType.SEQUENCE }

    val allSequences: Boolean
        get() = groups.all { it.type == MeldType.SEQUENCE }
}

/**
 * 台灣麻將的基本和牌形狀：五組面子加一對將牌。
 * 副露面子由 Fuuro 傳入，遞迴只分解尚未副露的手牌。
 */
object TaiwaneseHandEvaluator {
    private val baseTiles = MahjongTile.basicTiles

    fun findWinningShapes(
        concealedTiles: List<MahjongTile>,
        fuuroList: List<Fuuro>
    ): List<HandShape> {
        val expectedGroups = 5 - fuuroList.size
        if (expectedGroups < 0) return emptyList()

        val tiles = concealedTiles.filter { it.isNumbered || it.isHonor }
        if (tiles.size != expectedGroups * 3 + 2) return emptyList()

        val counts = IntArray(baseTiles.size)
        tiles.forEach { counts[it.code]++ }
        val result = LinkedHashSet<HandShape>()

        baseTiles.forEach { pairTile ->
            if (counts[pairTile.code] < 2) return@forEach
            counts[pairTile.code] -= 2
            searchGroups(counts, expectedGroups, emptyList(), pairTile, result)
            counts[pairTile.code] += 2
        }
        return result.toList()
    }

    fun canWin(concealedTiles: List<MahjongTile>, fuuroList: List<Fuuro>): Boolean =
        findWinningShapes(concealedTiles, fuuroList).isNotEmpty()

    private fun searchGroups(
        counts: IntArray,
        expectedGroups: Int,
        groups: List<HandGroup>,
        pairTile: MahjongTile,
        result: MutableSet<HandShape>
    ) {
        if (groups.size == expectedGroups) {
            if (counts.all { it == 0 }) {
                result += HandShape(groups, listOf(pairTile, pairTile))
            }
            return
        }

        val firstCode = counts.indexOfFirst { it > 0 }
        if (firstCode < 0) return
        val tile = baseTiles[firstCode]

        if (counts[firstCode] >= 3) {
            counts[firstCode] -= 3
            searchGroups(
                counts,
                expectedGroups,
                groups + HandGroup(MeldType.TRIPLET, List(3) { tile }, isOpen = false),
                pairTile,
                result
            )
            counts[firstCode] += 3
        }

        if (tile.isNumbered && tile.number <= 7) {
            val second = tileBySuitAndNumber(tile.suit, tile.number + 1)
            val third = tileBySuitAndNumber(tile.suit, tile.number + 2)
            if (second != null && third != null && counts[second.code] > 0 && counts[third.code] > 0) {
                counts[firstCode]--
                counts[second.code]--
                counts[third.code]--
                searchGroups(
                    counts,
                    expectedGroups,
                    groups + HandGroup(MeldType.SEQUENCE, listOf(tile, second, third), isOpen = false),
                    pairTile,
                    result
                )
                counts[firstCode]++
                counts[second.code]++
                counts[third.code]++
            }
        }
    }

    private fun tileBySuitAndNumber(suit: TileSuit, number: Int): MahjongTile? = when (suit) {
        TileSuit.MAN -> baseTiles.find { it.suit == TileSuit.MAN && it.number == number }
        TileSuit.PIN -> baseTiles.find { it.suit == TileSuit.PIN && it.number == number }
        TileSuit.SOU -> baseTiles.find { it.suit == TileSuit.SOU && it.number == number }
        else -> null
    }
}
