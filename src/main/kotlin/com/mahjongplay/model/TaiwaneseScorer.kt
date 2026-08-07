package com.mahjongplay.model

/**
 * 台灣 16 張麻將常見台數計算器。
 *
 * 台麻各地桌規會有差異；這裡延續專案既有牌型台數，並補上牌局流程常用的
 * 莊家／連莊拉莊、獨聽、三四五暗刻、海底、槓上開花、搶槓與天地人胡。
 */
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
        basePoints: Int = 0,
        context: TaiwanWinContext = TaiwanWinContext(),
    ): TaiwanSettlement {
        val items = mutableListOf<TaiItem>()
        val allGroups = fuuroList.map { it.toHandGroup() } + shape.concealedGroups
        val allTiles = concealedTiles + fuuroList.flatMap { it.tiles }
        val isMenzen = fuuroList.none { it.isOpen }

        // --- 牌局情境台 ---
        if (seatWind == Wind.EAST) {
            items += TaiItem("莊家", 1)
            if (context.dealerRepeat > 0) {
                items += TaiItem("連莊×${context.dealerRepeat}", context.dealerRepeat)
                items += TaiItem("拉莊×${context.dealerRepeat}", context.dealerRepeat)
            }
        }

        if (context.isSingleWait) items += TaiItem("獨聽", 1)

        when {
            context.isHeavenlyHand -> items += TaiItem("天胡", 16)
            context.isEarthlyHand -> items += TaiItem("地胡", 16)
            context.isHumanHand -> items += TaiItem("人胡", 8)
        }

        if (context.isKongReplacement) items += TaiItem("槓上開花", 1)
        if (context.isRobbingKong) items += TaiItem("搶槓", 1)
        if (context.isLastLiveTile) {
            items += TaiItem(if (isTsumo) "海底撈月" else "海底撈魚", 1)
        }

        // --- 花牌 ---
        // 保留專案既有花牌桌規：每張花 1 台，門花與圈花再各加 1 台。
        flowers.forEach { items += TaiItem("${it.displayName}花", 1) }
        if (flowers.any { it == MahjongTile.flowerTiles.getOrNull(seatWind.flowerIndex - 1) }) {
            items += TaiItem("門花", 1)
        }
        if (flowers.any { it == MahjongTile.flowerTiles.getOrNull(roundWind.flowerIndex - 1) }) {
            items += TaiItem("圈花", 1)
        }

        // --- 基本和牌方式 ---
        if (isMenzen) items += TaiItem("門清", 1)
        if (isTsumo) items += TaiItem("自摸", 1)
        // 常見「門清一摸三」：門清 1 + 自摸 1 + 不求人 1。
        if (isMenzen && isTsumo) items += TaiItem("不求人", 1)

        // --- 花色牌型 ---
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

        // --- 面子牌型 ---
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

        // 暗槓仍屬暗刻；榮和補成的刻子不算暗刻。
        var concealedTripletCount =
            shape.concealedGroups.count { it.type != MeldType.SEQUENCE } +
                fuuroList.count { it.isKong && !it.isOpen }
        if (!isTsumo) {
            val beforeWinCount = (concealedTiles.count { it == winningTile } - 1).coerceAtLeast(0)
            val ronCompletedTriplet = beforeWinCount < 3 && shape.concealedGroups.any {
                it.type != MeldType.SEQUENCE && it.representative == winningTile
            }
            if (ronCompletedTriplet) concealedTripletCount--
        }
        when {
            concealedTripletCount >= 5 -> items += TaiItem("五暗刻", 8)
            concealedTripletCount == 4 -> items += TaiItem("四暗刻", 5)
            concealedTripletCount == 3 -> items += TaiItem("三暗刻", 2)
        }

        fuuroList.forEach { fuuro ->
            if (fuuro.isKong) {
                items += TaiItem(
                    if (fuuro.isOpen) "明槓" else "暗槓",
                    if (fuuro.isOpen) 1 else 2,
                )
            }
        }

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
            isTsumo = isTsumo,
        )
    }

    private fun Fuuro.toHandGroup(): HandGroup = HandGroup(type, tiles, isOpen)

    private fun MahjongTile.isWind(): Boolean = this in MahjongTile.EAST..MahjongTile.NORTH

    private fun MahjongTile.isDragon(): Boolean = this in MahjongTile.WHITE_DRAGON..MahjongTile.RED_DRAGON
}
