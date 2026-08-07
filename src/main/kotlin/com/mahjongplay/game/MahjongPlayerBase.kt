package com.mahjongplay.game

import com.mahjongplay.model.*

/**
 * 台麻玩家共用狀態與牌操作。
 *
 * 16 張制的關鍵差異是：正常等待牌為 16 - 3 × 副露組數張，和牌為五組面子
 * 加一對將牌；花牌不留在手牌，而是放進 flowerTiles 並由牌山尾端補牌。
 */
abstract class MahjongPlayerBase {
    abstract val uuid: String
    abstract val displayName: String
    abstract val isRealPlayer: Boolean

    val hands: MutableList<MahjongTile> = mutableListOf()
    var autoArrangeHands: Boolean = true
    val fuuroList: MutableList<Fuuro> = mutableListOf()
    val flowerTiles: MutableList<MahjongTile> = mutableListOf()

    val discardedTiles: MutableList<MahjongTile> = mutableListOf()
    val discardedTilesForDisplay: MutableList<MahjongTile> = mutableListOf()
    var justDrewTile: Boolean = false

    open var ready: Boolean = false
    var points: Int = 0
    var basicThinkingTime = 0
    var extraThinkingTime = 0

    fun chii(
        claimedTile: MahjongTile,
        tilePair: Pair<MahjongTile, MahjongTile>,
        target: MahjongPlayerBase,
        onChii: (MahjongPlayerBase) -> Unit = {}
    ) {
        onChii(this)
        val chosen = listOf(
            claimedTile,
            hands.find { it == tilePair.first } ?: return,
            hands.find { it == tilePair.second } ?: return
        ).sortedBy { it.sortOrder }
        val fuuro = Fuuro(
            type = MeldType.SEQUENCE,
            tiles = chosen,
            claimTarget = ClaimTarget.LEFT,
            claimTile = claimedTile
        )
        var removedClaim = false
        chosen.forEach { tile ->
            if (tile == claimedTile && !removedClaim) removedClaim = true else hands.remove(tile)
        }
        target.discardedTilesForDisplay.remove(claimedTile)
        fuuroList += fuuro
    }

    fun pon(
        claimedTile: MahjongTile,
        claimTarget: ClaimTarget,
        target: MahjongPlayerBase,
        onPon: (MahjongPlayerBase) -> Unit = {}
    ) {
        onPon(this)
        val tiles = sameTilesInHands(claimedTile).take(2) + claimedTile
        if (tiles.size != 3) return
        tiles.forEach { if (it != claimedTile || tiles.count { t -> t == claimedTile } > 1) hands.remove(it) }
        target.discardedTilesForDisplay.remove(claimedTile)
        fuuroList += Fuuro(MeldType.TRIPLET, tiles, claimTarget, claimedTile)
    }

    fun minkan(
        claimedTile: MahjongTile,
        claimTarget: ClaimTarget,
        target: MahjongPlayerBase,
        onMinkan: (MahjongPlayerBase) -> Unit = {}
    ) {
        onMinkan(this)
        val tiles = sameTilesInHands(claimedTile).take(3) + claimedTile
        if (tiles.size != 4) return
        repeat(3) { hands.remove(claimedTile) }
        target.discardedTilesForDisplay.remove(claimedTile)
        fuuroList += Fuuro(MeldType.KONG, tiles, claimTarget, claimedTile)
    }

    fun ankan(tile: MahjongTile, onAnkan: (MahjongPlayerBase) -> Unit = {}) {
        onAnkan(this)
        val tiles = sameTilesInHands(tile)
        if (tiles.size != 4) return
        tiles.forEach { hands.remove(it) }
        fuuroList += Fuuro(MeldType.KONG, tiles, ClaimTarget.SELF, tile, isOpen = false)
    }

    fun kakan(tile: MahjongTile, onKakan: (MahjongPlayerBase) -> Unit = {}) {
        onKakan(this)
        val index = fuuroList.indexOfFirst { it.type == MeldType.TRIPLET && it.tiles.any { t -> t == tile } }
        if (index < 0) return
        val old = fuuroList.removeAt(index)
        val extra = hands.find { it == tile } ?: return
        hands.remove(extra)
        fuuroList.add(index, Fuuro(MeldType.KONG, old.tiles + extra, old.claimTarget, old.claimTile, isOpen = true, isAddedKong = true))
    }

