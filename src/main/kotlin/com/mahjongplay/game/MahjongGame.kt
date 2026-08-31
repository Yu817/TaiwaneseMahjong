package com.mahjongplay.game

import com.mahjongplay.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random
import com.mahjongplay.economy.EconomyObligation
import com.mahjongplay.economy.RealMoneyPolicy
import kotlin.coroutines.CoroutineContext

enum class GameStatus { WAITING, PLAYING }

interface GameEventListener {
    fun onGameStart(game: MahjongGame) {}
    fun onRoundStart(game: MahjongGame, round: MahjongRound) {}
    fun onWallInitialized(event: WallInitializedEvent) {}
    fun onSeatWindDrawStarted(event: SeatWindDrawStartEvent) {}
    fun onSeatWindTurnPrompt(event: SeatWindTurnPromptEvent) {}
    fun onSeatWindTilePicked(event: SeatWindTilePickedEvent) {}
    fun onSeatWindDrawCompleted(event: SeatWindDrawCompleteEvent) {}
    fun onOpeningDiceStarted(event: OpeningDiceEvent) {}
    fun onOpeningDiceCompleted(event: OpeningDiceEvent) {}
    fun onTurnChanged(game: MahjongGame, player: MahjongPlayerBase) {}
    fun onTileDrawStarted(event: TileDrawEvent) {}
    fun onTileDrawCompleted(event: TileDrawEvent) {}
    fun onTileDrawn(player: MahjongPlayerBase, tile: MahjongTile) {}
    fun onFlowerDrawn(player: MahjongPlayerBase, flower: MahjongTile) {}
    fun onTileDiscarded(player: MahjongPlayerBase, tile: MahjongTile) {}
    fun onChii(player: MahjongPlayerBase, claimedTile: MahjongTile, from: MahjongPlayerBase) {}
    fun onPon(player: MahjongPlayerBase, claimedTile: MahjongTile, from: MahjongPlayerBase) {}
    fun onKan(player: MahjongPlayerBase, tile: MahjongTile, kanType: String, from: MahjongPlayerBase? = null) {}
    fun onTsumo(player: MahjongPlayerBase, tile: MahjongTile, settlement: TaiwanSettlement) {}
    fun onRon(winners: List<MahjongPlayerBase>, loser: MahjongPlayerBase, tile: MahjongTile, settlements: List<TaiwanSettlement>) {}
    fun onDraw(draw: ExhaustiveDraw, settlement: ScoreSettlement) {}
    fun onScoreSettlement(settlement: ScoreSettlement) {}
    fun onEconomyObligation(obligation: EconomyObligation) {}
    fun onGameEnd(game: MahjongGame, scoreList: List<ScoreItem>) {}
    fun onHandsUpdated(player: MahjongPlayerBase) {}
    fun shouldTerminateGame(game: MahjongGame): Boolean = false
}

/**
 * 台灣麻將牌局流程：四人、16 張手牌、花牌即時補牌、五組面子加將牌。
 *
 * 規則核心遵循常見台灣 16 張流程：
 * - 莊家 17 張、閒家 16 張。
 * - 牌牆保留鐵八墩（16 張）；補花／槓牌由尾端補牌並同步向前移動殘牌界線。
 * - 胡 > 槓／碰 > 吃；吃只限上家。
 * - 過水期間不得胡牌或自摸，直到自己合法打出一張牌（或加槓）解除。
 * - 流局時莊家續莊。
 */
