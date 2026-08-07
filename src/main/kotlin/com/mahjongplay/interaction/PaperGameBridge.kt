package com.mahjongplay.interaction

import com.mahjongplay.MahjongPlayPlugin
import com.mahjongplay.display.BoardRenderer
import com.mahjongplay.game.*
import com.mahjongplay.model.*
import com.mahjongplay.ui.TurnTimerBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.time.Duration
import java.util.UUID

/** Paper 顯示／互動橋接；規則與台數計算留在 Bukkit 無關的核心類別。 */
class PaperGameBridge(
    val game: MahjongGame,
    val renderer: BoardRenderer,
    val tableManager: com.mahjongplay.table.MahjongTableManager
) : GameEventListener, PendingActionListener {
    private var hudTaskId: Int = -1
    private val turnTimerBar = TurnTimerBar(game)

    override fun onGameStart(game: MahjongGame) {
        renderer.onGameStart(game)
        game.realPlayers.forEach {
            it.gameId = game.tableId
            it.pendingActionListener = this
        }
        tableManager.getSession(game.tableId)?.let { tableManager.updateTableDisplay(it) }
        broadcast(Component.text("[台麻] 遊戲開始！", NamedTextColor.GOLD))
        startHudUpdates()
        turnTimerBar.cleanup()
        turnTimerBar.show()
    }

    override fun onRoundStart(game: MahjongGame, round: MahjongRound) {
        renderer.onRoundStart(game, round)
        val title = Title.title(
            Component.text(round.displayName(), NamedTextColor.GOLD),
            Component.text("連莊${round.honba}", NamedTextColor.YELLOW),
            Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(500))
        )
        forEachPlayer { it.showTitle(title) }
    }

    override fun onTileDrawn(player: MahjongPlayerBase, tile: MahjongTile) {
        renderer.onTileDrawn(player, tile)
    }

    override fun onTileDiscarded(player: MahjongPlayerBase, tile: MahjongTile) {
        renderer.onTileDiscarded(player, tile)
        updateHud()
    }

    override fun onHandsUpdated(player: MahjongPlayerBase) {
        renderer.onHandsUpdated(player)
        updateHud()
    }

    private val quickTitleTimes = Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(800), Duration.ofMillis(200))

    private fun showEventTitle(main: Component, subtitle: Component) {
        val title = Title.title(main, subtitle, quickTitleTimes)
        forEachPlayer { it.showTitle(title) }
    }

    override fun onChii(player: MahjongPlayerBase, claimedTile: MahjongTile, from: MahjongPlayerBase) {
        renderer.onChii(player, claimedTile, from)
        showEventTitle(Component.text("吃！", NamedTextColor.GREEN), Component.text(player.displayName, NamedTextColor.AQUA))
    }

    override fun onPon(player: MahjongPlayerBase, claimedTile: MahjongTile, from: MahjongPlayerBase) {
        renderer.onPon(player, claimedTile, from)
        showEventTitle(Component.text("碰！", NamedTextColor.BLUE), Component.text(player.displayName, NamedTextColor.AQUA))
    }

    override fun onKan(player: MahjongPlayerBase, tile: MahjongTile, kanType: String, from: MahjongPlayerBase?) {
        renderer.onKan(player, tile, kanType, from)
        showEventTitle(Component.text("槓！", NamedTextColor.DARK_AQUA), Component.text(player.displayName, NamedTextColor.AQUA))
    }

    override fun onTsumo(player: MahjongPlayerBase, tile: MahjongTile, settlement: TaiwanSettlement) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable { renderer.revealHands(player) })
        showEventTitle(Component.text("自摸！", NamedTextColor.GOLD), Component.text(player.displayName, NamedTextColor.AQUA))
        sendTaiSummary(settlement)
    }

    override fun onRon(winners: List<MahjongPlayerBase>, loser: MahjongPlayerBase, tile: MahjongTile, settlements: List<TaiwanSettlement>) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable { winners.forEach { renderer.revealHands(it) } })
        showEventTitle(Component.text("榮和！", NamedTextColor.RED), Component.text(winners.joinToString(", ") { it.displayName }, NamedTextColor.AQUA))
        settlements.forEach { sendTaiSummary(it) }
    }

    override fun onDraw(draw: ExhaustiveDraw, settlement: ScoreSettlement) {
        if (draw == ExhaustiveDraw.NORMAL) {
            Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
                game.players.filter { it.isTenpai }.forEach { renderer.revealHands(it) }
            })
        }
        showEventTitle(draw.toText().color(NamedTextColor.YELLOW), Component.text("流局", NamedTextColor.GRAY))
    }

    override fun onScoreSettlement(settlement: ScoreSettlement) {
        settlement.rankedScoreList.forEachIndexed { index, ranked ->
            broadcast(
                Component.text("  ${index + 1}. ", NamedTextColor.YELLOW)
                    .append(Component.text(ranked.scoreItem.displayName, NamedTextColor.AQUA))
                    .append(Component.text("  ${ranked.scoreTotal}分", NamedTextColor.WHITE))
                    .append(Component.text(" (${ranked.scoreChangeText})", NamedTextColor.GRAY))
            )
        }
    }

    override fun onGameEnd(game: MahjongGame, scoreList: List<ScoreItem>) {
        val task = Runnable {
            renderer.onGameEnd(game, scoreList)
            stopHudUpdates()
            turnTimerBar.cleanup()
            tableManager.getSession(game.tableId)?.let {
                tableManager.updateTableDisplay(it)
                it.table.showActionButtons()
                tableManager.registerJoinInteraction(it)
            }
            broadcast(Component.text("[台麻] 遊戲結束！", NamedTextColor.GOLD))
            scoreList.sortedByDescending { it.scoreOrigin }.forEachIndexed { index, item ->
                broadcast(Component.text("  ${index + 1}. ${item.displayName}  ${item.scoreOrigin}分", NamedTextColor.YELLOW))
            }
        }
        if (MahjongPlayPlugin.instance.isEnabled) Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, task) else task.run()
    }

    private fun sendTaiSummary(settlement: TaiwanSettlement) {
        if (settlement.taiList.isEmpty() && settlement.tai == 0) return
        val items = settlement.taiList.joinToString(", ") { "${it.name}${it.tai}台" }
        val flowers = if (settlement.flowerCount > 0) "，花牌${settlement.flowerCount}張" else ""
        broadcast(
            Component.text("  台: $items$flowers", NamedTextColor.GREEN)
                .append(Component.text("  合計${settlement.tai}台，${settlement.score}分", NamedTextColor.YELLOW))
        )
    }

    private fun startHudUpdates() {
        hudTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(MahjongPlayPlugin.instance, { updateHud() }, 0L, 20L)
    }

    private fun stopHudUpdates() {
        if (hudTaskId != -1) {
            Bukkit.getScheduler().cancelTask(hudTaskId)
            hudTaskId = -1
        }
    }

    private fun updateHud() = ActionBarHUD.sendUpdate(game)

    private fun broadcast(message: Component) = forEachPlayer { it.sendMessage(message) }

    private fun forEachPlayer(action: (Player) -> Unit) {
        game.players.forEach { player -> Bukkit.getPlayer(UUID.fromString(player.uuid))?.let(action) }
    }

    fun cleanup() {
        stopHudUpdates()
        turnTimerBar.cleanup()
    }

    fun hideBarForPlayer(playerUUID: String) = turnTimerBar.hideForPlayer(playerUUID)

    override fun onPendingActionStart(player: MahjongPlayer, behaviors: List<MahjongGameBehavior>, timeoutSeconds: Int) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            turnTimerBar.startAction(player, behaviors, timeoutSeconds)
            renderer.spawnActionOptions(player.uuid, player.actionOptions)
        })
    }

    override fun onPendingActionEnd(player: MahjongPlayer) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            turnTimerBar.endAction()
            renderer.clearActionOptions(player.uuid)
        })
    }
}