    fun canPon(tile: MahjongTile): Boolean = sameTilesInHands(tile).size >= 2

    fun canMinkan(tile: MahjongTile): Boolean = sameTilesInHands(tile).size >= 3

    val canKakan: Boolean
        get() = tilesCanKakan.isNotEmpty()

    val canAnkan: Boolean
        get() = tilesCanAnkan.isNotEmpty()

    fun canChii(tile: MahjongTile): Boolean = tilePairsForChii(tile).isNotEmpty()

    private fun sameTilesInHands(tile: MahjongTile): List<MahjongTile> = hands.filter { it == tile }

    private fun tilePairsForChii(tile: MahjongTile): List<Pair<MahjongTile, MahjongTile>> {
        if (!tile.isNumbered) return emptyList()
        val result = mutableListOf<Pair<MahjongTile, MahjongTile>>()
        fun handTile(suit: TileSuit, number: Int): MahjongTile? =
            MahjongTile.basicTiles.find { it.suit == suit && it.number == number && it in hands }

        if (tile.number <= 7) {
            val a = handTile(tile.suit, tile.number + 1)
            val b = handTile(tile.suit, tile.number + 2)
            if (a != null && b != null) result += a to b
        }
        if (tile.number in 2..8) {
            val a = handTile(tile.suit, tile.number - 1)
            val b = handTile(tile.suit, tile.number + 1)
            if (a != null && b != null) result += a to b
        }
        if (tile.number >= 3) {
            val a = handTile(tile.suit, tile.number - 2)
            val b = handTile(tile.suit, tile.number - 1)
            if (a != null && b != null) result += a to b
        }
        return result.distinct()
    }

    fun getTilePairsForChii(tile: MahjongTile): List<Pair<MahjongTile, MahjongTile>> = tilePairsForChii(tile)

    fun getTilePairForPon(tile: MahjongTile): Pair<MahjongTile, MahjongTile> =
        sameTilesInHands(tile).take(2).let { it[0] to it[1] }

    val tilesCanAnkan: Set<MahjongTile>
        get() = hands.distinct().filter { sameTilesInHands(it).size == 4 }.toSet()

    val tilesCanKakan: Set<Pair<MahjongTile, ClaimTarget>>
        get() = buildSet {
            fuuroList.filter { it.type == MeldType.TRIPLET }.forEach { fuuro ->
                hands.find { it == fuuro.claimTile }?.let { add(it to fuuro.claimTarget) }
            }
        }

    val isTenpai: Boolean
        get() = machiTiles.isNotEmpty()

    val machiTiles: List<MahjongTile>
        get() = calculateMachi()

    val previewMachiTiles: List<MahjongTile>
        get() {
            val waitingSize = waitingHandSize()
            if (hands.size == waitingSize) return calculateMachi()
            if (hands.size != waitingSize + 1) return emptyList()
            return hands.distinct().flatMap { discard -> machiIfDiscard(discard) }.distinct()
        }

    fun machiIfDiscard(discard: MahjongTile): List<MahjongTile> {
        val remaining = hands.toMutableList().also { it.remove(discard) }
        return calculateMachi(remaining)
    }

    private fun waitingHandSize(): Int = 16 - fuuroList.size * 3

    private fun calculateMachi(handsForWait: List<MahjongTile> = hands): List<MahjongTile> {
        if (handsForWait.size > waitingHandSize()) return emptyList()
        return MahjongTile.basicTiles.filter { tile ->
            val used = handsForWait.count { it == tile } + fuuroList.sumOf { f -> f.tiles.count { it == tile } }
            used < 4 && TaiwaneseHandEvaluator.canWin(handsForWait + tile, fuuroList)
        }
    }

    fun calculateMachiAndTai(
        rule: MahjongRule,
        roundWind: Wind,
        seatWind: Wind,
        handsForWait: List<MahjongTile> = hands
    ): Map<MahjongTile, Int> = calculateMachi(handsForWait).associateWith { tile ->
        bestSettlement(tile, false, rule, roundWind, seatWind).tai
    }

