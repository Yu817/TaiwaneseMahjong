package com.mahjongplay.game

import com.mahjongplay.model.MahjongRule
import com.mahjongplay.model.MahjongTile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileDrawEventTest {
    @Test
    fun `normal draw reports its live-wall source before it enters the hand`() = runBlocking {
        val events = mutableListOf<TileDrawEvent>()
        val game = MahjongGame(
            rule = MahjongRule(drawAnimationMs = 0),
            listener = object : GameEventListener {
                override fun onTileDrawStarted(event: TileDrawEvent) {
                    events += event
                }
            },
        )
        game.addBot("Bot")
        val player = game.players.single()
        setWall(game, "liveWall", mutableListOf(MahjongTile.M1))
        setWall(game, "supplementWall", mutableListOf())

        game.drawLiveFor(player, DrawReason.NORMAL_TURN)

        assertEquals(listOf(MahjongTile.M1), player.hands)
        assertEquals(1, events.size)
        assertEquals(DrawSource.LIVE_HEAD, events.single().source)
        assertEquals(DrawReason.NORMAL_TURN, events.single().reason)
        assertEquals(0, events.single().liveWallSize)
        assertEquals(0, events.single().supplementWallSize)
        assertEquals(0, events.single().handSizeBeforeDraw)
    }

    @Test
    fun `supplement draw refills dead wall from live tail and reports the refill`() = runBlocking {
        val events = mutableListOf<TileDrawEvent>()
        val game = MahjongGame(
            rule = MahjongRule(drawAnimationMs = 0),
            listener = object : GameEventListener {
                override fun onTileDrawStarted(event: TileDrawEvent) {
                    events += event
                }
            },
        )
        game.addBot("Bot")
        val player = game.players.single()
        setWall(game, "liveWall", mutableListOf(MahjongTile.P1, MahjongTile.P2))
        setWall(game, "supplementWall", mutableListOf(MahjongTile.M1))

        game.drawSupplementFor(player)

        assertEquals(listOf(MahjongTile.M1), player.hands)
        assertEquals(listOf(MahjongTile.P1), wall(game, "liveWall"))
        assertEquals(listOf(MahjongTile.P2), wall(game, "supplementWall"))
        assertTrue(events.single().supplementRefilledFromLiveWall)
        assertEquals(DrawSource.SUPPLEMENT_TAIL, events.single().source)
    }

    @Test
    fun `supplement draw can consume remaining dead wall after live wall is exhausted`() = runBlocking {
        val events = mutableListOf<TileDrawEvent>()
        val game = MahjongGame(
            rule = MahjongRule(drawAnimationMs = 0),
            listener = object : GameEventListener {
                override fun onTileDrawStarted(event: TileDrawEvent) { events += event }
            },
        )
        game.addBot("Bot")
        val player = game.players.single()
        setWall(game, "liveWall", mutableListOf())
        setWall(game, "supplementWall", mutableListOf(MahjongTile.M1))

        game.drawSupplementFor(player, DrawReason.KONG_REPLACEMENT)

        assertEquals(listOf(MahjongTile.M1), player.hands)
        assertEquals(1, events.size)
        assertEquals(false, events.single().supplementRefilledFromLiveWall)
        assertEquals(0, events.single().liveWallSize)
        assertEquals(0, events.single().supplementWallSize)
    }

    @Test
    fun `initial deal distributes four tiles per player in dealer order then gives dealer tile seventeen`() = runBlocking {
        val events = mutableListOf<TileDrawEvent>()
        val game = MahjongGame(
            rule = MahjongRule(drawAnimationMs = 0, initialDealAnimationMs = 0, initialDealGroupPauseMs = 0),
            listener = object : GameEventListener {
                override fun onTileDrawStarted(event: TileDrawEvent) {
                    events += event
                }
            },
        )
        repeat(4) { game.addBot("Bot$it") }
        game.seat = game.players.toMutableList()
        setWall(game, "liveWall", MutableList(64) { MahjongTile.M1 }.also { it += MahjongTile.M2 })
        setWall(game, "supplementWall", MutableList(16) { MahjongTile.P1 })

        game.dealHands()

        assertEquals(17, game.seat[0].hands.size)
        game.seat.drop(1).forEach { assertEquals(16, it.hands.size) }
        assertEquals(65, events.size)
        assertEquals(DrawReason.INITIAL_DEAL, events.first().reason)
        assertEquals(List(4) { game.seat[0].uuid }, events.take(4).map { it.playerUUID })
        assertEquals(List(4) { game.seat[1].uuid }, events.drop(4).take(4).map { it.playerUUID })
        assertEquals(game.seat[0].uuid, events.last().playerUUID)
        assertEquals(MahjongTile.M2, game.seat[0].hands.last())
    }

    private fun setWall(game: MahjongGame, fieldName: String, tiles: MutableList<MahjongTile>) {
        MahjongGame::class.java.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(game, tiles)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun wall(game: MahjongGame, fieldName: String): List<MahjongTile> =
        MahjongGame::class.java.getDeclaredField(fieldName).apply { isAccessible = true }
            .get(game) as MutableList<MahjongTile>
}
