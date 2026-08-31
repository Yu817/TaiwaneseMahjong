package com.mahjongplay.stats

import com.mahjongplay.model.ScoreItem
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MahjongStatsTest {

    @Test
    fun `stats calculations compute accurate win rates and titles`() {
        val stats = MahjongPlayerStats(
            uuid = "test-uuid-1",
            playerName = "Tester",
            totalMatches = 10,
            firstPlaces = 4,
            secondPlaces = 3,
            thirdPlaces = 2,
            fourthPlaces = 1,
            totalNetScore = 65000,
            ratingPoints = 1550,
            totalHands = 40,
            tsumoCount = 6,
            ronCount = 6,
            dealInCount = 4,
            totalTaiWon = 48,
        )

        assertEquals(40.0, stats.winRate)
        assertEquals(70.0, stats.top2Rate)
        assertEquals(10.0, stats.fourthPlaceRate)
        assertEquals(2.0, stats.averagePlacement)
        assertEquals(30.0, stats.winHandRate)
        assertEquals(10.0, stats.dealInRate)
        assertEquals(50.0, stats.tsumoRate)
        assertEquals(4.0, stats.averageTai)
        assertEquals("🏆 四段・雀豪 ★★★", stats.rankTitle)
    }

    @Test
    fun `manager records match end and ranks leaderboard`() {
        val tempDir = File.createTempFile("mjstats", "test").apply {
            delete()
            mkdirs()
        }
        try {
            val mgr = MahjongStatsManager(tempDir)
            val scoreList = listOf(
                ScoreItem(displayName = "P1", stringUUID = "p1-uuid", isRealPlayer = true, scoreOrigin = 32000, scoreChange = 32000),
                ScoreItem(displayName = "P2", stringUUID = "p2-uuid", isRealPlayer = true, scoreOrigin = 12000, scoreChange = 12000),
                ScoreItem(displayName = "P3", stringUUID = "p3-uuid", isRealPlayer = true, scoreOrigin = -14000, scoreChange = -14000),
                ScoreItem(displayName = "P4", stringUUID = "p4-uuid", isRealPlayer = true, scoreOrigin = -30000, scoreChange = -30000),
            )

            mgr.recordMatchEnd(scoreList)

            val p1 = mgr.getStats("p1-uuid")
            assertEquals(1, p1.totalMatches)
            assertEquals(1, p1.firstPlaces)
            assertEquals(32000, p1.totalNetScore)
            assertEquals(1, p1.recentHistory.size)
            assertEquals(1, p1.recentHistory[0].rank)

            val p4 = mgr.getStats("p4-uuid")
            assertEquals(1, p4.fourthPlaces)
            assertEquals(-30000, p4.totalNetScore)

            val leaderboard = mgr.getLeaderboard(5)
            assertEquals(4, leaderboard.size)
            assertEquals("p1-uuid", leaderboard[0].uuid)
            assertEquals("p2-uuid", leaderboard[1].uuid)
            assertEquals("p3-uuid", leaderboard[2].uuid)
            assertEquals("p4-uuid", leaderboard[3].uuid)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `human seat keeps match stats when bot takeover marks score non-real`() {
        val tempDir = File.createTempFile("mjstats-takeover", "test").apply {
            delete()
            mkdirs()
        }
        val manager = MahjongStatsManager(tempDir)
        try {
            manager.markHumanPlayer("human-seat", "Human")
            manager.recordMatchEnd(
                listOf(
                    ScoreItem("[Bot] Human", "human-seat", false, scoreOrigin = 16000, scoreChange = 0),
                    ScoreItem("Bot", "bot-seat", false, scoreOrigin = 0, scoreChange = 0),
                ),
            )

            assertEquals(1, manager.getStats("human-seat").totalMatches)
            assertEquals(1, manager.getStats("human-seat").firstPlaces)
            assertEquals(1, manager.getLeaderboard().size)
            assertEquals("human-seat", manager.getLeaderboard().single().uuid)
        } finally {
            manager.shutdown()
            tempDir.deleteRecursively()
        }
    }
}