class MahjongGame(
    val tableId: UUID = UUID.randomUUID(),
    var rule: MahjongRule = MahjongRule(),
    var listener: GameEventListener? = null,
    private val gameCoroutineContext: CoroutineContext = Dispatchers.Default,
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
    private var dealerOpeningDraw: DrawResult? = null
    private var dealerOpeningFlowerIndex: Int? = null
    private var dealerHadInitialFlowerReplacement: Boolean = false
    private var drawSequence: Long = 0L
    private var openingDice: OpeningDiceEvent? = null

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

    fun addBot(name: String = "Bot", difficulty: BotDifficulty = BotDifficulty.MEDIUM) {
        if (players.size < playerCount) {
            val bot = MahjongBot(displayName = name, difficulty = difficulty)
            bot.game = this
            players += bot
        }
    }

    fun join(playerUUID: String, playerName: String): Boolean {
        if (status != GameStatus.WAITING || players.size >= playerCount) return false
        if (players.any { it.uuid == playerUUID }) return false
        val p = MahjongPlayer(uuid = playerUUID, displayName = playerName)
        p.game = this
        players += p
        return true
    }

    fun leave(playerUUID: String) {
        if (isPlaying) {
            val player = players.find { it.uuid == playerUUID }
            if (player is MahjongPlayer) {
                player.activateBotTakeover(rule.defaultBotDifficulty)
                if (players.none { it is MahjongPlayer && !it.isBotTakeover }) {
                    end()
                }
            }
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
        if (status != GameStatus.WAITING) return
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
        if (status != GameStatus.WAITING) return
        if (players.size != playerCount || !players.all { it.ready }) return
        status = GameStatus.PLAYING
        currentPlayer = null
        seat = players.toMutableList()
        round = rule.length.getStartingRound()
        players.forEach {
            it.game = this
            it.points = rule.startingPoints
            it.basicThinkingTime = rule.thinkingTime.base
            it.extraThinkingTime = rule.thinkingTime.extra
        }
        listener?.onGameStart(this)
        gameJob = CoroutineScope(gameCoroutineContext).launch {
            if (rule.seatWindDrawEnabled) {
                performSeatWindDraw()
            } else {
                seat.shuffle()
            }
            delay(500)
            startRound()
        }
    }

    fun end() {
        status = GameStatus.WAITING
        currentPlayer = null
        players.filterIsInstance<MahjongPlayer>().forEach { it.cancelPendingActions() }
        gameJob?.cancel()
        val scoreList = players.map {
            ScoreItem(it.displayName, it.uuid, it.isRealPlayer, scoreOrigin = it.points, scoreChange = 0)
        }
        // 對局結束後重設所有玩家準備狀態，禁止自動準備
        players.forEach { it.ready = false }
        listener?.onGameEnd(this, scoreList)
        seat.clear()
        clearRoundState()
        round = MahjongRound()
    }

    fun cancelGame() {
        status = GameStatus.WAITING
        currentPlayer = null
        players.filterIsInstance<MahjongPlayer>().forEach { it.cancelPendingActions() }
        gameJob?.cancel()
        players.forEach { it.ready = false }
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
        dealerOpeningDraw = null
        dealerOpeningFlowerIndex = null
        dealerHadInitialFlowerReplacement = false
        drawSequence = 0L
        openingDice = null
    }

    private fun generateWall(opening: OpeningDiceEvent) {
        val sourceWall = if (rule.flowersEnabled) MahjongTile.taiwaneseWall else MahjongTile.normalWall
        val shuffled = sourceWall.shuffled()
        supplementWall = shuffled.takeLast(DEAD_WALL_SIZE).toMutableList()
        liveWall = shuffled.dropLast(DEAD_WALL_SIZE).toMutableList()
        listener?.onWallInitialized(
            WallInitializedEvent(
                tileCount = shuffled.size,
                liveWallSize = liveWall.size,
                supplementWallSize = supplementWall.size,
                openingDice = opening,
            )
        )
    }

    private suspend fun delayForBot(player: MahjongPlayerBase) {
        if (player is MahjongBot || (player is MahjongPlayer && player.isBotTakeover)) {
            val delayMs = rule.botResponseDelayMs.coerceIn(0L, 5000L)
            if (delayMs > 0L) delay(delayMs)
        }
    }

    /**
     * 從牌尾補牌。每補一張就把活牌牆最後一張移入殘牌區，
     * 讓「鐵八墩」界線維持 16 張。
     */
    private data class SupplementDraw(
        val tile: MahjongTile,
        val refilledFromLiveWall: Boolean,
    )

    private fun drawFromSupplement(): SupplementDraw? {
        // A supplement draw consumes the current dead-wall tail. While live
        // tiles remain, move the live-wall tail into the dead wall so the
        // iron-eight boundary stays sixteen tiles deep. After the live wall
        // is exhausted, remaining dead-wall tiles are still legal supplements
        // but cannot be refilled.
        if (supplementWall.isEmpty()) return null
        val tile = supplementWall.removeLast()
        val refilled = liveWall.isNotEmpty()
        if (refilled) supplementWall.add(0, liveWall.removeLast())
        return SupplementDraw(tile, refilledFromLiveWall = refilled)
    }

    internal data class DrawResult(
        val tile: MahjongTile,
        val wasFlowerReplacement: Boolean,
    )

    private fun newDrawEvent(
        player: MahjongPlayerBase,
        tile: MahjongTile,
        source: DrawSource,
        reason: DrawReason,
        handSizeBeforeDraw: Int,
        supplementRefilledFromLiveWall: Boolean,
    ): TileDrawEvent = TileDrawEvent(
        sequence = ++drawSequence,
        playerUUID = player.uuid,
        tile = tile,
        source = source,
        reason = reason,
        handSizeBeforeDraw = handSizeBeforeDraw,
        liveWallSize = liveWall.size,
        supplementWallSize = supplementWall.size,
        supplementRefilledFromLiveWall = supplementRefilledFromLiveWall,
        animationMillis = when (reason) {
            DrawReason.INITIAL_DEAL -> rule.initialDealAnimationMs
            else -> rule.drawAnimationMs
        }.coerceIn(0L, MahjongRule.MAX_DRAW_ANIMATION_MS),
    )

    private suspend fun animateDraw(event: TileDrawEvent) {
        listener?.onTileDrawStarted(event)
        if (event.animationMillis > 0L) delay(event.animationMillis)
    }

    private fun completeDraw(player: MahjongPlayerBase, event: TileDrawEvent) {
        if (event.tile.isFlower) {
            player.flowerTiles += event.tile
            if (event.reason != DrawReason.INITIAL_DEAL) {
                listener?.onFlowerDrawn(player, event.tile)
            }
        } else {
            player.drawTile(event.tile)
            listener?.onTileDrawn(player, event.tile)
        }
        listener?.onTileDrawCompleted(event)
    }

    internal suspend fun drawSupplementFor(
        player: MahjongPlayerBase,
        reason: DrawReason = DrawReason.FLOWER_REPLACEMENT,
    ): DrawResult? {
        while (supplementWall.isNotEmpty()) {
            val draw = drawFromSupplement() ?: return null
            val event = newDrawEvent(
                player = player,
                tile = draw.tile,
                source = DrawSource.SUPPLEMENT_TAIL,
                reason = reason,
                handSizeBeforeDraw = player.hands.size,
                supplementRefilledFromLiveWall = draw.refilledFromLiveWall,
            )
            animateDraw(event)
            completeDraw(player, event)
            if (draw.tile.isFlower) {
                continue
            }
            return DrawResult(draw.tile, wasFlowerReplacement = true)
        }
        return null
    }

    internal suspend fun drawLiveFor(
        player: MahjongPlayerBase,
        reason: DrawReason = DrawReason.NORMAL_TURN,
    ): DrawResult? {
        while (liveWall.isNotEmpty()) {
            val tile = liveWall.removeFirst()
            val event = newDrawEvent(
                player = player,
                tile = tile,
                source = DrawSource.LIVE_HEAD,
                reason = reason,
                handSizeBeforeDraw = player.hands.size,
                supplementRefilledFromLiveWall = false,
            )
            animateDraw(event)
            completeDraw(player, event)
            if (!tile.isFlower) return DrawResult(tile, wasFlowerReplacement = false)
            if (reason == DrawReason.INITIAL_DEAL) {
                return DrawResult(tile, wasFlowerReplacement = false)
            }
            return drawSupplementFor(player, DrawReason.FLOWER_REPLACEMENT)
        }
        return null
    }

    /** 四家各 16 張，莊家再拿第 17 張。 */
    internal suspend fun dealHands() {
        val order = seatOrderFromDealer
        repeat(4) {
            order.forEach { player ->
                repeat(4) { drawLiveFor(player, DrawReason.INITIAL_DEAL) }
                listener?.onHandsUpdated(player)
                if (rule.initialDealGroupPauseMs > 0L) {
                    delay(rule.initialDealGroupPauseMs.coerceIn(0L, MahjongRule.MAX_DRAW_ANIMATION_MS))
                }
            }
        }
        val dealerFlowerCountBeforeOpening = order[0].flowerTiles.size
        dealerOpeningDraw = drawLiveFor(order[0], DrawReason.INITIAL_DEAL)
        dealerOpeningFlowerIndex = dealerOpeningDraw?.tile
            ?.takeIf { it.isFlower }
            ?.let { dealerFlowerCountBeforeOpening }
        listener?.onHandsUpdated(order[0])

        // 四家發牌完畢後，由莊家開始依東、南、西、北順序進行正統「起手補花」
        performInitialFlowerReplacements(order)
    }

    private suspend fun performInitialFlowerReplacements(order: List<MahjongPlayerBase>) {
        order.forEach { player ->
            // Flowers dealt in the opening hand are the only flowers that
            // need an announcement scheduled before their replacement. Any
            // flower drawn while replacing one is announced by
            // completeDraw immediately, including chained replacements.
            val initialFlowerCount = player.flowerTiles.size
            var announcedFlowers = 0
            while (announcedFlowers < initialFlowerCount &&
                supplementWall.isNotEmpty()
            ) {
                if (player == order[0]) dealerHadInitialFlowerReplacement = true
                val flowerIndex = announcedFlowers
                listener?.onFlowerDrawn(player, player.flowerTiles[flowerIndex])
                announcedFlowers++
                val replacement = drawSupplementFor(player, DrawReason.FLOWER_REPLACEMENT)
                // A flower in the dealer's opening 17th draw is replaced
                // before the first turn. Preserve the actual playable tile so
                // sorting and the timeout/win context use that draw, rather
                // than whichever tile happens to sort last.
                if (player == order[0] && flowerIndex == dealerOpeningFlowerIndex && replacement != null) {
                    dealerOpeningDraw = replacement
                }
            }
            // If a malformed/incomplete wall stopped a replacement, retain
            // the dealt hand and let the normal round-end path handle it.
            listener?.onHandsUpdated(player)
            val openingTile = if (player == order[0]) dealerOpeningDraw?.tile else null
            sortHands(player, openingTile?.takeIf { it in player.hands })
            listener?.onHandsUpdated(player)
        }
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

    private suspend fun performSeatWindDraw() {
        if (!isPlaying) return

        val dice = OpeningDice.roll { Random.nextInt(1, 7) }
        val diceSum = dice.total
        val starterIndex = (diceSum - 1) % playerCount
        val windTiles = listOf(MahjongTile.EAST, MahjongTile.SOUTH, MahjongTile.WEST, MahjongTile.NORTH).shuffled()

        val startEvent = SeatWindDrawStartEvent(
            dice = dice,
            starterSeatIndex = starterIndex,
            windTiles = windTiles
        )
        listener?.onSeatWindDrawStarted(startEvent)
        delay(2200L)

        val pickOrder = (0 until playerCount).map { (starterIndex + it) % playerCount }
        val availableIndices = (0 until 4).toMutableList()
        val assigned = mutableMapOf<MahjongTile, MahjongPlayerBase>()

        for (seatIdx in pickOrder) {
            if (!isPlaying) return
            val picker = seat[seatIdx]

            listener?.onSeatWindTurnPrompt(SeatWindTurnPromptEvent(picker, availableIndices.toList()))

            val pickedTileIndex = if (picker is MahjongPlayer) {
                picker.askToPickSeatWind(availableIndices)
            } else {
                delay(900L)
                availableIndices.random()
            }
            availableIndices.remove(pickedTileIndex)

            val revealedWind = windTiles[pickedTileIndex]
            assigned[revealedWind] = picker

            val pickEvent = SeatWindTilePickedEvent(
                picker = picker,
                tileIndex = pickedTileIndex,
                windTile = revealedWind
            )
            listener?.onSeatWindTilePicked(pickEvent)
            delay(1200L)
        }

        val newSeatOrder = listOf(
            assigned[MahjongTile.EAST] ?: seat[0],
            assigned[MahjongTile.SOUTH] ?: seat[1],
            assigned[MahjongTile.WEST] ?: seat[2],
            assigned[MahjongTile.NORTH] ?: seat[3]
        )
        seat = newSeatOrder.toMutableList()

        val completeEvent = SeatWindDrawCompleteEvent(
            assignments = assigned,
            newSeatOrder = newSeatOrder
        )
        listener?.onSeatWindDrawCompleted(completeEvent)
        delay(2500L)
    }

    private suspend fun startRound() {
        if (!isPlaying) return
        clearRoundState()
        listener?.onRoundStart(this, round)
        val dealerSeatIndex = seat.indexOf(seatOrderFromDealer[0])
        val diceEvent = OpeningDiceEvent(
            dice = OpeningDice.roll { Random.nextInt(1, 7) },
            dealerSeatIndex = dealerSeatIndex,
            animationMillis = rule.openingDiceAnimationMs.coerceIn(0L, MahjongRule.MAX_OPENING_DICE_ANIMATION_MS),
        )
        openingDice = diceEvent
        generateWall(diceEvent)
        listener?.onOpeningDiceStarted(diceEvent)
        if (diceEvent.animationMillis > 0L) delay(diceEvent.animationMillis)
        listener?.onOpeningDiceCompleted(diceEvent)
        dealHands()
        val dealer = seatOrderFromDealer[0]
        players.forEach {
            val openingTile = if (it == dealer) dealerOpeningDraw?.tile else null
            sortHands(it, openingTile?.takeIf { tile -> tile in it.hands })
            listener?.onHandsUpdated(it)
        }

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
                    dealerOpeningDraw?.takeIf { it.tile in player.hands }
                        ?: DrawResult(player.hands.last(), wasFlowerReplacement = dealerHadInitialFlowerReplacement)
                } else {
                    val drawn = drawLiveFor(player, DrawReason.NORMAL_TURN)
                    if (drawn == null) {
                        roundDraw = ExhaustiveDraw.NORMAL
                        break@roundLoop
                    }
                    drawn
                }

                val lastTile = drawResult.tile
                sortHands(player, lastTile)
                listener?.onHandsUpdated(player)
                timeoutTile = lastTile
                drewTile = true
                lastDrawWasLastLiveTile = !initialDealerHand && !drawResult.wasFlowerReplacement && liveWall.isEmpty()

                val normalTsumoContext = winContextFor(
                    player = player,
                    isTsumo = true,
                    isLastLiveTile = lastDrawWasLastLiveTile,
                    isFlowerReplacement = drawResult.wasFlowerReplacement ||
                        (initialDealerHand && dealerHadInitialFlowerReplacement),
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
                    replacement = drawSupplementFor(player, DrawReason.KONG_REPLACEMENT)?.tile ?: break
                    sortHands(player, replacement)
                    listener?.onHandsUpdated(player)
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

            player.justDrewTile = false
            sortHands(player)
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
            val claimants = players
                .filter { it != player && (it.canPon(actualDiscard) || it.canMinkan(actualDiscard)) }
                .sortedBy { (discarderSeat - seat.indexOf(it) + playerCount) % playerCount }

            for (claimant in claimants) {
                val canMinkan = supplementWall.isNotEmpty() && claimant.canMinkan(actualDiscard)
                val decision = if (canMinkan) {
                    delayForBot(claimant)
                    claimant.askToMinkanOrPon(actualDiscard, claimant.asClaimTarget(player), rule)
                } else {
                    delayForBot(claimant)
                    if (claimant.askToPon(actualDiscard, claimant.getTilePairForPon(actualDiscard), claimant.asClaimTarget(player))) {
                        MahjongGameBehavior.PON
                    } else {
                        MahjongGameBehavior.SKIP
                    }
                }
                when (decision) {
                    MahjongGameBehavior.MINKAN -> {
                        if (canMinkan) {
                            claimant.minkan(
                                actualDiscard,
                                claimTargetBySeatDiff(seat.indexOf(claimant), discarderSeat),
                                player,
                            )
                            roundHadClaim = true
                            kanCount++
                            listener?.onKan(claimant, actualDiscard, "minkan", player)
                            listener?.onHandsUpdated(claimant)

                            val replacement = drawSupplementFor(claimant, DrawReason.KONG_REPLACEMENT)?.tile
                            if (replacement == null) {
                                roundDraw = ExhaustiveDraw.NORMAL
                                break@roundLoop
                            }
                            sortHands(claimant, replacement)
                            listener?.onHandsUpdated(claimant)
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
                    }
                    MahjongGameBehavior.PON -> {
                        claimant.pon(
                            actualDiscard,
                            claimTargetBySeatDiff(seat.indexOf(claimant), discarderSeat),
                            player,
                        )
                        claimant.justDrewTile = false
                        sortHands(claimant)
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

            if (!claimed) {
                val next = seat[nextPlayerSeat]
                if (next.canChii(actualDiscard)) {
                    delayForBot(next)
                    val pairs = next.getTilePairsForChii(actualDiscard)
                    val chosen = next.askToChii(actualDiscard, pairs, next.asClaimTarget(player))
                    if (chosen != null) {
                        next.chii(actualDiscard, chosen, player)
                        next.justDrewTile = false
                        sortHands(next)
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

        val scoreBust = rule.startingPoints > 0 && players.any { it.points < 0 }
        val shouldTerminate = scoreBust || (listener?.shouldTerminateGame(this) ?: false)

        if (!shouldTerminate && (!round.isAllLast(rule) || dealerRemains)) {
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

    /** 莊家付款時也要承擔莊家、連莊與拉莊台：1 + 2n 台。 */
    private fun dealerLiabilityTai(player: MahjongPlayerBase): Int =
        if (player == seatOrderFromDealer.firstOrNull()) 1 + round.honba * 2 else 0

    private fun paymentFor(settlementScore: Int, payer: MahjongPlayerBase): Int {
        return TaiwanPayment.calculate(
            settlementScore = settlementScore,
            dealerLiabilityTai = dealerLiabilityTai(payer),
            pointsPerTai = rule.pointsPerTai,
        )
    }

    private fun MahjongPlayerBase.changePoints(delta: Long) {
        points = (points.toLong() + delta)
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()
    }

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
        val payment = settlements.map { settlement ->
            paymentFor(settlement.score, target)
        }
        val original = players.associateWith { it.points }
        forEachIndexed { index, winner ->
            winner.changePoints(payment[index].toLong())
            target.changePoints(-payment[index].toLong())
            RealMoneyPolicy.obligation(target, winner, payment[index])?.let {
                listener?.onEconomyObligation(it)
            }
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
        var gain = 0L
        players.filter { it != this }.forEach { loser ->
            val payment = paymentFor(settlement.score, loser)
            loser.changePoints(-payment.toLong())
            gain += payment
            RealMoneyPolicy.obligation(loser, this, payment)?.let {
                listener?.onEconomyObligation(it)
            }
        }
        changePoints(gain)
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
