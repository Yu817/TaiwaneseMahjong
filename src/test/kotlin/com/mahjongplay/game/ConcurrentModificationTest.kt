package com.mahjongplay.game

import com.mahjongplay.model.MahjongTile
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

class ConcurrentModificationTest {
    @Test
    fun `concurrent iteration and mutation on hands does not throw ConcurrentModificationException`() {
        val player = MahjongBot("concurrency-test")
        player.hands += listOf(
            MahjongTile.M1, MahjongTile.M2, MahjongTile.M3,
            MahjongTile.M4, MahjongTile.M5, MahjongTile.M6,
            MahjongTile.M7, MahjongTile.M8, MahjongTile.M9,
            MahjongTile.P1, MahjongTile.P2, MahjongTile.P3,
            MahjongTile.S1, MahjongTile.S2, MahjongTile.S3,
            MahjongTile.P7,
        )

        var running = true
        var exception: Throwable? = null

        val writerThread = thread {
            while (running) {
                player.hands.add(MahjongTile.EAST)
                player.hands.sortBy { it.sortOrder }
                player.hands.remove(MahjongTile.EAST)
            }
        }

        val readerThread = thread {
            try {
                repeat(1000) {
                    player.hands.forEachIndexed { _, _ -> }
                    player.hands.toList()
                }
            } catch (t: Throwable) {
                exception = t
            } finally {
                running = false
            }
        }

        readerThread.join(5000)
        running = false
        writerThread.join(5000)

        assertTrue(exception == null, "Iteration threw exception: ${exception?.message}")
    }
}
