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
 * Paper 顯示層只透過 GameEventListener 觀察這個類別，因此規則核心不依賴 Bukkit。
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

    val wallSize: Int
        get() = liveWall.size + supplementWall.size

    private val seatOrderFromDealer: List<MahjongPlayerBase>
        get() = List(playerCount) { seat[(round.round + it) % playerCount] }

    private fun seatWindOf(player: MahjongPlayerBase): Wind {
        val index = seatOrderFromDealer.indexOf(player).coerceIn(0, Wind.entries.lastIndex)
        return Wind.entries[index]
    }

    init {
        // 台麻固定四人；舊 tables.yml 只沿用牌桌位置與局數設定。
        rule.playerCount = 4
    }

    // --- Lifecycle ---

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
        players.forEachIndexed { index, player -> if (index != 0 && player is MahjongPlayer) player.ready = false }
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
        val scoreList = players.map { ScoreItem(it.displayName, it.uuid, it.isRealPlayer, scoreOrigin = it.points, scoreChange = 0) }
        listener?.onGameEnd(this, scoreList)
        seat.clear()
        clearRoundState()
        round = MahjongRound()
    }

    // --- Wall and hand helpers ---

    private fun clearRoundState() {
        players.forEach {
            it.hands.clear()
            it.fuuroList.clear()
            it.flowerTiles.clear()
            it.discardedTiles.clear()
            it.discardedTilesForDisplay.clear()
        }
        liveWall.clear()
        supplementWall.clear()
        allDiscards.clear()
        kanCount = 0
    }

    private fun generateWall() {
        val sourceWall = if (rule.flowersEnabled) MahjongTile.taiwaneseWall else MahjongTile.normalWall
        val shuffled = sourceWall.shuffled()
        supplementWall = shuffled.takeLast(16).toMutableList()
        liveWall = shuffled.dropLast(16).toMutableList()
    }

    private suspend fun delayForBot(player: MahjongPlayerBase) {
        if (player is MahjongBot) {
            val delayMs = rule.botResponseDelayMs.coerceIn(0L, 5000L)
            if (delayMs > 0L) delay(delayMs)
        }
    }

    private fun drawFromSupplement(): MahjongTile? =
        if (supplementWall.isEmpty()) null else supplementWall.removeLast()

    /** 從補牌區拿到一張非花牌；途中抽到的花牌會立即登錄並繼續補牌。 */
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

    /** 從活牌區抽牌；花牌會移到花牌區，再從牌山尾端補牌。 */
    private fun drawLiveFor(player: MahjongPlayerBase): MahjongTile? {
        while (liveWall.isNotEmpty()) {
            val tile = liveWall.removeFirst()
            if (tile.isFlower) {
                player.flowerTiles += tile
                listener?.onHandsUpdated(player)
                return drawSupplementFor(player)
            }
            player.drawTile(tile)
            listener?.onTileDrawn(player, tile)
            listener?.onHandsUpdated(player)
            return tile
        }
        return null
    }

    private suspend fun dealHands() {
        val order = seatOrderFromDealer
        repeat(4) {
            order.forEach { player ->
                repeat(4) { drawLiveFor(player) }
            }
        }
        order.forEach { drawLiveFor(it) }
        drawLiveFor(order[0])
    }

    private fun sortHands(player: MahjongPlayerBase, lastTile: MahjongTile? = null) {
        if (!player.autoArrangeHands) return
        if (lastTile != null && player.hands.isNotEmpty()) {
            val last = player.hands.removeLast()
            player.hands.sortBy { it.sortOrder }
            player.hands += last
        } else {
            player.hands.sortBy { it.sortOrder }
        }
    }

    private fun MahjongPlayerBase.asClaimTarget(target: MahjongPlayerBase): ClaimTarget {
        val self = seat.indexOf(this)
        val targetIndex = seat.indexOf(target)
        return when ((targetIndex - self + playerCount) % playerCount) {
            1 -> ClaimTarget.RIGHT
            playerCount - 1 -> ClaimTarget.LEFT
            else -> ClaimTarget.ACROSS
        }
    }

    private fun claimTargetBySeatDiff(claimerSeat: Int, discarderSeat: Int): ClaimTarget =
        when ((discarderSeat - claimerSeat + playerCount) % playerCount) {
            1 -> ClaimTarget.RIGHT
            playerCount - 1 -> ClaimTarget.LEFT
            else -> ClaimTarget.ACROSS
        }

    // --- Round loop ---

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
        val cannotDiscard = mutableListOf<MahjongTile>()

        roundLoop@ while (isPlaying) {
            val player = nextPlayer
            currentPlayer = player
            val isDealer = player == dealer
            var timeoutTile = player.hands.lastOrNull() ?: break@roundLoop
            var drewTile = false

            if (needDraw) {
                player.justDrewTile = true
                val lastTile = if (isDealer && player.discardedTiles.isEmpty()) {
                    player.hands.last()
                } else {
                    val drawn = drawLiveFor(player)
                    if (drawn == null) {
                        roundDraw = ExhaustiveDraw.NORMAL
                        break@roundLoop
                    }
                    drawn
                }
                sortHands(player, lastTile)
                timeoutTile = lastTile
                drewTile = true

                delayForBot(player)
                if (player.canWin(lastTile, true, rule, round.wind, seatWindOf(player), isTsumo = true) && player.askToTsumo()) {
                    player.tsumo(lastTile)
                    dealerRemains = isDealer
                    break@roundLoop
                }

                var replacement: MahjongTile? = null
                while ((player.canKakan || player.canAnkan) && supplementWall.isNotEmpty()) {
                    delayForBot(player)
                    val kanTile = player.askToAnkanOrKakan(player.tilesCanAnkan, player.tilesCanKakan, rule) ?: break
                    val isAnkan = kanTile in player.tilesCanAnkan
                    if (isAnkan) player.ankan(kanTile) else player.kakan(kanTile)
                    kanCount++
                    listener?.onKan(player, kanTile, if (isAnkan) "ankan" else "kakan")
                    listener?.onHandsUpdated(player)
                    replacement = drawSupplementFor(player) ?: break
                    sortHands(player, replacement)
                    if (player.canWin(replacement, true, rule, round.wind, seatWindOf(player), isTsumo = true) && player.askToTsumo()) {
                        player.tsumo(replacement)
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
            val discarded = player.askToDiscardTile(timeoutTile, cannotDiscard, skippable = false)
            val actualDiscard = player.discardTile(discarded) ?: break@roundLoop
            allDiscards += actualDiscard
            listener?.onTileDiscarded(player, actualDiscard)
            listener?.onHandsUpdated(player)
            cannotDiscard.clear()

            val ronList = canRonList(actualDiscard, player)
            if (ronList.isNotEmpty()) {
                ronList.ron(target = player, tile = actualDiscard)
                dealerRemains = dealer in ronList
                break@roundLoop
            }

            val discarderSeat = seat.indexOf(player)
            val minkanList = players.filter { it != player && it.canMinkan(actualDiscard) }
            var claimed = false
            if (minkanList.isNotEmpty() && supplementWall.isNotEmpty()) {
                val claimant = minkanList.minBy { (seat.indexOf(it) - discarderSeat + playerCount) % playerCount }
                delayForBot(claimant)
                if (claimant.askToMinkanOrPon(actualDiscard, claimant.asClaimTarget(player), rule) == MahjongGameBehavior.MINKAN) {
                    claimant.minkan(actualDiscard, claimTargetBySeatDiff(seat.indexOf(claimant), discarderSeat), player)
                    kanCount++
                    listener?.onKan(claimant, actualDiscard, "minkan", player)
                    listener?.onHandsUpdated(claimant)
                    val replacement = drawSupplementFor(claimant)
                    if (replacement == null) {
                        roundDraw = ExhaustiveDraw.NORMAL
                        break@roundLoop
                    }
                    sortHands(claimant, replacement)
                    if (claimant.canWin(replacement, true, rule, round.wind, seatWindOf(claimant), isTsumo = true) && claimant.askToTsumo()) {
                        claimant.tsumo(replacement)
                        dealerRemains = claimant == dealer
                        break@roundLoop
                    }
                    nextPlayer = claimant
                    needDraw = false
                    claimed = true
                }
            }

            if (!claimed) {
                val ponList = players.filter { it != player && it.canPon(actualDiscard) }
                for (claimant in ponList.sortedBy { (seat.indexOf(it) - discarderSeat + playerCount) % playerCount }) {
                    delayForBot(claimant)
                    if (claimant.askToPon(actualDiscard, claimant.getTilePairForPon(actualDiscard), claimant.asClaimTarget(player))) {
                        claimant.pon(actualDiscard, claimTargetBySeatDiff(seat.indexOf(claimant), discarderSeat), player)
                        listener?.onPon(claimant, actualDiscard, player)
                        listener?.onHandsUpdated(claimant)
                        nextPlayer = claimant
                        needDraw = false
                        claimed = true
                        break
                    }
                }
            }

            if (!claimed) {
                val next = seat[(discarderSeat + 1) % playerCount]
                if (next.canChii(actualDiscard)) {
                    delayForBot(next)
                    val pairs = next.getTilePairsForChii(actualDiscard)
                    val chosen = next.askToChii(actualDiscard, pairs, next.asClaimTarget(player))
                    if (chosen != null) {
                        next.chii(actualDiscard, chosen, player)
                        listener?.onChii(next, actualDiscard, player)
                        listener?.onHandsUpdated(next)
                        nextPlayer = next
                        needDraw = false
                        claimed = true
                    }
                }
            }

            if (!claimed) nextPlayer = seat[(discarderSeat + 1) % playerCount]
            if (liveWall.isEmpty()) {
                roundDraw = ExhaustiveDraw.NORMAL
                break@roundLoop
            }
            if (!drewTile) delay(MIN_WAITING_TIME)
        }

        if (roundDraw != null) {
            dealerRemains = dealer.isTenpai
            roundDraw(roundDraw!!)
        }

        delay(1200)
        if (!isPlaying) return
        // A dealer repeat does not consume the scheduled hand, including on
        // the final hand of a selected circle count. The game ends only after
        // the final scheduled hand finishes without a repeat.
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

    // --- Win and settlement logic ---

    private fun canRonList(tile: MahjongTile, discardedPlayer: MahjongPlayerBase): List<MahjongPlayerBase> =
        players.filter { it != discardedPlayer && it.canWin(tile, false, rule, round.wind, seatWindOf(it), isTsumo = false) }

    private suspend fun List<MahjongPlayerBase>.ron(target: MahjongPlayerBase, tile: MahjongTile) {
        val settlements = map {
            it.calcTaiwanSettlementForWin(tile, false, rule, round.wind, seatWindOf(it), isTsumo = false)
        }
        val payment = settlements.map { it.score + round.honba * rule.honbaPoints }
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

    private suspend fun MahjongPlayerBase.tsumo(tile: MahjongTile) {
        val settlement = calcTaiwanSettlementForWin(tile, true, rule, round.wind, seatWindOf(this), isTsumo = true)
        val original = players.associateWith { it.points }
        var gain = 0
        val dealer = seatOrderFromDealer[0]
        players.filter { it != this }.forEach { loser ->
            var payment = settlement.score + round.honba * rule.honbaPoints
            if (this != dealer && loser == dealer) payment *= rule.dealerTsumoMultiplier
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
        return player.calculateMachiAndTai(rule, round.wind, seatWindOf(player))
    }

    companion object {
        const val MIN_WAITING_TIME = 1200L
        private const val SCORE_SETTLE_MS = 2500L
    }
}
