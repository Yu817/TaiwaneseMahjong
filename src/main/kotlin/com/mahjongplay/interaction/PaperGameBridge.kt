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
import com.mahjongplay.economy.EconomyObligation
import com.mahjongplay.economy.EconomyTransferService

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
    private val activeBukkitPlayers: List<Player>
        get() = game.realPlayers.mapNotNull { runCatching { Bukkit.getPlayer(UUID.fromString(it.uuid)) }.getOrNull() }

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
        val playingUUIDs = game.realPlayers.map { it.uuid }.toSet()
        tableManager.getSession(game.tableId)?.table?.setHiddenFromPlayers(playingUUIDs)
        val hasFillerBots = game.players.any { !it.isRealPlayer }
        val isRealMoney = game.rule.moneyMatch && !hasFillerBots
        MahjongChatFormat.gameStart(isRealMoney).forEach(::broadcast)
        startHudUpdates()
        turnTimerBar.cleanup()
        turnTimerBar.show()

        val seatTask = Runnable {
            tableManager.getSession(game.tableId)?.table?.releaseAllChairPassengers()
            tableManager.teleportPlayersToSeats(game)
            forEachPlayer { player ->
                val mjPlayer = game.realPlayers.find { it.uuid == player.uniqueId.toString() }
                if (game.rule.seatWindDrawEnabled) {
                    player.showTitle(
                        Title.title(
                            Component.text("【開局抓位・抓風】", NamedTextColor.GOLD)
                                .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD),
                            Component.text("請依序抽取風牌！(按【F 鍵】可開啟 🤖 託管代打)", NamedTextColor.GREEN),
                            Title.Times.times(Duration.ofMillis(150), Duration.ofSeconds(3), Duration.ofMillis(500))
                        )
                    )
                } else {
                    val wind = mjPlayer?.let { ActionBarHUD.seatWindOf(game, it) }
                    player.showTitle(
                        Title.title(
                            Component.text("${wind?.displayName ?: "?"}家", NamedTextColor.GOLD)
                                .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD),
                            Component.text("你的牌就在面前 • 按【F 鍵】可開啟 🤖 託管代打", NamedTextColor.GREEN),
                            Title.Times.times(Duration.ofMillis(150), Duration.ofSeconds(3), Duration.ofMillis(500))
                        )
                    )
                }
            }
            updateHud()
        }
        if (Bukkit.isPrimaryThread()) seatTask.run()
        else Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, seatTask)
    }

    override fun onRoundStart(game: MahjongGame, round: MahjongRound) {
        runCatching {
            MahjongPlayPlugin.instance.statsManager.recordHandsPlayed(game.players.map { it.uuid })
        }
        renderer.onRoundStart(game, round)
        broadcast(MahjongChatFormat.roundStart(round.displayName(), round.honba))
        forEachPlayer { player ->
            val mjPlayer = game.realPlayers.find { it.uuid == player.uniqueId.toString() }
            val wind = mjPlayer?.let { ActionBarHUD.seatWindOf(game, it) }
            val title = Title.title(
                Component.text(round.displayName(), NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD),
                Component.text(
                    "${wind?.displayName ?: "?"}家  •  連莊${round.honba}  •  按【F 鍵】開啟託管代打",
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

    override fun onSeatWindDrawStarted(event: SeatWindDrawStartEvent) {
        renderer.onSeatWindDrawStarted(event)
    }

    override fun onSeatWindTurnPrompt(event: SeatWindTurnPromptEvent) {
        renderer.onSeatWindTurnPrompt(event)
    }

    override fun onSeatWindTilePicked(event: SeatWindTilePickedEvent) {
        renderer.onSeatWindTilePicked(event)
    }

    override fun onSeatWindDrawCompleted(event: SeatWindDrawCompleteEvent) {
        renderer.onSeatWindDrawCompleted(event)
        updateHud()
    }

    override fun onWallInitialized(event: WallInitializedEvent) {
        renderer.onWallInitialized(event)
    }

    override fun onOpeningDiceStarted(event: OpeningDiceEvent) {
        renderer.onOpeningDiceStarted(event)
        val dealer = game.seat.getOrNull(event.dealerSeatIndex)?.displayName ?: "莊家"
        showEventTitle(
            Component.text("莊家擲骰子", NamedTextColor.GOLD),
            Component.text(dealer, NamedTextColor.AQUA),
        )
    }

    override fun onOpeningDiceCompleted(event: OpeningDiceEvent) {
        renderer.onOpeningDiceCompleted(event)
        val wallOwner = game.seat.getOrNull(event.dice.selectedWall(event.dealerSeatIndex))?.displayName ?: "牌牆"
        showEventTitle(
            Component.text("骰點 ${event.dice.total}", NamedTextColor.GOLD),
            Component.text("${event.dice.values.joinToString(" + ")}，從 $wallOwner 的牌牆開門", NamedTextColor.YELLOW),
        )
    }

    override fun onTileDrawStarted(event: TileDrawEvent) {
        renderer.onTileDrawStarted(event)
    }

    override fun onTileDrawCompleted(event: TileDrawEvent) {
        renderer.onTileDrawCompleted(event)
        if (event.liveWallSize == 16) {
            broadcast(
                MahjongChatFormat.ALERT_PREFIX.append(
                    Component.text("牌牆剩餘 16 張，即將進入快流局階段！", NamedTextColor.GOLD)
                )
            )
        } else if (event.liveWallSize == 8) {
            broadcast(
                MahjongChatFormat.ALERT_PREFIX.append(
                    Component.text("牌牆僅剩最後 8 張，進入快流局階段！", NamedTextColor.RED)
                        .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                )
            )
            forEachPlayer { player ->
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 1.0f, 1.5f)
                player.showTitle(
                    Title.title(
                        Component.text("⚠️ 快流局警告", NamedTextColor.RED).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD),
                        Component.text("活牌僅剩最後 8 張！", NamedTextColor.YELLOW),
                        Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofMillis(300))
                    )
                )
            }
        } else if (event.liveWallSize == 4) {
            broadcast(
                MahjongChatFormat.ALERT_PREFIX.append(
                    Component.text("牌牆僅剩最後 4 張（最後一輪摸牌）！", NamedTextColor.DARK_RED)
                        .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                )
            )
            forEachPlayer { player ->
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.PLAYERS, 1.0f, 1.8f)
                player.showTitle(
                    Title.title(
                        Component.text("🚨 最後一輪摸牌", NamedTextColor.DARK_RED).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD),
                        Component.text("活牌僅剩最後 4 張！", NamedTextColor.RED),
                        Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofMillis(300))
                    )
                )
            }
        }
    }

    override fun onTileDrawn(player: MahjongPlayerBase, tile: MahjongTile) {
        renderer.onTileDrawn(player, tile)
        MahjongSoundHelper.playTileDraw(renderer.tableCenter, activeBukkitPlayers)
    }

    override fun onFlowerDrawn(player: MahjongPlayerBase, flower: MahjongTile) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            MahjongSoundHelper.playFlower(renderer.tableCenter, activeBukkitPlayers)
            showEventTitle(
                Component.text("補花！", NamedTextColor.LIGHT_PURPLE),
                Component.text("${player.displayName} · ${flower.displayName}", NamedTextColor.AQUA),
            )
            broadcast(MahjongChatFormat.flowerDrawn(player.displayName, flower))
        })
    }

    override fun onTileDiscarded(player: MahjongPlayerBase, tile: MahjongTile) {
        renderer.onTileDiscarded(player, tile)
        MahjongSoundHelper.playTileDiscard(renderer.tableCenter, activeBukkitPlayers)
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
        MahjongSoundHelper.playChii(renderer.tableCenter, activeBukkitPlayers)
        showEventTitle(Component.text("吃！", NamedTextColor.GREEN), Component.text(player.displayName, NamedTextColor.AQUA))
        broadcast(MahjongChatFormat.chii(player.displayName, from.displayName, claimedTile))
    }

    override fun onPon(player: MahjongPlayerBase, claimedTile: MahjongTile, from: MahjongPlayerBase) {
        renderer.onPon(player, claimedTile, from)
        MahjongSoundHelper.playPon(renderer.tableCenter, activeBukkitPlayers)
        showEventTitle(Component.text("碰！", NamedTextColor.AQUA), Component.text(player.displayName, NamedTextColor.AQUA))
        broadcast(MahjongChatFormat.pon(player.displayName, from.displayName, claimedTile))
    }

    override fun onKan(player: MahjongPlayerBase, tile: MahjongTile, kanType: String, from: MahjongPlayerBase?) {
        renderer.onKan(player, tile, kanType, from)
        MahjongSoundHelper.playKan(renderer.tableCenter, activeBukkitPlayers)
        val label = when (kanType) {
            "ankan" -> "暗槓"
            "kakan" -> "加槓"
            else -> "明槓"
        }
        showEventTitle(
            Component.text("$label！", NamedTextColor.DARK_AQUA),
            Component.text(player.displayName, NamedTextColor.AQUA),
        )
        broadcast(MahjongChatFormat.kan(player.displayName, kanType, tile))
    }

    override fun onTsumo(player: MahjongPlayerBase, tile: MahjongTile, settlement: TaiwanSettlement) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable { renderer.revealHands(player) })
        MahjongSoundHelper.playTsumo(renderer.tableCenter, activeBukkitPlayers)
        showEventTitle(Component.text("自摸！", NamedTextColor.GOLD), Component.text(player.displayName, NamedTextColor.AQUA))
        MahjongChatFormat.tsumoCard(player, settlement).forEach(::broadcast)
        runCatching {
            MahjongPlayPlugin.instance.statsManager.recordTsumo(player.uuid, player.displayName, settlement)
        }
    }

    override fun onRon(winners: List<MahjongPlayerBase>, loser: MahjongPlayerBase, tile: MahjongTile, settlements: List<TaiwanSettlement>) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable { winners.forEach { renderer.revealHands(it) } })
        MahjongSoundHelper.playRon(renderer.tableCenter, activeBukkitPlayers)
        showEventTitle(Component.text("胡牌！", NamedTextColor.RED), Component.text(winners.joinToString(", ") { it.displayName }, NamedTextColor.AQUA))
        MahjongChatFormat.ronCard(winners, loser, settlements).forEach(::broadcast)
        runCatching {
            winners.forEachIndexed { i, w ->
                val s = settlements.getOrElse(i) { settlements.first() }
                MahjongPlayPlugin.instance.statsManager.recordRon(w.uuid, w.displayName, loser.uuid, loser.displayName, s)
            }
        }
    }

    override fun onDraw(draw: ExhaustiveDraw, settlement: ScoreSettlement) {
        if (draw == ExhaustiveDraw.NORMAL) {
            Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
                game.players.filter { it.isTenpai }.forEach { renderer.revealHands(it) }
            })
        }
        MahjongSoundHelper.playDraw(renderer.tableCenter, activeBukkitPlayers)
        showEventTitle(draw.toText().color(NamedTextColor.YELLOW), Component.text("流局", NamedTextColor.GRAY))
        val tenpais = game.players.map { it.displayName to it.isTenpai }
        MahjongChatFormat.drawCard(draw.name, tenpais).forEach(::broadcast)
    }

    override fun onScoreSettlement(settlement: ScoreSettlement) {
        MahjongSoundHelper.playScoreSettlement(renderer.tableCenter, activeBukkitPlayers)
        val scores = settlement.rankedScoreList.map { it.scoreItem.displayName to it.scoreTotal }
        MahjongChatFormat.roundScoreSummary(scores).forEach(::broadcast)
    }

    override fun onEconomyObligation(obligation: EconomyObligation) {
        val hasFillerBots = game.players.any { !it.isRealPlayer }
        if (!game.rule.moneyMatch || hasFillerBots) return

        val plugin = MahjongPlayPlugin.instance
        val economy = plugin.currentEconomy()
        if (economy == null) {
            plugin.recordEconomyPayment(obligation.payerUUID, obligation.winnerUUID, obligation.amount.toDouble())
            broadcast(Component.text("[麻將經濟] ${plugin.economyUnavailableReason()} 本筆真人付款已記錄為待人工處理，不會無聲略過。", NamedTextColor.RED))
            return
        }
        val result = EconomyTransferService(economy).transfer(obligation)
        val payerName = game.players.find { it.uuid == obligation.payerUUID }?.displayName ?: "付款者"
        val winnerName = game.players.find { it.uuid == obligation.winnerUUID }?.displayName ?: "贏家"
        if (!result.success) {
            // The score settlement has already happened. A provider failure
            // must therefore remain a durable winner-payment obligation,
            // never an untracked score-only result.
            plugin.recordEconomyPayment(obligation.payerUUID, obligation.winnerUUID, result.requestedAmount)
            if (result.unrefundedAmount > 0.0) {
                plugin.recordEconomyRefund(obligation.payerUUID, result.unrefundedAmount)
            }
            broadcast(Component.text("[麻將經濟] $payerName → $winnerName 轉帳失敗：${result.error}", NamedTextColor.RED))
            return
        }
        broadcast(
            MahjongChatFormat.ECO_PREFIX.append(
                Component.text("$payerName 支付 ${economy.format(result.paidAmount)} 給 $winnerName", NamedTextColor.GREEN)
            )
        )
        if (result.shortfall > 0.0) {
            plugin.recordEconomyPayment(obligation.payerUUID, obligation.winnerUUID, result.shortfall)
            broadcast(
                MahjongChatFormat.ECO_PREFIX.append(
                    Component.text(
                        "$payerName 餘額不足，尚有 ${economy.format(result.shortfall)} 未支付。",
                        NamedTextColor.YELLOW,
                    )
                )
            )
        }
    }

    override fun shouldTerminateGame(game: MahjongGame): Boolean {
        val settings = tableManager.settings
        if (!settings.economyEnabled || !settings.economyBankruptcyEnabled) return false
        val hasFillerBots = game.players.any { !it.isRealPlayer }
        if (!game.rule.moneyMatch || hasFillerBots) return false

        val humanPlayers = game.players.filterIsInstance<MahjongPlayer>()
        if (humanPlayers.size < 2) return false

        val economy = MahjongPlayPlugin.instance.currentEconomy() ?: return false
        // 最低續玩門檻：至少要有 1 底（若底分為 0 則至少要有 1 台，若均為 0 則為 1.0）
        val minRequired = when {
            game.rule.basePoints > 0 -> game.rule.basePoints.toDouble()
            game.rule.pointsPerTai > 0 -> game.rule.pointsPerTai.toDouble()
            else -> 1.0
        }

        val bankruptPlayers = humanPlayers.filter {
            economy.balance(it.uuid) < minRequired
        }

        if (bankruptPlayers.isNotEmpty()) {
            val names = bankruptPlayers.joinToString("、") { it.displayName }
            MahjongChatFormat.bankruptcyAlert(names).forEach(::broadcast)
            return true
        }
        return false
    }

    override fun onGameEnd(game: MahjongGame, scoreList: List<ScoreItem>) {
        val isRanked = game.players.all { it.isRealPlayer || (it is MahjongPlayer && it.isBotTakeover) }
        runCatching {
            MahjongPlayPlugin.instance.statsManager.recordMatchEnd(scoreList, isRankedMatch = isRanked)
        }
        val task = Runnable {
            renderer.onGameEnd(game, scoreList)
            stopHudUpdates()
            stopDiscardHoverUpdates()
            turnTimerBar.cleanup()
            stopTurnParticleTask()
            tableManager.resetCenterInspection(game)
            // 剃除因離線而託管的玩家，避免永遠卡在牌桌隊列中
            val offlinePlayers = game.players.filterIsInstance<MahjongPlayer>().filter {
                it.isQuitOffline || (Bukkit.getPlayer(UUID.fromString(it.uuid))?.isOnline != true)
            }
            offlinePlayers.forEach { op ->
                tableManager.leaveTable(op.uuid)
                runCatching { UUID.fromString(op.uuid) }.getOrNull()?.let { tableManager.releasePlayerFromChairs(it) }
            }

            tableManager.getSession(game.tableId)?.let {
                it.table.updateTurnDisplay(null)
                tableManager.updateTableDisplay(it)
                it.table.showActionButtons()
                tableManager.registerJoinInteraction(it)
            }
            MahjongChatFormat.gameEndPodium(scoreList).forEach(::broadcast)
        }
        if (MahjongPlayPlugin.instance.isEnabled) Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, task) else task.run()
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

    fun updateHud() = ActionBarHUD.sendUpdate(game)

    fun broadcast(message: Component) = forEachPlayer { it.sendMessage(message) }

    private fun forEachPlayer(action: (Player) -> Unit) {
        game.players.forEach { player -> Bukkit.getPlayer(UUID.fromString(player.uuid))?.let(action) }
    }

    fun cleanup() {
        stopHudUpdates()
        stopDiscardHoverUpdates()
        turnTimerBar.cleanup()
        stopTurnParticleTask()
        tableManager.resetCenterInspection(game)
    }

    fun hideBarForPlayer(playerUUID: String) = turnTimerBar.hideForPlayer(playerUUID)

    override fun onPendingActionStart(player: MahjongPlayer, behaviors: List<MahjongGameBehavior>, timeoutSeconds: Int) {
        Bukkit.getScheduler().runTask(MahjongPlayPlugin.instance, Runnable {
            turnTimerBar.startAction(player, behaviors, timeoutSeconds)
            renderer.spawnActionOptions(player.uuid, player.actionOptions)
            Bukkit.getPlayer(UUID.fromString(player.uuid))?.let { renderer.refreshDiscardHover(it) }
            val isDiscardTurn = MahjongGameBehavior.DISCARD in behaviors
            if (isDiscardTurn) {
                updateTurnDisplay(player, "請出牌", NamedTextColor.YELLOW)
            } else {
                updateTurnDisplay(null, "等待操作...", NamedTextColor.GRAY)
            }
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
        val currentSeat = game.seat.getOrNull(seatIndex) ?: return
        val player = Bukkit.getPlayer(UUID.fromString(currentSeat.uuid)) ?: return
        MahjongSoundHelper.playTurnPrompt(location, player)
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
                    MahjongGameBehavior.RON -> "胡牌"
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
