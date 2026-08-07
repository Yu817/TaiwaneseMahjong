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
import org.bukkit.command.PluginIdentifiableCommand
import org.bukkit.plugin.java.JavaPlugin
import fr.skytasul.glowingentities.GlowingEntities

class MahjongPlayPlugin : JavaPlugin(), Listener {

    companion object {
        lateinit var instance: MahjongPlayPlugin
            private set
    }

    lateinit var tableManager: MahjongTableManager
        private set

    lateinit var glowingEntities: GlowingEntities
        private set

    override fun onEnable() {
        instance = this
        unregisterStaleCommands()
        saveDefaultConfig()
        glowingEntities = GlowingEntities(this)
        tableManager = MahjongTableManager(MahjongSettings.from(config))
        tableManager.startDisplayRepairTask()

        val mahjongCommand = getCommand("mahjong")
            ?: (server.commandMap.getCommand("${name.lowercase()}:mahjong") as? PluginCommand)
        mahjongCommand?.let {
            val cmd = MahjongCommand(tableManager)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
            if (server.commandMap.getCommand("mahjong") !== it) {
                server.commandMap.register(name.lowercase(), it)
            }
        }
        syncPaperCommands()

        server.pluginManager.registerEvents(EntityInteractionListener(tableManager), this)
        server.pluginManager.registerEvents(this, this)

        server.scheduler.runTaskLater(this, Runnable {
            tableManager.loadTables(dataFolder)
        }, 20L)

        logger.info("TaiwaneseMahjong v${pluginMeta.version} enabled!")
    }

    override fun onDisable() {
        if (::glowingEntities.isInitialized) {
            glowingEntities.disable()
        }
        if (::tableManager.isInitialized) {
            tableManager.saveTables(dataFolder)
            tableManager.shutdown()
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
        tableManager.notifyIfInterrupted(event.player.uniqueId.toString(), event.player)
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
        tableManager.clearPendingNumericInput(uuid)
        tableManager.leaveTable(uuid)
    }

    @EventHandler
    fun onPlayerToggleSneak(event: PlayerToggleSneakEvent) {
        if (!event.isSneaking) return
        tableManager.releasePlayerFromChairs(event.player.uniqueId)
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
