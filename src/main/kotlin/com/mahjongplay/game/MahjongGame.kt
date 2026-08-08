package com.mahjongplay.game

import com.mahjongplay.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

enum class GameStatus { WAITING, PLAYING }

interface GameEventListener {
    fun onGameStart(game: MahjongGame) {}
    fun onRoundStart(game: MahjongGame, round: MahjongRound) {}
    fun onTurnChanged(game: MahjongGame, player: MahjongPlayerBase) {}
    fun onTileDrawn(player: MahjongPlayerBase, tile: MahjongTile) {}
    fun onTileDiscarded(player: MahjongPlayerBase, tile: MahjongTile) {}
    fun onChii(player: MahjongPlayerBase, claimedTile: MahjongTile, from: MahjongPlayerBase) {}
    fun onPon(player: MahjongPlayerBase, claimedTile: MahjongTile, from: MahjongPlayerBase) {}
    fun onKan(player: MahjongPlayerBase, tile: MahjongTile, kanType: String, from: MahjongPlayerBase? = null) {}
    fun onTsumo(player: MahjongPlayerBase, tile: MahjongTile, settlement: TaiwanSettlement) {}
    fun onRon(winners: List<MahjongPlayerBase>, loser: MahjongPlayerBase, tile: MahjongTile, settlements: List<TaiwanSettlement>) {}
    fun onDraw(draw: ExhaustiveDraw, settlement: ScoreSettlement) {}
    fun onScoreSettlement(settlement: ScoreSettlement) {}
    fun onGameEnd(game: MahjongGame, scoreList: List<ScoreItem>) {}
    fun onHandsUpdated(player: MahjongPlayerBase) {}
}

/**
 * 台灣麻將牌局流程：四人、16 張手牌、花牌即時補牌、五組面子加將牌。
 *
 * 規則核心遵循常見台灣 16 張流程：
 * - 莊家 17 張、閒家 16 張。
 * - 牌牆保留鐵八墩（16 張）；補花／槓牌由尾端補牌並同步向前移動殘牌界線。
 * - 胡 > 槓／碰 > 吃；吃只限上家。
 * - 過水期間不得榮和或自摸，直到自己合法打出一張牌（或加槓）解除。
 * - 流局時莊家續莊。
 */
