package com.mahjongplay.model

/** 台灣麻將常見台數的計算器。 */
object TaiwaneseScorer {
    fun score(
        displayName: String,
        uuid: String,
        isRealPlayer: Boolean,
        botCode: Int,
        concealedTiles: List<MahjongTile>,
        fuuroList: List<Fuuro>,
        flowers: List<MahjongTile>,
        shape: HandShape,
        winningTile: MahjongTile,
        isTsumo: Boolean,
        seatWind: Wind,
        roundWind: Wind,
        pointsPerTai: Int,
        basePoints: Int = 0
    ): TaiwanSettlement {
        val items = mutableListOf<TaiItem>()
        val allGroups = fuuroList.map { it.toHandGroup() } + shape.concealedGroups
        val allTiles = concealedTiles + fuuroList.flatMap { it.tiles }
        val isMenzen = fuuroList.none { it.isOpen }

        flowers.forEach { items += TaiItem("${it.displayName}花", 1) }
        if (flowers.any { it == MahjongTile.flowerTiles.getOrNull(seatWind.flowerIndex - 1) }) {
            items += TaiItem("門花", 1)
        }
        if (flowers.any { it == MahjongTile.flowerTiles.getOrNull(roundWind.flowerIndex - 1) }) {
            items += TaiItem("圈花", 1)
        }

        if (isMenzen) items += TaiItem("門清", 1)
        if (isTsumo) items += TaiItem("自摸", 1)

        val numberedSuits = allTiles.filter { it.isNumbered }.map { it.suit }.toSet()
        val hasHonors = allTiles.any { it.isHonor }
        when {
            numberedSuits.isEmpty() && hasHonors -> items += TaiItem("字一色", 8)
            numberedSuits.size == 1 && !hasHonors -> items += TaiItem("清一色", 8)
            numberedSuits.size == 1 && hasHonors -> items += TaiItem("混一色", 4)
        }

        if (allTiles.isNotEmpty() && allTiles.all { it.isTerminalOrHonor }) {
            if (hasHonors && numberedSuits.isNotEmpty()) items += TaiItem("混老頭", 4)
            else if (!hasHonors) items += TaiItem("清老頭", 8)
        }

        if (allGroups.isNotEmpty() && allGroups.all { it.type != MeldType.SEQUENCE }) {
            items += TaiItem("碰碰胡", 4)
        }
        if (allGroups.isNotEmpty() && allGroups.all { it.type == MeldType.SEQUENCE } &&
            shape.pair.firstOrNull()?.let { it.isNumbered && it.number in 2..8 } == true
        ) {
            items += TaiItem("平胡", 2)
        }

        val dragonTriplets = allGroups.filter { it.type != MeldType.SEQUENCE && it.representative.isDragon() }
        val dragonTypes = dragonTriplets.map { it.representative }.toSet()
        val dragonPair = shape.pair.firstOrNull()?.isDragon() == true
        when {
            dragonTypes.size == 3 -> items += TaiItem("大三元", 8)
            dragonTypes.size == 2 && dragonPair -> items += TaiItem("小三元", 4)
            else -> dragonTypes.forEach { items += TaiItem("${it.displayName}三元牌", 1) }
        }

        val windTriplets = allGroups.filter { it.type != MeldType.SEQUENCE && it.representative.isWind() }
        val windTypes = windTriplets.map { it.representative }.toSet()
        val windPair = shape.pair.firstOrNull()?.isWind() == true
        when {
            windTypes.size == 4 -> items += TaiItem("大四喜", 16)
            windTypes.size == 3 && windPair -> items += TaiItem("小四喜", 8)
            else -> {
                windTriplets.forEach { group ->
                    if (group.representative == seatWind.tile) items += TaiItem("門風", 1)
                    if (group.representative == roundWind.tile) items += TaiItem("圈風", 1)
                }
            }
        }

        val concealedTripletCount = shape.concealedGroups.count { it.type != MeldType.SEQUENCE }
        if (concealedTripletCount >= 4 && fuuroList.none { it.type != MeldType.SEQUENCE && it.isOpen }) {
            items += TaiItem("四暗刻", 5)
        }

        fuuroList.forEach { fuuro ->
            if (fuuro.isKong) items += TaiItem(if (fuuro.isOpen) "明槓" else "暗槓", if (fuuro.isOpen) 1 else 2) }

        if (!isMenzen && fuuroList.size == 5 && !isTsumo) items += TaiItem("全求人", 4)

        val sequenceGroups = allGroups.filter { it.type == MeldType.SEQUENCE }
        val sequenceNumbersBySuit = sequenceGroups.groupBy { it.representative.suit }
            .mapValues { (_, groups) -> groups.map { it.representative.number }.toSet() }
        if (sequenceNumbersBySuit.values.any { starts -> setOf(1, 4, 7).all { number -> number in starts } }) {
            items += TaiItem("一氣通貫", 2)
        }
        val hasThreeColorSequence = (1..7).any { number ->
            TileSuit.entries.filter { it == TileSuit.MAN || it == TileSuit.PIN || it == TileSuit.SOU }
                .all { suit -> sequenceNumbersBySuit[suit]?.contains(number) == true }
        }
        if (hasThreeColorSequence) items += TaiItem("三色同順", 2)

        val totalTai = items.sumOf { it.tai }
        val fuuroForDisplay = fuuroList.map { it.isOpen to it.tiles }
        return TaiwanSettlement(
            displayName = displayName,
            uuid = uuid,
            isRealPlayer = isRealPlayer,
            botCode = botCode,
            taiList = items,
            flowerCount = flowers.size,
            winningTile = winningTile,
            hands = concealedTiles,
            fuuroList = fuuroForDisplay,
            tai = totalTai,
            score = basePoints + totalTai * pointsPerTai,
            isTsumo = isTsumo
        )
    }

    private fun Fuuro.toHandGroup(): HandGroup = HandGroup(type, tiles, isOpen)

    private fun MahjongTile.isWind(): Boolean = this in MahjongTile.EAST..MahjongTile.NORTH

    private fun MahjongTile.isDragon(): Boolean = this in MahjongTile.WHITE_DRAGON..MahjongTile.RED_DRAGON
}
