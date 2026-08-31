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

    @Test
    fun `listener shouldTerminateGame stops the game loop`() {
        var terminationChecked = false
        val game = MahjongGame(
            listener = object : GameEventListener {
                override fun shouldTerminateGame(game: MahjongGame): Boolean {
                    terminationChecked = true
                    return true
                }
            }
        ).apply {
            repeat(4) { addBot("Bot-$it") }
        }

        assertEquals(false, terminationChecked)
        val shouldStop = game.listener?.shouldTerminateGame(game) ?: false
        assertEquals(true, shouldStop)
        assertEquals(true, terminationChecked)
    }

    @Test
    fun `end and cancelGame purge bots and retain real players`() {
        val game = MahjongGame().apply {
            join("human-1", "Alice")
            join("human-2", "Bob")
            addBot("Bot-1")
            addBot("Bot-2")
        }

        assertEquals(4, game.players.size)
        assertEquals(2, game.realPlayers.size)

        game.end()
        assertEquals(2, game.players.size)
        assertEquals(listOf("human-1", "human-2"), game.players.map { it.uuid })

        game.addBot("Bot-3")
        assertEquals(3, game.players.size)
        game.cancelGame()
        assertEquals(2, game.players.size)
        assertEquals(listOf("human-1", "human-2"), game.players.map { it.uuid })
    }

    private fun readyGame() = MahjongGame().apply {
        repeat(4) { addBot("Bot-$it") }
    }
}
