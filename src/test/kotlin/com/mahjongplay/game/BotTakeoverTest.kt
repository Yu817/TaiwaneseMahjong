package com.mahjongplay.game

import com.mahjongplay.model.MahjongRule
import com.mahjongplay.model.MahjongTile
import com.mahjongplay.model.ClaimTarget
import com.mahjongplay.model.MahjongGameBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BotTakeoverTest {

    @Test
    fun `native and takeover bots follow the same difficulty kong policy`() = runBlocking {
        val lowBot = MahjongBot("LowBot", BotDifficulty.LOW)
        val lowPlayer = MahjongPlayer("low-player", "LowPlayer").also {
            it.activateBotTakeover(BotDifficulty.LOW)
        }
        val highBot = MahjongBot("HighBot", BotDifficulty.HIGH)
        val highPlayer = MahjongPlayer("high-player", "HighPlayer").also {
            it.activateBotTakeover(BotDifficulty.HIGH)
        }
        listOf(lowBot, lowPlayer, highBot, highPlayer).forEach {
            it.hands.addAll(List(4) { MahjongTile.M1 })
        }

        assertEquals(null, lowBot.askToAnkanOrKakan(lowBot.tilesCanAnkan, emptySet(), MahjongRule()))
        assertEquals(null, lowPlayer.askToAnkanOrKakan(lowPlayer.tilesCanAnkan, emptySet(), MahjongRule()))
        assertEquals(MahjongTile.M1, highBot.askToAnkanOrKakan(highBot.tilesCanAnkan, emptySet(), MahjongRule()))
        assertEquals(MahjongTile.M1, highPlayer.askToAnkanOrKakan(highPlayer.tilesCanAnkan, emptySet(), MahjongRule()))

        assertEquals(
            MahjongGameBehavior.PON,
            lowBot.askToMinkanOrPon(MahjongTile.M1, ClaimTarget.RIGHT, MahjongRule()),
        )
        assertEquals(
            MahjongGameBehavior.PON,
            lowPlayer.askToMinkanOrPon(MahjongTile.M1, ClaimTarget.RIGHT, MahjongRule()),
        )
        assertEquals(
            MahjongGameBehavior.MINKAN,
            highBot.askToMinkanOrPon(MahjongTile.M1, ClaimTarget.RIGHT, MahjongRule()),
        )
        assertEquals(
            MahjongGameBehavior.MINKAN,
            highPlayer.askToMinkanOrPon(MahjongTile.M1, ClaimTarget.RIGHT, MahjongRule()),
        )
    }

    @Test
    fun `bot takeover activates and restores correctly`() {
        val player = MahjongPlayer("uuid-1", "Alice")
        assertFalse(player.isBotTakeover)
        assertTrue(player.isRealPlayer)
        assertEquals("Alice", player.displayName)

        player.activateBotTakeover(BotDifficulty.HIGH)
        assertTrue(player.isBotTakeover)
        assertFalse(player.isRealPlayer)
        assertEquals("🤖 [代打] Alice", player.displayName)
        assertEquals(BotDifficulty.HIGH, player.botDifficulty)

        player.deactivateBotTakeover()
        assertFalse(player.isBotTakeover)
        assertTrue(player.isRealPlayer)
        assertEquals("Alice", player.displayName)
    }

    @Test
    fun `bot takeover immediately resolves active pending discard`() = runBlocking {
        val player = MahjongPlayer("uuid-2", "Bob")
        player.hands.addAll(listOf(MahjongTile.P1, MahjongTile.P2, MahjongTile.S5))

        val discardDeferred = async(Dispatchers.Default) {
            player.askToDiscardTile(MahjongTile.S5, emptyList(), skippable = false)
        }

        // Wait briefly for pendingAction to be created
        while (player.pendingAction == null) {
            kotlinx.coroutines.delay(5)
        }
        assertTrue(player.pendingAction != null)

        // Player disconnects/leaves -> Bot takeover activates
        player.activateBotTakeover(BotDifficulty.MEDIUM)

        val discardedTile = discardDeferred.await()
        assertTrue(discardedTile in player.hands || discardedTile == MahjongTile.S5)
        assertTrue(player.isBotTakeover)
    }

    @Test
    fun `game leave during play activates bot takeover and only ends when all leave`() {
        val rule = MahjongRule(seatWindDrawEnabled = false)
        val game = MahjongGame(rule = rule, gameCoroutineContext = Dispatchers.Unconfined)
        val p1 = MahjongPlayer("uuid-p1", "Player1").also { it.game = game; it.ready = true }
        val p2 = MahjongPlayer("uuid-p2", "Player2").also { it.game = game; it.ready = true }
        val b1 = MahjongBot("Bot1").also { it.game = game; it.ready = true }
        val b2 = MahjongBot("Bot2").also { it.game = game; it.ready = true }

        game.players.addAll(listOf(p1, p2, b1, b2))
        game.start()
        assertEquals(GameStatus.PLAYING, game.status)

        // Player1 leaves -> game does not end, Player1 is taken over by bot
        game.leave("uuid-p1")
        assertEquals(GameStatus.PLAYING, game.status)
        assertTrue(p1.isBotTakeover)

        // Player2 leaves -> all real players gone, game ends
        game.leave("uuid-p2")
        assertEquals(GameStatus.WAITING, game.status)
    }
}
