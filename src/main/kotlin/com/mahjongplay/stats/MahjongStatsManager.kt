package com.mahjongplay.stats

import com.mahjongplay.model.ScoreItem
import com.mahjongplay.model.TaiwanSettlement
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Durable player statistics with serialized, snapshot-based file writes. */
class MahjongStatsManager(private val dataFolder: File) {

    private val statsFolder = File(dataFolder, "stats")
    private val statsCache = ConcurrentHashMap<String, MahjongPlayerStats>()
    private val stateLock = Any()
    private val writeLock = Any()
    private val knownHumanUUIDs = ConcurrentHashMap.newKeySet<String>()
    private val acceptingWrites = AtomicBoolean(true)
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mahjong-stats-writer").apply { isDaemon = true }
    }

    init {
        statsFolder.mkdirs()
        synchronized(stateLock) { loadAll() }
    }

    fun getStats(uuid: String, fallbackName: String = ""): MahjongPlayerStats = synchronized(stateLock) {
        statsCache[uuid] ?: loadStats(uuid, fallbackName)
    }

    /** Remember a real player before a disconnect can turn their seat into a bot. */
    fun markHumanPlayer(uuid: String, playerName: String = "") {
        synchronized(stateLock) {
            knownHumanUUIDs += uuid
            val stats = statsCache[uuid] ?: loadStats(uuid, playerName)
            if (stats.playerName.isBlank() && playerName.isNotBlank()) stats.playerName = playerName
        }
    }

    fun getStatsByName(name: String): MahjongPlayerStats? = synchronized(stateLock) {
        statsCache.values.firstOrNull { it.playerName.equals(name, ignoreCase = true) }
            ?: statsFolder.listFiles { file -> file.extension == "yml" }
                ?.asSequence()
                ?.map { loadStats(it.nameWithoutExtension) }
                ?.firstOrNull { it.playerName.equals(name, ignoreCase = true) }
    }

    fun resetStats(uuid: String): Boolean = synchronized(stateLock) {
        val file = File(statsFolder, "$uuid.yml")
        if (file.exists()) file.delete()
        statsCache.remove(uuid)
        true
    }

    fun recordHandsPlayed(uuids: Collection<String>) {
        uuids.forEach { uuid ->
            val player = runCatching { UUID.fromString(uuid).let(Bukkit::getPlayer) }.getOrNull()
            // Do not create bot profiles.  On-join identity tracking identifies
            // a disconnected human seat while its bot takeover is active.
            if (player != null || knownHumanUUIDs.contains(uuid)) {
                synchronized(stateLock) {
                    if (player != null) knownHumanUUIDs += uuid
                    val stats = statsCache[uuid] ?: loadStats(uuid, player?.name ?: "")
                    stats.totalHands++
                    enqueueSnapshot(snapshotOf(stats))
                }
            }
        }
    }

    fun recordTsumo(winnerUUID: String, winnerName: String, settlement: TaiwanSettlement) {
        if (!settlement.isRealPlayer && !knownHumanUUIDs.contains(winnerUUID)) return
        synchronized(stateLock) {
            knownHumanUUIDs += winnerUUID
            val stats = statsCache[winnerUUID] ?: loadStats(winnerUUID, winnerName)
            stats.tsumoCount++
            stats.totalTaiWon += settlement.tai
            if (settlement.tai > stats.maxTaiInHand) {
                stats.maxTaiInHand = settlement.tai
                stats.maxTaiHandName = settlement.taiList.joinToString(", ") { it.name }.ifEmpty { "tsumo" }
            }
            enqueueSnapshot(snapshotOf(stats))
        }
    }

    fun recordRon(
        winnerUUID: String,
        winnerName: String,
        loserUUID: String,
        loserName: String,
        settlement: TaiwanSettlement,
    ) {
        synchronized(stateLock) {
            if (settlement.isRealPlayer || knownHumanUUIDs.contains(winnerUUID)) {
                knownHumanUUIDs += winnerUUID
                val winner = statsCache[winnerUUID] ?: loadStats(winnerUUID, winnerName)
                winner.ronCount++
                winner.totalTaiWon += settlement.tai
                if (settlement.tai > winner.maxTaiInHand) {
                    winner.maxTaiInHand = settlement.tai
                    winner.maxTaiHandName = settlement.taiList.joinToString(", ") { it.name }.ifEmpty { "ron" }
                }
                enqueueSnapshot(snapshotOf(winner))
            }
            if (knownHumanUUIDs.contains(loserUUID)) {
                val loser = statsCache[loserUUID] ?: loadStats(loserUUID, loserName)
                loser.dealInCount++
                enqueueSnapshot(snapshotOf(loser))
            }
        }
    }

    fun recordMatchEnd(scoreList: List<ScoreItem>, isRankedMatch: Boolean = true) {
        val sorted = scoreList.sortedByDescending { it.scoreOrigin }
        val now = System.currentTimeMillis()
        synchronized(stateLock) {
            sorted.forEachIndexed { rankIndex, item ->
                // A bot takeover has the human seat's UUID but reports
                // isRealPlayer=false. Unknown bot UUIDs stay excluded.
                if (!item.isRealPlayer && !knownHumanUUIDs.contains(item.stringUUID)) return@forEachIndexed

                val rank = rankIndex + 1
                val stats = statsCache[item.stringUUID] ?: loadStats(item.stringUUID, item.displayName)
                stats.totalMatches++
                stats.totalNetScore += item.scoreOrigin
                when (rank) {
                    1 -> stats.firstPlaces++
                    2 -> stats.secondPlaces++
                    3 -> stats.thirdPlaces++
                    4 -> stats.fourthPlaces++
                }
                if (item.scoreOrigin > stats.maxMatchScore) stats.maxMatchScore = item.scoreOrigin

                // 計算天梯雀力 RP 增減（完全根據真人名次與段位係數，不受自訂底台影響）
                val rpDelta = if (isRankedMatch) {
                    calculateRPDelta(rank, stats.ratingPoints)
                } else 0

                stats.ratingPoints = (stats.ratingPoints + rpDelta).coerceAtLeast(0)
                stats.highestRatingPoints = maxOf(stats.highestRatingPoints, stats.ratingPoints)

                val scoreSign = if (item.scoreOrigin >= 0) "+${item.scoreOrigin}" else item.scoreOrigin.toString()
                val rpSign = if (rpDelta >= 0) "+$rpDelta" else "$rpDelta"
                val summary = if (isRankedMatch) "#${rank} ($scoreSign, $rpSign RP)" else "#${rank} ($scoreSign [休閒])"

                stats.recentHistory.add(
                    0,
                    MatchLogItem(
                        timestamp = now,
                        rank = rank,
                        scoreDelta = item.scoreOrigin,
                        rpDelta = rpDelta,
                        taiWon = 0,
                        summary = summary,
                    ),
                )
                while (stats.recentHistory.size > 21) stats.recentHistory.removeAt(stats.recentHistory.lastIndex)
                enqueueSnapshot(snapshotOf(stats), wait = true)
            }
        }
    }

    private fun calculateRPDelta(rank: Int, currentRP: Int): Int {
        return when (rank) {
            1 -> 60 // 🥇 一位固定 +60 RP
            2 -> 20 // 🥈 二位固定 +20 RP
            3 -> when {
                currentRP < 600 -> 0    // 新手雀生/雀士 三位不扣分
                currentRP < 1200 -> -15 // 雀傑 三位 -15 RP
                currentRP < 2200 -> -20 // 雀豪 三位 -20 RP
                else -> -30             // 雀聖/雀神 三位 -30 RP
            }
            4 -> when {
                currentRP < 600 -> -20  // 新手雀生/雀士 四位保護 -20 RP
                currentRP < 1200 -> -45 // 雀傑 四位 -45 RP
                currentRP < 2200 -> -60 // 雀豪 四位 -60 RP
                else -> -80             // 雀聖/雀神 四位 -80 RP
            }
            else -> 0
        }
    }

    fun getLeaderboard(limit: Int = 10): List<MahjongPlayerStats> = synchronized(stateLock) {
        statsCache.values
            .filter { it.totalMatches > 0 }
            .sortedWith(compareByDescending<MahjongPlayerStats> { it.ratingPoints }.thenByDescending { it.winRate })
            .take(limit)
    }

    private fun loadAll() {
        statsFolder.listFiles { file -> file.extension == "yml" }?.forEach {
            knownHumanUUIDs += it.nameWithoutExtension
            loadStats(it.nameWithoutExtension)
        }
    }

    private fun loadStats(uuid: String, fallbackName: String = ""): MahjongPlayerStats {
        val file = File(statsFolder, "$uuid.yml")
        if (!file.exists()) return MahjongPlayerStats(uuid, fallbackName).also { statsCache[uuid] = it }

        val yaml = YamlConfiguration.loadConfiguration(file)
        val stats = MahjongPlayerStats(
            uuid = uuid,
            playerName = yaml.getString("playerName") ?: fallbackName,
            totalMatches = yaml.getInt("totalMatches", 0),
            firstPlaces = yaml.getInt("firstPlaces", 0),
            secondPlaces = yaml.getInt("secondPlaces", 0),
            thirdPlaces = yaml.getInt("thirdPlaces", 0),
            fourthPlaces = yaml.getInt("fourthPlaces", 0),
            totalNetScore = yaml.getInt("totalNetScore", 0),
            ratingPoints = yaml.getInt("ratingPoints", 1000),
            highestRatingPoints = yaml.getInt("highestRatingPoints", yaml.getInt("ratingPoints", 1000)),
            totalHands = yaml.getInt("totalHands", 0),
            tsumoCount = yaml.getInt("tsumoCount", 0),
            ronCount = yaml.getInt("ronCount", 0),
            dealInCount = yaml.getInt("dealInCount", 0),
            totalTaiWon = yaml.getInt("totalTaiWon", 0),
            maxTaiInHand = yaml.getInt("maxTaiInHand", 0),
            maxTaiHandName = yaml.getString("maxTaiHandName") ?: "",
            maxMatchScore = yaml.getInt("maxMatchScore", 0),
            maxDealerStreak = yaml.getInt("maxDealerStreak", 0),
        )
        yaml.getMapList("recentHistory").forEach { map ->
            stats.recentHistory += MatchLogItem(
                timestamp = (map["timestamp"] as? Number)?.toLong() ?: 0L,
                rank = (map["rank"] as? Number)?.toInt() ?: 1,
                scoreDelta = (map["scoreDelta"] as? Number)?.toInt() ?: 0,
                rpDelta = (map["rpDelta"] as? Number)?.toInt() ?: 0,
                taiWon = (map["taiWon"] as? Number)?.toInt() ?: 0,
                summary = map["summary"] as? String ?: "",
            )
        }
        statsCache[uuid] = stats
        return stats
    }

    /** Synchronous API retained for GUI/admin callers; replacement is atomic. */
    fun saveStats(stats: MahjongPlayerStats) {
        val snapshot = synchronized(stateLock) { snapshotOf(stats) }
        enqueueSnapshot(snapshot, wait = true)
    }

    private fun writeSnapshot(stats: MahjongPlayerStats) {
        synchronized(writeLock) {
            writeSnapshotLocked(stats)
        }
    }

    private fun writeSnapshotLocked(stats: MahjongPlayerStats) {
        val file = File(statsFolder, "${stats.uuid}.yml")
        val yaml = YamlConfiguration().apply {
            set("playerName", stats.playerName)
            set("totalMatches", stats.totalMatches)
            set("firstPlaces", stats.firstPlaces)
            set("secondPlaces", stats.secondPlaces)
            set("thirdPlaces", stats.thirdPlaces)
            set("fourthPlaces", stats.fourthPlaces)
            set("totalNetScore", stats.totalNetScore)
            set("ratingPoints", stats.ratingPoints)
            set("highestRatingPoints", stats.highestRatingPoints)
            set("totalHands", stats.totalHands)
            set("tsumoCount", stats.tsumoCount)
            set("ronCount", stats.ronCount)
            set("dealInCount", stats.dealInCount)
            set("totalTaiWon", stats.totalTaiWon)
            set("maxTaiInHand", stats.maxTaiInHand)
            set("maxTaiHandName", stats.maxTaiHandName)
            set("maxMatchScore", stats.maxMatchScore)
            set("maxDealerStreak", stats.maxDealerStreak)
            set("recentHistory", stats.recentHistory.map {
                mapOf(
                    "timestamp" to it.timestamp,
                    "rank" to it.rank,
                    "scoreDelta" to it.scoreDelta,
                    "rpDelta" to it.rpDelta,
                    "taiWon" to it.taiWon,
                    "summary" to it.summary,
                )
            })
        }
        runCatching {
            statsFolder.mkdirs()
            val temp = Files.createTempFile(statsFolder.toPath(), "${stats.uuid}.", ".yml.tmp").toFile()
            try {
                yaml.save(temp)
                try {
                    Files.move(
                        temp.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
    }

    private fun enqueueSnapshot(stats: MahjongPlayerStats, wait: Boolean = false) {
        if (!acceptingWrites.get()) return
        try {
            val future = writer.submit { writeSnapshot(stats) }
            if (wait) future.get()
        } catch (_: RejectedExecutionException) {
            // A shutdown flush owns the final durable state.
        }
    }

    fun saveAll() {
        val snapshots = synchronized(stateLock) { statsCache.values.map(::snapshotOf) }
        enqueueSnapshotsAndWait(snapshots)
    }

    /** Stop new queued writes, flush latest snapshots, then stop the writer. */
    fun shutdown() {
        val snapshots = synchronized(stateLock) {
            acceptingWrites.set(false)
            statsCache.values.map(::snapshotOf)
        }
        enqueueSnapshotsAndWait(snapshots, allowWhenClosed = true)
        writer.shutdown()
        runCatching { writer.awaitTermination(10, TimeUnit.SECONDS) }
    }

    private fun enqueueSnapshotsAndWait(
        snapshots: Collection<MahjongPlayerStats>,
        allowWhenClosed: Boolean = false,
    ) {
        if (snapshots.isEmpty()) return
        try {
            val future: Future<*> = writer.submit {
                if (allowWhenClosed || acceptingWrites.get()) snapshots.forEach(::writeSnapshot)
            }
            future.get()
        } catch (_: RejectedExecutionException) {
            // Idempotent second shutdown.
        }
    }

    private fun snapshotOf(stats: MahjongPlayerStats): MahjongPlayerStats =
        stats.copy(recentHistory = stats.recentHistory.toMutableList())
}
