package com.mahjongplay.ui

import com.mahjongplay.MahjongPlayPlugin
import com.mahjongplay.game.MahjongGame
import com.mahjongplay.game.MahjongPlayerBase
import com.mahjongplay.model.MahjongGameBehavior
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import java.util.UUID

class TurnTimerBar(private val game: MahjongGame) {

    private var bar: BossBar? = null
    private var timerTaskId: Int = -1
    private var startTimeMs: Long = 0
    private var durationMs: Long = 0
    private var activePlayerUUID: String? = null
    private val shownPlayerUUIDs = mutableSetOf<UUID>()

    fun show() {
        if (bar != null) return
        val b = BossBar.bossBar(buildTitle(), 1.0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS)
        bar = b
        showToAll(b)
        startIdleUpdater()
    }

    fun hide() {
        cancelTimer()
        bar?.let { b ->
            shownPlayerUUIDs.forEach { uuid ->
                Bukkit.getPlayer(uuid)?.hideBossBar(b)
            }
            shownPlayerUUIDs.clear()
        }
        bar = null
    }

    fun startAction(player: MahjongPlayerBase, _actions: List<MahjongGameBehavior>, totalSeconds: Int) {
        cancelTimer()
        activePlayerUUID = player.uuid
        startTimeMs = System.currentTimeMillis()
        durationMs = totalSeconds * 1000L

        val b = bar ?: return
        b.progress(1.0f)
        b.color(BossBar.Color.GREEN)
        b.name(buildTitle())

        timerTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(MahjongPlayPlugin.instance, {
            val elapsed = System.currentTimeMillis() - startTimeMs
            val remaining = (durationMs - elapsed).coerceAtLeast(0)
            val progress = (remaining.toFloat() / durationMs).coerceIn(0f, 1f)
            b.progress(progress)
            b.color(when {
                progress > 0.5f -> BossBar.Color.GREEN
                progress > 0.25f -> BossBar.Color.YELLOW
                else -> BossBar.Color.RED
            })
            b.name(buildTitle())

            if (remaining <= 0) endAction()
        }, 0L, 2L)
    }

    fun endAction() {
        cancelTimer()
        activePlayerUUID = null
        bar?.let {
            it.progress(1.0f)
            it.color(BossBar.Color.GREEN)
            it.name(buildTitle())
        }
    }

    private fun buildTitle(): Component {
        val activePlayer = activePlayerUUID?.let { uuid ->
            game.players.firstOrNull { it.uuid == uuid }
        }
        return if (activePlayer == null) {
            Component.text("麻將", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
        } else {
            Component.text("目前：${activePlayer.displayName} 出牌", NamedTextColor.AQUA)
                .decorate(TextDecoration.BOLD)
        }
    }

    private fun showToAll(b: BossBar) {
        game.players.forEach { mjp ->
            val uuid = UUID.fromString(mjp.uuid)
            Bukkit.getPlayer(uuid)?.showBossBar(b)
            shownPlayerUUIDs.add(uuid)
        }
    }

    private fun cancelTimer() {
        if (timerTaskId != -1) {
            Bukkit.getScheduler().cancelTask(timerTaskId)
            timerTaskId = -1
        }
    }

    private var idleTaskId: Int = -1

    private fun startIdleUpdater() {
        idleTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(MahjongPlayPlugin.instance, {
            if (activePlayerUUID == null) {
                bar?.name(buildTitle())
            }
        }, 0L, 20L)
    }

    fun hideForPlayer(playerUUID: String) {
        val uuid = UUID.fromString(playerUUID)
        bar?.let { b -> Bukkit.getPlayer(uuid)?.hideBossBar(b) }
        shownPlayerUUIDs.remove(uuid)
    }

    fun cleanup() {
        cancelTimer()
        if (idleTaskId != -1) {
            Bukkit.getScheduler().cancelTask(idleTaskId)
            idleTaskId = -1
        }
        hide()
    }
}
