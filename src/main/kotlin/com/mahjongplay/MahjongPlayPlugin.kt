package com.mahjongplay

import com.mahjongplay.interaction.EntityInteractionListener
import com.mahjongplay.table.MahjongCommand
import com.mahjongplay.table.MahjongTableManager
import com.mahjongplay.config.MahjongSettings
import org.bukkit.command.Command
import org.bukkit.command.PluginCommand
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.command.PluginIdentifiableCommand
import org.bukkit.plugin.java.JavaPlugin
import fr.skytasul.glowingentities.GlowingEntities
import com.mahjongplay.economy.ServerEconomy
import com.mahjongplay.economy.EconomyRecoveryStore
import com.mahjongplay.interaction.VaultEconomyGateway
import java.io.File

class MahjongPlayPlugin : JavaPlugin(), Listener {

    companion object {
        lateinit var instance: MahjongPlayPlugin
            private set
    }

    lateinit var tableManager: MahjongTableManager
        private set

    lateinit var glowingEntities: GlowingEntities
        private set

    lateinit var statsManager: com.mahjongplay.stats.MahjongStatsManager
        private set

    var economy: ServerEconomy? = null
        private set

    private lateinit var economyRecoveryStore: EconomyRecoveryStore
    private var economyRecoveryBlocked: Boolean = false
    private lateinit var interactionListener: EntityInteractionListener

    override fun onEnable() {
        instance = this
        unregisterStaleCommands()
        saveDefaultConfig()
        glowingEntities = GlowingEntities(this)
        statsManager = com.mahjongplay.stats.MahjongStatsManager(dataFolder)
        server.pluginManager.registerEvents(com.mahjongplay.stats.MahjongStatsListener(statsManager), this)

        economyRecoveryStore = EconomyRecoveryStore(File(dataFolder, "economy-recovery.yml"))
        economyRecoveryBlocked = economyRecoveryStore.hasPending()
        economy = currentEconomy()
        if (economyRecoveryBlocked) {
            logger.severe(
                "存在 ${economyRecoveryStore.totalPending()} 的待處理經濟退款；已停用真人金流。" +
                    "請核對 economy-recovery.yml、人工退款並清除紀錄後重載插件。",
            )
        } else if (economy != null) {
            logger.info("已連接 Vault 經濟提供者：${economy!!.providerName}")
        } else {
            logger.warning("找不到 Vault 經濟提供者；1v3 Bot 仍可遊玩，但兩位以上真人的牌局將無法開始。")
        }

        tableManager = MahjongTableManager(MahjongSettings.from(config))
        tableManager.startDisplayRepairTask()

        val mahjongCommand = getCommand("mahjong")
            ?: (server.commandMap.getCommand("${name.lowercase()}:mahjong") as? PluginCommand)
        mahjongCommand?.let {
            val cmd = MahjongCommand(tableManager, statsManager)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
            if (server.commandMap.getCommand("mahjong") !== it) {
                server.commandMap.register(name.lowercase(), it)
            }
        }
        syncPaperCommands()

        interactionListener = EntityInteractionListener(tableManager)
        server.pluginManager.registerEvents(interactionListener, this)
        server.pluginManager.registerEvents(this, this)

        server.scheduler.runTaskLater(this, Runnable {
            tableManager.loadTables(dataFolder)
        }, 20L)

        logger.info("TaiwaneseMahjong v${pluginMeta.version} enabled!")
    }

    /** Resolve on demand so Vault/provider reloads cannot leave a stale handle. */
    fun currentEconomy(): ServerEconomy? {
        if (economyRecoveryBlocked) {
            economy = null
            return null
        }
        economy = if (server.pluginManager.isPluginEnabled("Vault")) {
            runCatching { VaultEconomyGateway.connect(this) }.getOrNull()
        } else null
        return economy
    }

    fun recordEconomyRefund(playerUUID: String, amount: Double) {
        economyRecoveryBlocked = true
        economy = null
        runCatching { economyRecoveryStore.record(playerUUID, amount) }
            .onSuccess { logger.severe("經濟退款失敗：已記錄待人工補發 $amount 給 $playerUUID，真人金流已停用。") }
            .onFailure { logger.severe("經濟退款紀錄亦寫入失敗：$amount 給 $playerUUID；真人金流已停用，請立即人工處理。原因：${it.message}") }
    }

