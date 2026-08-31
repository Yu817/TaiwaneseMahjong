package com.mahjongplay.game

import com.mahjongplay.model.MahjongRule
import com.mahjongplay.model.MahjongTile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class FlowerReplacementTest {
    @Test
    fun `drawing a flower announces it and draws a replacement from the tail`() = runBlocking {
        val flowers = mutableListOf<MahjongTile>()
        val game = MahjongGame(rule = MahjongRule(drawAnimationMs = 0), listener = object : GameEventListener {
            override fun onFlowerDrawn(player: MahjongPlayerBase, flower: MahjongTile) {
                flowers += flower
            }
        })
        game.addBot("Bot")
        val player = game.players.single()
        setWall(game, "liveWall", mutableListOf(MahjongTile.FLOWER_1))
        setWall(game, "supplementWall", mutableListOf(MahjongTile.M1))

        game.drawLiveFor(player, DrawReason.NORMAL_TURN)

        assertEquals(listOf(MahjongTile.FLOWER_1), flowers)
        assertEquals(listOf(MahjongTile.FLOWER_1), player.flowerTiles)
        assertEquals(listOf(MahjongTile.M1), player.hands)
    }

    @Test
    fun `initial flower replacements announce the original and chained flowers in draw order`() = runBlocking {
        val flowers = mutableListOf<MahjongTile>()
        val game = MahjongGame(
            rule = MahjongRule(drawAnimationMs = 0, initialDealAnimationMs = 0, initialDealGroupPauseMs = 0),
            listener = object : GameEventListener {
                override fun onFlowerDrawn(player: MahjongPlayerBase, flower: MahjongTile) {
                    flowers += flower
                }
            },
        )
        repeat(4) { game.addBot("Bot$it") }
        game.seat = game.players.toMutableList()

        // The dealer receives FLOWER_1 during the opening deal. The first
        // supplement tile is FLOWER_2; its refill from the live tail is M1.
        val live = MutableList(64) { MahjongTile.M2 }
        live += MahjongTile.FLOWER_1
        live += MahjongTile.M1
        live += MahjongTile.M4
        setWall(game, "liveWall", live)
        setWall(game, "supplementWall", mutableListOf(MahjongTile.M3, MahjongTile.FLOWER_2))

        game.dealHands()

        assertEquals(listOf(MahjongTile.FLOWER_1, MahjongTile.FLOWER_2), flowers)
        assertEquals(listOf(MahjongTile.FLOWER_1, MahjongTile.FLOWER_2), game.seat[0].flowerTiles)
        assertEquals(MahjongTile.M3, game.seat[0].hands.last())
    }

    private fun setWall(game: MahjongGame, fieldName: String, tiles: MutableList<MahjongTile>) {
        MahjongGame::class.java.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(game, tiles)
        }
    }
}
