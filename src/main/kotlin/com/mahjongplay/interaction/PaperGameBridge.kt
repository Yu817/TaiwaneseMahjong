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
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import java.time.Duration
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Paper 顯示／互動橋接；規則與台數計算留在 Bukkit 無關的核心類別。 */
class PaperGameBridge(
    val game: MahjongGame,
    val renderer: BoardRenderer,
    val tableManager: com.mahjongplay.table.MahjongTableManager
) : GameEventListener, PendingActionListener {
    private var hudTaskId: Int = -1
    private var discardHoverTaskId: Int = -1
    private var turnParticleTaskId: Int = -1
    private var activeTurnPlayerUUID: String? = null
    private var activeTurnSeatIndex: Int = -1
    private val turnTimerBar = TurnTimerBar(game)

    override fun onGameStart(game: MahjongGame) {
        renderer.onGameStart(game)
        game.realPlayers.forEach {
            it.gameId = game.tableId
            it.pendingActionListener = this
        }
        tableManager.getSession(game.tableId)?.let { tableManager.updateTableDisplay(it) }
        stopTurnParticleTask()
        startDiscardHoverUpdates()
        updateTurnDisplay(null)
        broadcast(Component.text("[台麻] 遊戲開始！", NamedTextColor.GOLD))
        startHudUpdates()
        turnTimerBar.cleanup()
        turnTimerBar.show()

        val seatTask = Runnable {
            tableManager.getSession(game.tableId)?.table?.releaseAllChairPassengers()
            tableManager.teleportPlayersToSeats(game)
            forEachPlayer { player ->
                val mjPlayer = game.realPlayers.find { it.uuid == player.uniqueId.toString() }
                val wind = mjPlayer?.let { ActionBarHUD.seatWindOf(game, it) }
                player.showTitle(
                    Title.title(
                        Component.text("${wind?.displayName ?: "?"}家", NamedTextColor.GOLD)
                            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD),
                        Component.text("你的牌就在面前，準備開始！", NamedTextColor.GREEN),
                        Title.Times.times(Duration.ofMillis(150), Duration.ofSeconds(2), Duration.ofMillis(350))
                    )
                )
            }
            updateHud()
        }
        if (Bukkit.isPrimaryThread()) seatTask.run()
        else Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, seatTask)
    }

    override fun onRoundStart(game: MahjongGame, round: MahjongRound) {
        renderer.onRoundStart(game, round)
        forEachPlayer { player ->
            val mjPlayer = game.realPlayers.find { it.uuid == player.uniqueId.toString() }
            val wind = mjPlayer?.let { ActionBarHUD.seatWindOf(game, it) }
            val title = Title.title(
                Component.text(round.displayName(), NamedTextColor.GOLD),
                Component.text(
                    "${wind?.displayName ?: "?"}家  •  連莊${round.honba}  •  牌在你面前",
                    NamedTextColor.YELLOW
                ),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(500))
            )
            player.showTitle(title)
        }
    }

    override fun onTurnChanged(game: MahjongGame, player: MahjongPlayerBase) {
        val action = if (player.isRealPlayer) "準備操作" else "電腦思考中"
        scheduleTurnChange(player, action)
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
            stopDiscardHoverUpdates()
            turnTimerBar.cleanup()
            stopTurnParticleTask()
            tableManager.getSession(game.tableId)?.let {
                it.table.updateTurnDisplay(null)
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

    private fun startDiscardHoverUpdates() {
        stopDiscardHoverUpdates()
        discardHoverTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
            MahjongPlayPlugin.instance,
            {
                if (game.status != GameStatus.PLAYING) return@scheduleSyncRepeatingTask
                game.realPlayers.forEach { mjPlayer ->
                    Bukkit.getPlayer(UUID.fromString(mjPlayer.uuid))?.let { renderer.refreshDiscardHover(it) }
                }
            },
            0L,
            2L,
        )
    }

    private fun stopDiscardHoverUpdates() {
        if (discardHoverTaskId != -1) {
            Bukkit.getScheduler().cancelTask(discardHoverTaskId)
            discardHoverTaskId = -1
        }
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
        stopDiscardHoverUpdates()
        turnTimerBar.cleanup()
        stopTurnParticleTask()
    }

    fun hideBarForPlayer(playerUUID: String) = turnTimerBar.hideForPlayer(playerUUID)

    override fun onPendingActionStart(player: MahjongPlayer, behaviors: List<MahjongGameBehavior>, timeoutSeconds: Int) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            turnTimerBar.startAction(player, behaviors, timeoutSeconds)
            renderer.spawnActionOptions(player.uuid, player.actionOptions)
            Bukkit.getPlayer(UUID.fromString(player.uuid))?.let { renderer.refreshDiscardHover(it) }
            updateTurnDisplay(player, pendingActionText(behaviors), NamedTextColor.YELLOW)
            updateHud()
        })
    }

    override fun onPendingActionEnd(player: MahjongPlayer) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            turnTimerBar.endAction()
            renderer.clearActionOptions(player.uuid)
            renderer.previewTileForDiscard(player.uuid, null)
            updateHud()
        })
    }

    private fun scheduleTurnChange(player: MahjongPlayerBase, action: String) {
        val task = Runnable {
            if (game.status != GameStatus.PLAYING) return@Runnable
            updateTurnDisplay(player, action, NamedTextColor.YELLOW)
            updateTurnParticle(player)
        }
        if (Bukkit.isPrimaryThread()) task.run()
        else Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, task)
    }

    private fun updateTurnParticle(player: MahjongPlayerBase) {
        val seatIndex = game.seat.indexOf(player)
        if (seatIndex < 0) return

        val changed = activeTurnPlayerUUID != player.uuid || activeTurnSeatIndex != seatIndex
        activeTurnPlayerUUID = player.uuid
        activeTurnSeatIndex = seatIndex
        if (turnParticleTaskId == -1) {
            turnParticleTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                MahjongPlayPlugin.instance,
                { emitTurnParticles() },
                0L,
                6L,
            )
        }
        emitTurnParticles()
        if (changed) playTurnChangeSound(seatIndex)
    }

    private fun emitTurnParticles() {
        if (game.status != GameStatus.PLAYING || activeTurnSeatIndex < 0) {
            stopTurnParticleTask()
            return
        }

        val player = game.seat.getOrNull(activeTurnSeatIndex) ?: return
        val session = tableManager.getSession(game.tableId) ?: return
        val location = session.table.turnIndicatorLocation(activeTurnSeatIndex)
        val sideAxis = session.table.turnIndicatorSideAxis(activeTurnSeatIndex)
        val world = location.world ?: return
        val color = when (ActionBarHUD.seatWindOf(game, player)) {
            Wind.EAST -> Color.fromRGB(255, 170, 0)
            Wind.SOUTH -> Color.fromRGB(75, 220, 255)
            Wind.WEST -> Color.fromRGB(100, 255, 100)
            Wind.NORTH -> Color.fromRGB(210, 100, 255)
        }
        val dust = Particle.DustOptions(color, 1.0f)
        val phase = (System.currentTimeMillis() % 1600L).toDouble() / 1600.0 * (PI * 2.0)
        repeat(6) { index ->
            val angle = phase + index * (PI * 2.0 / 6.0)
            val particleLocation = location.clone().add(
                sideAxis[0] * cos(angle) * 0.23,
                sin(angle) * 0.18,
                sideAxis[1] * cos(angle) * 0.23,
            )
            world.spawnParticle(Particle.DUST, particleLocation, 1, 0.0, 0.0, 0.0, 0.0, dust)
        }
        world.spawnParticle(
            Particle.END_ROD,
            location.clone().add(0.0, 0.20, 0.0),
            1,
            0.02,
            0.04,
            0.02,
            0.0,
        )
    }

    private fun playTurnChangeSound(seatIndex: Int) {
        val location = tableManager.getSession(game.tableId)?.table?.turnIndicatorLocation(seatIndex) ?: return
        forEachPlayer { player ->
            player.playSound(location, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 0.55f, 1.2f)
        }
    }

    private fun stopTurnParticleTask() {
        if (turnParticleTaskId != -1) {
            Bukkit.getScheduler().cancelTask(turnParticleTaskId)
            turnParticleTaskId = -1
        }
        activeTurnPlayerUUID = null
        activeTurnSeatIndex = -1
    }

    private fun updateTurnDisplay(player: MahjongPlayerBase?, action: String? = null, actionColor: NamedTextColor = NamedTextColor.YELLOW) {
        val text = player?.let {
            val wind = ActionBarHUD.seatWindOf(game, it)
            Component.text("▶ 目前出牌：${wind.displayName}家 ${it.displayName}", NamedTextColor.AQUA)
                .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.text(action ?: "等待操作", actionColor).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
        }
        tableManager.getSession(game.tableId)?.table?.updateTurnDisplay(text)
    }

    private fun pendingActionText(behaviors: List<MahjongGameBehavior>): String {
        if (MahjongGameBehavior.DISCARD in behaviors) return "請出牌"

        val options = behaviors
            .filter { it != MahjongGameBehavior.SKIP }
            .distinct()
            .joinToString("／") { behavior ->
                when (behavior) {
                    MahjongGameBehavior.TSUMO -> "自摸"
                    MahjongGameBehavior.RON -> "榮和"
                    MahjongGameBehavior.CHII -> "吃"
                    MahjongGameBehavior.PON_OR_CHII -> "碰／吃"
                    MahjongGameBehavior.PON -> "碰"
                    MahjongGameBehavior.KAN -> "槓"
                    MahjongGameBehavior.MINKAN -> "明槓"
                    MahjongGameBehavior.ANKAN -> "暗槓"
                    MahjongGameBehavior.ANKAN_OR_KAKAN -> "暗槓／加槓"
                    MahjongGameBehavior.KAKAN -> "加槓"
                    else -> "動作"
                }
            }
        return if (options.isBlank()) "請選擇動作" else "請選擇：$options"
    }
}
