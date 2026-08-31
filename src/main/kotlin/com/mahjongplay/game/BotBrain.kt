package com.mahjongplay.game

import com.mahjongplay.display.TileCounter
import com.mahjongplay.model.*

object BotBrain {
    fun decideDiscard(
        player: MahjongPlayerBase,
        timeoutTile: MahjongTile,
        cannotDiscardTiles: List<MahjongTile>,
        difficulty: BotDifficulty = difficultyOf(player),
    ): MahjongTile {
        val candidates = player.hands.filter { it !in cannotDiscardTiles }
        if (candidates.isEmpty()) return timeoutTile
        if (candidates.size == 1) return candidates.first()
        return when (difficulty) {
            BotDifficulty.LOW -> decideDiscardLow(player, candidates, timeoutTile)
            BotDifficulty.MEDIUM -> decideDiscardMedium(player, candidates)
            BotDifficulty.HIGH -> decideDiscardHigh(player, candidates)
        }
    }

    private fun difficultyOf(player: MahjongPlayerBase): BotDifficulty =
        (player as? MahjongBot)?.difficulty
            ?: (player as? MahjongPlayer)?.botDifficulty
            ?: BotDifficulty.MEDIUM

    private fun decideDiscardLow(
        player: MahjongPlayerBase,
        candidates: List<MahjongTile>,
        timeoutTile: MahjongTile,
    ): MahjongTile {
        if (player.justDrewTile && timeoutTile in candidates && kotlin.random.Random.nextInt(100) < 60) {
            return timeoutTile
        }
        val isolatedHonors = candidates.filter { it.isHonor && player.hands.count { h -> h == it } == 1 }
        return isolatedHonors.randomOrNull() ?: candidates.random()
    }

    private fun decideDiscardMedium(
        player: MahjongPlayerBase,
        candidates: List<MahjongTile>,
    ): MahjongTile {
        val tenpaiDiscards = candidates.filter { player.machiIfDiscard(it).isNotEmpty() }
        if (tenpaiDiscards.isNotEmpty()) {
            return tenpaiDiscards.maxByOrNull { player.machiIfDiscard(it).size } ?: tenpaiDiscards.first()
        }
        return candidates.minByOrNull { evaluateTileValueMedium(player, it) } ?: candidates.first()
    }

    private fun decideDiscardHigh(
        player: MahjongPlayerBase,
        candidates: List<MahjongTile>,
    ): MahjongTile {
        val game = player.game
        val tenpaiDiscards = candidates.filter { player.machiIfDiscard(it).isNotEmpty() }
        if (tenpaiDiscards.isEmpty()) {
            game?.takeIf { it.wallSize <= 32 }?.let { lateGame ->
                val opponentDiscards = lateGame.seat.filter { it != player }.flatMap { it.discardedTiles }.toSet()
                val safeDiscards = candidates.filter { it in opponentDiscards }
                if (safeDiscards.isNotEmpty()) {
                    return safeDiscards.minByOrNull { evaluateTileValueMedium(player, it) } ?: safeDiscards.first()
                }
            }
        }
        if (tenpaiDiscards.isNotEmpty()) {
            return if (game != null) {
                tenpaiDiscards.maxByOrNull { discard ->
                    player.machiIfDiscard(discard).sumOf { target ->
                        TileCounter.countRemainingUnseenTiles(game, player, target)
                    }
                } ?: tenpaiDiscards.first()
            } else {
                tenpaiDiscards.maxByOrNull { player.machiIfDiscard(it).size } ?: tenpaiDiscards.first()
            }
        }
        return candidates.minByOrNull { evaluateTileValueHigh(player, it) } ?: candidates.first()
    }

    fun evaluateTileValueMedium(bot: MahjongPlayerBase, tile: MahjongTile): Int {
        val countInHand = bot.hands.count { it == tile }
        if (countInHand >= 3) return 100
        if (countInHand == 2) return if (tile.isHonor) 55 else 60
        if (tile.isHonor) return 10

        val suit = tile.suit
        val number = tile.number
        val hasR1 = bot.hands.any {
            it.suit == suit && (it.number == number - 1 || it.number == number + 1)
        }
        val hasR2 = bot.hands.any {
            it.suit == suit && (it.number == number - 2 || it.number == number + 2)
        }
        if (hasR1) return 80
        if (hasR2) return 65
        if (number == 1 || number == 9) return 18
        if (number == 2 || number == 8) return 28
        return 40
    }

