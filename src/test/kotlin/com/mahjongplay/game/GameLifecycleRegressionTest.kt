package com.mahjongplay.game

import kotlin.test.Test
import kotlin.test.assertEquals

class GameLifecycleRegressionTest {
    @Test
    fun `starting an active game does not reset player state`() {
        val game = readyGame()

        try {
            game.start()
            game.players.first().points = 12_345

            game.start()

            assertEquals(12_345, game.players.first().points)
        } finally {
            game.end()
        }
    }

    @Test
    fun `players cannot be kicked while game is active`() {
        val game = readyGame()

        try {
            game.start()
            game.kick(1)

            assertEquals(4, game.players.size)
        } finally {
            game.end()
        }
    }

    private fun readyGame() = MahjongGame().apply {
        repeat(4) { addBot("Bot-$it") }
    }
}
