package com.mahjongplay.game

import com.mahjongplay.model.*
import java.util.UUID

class MahjongBot(
    override val displayName: String = "Bot",
    var difficulty: BotDifficulty = BotDifficulty.MEDIUM,
    val botTileCode: Int = MahjongTile.random().code
) : MahjongPlayerBase() {
    override val uuid: String = UUID.randomUUID().toString()
    override val isRealPlayer = false
    override var ready: Boolean = true

    override suspend fun askToDiscardTile(
        timeoutTile: MahjongTile,
        cannotDiscardTiles: List<MahjongTile>,
        skippable: Boolean
    ): MahjongTile {
        return BotBrain.decideDiscard(this, timeoutTile, cannotDiscardTiles)
    }

    override suspend fun askToChii(
        tile: MahjongTile,
        tilePairs: List<Pair<MahjongTile, MahjongTile>>,
        target: ClaimTarget
    ): Pair<MahjongTile, MahjongTile>? {
        return BotBrain.decideChii(this, tile, tilePairs, target)
    }

    override suspend fun askToPon(
        tile: MahjongTile,
        tilePairForPon: Pair<MahjongTile, MahjongTile>,
        target: ClaimTarget
    ): Boolean {
        return BotBrain.decidePon(this, tile, target)
    }

    override suspend fun askToPonOrChii(
        tile: MahjongTile,
        tilePairsForChii: List<Pair<MahjongTile, MahjongTile>>,
        tilePairForPon: Pair<MahjongTile, MahjongTile>,
        target: ClaimTarget
    ): Pair<MahjongTile, MahjongTile>? {
        if (askToPon(tile, tilePairForPon, target)) {
            return tilePairForPon
        }
        return askToChii(tile, tilePairsForChii, target)
    }

    override suspend fun askToAnkanOrKakan(
        canAnkanTiles: Set<MahjongTile>,
        canKakanTiles: Set<Pair<MahjongTile, ClaimTarget>>,
        rule: MahjongRule
    ): MahjongTile? {
        if (difficulty == BotDifficulty.LOW) return null
        return canAnkanTiles.firstOrNull() ?: canKakanTiles.firstOrNull()?.first
    }

    override suspend fun askToMinkanOrPon(
        tile: MahjongTile,
        target: ClaimTarget,
        rule: MahjongRule,
    ): MahjongGameBehavior = BotBrain.decideMinkanOrPon(this, tile, target, difficulty)

    override suspend fun askToTsumo(): Boolean = true
    override suspend fun askToRon(tile: MahjongTile, target: ClaimTarget): Boolean = true
}