    private fun evaluateTileValueHigh(bot: MahjongPlayerBase, tile: MahjongTile): Int {
        val baseValue = evaluateTileValueMedium(bot, tile)
        val game = bot.game ?: return baseValue
        val remaining = TileCounter.countRemainingUnseenTiles(game, bot, tile)
        var adjusted = baseValue + remaining * 3
        if (tile.isNumbered) {
            val suit = tile.suit
            val number = tile.number
            val left = MahjongTile.basicTiles.find { it.suit == suit && it.number == number - 1 }
            val right = MahjongTile.basicTiles.find { it.suit == suit && it.number == number + 1 }
            val leftOuts = left?.let { TileCounter.countRemainingUnseenTiles(game, bot, it) } ?: 0
            val rightOuts = right?.let { TileCounter.countRemainingUnseenTiles(game, bot, it) } ?: 0
            if (leftOuts == 0 && rightOuts == 0 && bot.hands.count { it == tile } == 1) adjusted -= 25
        }
        return adjusted
    }

    fun decideChii(
        player: MahjongPlayerBase,
        tile: MahjongTile,
        tilePairs: List<Pair<MahjongTile, MahjongTile>>,
        target: ClaimTarget,
        difficulty: BotDifficulty = difficultyOf(player),
    ): Pair<MahjongTile, MahjongTile>? {
        if (tilePairs.isEmpty()) return null
        return when (difficulty) {
            BotDifficulty.LOW -> null
            BotDifficulty.MEDIUM -> tilePairs.firstOrNull { (first, second) ->
                remainsTenpaiAfterChii(player, tile, first, second, target)
            }
            BotDifficulty.HIGH -> tilePairs.firstOrNull()
        }
    }

    private fun remainsTenpaiAfterChii(
        player: MahjongPlayerBase,
        claimedTile: MahjongTile,
        first: MahjongTile,
        second: MahjongTile,
        target: ClaimTarget,
    ): Boolean {
        val concealedAfterChii = player.hands.toMutableList()
        if (!concealedAfterChii.remove(first) || !concealedAfterChii.remove(second)) return false
        val sequence = listOf(first, second, claimedTile).sortedBy { it.sortOrder }
        val fuuroAfterChii = player.fuuroList + Fuuro(MeldType.SEQUENCE, sequence, target, claimedTile)
        return concealedAfterChii.any { discard ->
            val afterDiscard = concealedAfterChii.toMutableList().also { it.remove(discard) }
            MahjongTile.basicTiles.any { wait ->
                val used = afterDiscard.count { it == wait } +
                    fuuroAfterChii.sumOf { fuuro -> fuuro.tiles.count { it == wait } }
                used < 4 && TaiwaneseHandEvaluator.canWin(afterDiscard + wait, fuuroAfterChii)
            }
        }
    }

    fun decidePon(
        player: MahjongPlayerBase,
        tile: MahjongTile,
        target: ClaimTarget,
        difficulty: BotDifficulty = difficultyOf(player),
    ): Boolean = when (difficulty) {
        BotDifficulty.LOW -> true
        BotDifficulty.MEDIUM -> if (tile.isHonor) {
            true
        } else {
            val hasAdjacent = player.hands.any {
                it.suit == tile.suit && (it.number == tile.number - 1 || it.number == tile.number + 1)
            }
            !hasAdjacent
        }
        BotDifficulty.HIGH -> if (tile.isHonor) true else player.game?.let { it.wallSize > 24 } ?: true
    }

    fun decideMinkanOrPon(
        player: MahjongPlayerBase,
        tile: MahjongTile,
        target: ClaimTarget,
        difficulty: BotDifficulty = difficultyOf(player),
    ): MahjongGameBehavior = when (difficulty) {
        BotDifficulty.LOW -> if (decidePon(player, tile, target, difficulty)) {
            MahjongGameBehavior.PON
        } else {
            MahjongGameBehavior.SKIP
        }
        BotDifficulty.MEDIUM, BotDifficulty.HIGH -> MahjongGameBehavior.MINKAN
    }
}