    fun canWin(
        winningTile: MahjongTile,
        isWinningTileInHands: Boolean,
        rule: MahjongRule,
        roundWind: Wind,
        seatWind: Wind,
        isTsumo: Boolean = false
    ): Boolean {
        val settlement = bestSettlement(winningTile, isWinningTileInHands, rule, roundWind, seatWind, isTsumo)
        return settlement.tai >= rule.minimumTai.tai && TaiwaneseHandEvaluator.canWin(
            (hands + if (isWinningTileInHands) emptyList() else listOf(winningTile)),
            fuuroList
        )
    }

    fun calcTaiwanSettlementForWin(
        winningTile: MahjongTile,
        isWinningTileInHands: Boolean,
        rule: MahjongRule,
        roundWind: Wind,
        seatWind: Wind,
        isTsumo: Boolean
    ): TaiwanSettlement = bestSettlement(winningTile, isWinningTileInHands, rule, roundWind, seatWind, isTsumo)

    private fun bestSettlement(
        winningTile: MahjongTile,
        isWinningTileInHands: Boolean,
        rule: MahjongRule,
        roundWind: Wind,
        seatWind: Wind,
        isTsumo: Boolean = false
    ): TaiwanSettlement {
        val combined = hands.toMutableList().also { if (!isWinningTileInHands) it += winningTile }
        val shapes = TaiwaneseHandEvaluator.findWinningShapes(combined, fuuroList)
        if (shapes.isEmpty()) return TaiwanSettlement.NO_TAI
        return shapes.map { shape ->
            TaiwaneseScorer.score(
                displayName = displayName,
                uuid = uuid,
                isRealPlayer = isRealPlayer,
                botCode = if (this is MahjongBot) this.botTileCode else MahjongTile.UNKNOWN.code,
                concealedTiles = combined,
                fuuroList = fuuroList,
                flowers = flowerTiles,
                shape = shape,
                winningTile = winningTile,
                isTsumo = isTsumo,
                seatWind = seatWind,
                roundWind = roundWind,
                pointsPerTai = rule.pointsPerTai
            )
        }.maxWithOrNull(compareBy<TaiwanSettlement> { it.tai }.thenBy { it.score }) ?: TaiwanSettlement.NO_TAI
    }

    open suspend fun askToDiscardTile(
        timeoutTile: MahjongTile,
        cannotDiscardTiles: List<MahjongTile>,
        skippable: Boolean
    ): MahjongTile = hands.lastOrNull() ?: timeoutTile

    open suspend fun askToChii(
        tile: MahjongTile,
        tilePairs: List<Pair<MahjongTile, MahjongTile>>,
        target: ClaimTarget
    ): Pair<MahjongTile, MahjongTile>? = null

    open suspend fun askToPonOrChii(
        tile: MahjongTile,
        tilePairsForChii: List<Pair<MahjongTile, MahjongTile>>,
        tilePairForPon: Pair<MahjongTile, MahjongTile>,
        target: ClaimTarget
    ): Pair<MahjongTile, MahjongTile>? = null

    open suspend fun askToPon(
        tile: MahjongTile,
        tilePairForPon: Pair<MahjongTile, MahjongTile>,
        target: ClaimTarget
    ): Boolean = true

    open suspend fun askToAnkanOrKakan(
        canAnkanTiles: Set<MahjongTile>,
        canKakanTiles: Set<Pair<MahjongTile, ClaimTarget>>,
        rule: MahjongRule
    ): MahjongTile? = null

    open suspend fun askToMinkanOrPon(
        tile: MahjongTile,
        target: ClaimTarget,
        rule: MahjongRule
    ): MahjongGameBehavior = MahjongGameBehavior.MINKAN

    open suspend fun askToTsumo(): Boolean = true

    open suspend fun askToRon(tile: MahjongTile, target: ClaimTarget): Boolean = true

    fun drawTile(tile: MahjongTile) {
        hands += tile
    }

    fun discardTile(tile: MahjongTile): MahjongTile? = hands.findLast { it == tile }?.also {
        hands.remove(it)
        discardedTiles += it
        discardedTilesForDisplay += it
    }
}