class MahjongGame(
    val tableId: UUID = UUID.randomUUID(),
    var rule: MahjongRule = MahjongRule(),
    var listener: GameEventListener? = null
) {
    var status = GameStatus.WAITING
        private set

    @Volatile
    var currentPlayer: MahjongPlayerBase? = null
        private set

    private val isPlaying: Boolean get() = status == GameStatus.PLAYING
    private val playerCount: Int get() = 4

    val players = ArrayList<MahjongPlayerBase>(4)
    val realPlayers: List<MahjongPlayer> get() = players.filterIsInstance<MahjongPlayer>()

    var seat = mutableListOf<MahjongPlayerBase>()
    var round: MahjongRound = MahjongRound()

    private var gameJob: Job? = null
    private var liveWall: MutableList<MahjongTile> = mutableListOf()
    private var supplementWall: MutableList<MahjongTile> = mutableListOf()
    private var allDiscards: MutableList<MahjongTile> = mutableListOf()
    private var kanCount: Int = 0
    private var roundHadClaim: Boolean = false
    private var dealerOpeningTile: MahjongTile? = null

    /** HUD 顯示仍可正常摸取的牌數，不把鐵八墩算進去。 */
    val wallSize: Int
        get() = liveWall.size

    private val seatOrderFromDealer: List<MahjongPlayerBase>
        get() = List(playerCount) {
            seat[(playerCount - ((round.round + it) % playerCount)) % playerCount]
        }

    private fun seatWindOf(player: MahjongPlayerBase): Wind {
        val index = seatOrderFromDealer.indexOf(player).coerceIn(0, Wind.entries.lastIndex)
        return Wind.entries[index]
    }

    init {
        rule.playerCount = 4
    }

    fun addBot(name: String = "Bot") {
        if (players.size < playerCount) players += MahjongBot(displayName = name)
    }

    fun join(playerUUID: String, playerName: String): Boolean {
        if (status != GameStatus.WAITING || players.size >= playerCount) return false
        if (players.any { it.uuid == playerUUID }) return false
        players += MahjongPlayer(uuid = playerUUID, displayName = playerName)
        return true
    }

    fun leave(playerUUID: String) {
        if (isPlaying) {
            end()
            return
        }
        val isHost = players.firstOrNull()?.uuid == playerUUID
        if (isHost) {
            val newHost = players.find { it.isRealPlayer && it.uuid != playerUUID }
            if (newHost != null) {
                players.remove(newHost)
                players.add(0, newHost)
                newHost.ready = true
            } else {
                players.removeAll { it is MahjongBot }
            }
        }
        players.removeIf { it.uuid == playerUUID }
    }

    fun readyOrNot(playerUUID: String, ready: Boolean) {
        players.find { it.uuid == playerUUID }?.ready = ready
    }

    fun kick(index: Int) {
        if (index in players.indices) players.removeAt(index)
    }

    fun changeRules(newRule: MahjongRule) {
        newRule.playerCount = 4
        rule = newRule
        players.forEachIndexed { index, player ->
            if (index != 0 && player is MahjongPlayer) player.ready = false
        }
    }

    fun start() {
        if (players.size != playerCount || !players.all { it.ready }) return
        status = GameStatus.PLAYING
        currentPlayer = null
        seat = players.toMutableList().apply { shuffle() }
        round = rule.length.getStartingRound()
        players.forEach {
            it.points = rule.startingPoints
            it.basicThinkingTime = rule.thinkingTime.base
            it.extraThinkingTime = rule.thinkingTime.extra
        }
        listener?.onGameStart(this)
        gameJob = CoroutineScope(Dispatchers.Default).launch {
            delay(500)
            startRound()
        }
    }

    fun end() {
        status = GameStatus.WAITING
        currentPlayer = null
        gameJob?.cancel()
        val scoreList = players.map {
            ScoreItem(it.displayName, it.uuid, it.isRealPlayer, scoreOrigin = it.points, scoreChange = 0)
        }
        listener?.onGameEnd(this, scoreList)
        seat.clear()
        clearRoundState()
        round = MahjongRound()
    }

    private fun clearRoundState() {
        players.forEach {
            it.hands.clear()
            it.fuuroList.clear()
            it.flowerTiles.clear()
            it.discardedTiles.clear()
            it.discardedTilesForDisplay.clear()
            it.clearPassedWin()
        }
        liveWall.clear()
        supplementWall.clear()
        allDiscards.clear()
        kanCount = 0
        roundHadClaim = false
        dealerOpeningTile = null
    }

    private fun generateWall() {
        val sourceWall = if (rule.flowersEnabled) MahjongTile.taiwaneseWall else MahjongTile.normalWall
        val shuffled = sourceWall.shuffled()
        supplementWall = shuffled.takeLast(DEAD_WALL_SIZE).toMutableList()
        liveWall = shuffled.dropLast(DEAD_WALL_SIZE).toMutableList()
    }

    private suspend fun delayForBot(player: MahjongPlayerBase) {
        if (player is MahjongBot) {
            val delayMs = rule.botResponseDelayMs.coerceIn(0L, 5000L)
            if (delayMs > 0L) delay(delayMs)
        }
    }

    /**
     * 從牌尾補牌。每補一張就把活牌牆最後一張移入殘牌區，
     * 讓「鐵八墩」界線維持 16 張。
     */
    private fun drawFromSupplement(): MahjongTile? {
        if (supplementWall.isEmpty()) return null
        val tile = supplementWall.removeLast()
        if (liveWall.isNotEmpty()) {
            supplementWall.add(0, liveWall.removeLast())
        }
        return tile
    }

    private fun drawSupplementFor(player: MahjongPlayerBase): MahjongTile? {
        while (supplementWall.isNotEmpty()) {
            val tile = drawFromSupplement() ?: return null
            if (tile.isFlower) {
                player.flowerTiles += tile
                listener?.onHandsUpdated(player)
                continue
            }
            player.drawTile(tile)
            listener?.onTileDrawn(player, tile)
            listener?.onHandsUpdated(player)
            return tile
        }
        return null
    }

    private data class DrawResult(
        val tile: MahjongTile,
        val wasFlowerReplacement: Boolean,
    )

    private fun drawLiveFor(player: MahjongPlayerBase): DrawResult? {
        while (liveWall.isNotEmpty()) {
            val tile = liveWall.removeFirst()
            if (tile.isFlower) {
                player.flowerTiles += tile
                listener?.onHandsUpdated(player)
                val replacement = drawSupplementFor(player) ?: return null
                return DrawResult(replacement, wasFlowerReplacement = true)
            }
            player.drawTile(tile)
            listener?.onTileDrawn(player, tile)
            listener?.onHandsUpdated(player)
            return DrawResult(tile, wasFlowerReplacement = false)
        }
        return null
    }

    /** 四家各 16 張，莊家再拿第 17 張。 */
    private suspend fun dealHands() {
        val order = seatOrderFromDealer
        repeat(4) {
            order.forEach { player ->
                repeat(4) { drawLiveFor(player) }
            }
        }
        dealerOpeningTile = drawLiveFor(order[0])?.tile
    }

    private fun sortHands(player: MahjongPlayerBase, lastTile: MahjongTile? = null) {
        if (!player.autoArrangeHands) return
        if (lastTile != null && player.hands.isNotEmpty()) {
            val index = player.hands.indexOfLast { it == lastTile }
            if (index >= 0) {
                val drawn = player.hands.removeAt(index)
                player.hands.sortBy { it.sortOrder }
                player.hands += drawn
            } else {
                player.hands.sortBy { it.sortOrder }
            }
        } else {
            player.hands.sortBy { it.sortOrder }
        }
    }

    private fun MahjongPlayerBase.asClaimTarget(target: MahjongPlayerBase): ClaimTarget {
        val self = seat.indexOf(this)
        val targetIndex = seat.indexOf(target)
        return when ((targetIndex - self + playerCount) % playerCount) {
            playerCount - 1 -> ClaimTarget.RIGHT
            1 -> ClaimTarget.LEFT
            else -> ClaimTarget.ACROSS
        }
    }

    private fun claimTargetBySeatDiff(claimerSeat: Int, discarderSeat: Int): ClaimTarget =
        when ((discarderSeat - claimerSeat + playerCount) % playerCount) {
            playerCount - 1 -> ClaimTarget.RIGHT
            1 -> ClaimTarget.LEFT
            else -> ClaimTarget.ACROSS
        }

    private fun nextSeatIndex(seatIndex: Int): Int =
        (seatIndex - 1 + playerCount) % playerCount

    private suspend fun startRound() {
        if (!isPlaying) return
        clearRoundState()
        listener?.onRoundStart(this, round)
        generateWall()
        dealHands()
        players.forEach { sortHands(it); listener?.onHandsUpdated(it) }

        val dealer = seatOrderFromDealer[0]
        var dealerRemains = false
        var roundDraw: ExhaustiveDraw? = null
        delay(500)

        var nextPlayer: MahjongPlayerBase = dealer
        var needDraw = true
        val cannotDiscard = mutableSetOf<MahjongTile>()

        roundLoop@ while (isPlaying) {
            val player = nextPlayer
            currentPlayer = player
            listener?.onTurnChanged(this, player)
            val isDealer = player == dealer
            var timeoutTile = player.hands.lastOrNull() ?: break@roundLoop
            var drewTile = false
            var lastDrawWasLastLiveTile = false

            if (needDraw) {
                player.justDrewTile = true
                val initialDealerHand = isDealer && player.discardedTiles.isEmpty() && allDiscards.isEmpty()

                val drawResult = if (initialDealerHand) {
                    DrawResult(dealerOpeningTile ?: player.hands.last(), wasFlowerReplacement = false)
                } else {
                    drawLiveFor(player) ?: run {
                        roundDraw = ExhaustiveDraw.NORMAL
                        break@roundLoop
                    }
                }

                val lastTile = drawResult.tile
                sortHands(player, lastTile)
                timeoutTile = lastTile
                drewTile = true
                lastDrawWasLastLiveTile = !initialDealerHand && !drawResult.wasFlowerReplacement && liveWall.isEmpty()

                val normalTsumoContext = winContextFor(
                    player = player,
                    isTsumo = true,
                    isLastLiveTile = lastDrawWasLastLiveTile,
                    isFlowerReplacement = drawResult.wasFlowerReplacement,
                )

                delayForBot(player)
                if (
                    player.canWin(
                        lastTile,
                        true,
                        rule,
                        round.wind,
                        seatWindOf(player),
                        isTsumo = true,
                        context = normalTsumoContext,
                    ) && player.askToTsumo()
                ) {
                    player.tsumo(lastTile, normalTsumoContext)
                    dealerRemains = isDealer
                    break@roundLoop
                }

                var replacement: MahjongTile? = null
                while ((player.canKakan || player.canAnkan) && supplementWall.isNotEmpty()) {
                    delayForBot(player)
                    val kanTile = player.askToAnkanOrKakan(player.tilesCanAnkan, player.tilesCanKakan, rule) ?: break
                    val isAnkan = kanTile in player.tilesCanAnkan

                    if (!isAnkan) {
                        val robbers = askRonList(kanTile, player, isRobbingKong = true)
                        if (robbers.isNotEmpty()) {
                            robbers.ron(target = player, tile = kanTile, isRobbingKong = true)
                            dealerRemains = dealer in robbers
                            break@roundLoop
                        }
                        // 加槓視為打出一張非胡之牌，可解除過水。
                        player.clearPassedWin()
                    }

                    if (isAnkan) player.ankan(kanTile) else player.kakan(kanTile)
                    roundHadClaim = true
                    kanCount++
                    listener?.onKan(player, kanTile, if (isAnkan) "ankan" else "kakan")
                    listener?.onHandsUpdated(player)
                    replacement = drawSupplementFor(player) ?: break
                    sortHands(player, replacement)
                    val kongTsumoContext = winContextFor(
                        player = player,
                        isTsumo = true,
                        isKongReplacement = true,
                    )
                    if (
                        player.canWin(
                            replacement,
                            true,
                            rule,
                            round.wind,
                            seatWindOf(player),
                            isTsumo = true,
                            context = kongTsumoContext,
                        ) && player.askToTsumo()
                    ) {
                        player.tsumo(replacement, kongTsumoContext)
                        dealerRemains = isDealer
                        break@roundLoop
                    }
                }
                timeoutTile = replacement ?: lastTile
            } else {
                player.justDrewTile = false
                needDraw = true
                drewTile = false
            }

            if (!drewTile) delayForBot(player)
            val discarded = player.askToDiscardTile(timeoutTile, cannotDiscard.toList(), skippable = false)
            val actualDiscard = player.discardTile(discarded)
                ?: player.discardTile(player.hands.lastOrNull { it !in cannotDiscard } ?: break@roundLoop)
                ?: break@roundLoop

            player.clearPassedWin()
            allDiscards += actualDiscard
            listener?.onTileDiscarded(player, actualDiscard)
            listener?.onHandsUpdated(player)
            cannotDiscard.clear()

            val ronList = askRonList(actualDiscard, player)
            if (ronList.isNotEmpty()) {
                ronList.ron(target = player, tile = actualDiscard)
                dealerRemains = dealer in ronList
                break@roundLoop
            }

            // 海底最後一張打出後只允許胡牌，不再展開吃／碰／槓。
            if (lastDrawWasLastLiveTile || liveWall.isEmpty()) {
                roundDraw = ExhaustiveDraw.NORMAL
                break@roundLoop
            }

            val discarderSeat = seat.indexOf(player)
            val nextPlayerSeat = nextSeatIndex(discarderSeat)
            var claimed = false

            // 明槓可對任何一家打出的第四張進行；若玩家選碰，同輪直接完成碰。
            val minkanList = players
                .filter { it != player && it.canMinkan(actualDiscard) }
                .sortedBy { (discarderSeat - seat.indexOf(it) + playerCount) % playerCount }

            if (minkanList.isNotEmpty() && supplementWall.isNotEmpty()) {
                for (claimant in minkanList) {
                    delayForBot(claimant)
                    when (claimant.askToMinkanOrPon(actualDiscard, claimant.asClaimTarget(player), rule)) {
                        MahjongGameBehavior.MINKAN -> {
                            claimant.minkan(
                                actualDiscard,
                                claimTargetBySeatDiff(seat.indexOf(claimant), discarderSeat),
                                player,
                            )
                            roundHadClaim = true
                            kanCount++
                            listener?.onKan(claimant, actualDiscard, "minkan", player)
                            listener?.onHandsUpdated(claimant)

                            val replacement = drawSupplementFor(claimant)
                            if (replacement == null) {
                                roundDraw = ExhaustiveDraw.NORMAL
                                break@roundLoop
                            }
                            sortHands(claimant, replacement)
                            val kongTsumoContext = winContextFor(
                                player = claimant,
                                isTsumo = true,
                                isKongReplacement = true,
                            )
                            if (
                                claimant.canWin(
                                    replacement,
                                    true,
                                    rule,
                                    round.wind,
                                    seatWindOf(claimant),
                                    isTsumo = true,
                                    context = kongTsumoContext,
                                ) && claimant.askToTsumo()
                            ) {
                                claimant.tsumo(replacement, kongTsumoContext)
                                dealerRemains = claimant == dealer
                                break@roundLoop
                            }
                            cannotDiscard += actualDiscard
                            nextPlayer = claimant
                            needDraw = false
                            claimed = true
                        }

                        MahjongGameBehavior.PON -> {
                            claimant.pon(
                                actualDiscard,
                                claimTargetBySeatDiff(seat.indexOf(claimant), discarderSeat),
                                player,
                            )
                            roundHadClaim = true
                            listener?.onPon(claimant, actualDiscard, player)
                            listener?.onHandsUpdated(claimant)
                            cannotDiscard += actualDiscard
                            nextPlayer = claimant
                            needDraw = false
                            claimed = true
                        }

                        else -> Unit
                    }
                    if (claimed) break
                }
            }

            if (!claimed) {
                val ponList = players
                    .filter { it != player && it.canPon(actualDiscard) }
                    .sortedBy { (discarderSeat - seat.indexOf(it) + playerCount) % playerCount }
                for (claimant in ponList) {
                    delayForBot(claimant)
                    if (claimant.askToPon(actualDiscard, claimant.getTilePairForPon(actualDiscard), claimant.asClaimTarget(player))) {
                        claimant.pon(
                            actualDiscard,
                            claimTargetBySeatDiff(seat.indexOf(claimant), discarderSeat),
                            player,
                        )
                        roundHadClaim = true
                        listener?.onPon(claimant, actualDiscard, player)
                        listener?.onHandsUpdated(claimant)
                        cannotDiscard += actualDiscard
                        nextPlayer = claimant
                        needDraw = false
                        claimed = true
                        break
                    }
                }
            }

            if (!claimed) {
                val next = seat[nextPlayerSeat]
                if (next.canChii(actualDiscard)) {
                    delayForBot(next)
                    val pairs = next.getTilePairsForChii(actualDiscard)
                    val chosen = next.askToChii(actualDiscard, pairs, next.asClaimTarget(player))
                    if (chosen != null) {
                        next.chii(actualDiscard, chosen, player)
                        roundHadClaim = true
                        listener?.onChii(next, actualDiscard, player)
                        listener?.onHandsUpdated(next)
                        cannotDiscard += actualDiscard
                        nextPlayer = next
                        needDraw = false
                        claimed = true
                    }
                }
            }

            if (!claimed) nextPlayer = seat[nextPlayerSeat]
            if (!drewTile) delay(MIN_WAITING_TIME)
        }

        if (roundDraw != null) {
            // 台麻荒局：莊家無條件續莊並增加連莊次數。
            dealerRemains = true
            roundDraw(roundDraw!!)
        }

        delay(1200)
        if (!isPlaying) return

        if (!round.isAllLast(rule) || dealerRemains) {
            if (dealerRemains) {
                round.honba++
            } else {
                round.nextRound(playerCount)
            }
            startRound()
        } else {
            end()
        }
    }

    private fun winContextFor(
        player: MahjongPlayerBase,
        isTsumo: Boolean,
        isLastLiveTile: Boolean = false,
        isKongReplacement: Boolean = false,
        isFlowerReplacement: Boolean = false,
        isRobbingKong: Boolean = false,
    ): TaiwanWinContext {
        val dealer = seatOrderFromDealer.firstOrNull()
        val isDealer = player == dealer
        val firstUninterruptedTurn = player.discardedTiles.isEmpty() && !roundHadClaim
        return TaiwanWinContext(
            dealerRepeat = if (isDealer) round.honba else 0,
            isLastLiveTile = isLastLiveTile,
            isKongReplacement = isKongReplacement,
            isFlowerReplacement = isFlowerReplacement,
            isRobbingKong = isRobbingKong,
            isHeavenlyHand = isTsumo && isDealer && allDiscards.isEmpty() && !roundHadClaim && !isKongReplacement && !isFlowerReplacement,
            isEarthlyHand = isTsumo && !isDealer && firstUninterruptedTurn && !isKongReplacement && !isFlowerReplacement,
            isHumanHand = !isTsumo && !isDealer && allDiscards.size == 1 && player.discardedTiles.isEmpty() && !roundHadClaim && !isRobbingKong,
        )
    }

    /** 莊家作為付款者時，莊家 1 台加上連 n 拉 n 的 2n 台。 */
    private fun dealerLiabilityTai(player: MahjongPlayerBase): Int =
        if (player == seatOrderFromDealer.firstOrNull()) 1 + round.honba * 2 else 0

    private suspend fun askRonList(
        tile: MahjongTile,
        target: MahjongPlayerBase,
        isRobbingKong: Boolean = false,
    ): List<MahjongPlayerBase> {
        val targetSeat = seat.indexOf(target)
        val candidates = players
            .filter { candidate ->
                if (candidate == target) return@filter false
                val context = winContextFor(
                    player = candidate,
                    isTsumo = false,
                    isLastLiveTile = !isRobbingKong && liveWall.isEmpty(),
                    isRobbingKong = isRobbingKong,
                )
                candidate.canWin(
                    tile,
                    false,
                    rule,
                    round.wind,
                    seatWindOf(candidate),
                    isTsumo = false,
                    context = context,
                )
            }
            .sortedBy { (targetSeat - seat.indexOf(it) + playerCount) % playerCount }

        return buildList {
            for (candidate in candidates) {
                delayForBot(candidate)
                if (candidate.askToRon(tile, candidate.asClaimTarget(target))) {
                    add(candidate)
                } else {
                    candidate.markPassedWin()
                }
            }
        }
    }

    private suspend fun List<MahjongPlayerBase>.ron(
        target: MahjongPlayerBase,
        tile: MahjongTile,
        isRobbingKong: Boolean = false,
    ) {
        val settlements = map { winner ->
            val context = winContextFor(
                player = winner,
                isTsumo = false,
                isLastLiveTile = !isRobbingKong && liveWall.isEmpty(),
                isRobbingKong = isRobbingKong,
            )
            winner.calcTaiwanSettlementForWin(
                tile,
                false,
                rule,
                round.wind,
                seatWindOf(winner),
                isTsumo = false,
                context = context,
            )
        }
        val dealerPayerTai = dealerLiabilityTai(target)
        val payment = settlements.map { settlement ->
            settlement.score + dealerPayerTai * rule.pointsPerTai
        }
        val original = players.associateWith { it.points }
        forEachIndexed { index, winner ->
            winner.points += payment[index]
            target.points -= payment[index]
        }
        val scoreList = players.map { player ->
            ScoreItem(
                player.displayName,
                player.uuid,
                player.isRealPlayer,
                scoreOrigin = original[player] ?: player.points,
                scoreChange = player.points - (original[player] ?: player.points)
            )
        }
        listener?.onRon(this, target, tile, settlements)
        listener?.onScoreSettlement(ScoreSettlement("ron", scoreList))
        delay(SCORE_SETTLE_MS)
    }

    private suspend fun MahjongPlayerBase.tsumo(tile: MahjongTile, context: TaiwanWinContext) {
        val settlement = calcTaiwanSettlementForWin(
            tile,
            true,
            rule,
            round.wind,
            seatWindOf(this),
            isTsumo = true,
            context = context,
        )
        val original = players.associateWith { it.points }
        var gain = 0
        players.filter { it != this }.forEach { loser ->
            val payment = settlement.score + dealerLiabilityTai(loser) * rule.pointsPerTai
            loser.points -= payment
            gain += payment
        }
        points += gain
        val scoreList = players.map { player ->
            ScoreItem(
                player.displayName,
                player.uuid,
                player.isRealPlayer,
                scoreOrigin = original[player] ?: player.points,
                scoreChange = player.points - (original[player] ?: player.points)
            )
        }
        listener?.onTsumo(this, tile, settlement)
        listener?.onScoreSettlement(ScoreSettlement("tsumo", scoreList))
        delay(SCORE_SETTLE_MS)
    }

    private suspend fun roundDraw(draw: ExhaustiveDraw) {
        val scoreList = players.map { player ->
            ScoreItem(player.displayName, player.uuid, player.isRealPlayer, scoreOrigin = player.points, scoreChange = 0)
        }
        val settlement = ScoreSettlement(draw.name.lowercase(), scoreList)
        listener?.onDraw(draw, settlement)
        listener?.onScoreSettlement(settlement)
        delay(SCORE_SETTLE_MS)
    }

    fun getMachiAndTai(player: MahjongPlayerBase): Map<MahjongTile, Int> {
        if (liveWall.isEmpty()) return emptyMap()
        val seatWind = seatWindOf(player)
        val context = TaiwanWinContext(
            dealerRepeat = if (seatWind == Wind.EAST) round.honba else 0,
        )
        return player.calculateMachiAndTai(rule, round.wind, seatWind, context = context)
    }

    companion object {
        const val MIN_WAITING_TIME = 1200L
        private const val SCORE_SETTLE_MS = 2500L
        private const val DEAD_WALL_SIZE = 16
    }
}