    /** Persist a payment that could not be completed; never silently convert it to score-only. */
    fun recordEconomyPayment(payerUUID: String, winnerUUID: String, amount: Double) {
        economyRecoveryBlocked = true
        economy = null
        runCatching { economyRecoveryStore.recordPayment(payerUUID, winnerUUID, amount) }
            .onSuccess {
                logger.severe("經濟付款未完成：已記錄待人工補付 $amount（$payerUUID -> $winnerUUID），真人金流已停用。")
            }
            .onFailure {
                logger.severe("經濟待付款紀錄亦寫入失敗：$amount（$payerUUID -> $winnerUUID）；請立即人工處理。原因：${it.message}")
            }
    }

    fun economyUnavailableReason(): String = if (economyRecoveryBlocked) {
        "存在尚未人工核對的退款或待付款紀錄，真人金流已停用。"
    } else {
        "找不到可用的 Vault 經濟提供者。"
    }

    override fun onDisable() {
        if (::glowingEntities.isInitialized) {
            glowingEntities.disable()
        }
        if (::tableManager.isInitialized) {
            tableManager.saveTables(dataFolder)
            tableManager.shutdown()
        }
        // Stop the producer (game callbacks) before flushing and closing the
        // serialized stats writer, avoiding a final-match/shutdown race.
        if (::statsManager.isInitialized) {
            statsManager.shutdown()
        }
        unregisterOwnedCommands()
        logger.info("TaiwaneseMahjong disabled.")
    }

    private fun unregisterStaleCommands() {
        server.commandMap.knownCommands.values
            .filterIsInstance<PluginIdentifiableCommand>()
            .filter { it.plugin.name == name && it.plugin !== this }
            .map { it as Command }
            .distinct()
            .forEach(::unregisterCommand)
    }

    private fun unregisterOwnedCommands() {
        server.commandMap.knownCommands.values
            .filterIsInstance<PluginIdentifiableCommand>()
            .filter { it.plugin === this }
            .map { it as Command }
            .distinct()
            .forEach(::unregisterCommand)
    }

    private fun unregisterCommand(command: Command) {
        command.unregister(server.commandMap)
        val commandKeys = server.commandMap.knownCommands
            .filterValues { it === command }
            .keys
            .toList()
        commandKeys.forEach { server.commandMap.knownCommands.remove(it) }
    }

    private fun syncPaperCommands() {
        runCatching {
            server.javaClass.getMethod("syncCommands").invoke(server)
        }.onFailure {
            logger.warning("Unable to synchronize Paper command tree: ${it.message}")
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        if (::statsManager.isInitialized) {
            statsManager.markHumanPlayer(event.player.uniqueId.toString(), event.player.name)
        }
        tableManager.notifyIfInterrupted(event.player.uniqueId.toString(), event.player)
        tableManager.getAllSessions().forEach {
            it.table.syncFloatingTextVisibility()
            it.renderer.syncPlayerVisibility(event.player)
        }
        tableManager.rejoinIfBotTakeover(event.player)
        // Rejoin can reveal this player's private hand after bot takeover;
        // reconcile once more after the seat ownership changes.
        tableManager.getAllSessions().forEach { it.renderer.syncPlayerVisibility(event.player) }
    }

    @EventHandler
    fun onNumericSettingChat(event: AsyncPlayerChatEvent) {
        val playerUUID = event.player.uniqueId.toString()
        val input = tableManager.consumeNumericInput(playerUUID, event.message) ?: return
        event.isCancelled = true
        server.scheduler.runTask(this, Runnable {
            tableManager.applyNumericInput(playerUUID, input)
        })
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId.toString()
        if (::interactionListener.isInitialized) {
            interactionListener.clearRecentInput(event.player.uniqueId)
        }
        tableManager.clearPendingNumericInput(uuid)
        tableManager.leaveTable(uuid)
    }

    @EventHandler
    fun onPlayerToggleSneak(event: PlayerToggleSneakEvent) {
        tableManager.handlePlayerToggleSneak(event.player, event.isSneaking)
    }

    @EventHandler
    fun onPlayerSwapHandItems(event: org.bukkit.event.player.PlayerSwapHandItemsEvent) {
        if (tableManager.toggleBotTakeoverForPlayer(event.player)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (::tableManager.isInitialized) {
            tableManager.onChunkLoad(event.chunk)
        }
    }

    @EventHandler
    fun onEntityDismount(event: EntityDismountEvent) {
        val player = event.entity as? org.bukkit.entity.Player ?: return
        tableManager.releasePlayerFromChairs(player.uniqueId)
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        if (tableManager.isProtectedBlock(event.block.location)) {
            event.isCancelled = true
        }
    }
}
