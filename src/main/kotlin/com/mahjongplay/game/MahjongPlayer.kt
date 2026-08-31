package com.mahjongplay.game

import com.mahjongplay.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import net.kyori.adventure.text.format.NamedTextColor
import java.util.UUID

data class PendingAction(
    val behaviors: List<MahjongGameBehavior>,
    val deferred: CompletableDeferred<Pair<MahjongGameBehavior, String>>
)

data class ActionDisplayOption(
    val behavior: MahjongGameBehavior,
    val label: String,
    val data: String,
    val color: NamedTextColor = NamedTextColor.WHITE,
    val subOptions: List<ActionDisplayOption>? = null,
    val tiles: List<MahjongTile> = emptyList(),
)

interface PendingActionListener {
    fun onPendingActionStart(player: MahjongPlayer, behaviors: List<MahjongGameBehavior>, timeoutSeconds: Int)
    fun onPendingActionEnd(player: MahjongPlayer)
}

class MahjongPlayer(
    override val uuid: String,
    displayName: String,
) : MahjongPlayerBase() {
    var rawDisplayName: String = displayName
    var isBotTakeover: Boolean = false
    var isQuitOffline: Boolean = false
    var botDifficulty: BotDifficulty = BotDifficulty.MEDIUM

    override val displayName: String
        get() = if (isBotTakeover) "🤖 [代打] $rawDisplayName" else rawDisplayName

    override val isRealPlayer: Boolean
        get() = !isBotTakeover

    fun activateBotTakeover(difficulty: BotDifficulty = BotDifficulty.MEDIUM, offline: Boolean = false) {
        isBotTakeover = true
        isQuitOffline = offline
        botDifficulty = difficulty
        val pending = pendingAction
        if (pending != null) {
            val behaviors = pending.behaviors
            when {
                MahjongGameBehavior.DISCARD in behaviors -> {
                    val tile = BotBrain.decideDiscard(this, hands.lastOrNull() ?: MahjongTile.UNKNOWN, emptyList(), difficulty)
                    resolveAction(MahjongGameBehavior.DISCARD, tile.code.toString())
                }
                MahjongGameBehavior.TSUMO in behaviors -> resolveAction(MahjongGameBehavior.TSUMO)
                MahjongGameBehavior.RON in behaviors -> resolveAction(MahjongGameBehavior.RON)
                else -> resolveAction(MahjongGameBehavior.SKIP)
            }
        }
        val windPicker = pendingSeatWindPicker
        if (windPicker != null) {
            resolveSeatWindPick(0)
        }
    }

    fun deactivateBotTakeover() {
        isBotTakeover = false
        isQuitOffline = false
    }
    var pendingAction: PendingAction? = null
    var gameId: UUID = UUID.randomUUID()
    var pendingActionListener: PendingActionListener? = null
    var actionOptions: List<ActionDisplayOption> = emptyList()

    private suspend fun <T> waitForBehaviorResult(
        behavior: MahjongGameBehavior,
        waitingBehaviors: List<MahjongGameBehavior> = listOf(behavior, MahjongGameBehavior.SKIP),
        timeoutSeconds: Int = basicThinkingTime + extraThinkingTime,
        onResult: (MahjongGameBehavior, String) -> T,
    ): T {
        val deferred = CompletableDeferred<Pair<MahjongGameBehavior, String>>()
        pendingAction = PendingAction(waitingBehaviors, deferred)
        val effectiveTimeout = if (timeoutSeconds > 0) timeoutSeconds else 15
        pendingActionListener?.onPendingActionStart(this, waitingBehaviors, effectiveTimeout)
        val result = withTimeoutOrNull(effectiveTimeout * 1000L) { deferred.await() }
            ?: (MahjongGameBehavior.SKIP to "")
        pendingAction = null
        actionOptions = emptyList()
        pendingActionListener?.onPendingActionEnd(this)
        return onResult(result.first, result.second)
    }

    fun resolveAction(behavior: MahjongGameBehavior, data: String = ""): Boolean {
        val pending = pendingAction ?: return false
        if (behavior !in pending.behaviors) return false
        return pending.deferred.complete(behavior to data)
    }

    /** Cancel an interaction when the owning game ends or is reloaded. */
    fun cancelPendingActions() {
        pendingAction?.deferred?.cancel()
        pendingAction = null
        actionOptions = emptyList()
        pendingSeatWindPicker?.cancel()
        pendingSeatWindPicker = null
    }

    var pendingSeatWindPicker: CompletableDeferred<Int>? = null

    suspend fun askToPickSeatWind(availableIndices: List<Int>, timeoutSeconds: Int = 12): Int {
        if (isBotTakeover) return availableIndices.random()
        val deferred = CompletableDeferred<Int>()
        pendingSeatWindPicker = deferred
        val result = withTimeoutOrNull(timeoutSeconds * 1000L) { deferred.await() }
            ?: availableIndices.random()
        pendingSeatWindPicker = null
        return if (result in availableIndices) result else availableIndices.random()
    }

    fun resolveSeatWindPick(tileIndex: Int): Boolean {
        val deferred = pendingSeatWindPicker ?: return false
        return deferred.complete(tileIndex)
    }

    private val skipOption = ActionDisplayOption(MahjongGameBehavior.SKIP, "跳過", "", NamedTextColor.GRAY)

    override suspend fun askToDiscardTile(
        timeoutTile: MahjongTile,
        cannotDiscardTiles: List<MahjongTile>,
        skippable: Boolean,
    ): MahjongTile {
        if (isBotTakeover) {
            return BotBrain.decideDiscard(this, timeoutTile, cannotDiscardTiles)
        }
        actionOptions = emptyList()
        fun safeFallback(): MahjongTile = hands.lastOrNull { it !in cannotDiscardTiles }
            ?: hands.lastOrNull()
            ?: timeoutTile

        return waitForBehaviorResult(
            behavior = MahjongGameBehavior.DISCARD,
            waitingBehaviors = if (skippable) {
                listOf(MahjongGameBehavior.DISCARD, MahjongGameBehavior.SKIP)
            } else {
                listOf(MahjongGameBehavior.DISCARD)
            }
        ) { behavior, data ->
            if (behavior != MahjongGameBehavior.DISCARD) return@waitForBehaviorResult safeFallback()
            val tileCode = data.toIntOrNull() ?: return@waitForBehaviorResult safeFallback()
            val selected = MahjongTile.entries.find { it.code == tileCode }
                ?: return@waitForBehaviorResult safeFallback()
            if (selected in cannotDiscardTiles || selected !in hands) safeFallback() else selected
        }
    }

    override suspend fun askToChii(
        tile: MahjongTile,
        tilePairs: List<Pair<MahjongTile, MahjongTile>>,
        target: ClaimTarget,
    ): Pair<MahjongTile, MahjongTile>? {
        if (isBotTakeover) {
            return BotBrain.decideChii(this, tile, tilePairs, target)
        }
        val subs = tilePairs.map { (a, b) ->
            val meld = listOf(a, b, tile).sortedBy { it.sortOrder }
            ActionDisplayOption(
                behavior = MahjongGameBehavior.CHII,
                label = "",
                data = "${a.code},${b.code}",
                color = NamedTextColor.GREEN,
                tiles = meld,
            )
        }
        actionOptions = if (subs.size == 1) {
            listOf(subs[0].copy(label = "吃"), skipOption)
        } else {
            listOf(ActionDisplayOption(MahjongGameBehavior.CHII, "吃", "", NamedTextColor.GREEN, subOptions = subs), skipOption)
        }
        return waitForBehaviorResult(behavior = MahjongGameBehavior.CHII) { behavior, data ->
            if (behavior == MahjongGameBehavior.CHII) {
                val parts = data.split(",")
                if (parts.size == 2) {
                    val p1 = MahjongTile.entries.find { it.code == parts[0].toIntOrNull() }
                    val p2 = MahjongTile.entries.find { it.code == parts[1].toIntOrNull() }
                    if (p1 != null && p2 != null) {
                        val pair = p1 to p2
                        if (pair in tilePairs) return@waitForBehaviorResult pair
                    }
                }
            }
            null
        }
    }

    override suspend fun askToPonOrChii(
        tile: MahjongTile,
        tilePairsForChii: List<Pair<MahjongTile, MahjongTile>>,
        tilePairForPon: Pair<MahjongTile, MahjongTile>,
        target: ClaimTarget,
    ): Pair<MahjongTile, MahjongTile>? {
        if (isBotTakeover) {
            val pon = BotBrain.decidePon(this, tile, target)
            if (pon) return tile to tile
            return BotBrain.decideChii(this, tile, tilePairsForChii, target)
        }
        val ponTiles = listOf(tilePairForPon.first, tilePairForPon.second, tile)
        val chiiSubs = tilePairsForChii.map { (a, b) ->
            val meld = listOf(a, b, tile).sortedBy { it.sortOrder }
            ActionDisplayOption(
                behavior = MahjongGameBehavior.CHII,
                label = "",
                data = "${a.code},${b.code}",
                color = NamedTextColor.GREEN,
                tiles = meld,
            )
        }
        actionOptions = buildList {
            add(ActionDisplayOption(MahjongGameBehavior.PON, "碰", "", NamedTextColor.AQUA, tiles = ponTiles))
            if (chiiSubs.size == 1) add(chiiSubs[0].copy(label = "吃"))
            else add(ActionDisplayOption(MahjongGameBehavior.CHII, "吃", "", NamedTextColor.GREEN, subOptions = chiiSubs))
            add(skipOption)
        }
        return waitForBehaviorResult(
            behavior = MahjongGameBehavior.PON_OR_CHII,
            waitingBehaviors = listOf(MahjongGameBehavior.CHII, MahjongGameBehavior.PON, MahjongGameBehavior.SKIP)
        ) { behavior, data ->
            when (behavior) {
                MahjongGameBehavior.CHII -> {
                    val parts = data.split(",")
                    if (parts.size == 2) {
                        val p1 = MahjongTile.entries.find { it.code == parts[0].toIntOrNull() }
                        val p2 = MahjongTile.entries.find { it.code == parts[1].toIntOrNull() }
                        if (p1 != null && p2 != null) {
                            val pair = p1 to p2
                            if (pair in tilePairsForChii) return@waitForBehaviorResult pair
                        }
                    }
                    null
                }
                MahjongGameBehavior.PON -> tile to tile
                else -> null
            }
        }
    }

    override suspend fun askToPon(
        tile: MahjongTile,
        tilePairForPon: Pair<MahjongTile, MahjongTile>,
        target: ClaimTarget,
    ): Boolean {
        if (isBotTakeover) {
            return BotBrain.decidePon(this, tile, target)
        }
        actionOptions = listOf(
            ActionDisplayOption(MahjongGameBehavior.PON, "碰", "", NamedTextColor.AQUA, tiles = listOf(tilePairForPon.first, tilePairForPon.second, tile)),
            skipOption
        )
        return waitForBehaviorResult(behavior = MahjongGameBehavior.PON) { behavior, _ ->
            behavior == MahjongGameBehavior.PON
        }
    }

    override suspend fun askToAnkanOrKakan(
        canAnkanTiles: Set<MahjongTile>,
        canKakanTiles: Set<Pair<MahjongTile, ClaimTarget>>,
        rule: MahjongRule,
    ): MahjongTile? {
        if (isBotTakeover) {
            if (botDifficulty == BotDifficulty.LOW) return null
            return canAnkanTiles.firstOrNull() ?: canKakanTiles.firstOrNull()?.first
        }
        val kanSubs = buildList {
            canAnkanTiles.forEach { t ->
                add(ActionDisplayOption(MahjongGameBehavior.ANKAN_OR_KAKAN, "暗槓", "${t.code}", NamedTextColor.DARK_AQUA, tiles = listOf(t, t, t, t)))
            }
            canKakanTiles.forEach { (t, _) ->
                add(ActionDisplayOption(MahjongGameBehavior.ANKAN_OR_KAKAN, "加槓", "${t.code}", NamedTextColor.AQUA, tiles = listOf(t, t, t, t)))
            }
        }
        actionOptions = if (kanSubs.size == 1) {
            listOf(kanSubs[0], skipOption)
        } else {
            listOf(ActionDisplayOption(MahjongGameBehavior.ANKAN_OR_KAKAN, "槓", "", NamedTextColor.DARK_AQUA, subOptions = kanSubs), skipOption)
        }
        return waitForBehaviorResult(behavior = MahjongGameBehavior.ANKAN_OR_KAKAN) { behavior, data ->
            if (behavior == MahjongGameBehavior.ANKAN_OR_KAKAN) {
                val selected = MahjongTile.entries.find { it.code == data.toIntOrNull() }
                if (selected != null && (selected in canAnkanTiles || selected in canKakanTiles.map { it.first })) {
                    return@waitForBehaviorResult selected
                }
            }
            null
        }
    }

    override suspend fun askToMinkanOrPon(
        tile: MahjongTile,
        target: ClaimTarget,
        rule: MahjongRule,
    ): MahjongGameBehavior {
        if (isBotTakeover) {
            return BotBrain.decideMinkanOrPon(this, tile, target, botDifficulty)
        }
        actionOptions = listOf(
            ActionDisplayOption(MahjongGameBehavior.MINKAN, "明槓", "", NamedTextColor.DARK_AQUA, tiles = listOf(tile, tile, tile, tile)),
            ActionDisplayOption(MahjongGameBehavior.PON, "碰", "", NamedTextColor.AQUA, tiles = listOf(tile, tile, tile)),
            skipOption
        )
        return waitForBehaviorResult(
            behavior = MahjongGameBehavior.MINKAN,
            waitingBehaviors = listOf(MahjongGameBehavior.PON, MahjongGameBehavior.MINKAN, MahjongGameBehavior.SKIP)
        ) { behavior, _ ->
            when (behavior) {
                MahjongGameBehavior.PON, MahjongGameBehavior.MINKAN -> behavior
                else -> MahjongGameBehavior.SKIP
            }
        }
    }

    override suspend fun askToTsumo(): Boolean {
        if (isBotTakeover) return true
        actionOptions = listOf(
            ActionDisplayOption(MahjongGameBehavior.TSUMO, "自摸", "", NamedTextColor.GOLD),
            skipOption
        )
        return waitForBehaviorResult(behavior = MahjongGameBehavior.TSUMO) { behavior, _ ->
            behavior == MahjongGameBehavior.TSUMO
        }
    }

    override suspend fun askToRon(tile: MahjongTile, target: ClaimTarget): Boolean {
        if (isBotTakeover) return true
        actionOptions = listOf(
            ActionDisplayOption(MahjongGameBehavior.RON, "胡牌", "", NamedTextColor.RED),
            skipOption
        )
        return waitForBehaviorResult(behavior = MahjongGameBehavior.RON) { behavior, _ ->
            behavior == MahjongGameBehavior.RON
        }
    }
}
