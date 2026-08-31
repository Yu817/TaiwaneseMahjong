package com.mahjongplay.interaction

import com.mahjongplay.economy.ServerEconomy
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class VaultEconomyGateway private constructor(
    private val economy: Economy,
) : ServerEconomy {
    override val providerName: String get() = economy.name

    override fun balance(playerUUID: String): Double =
        economy.getBalance(Bukkit.getOfflinePlayer(UUID.fromString(playerUUID)))

    override fun withdraw(playerUUID: String, amount: Double): Boolean =
        economy.withdrawPlayer(Bukkit.getOfflinePlayer(UUID.fromString(playerUUID)), amount).transactionSuccess()

    override fun deposit(playerUUID: String, amount: Double): Boolean =
        economy.depositPlayer(Bukkit.getOfflinePlayer(UUID.fromString(playerUUID)), amount).transactionSuccess()

    override fun format(amount: Double): String = economy.format(amount)

    companion object {
        fun connect(plugin: JavaPlugin): VaultEconomyGateway? {
            if (!plugin.server.pluginManager.isPluginEnabled("Vault")) return null
            val provider = plugin.server.servicesManager.getRegistration(Economy::class.java)?.provider ?: return null
            return VaultEconomyGateway(provider)
        }
    }
}
