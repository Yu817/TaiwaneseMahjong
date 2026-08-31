package com.mahjongplay.economy

import com.mahjongplay.game.MahjongPlayerBase
import com.mahjongplay.game.MahjongPlayer
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.UUID

data class EconomyObligation(
    val payerUUID: String,
    val winnerUUID: String,
    val amount: Int,
) {
    init {
        require(amount > 0) { "Economy obligation must be positive." }
        require(payerUUID != winnerUUID) { "Payer and winner must be different players." }
    }
}

object RealMoneyPolicy {
    /** Bot seats never pay or receive currency; takeover keeps a seat real. */
    fun obligation(
        payer: MahjongPlayerBase,
        winner: MahjongPlayerBase,
        amount: Int,
    ): EconomyObligation? {
        // MahjongPlayer remains a human-owned seat while bot takeover is
        // active; isRealPlayer intentionally becomes false during takeover.
        if (payer !is MahjongPlayer || winner !is MahjongPlayer || amount <= 0 || payer.uuid == winner.uuid) return null
        return EconomyObligation(payer.uuid, winner.uuid, amount)
    }
}

interface EconomyGateway {
    fun balance(playerUUID: String): Double
    fun withdraw(playerUUID: String, amount: Double): Boolean
    fun deposit(playerUUID: String, amount: Double): Boolean
}

interface ServerEconomy : EconomyGateway {
    val providerName: String
    fun format(amount: Double): String
}

data class EconomyTransferResult(
    val success: Boolean,
    val requestedAmount: Double,
    val paidAmount: Double,
    val shortfall: Double,
    val error: String? = null,
    /** Amount already debited but not restored; caller must persist/retry it. */
    val unrefundedAmount: Double = 0.0,
)

/**
 * Moves only funds that really exist. If crediting the winner fails, the
 * payer is refunded so a provider error cannot silently destroy currency.
 */
class EconomyTransferService(private val gateway: EconomyGateway) {
    fun transfer(obligation: EconomyObligation): EconomyTransferResult {
        val requested = obligation.amount.toDouble()
        val rawBalance = runCatching { gateway.balance(obligation.payerUUID) }.getOrElse {
            return EconomyTransferResult(false, requested, 0.0, requested, "讀取餘額失敗：${it.message}")
        }
        val available = if (rawBalance.isFinite()) rawBalance.coerceAtLeast(0.0) else 0.0
        val payable = minOf(requested, available)

        if (payable <= 0.0) {
            return EconomyTransferResult(true, requested, 0.0, requested)
        }
        val withdrawn = runCatching { gateway.withdraw(obligation.payerUUID, payable) }.getOrElse {
            return EconomyTransferResult(false, requested, 0.0, requested, "扣款失敗：${it.message}")
        }
        if (!withdrawn) {
            return EconomyTransferResult(false, requested, 0.0, requested, "扣款失敗")
        }
        val deposited = runCatching { gateway.deposit(obligation.winnerUUID, payable) }.getOrDefault(false)
        if (!deposited) {
            val refunded = runCatching { gateway.deposit(obligation.payerUUID, payable) }.getOrDefault(false)
            return EconomyTransferResult(
                success = false,
                requestedAmount = requested,
                paidAmount = 0.0,
                shortfall = requested,
                error = if (refunded) "入帳失敗，已退款" else "入帳及退款皆失敗，請聯絡管理員",
                unrefundedAmount = if (refunded) 0.0 else payable,
            )
        }
        return EconomyTransferResult(true, requested, payable, requested - payable)
    }
}

/** Durable refunds for the rare case where both winner credit and rollback fail. */
class EconomyRecoveryStore(private val file: File) {
    @Synchronized
    fun record(playerUUID: String, amount: Double) {
        if (!amount.isFinite() || amount <= 0.0) return
        val config = load()
        val path = path(playerUUID)
        config.set(path, config.getDouble(path, 0.0) + amount)
        save(config)
    }

    @Synchronized
    fun pendingAmount(playerUUID: String): Double = load().getDouble(path(playerUUID), 0.0)

    /**
     * Record money that was owed to another real player but could not be
     * transferred (for example, because the Vault provider disappeared or the
     * payer had insufficient funds).  This is deliberately manual-reconcile
     * state: retrying an external transfer automatically could double-charge a
     * payer after a provider timeout.
     */
    @Synchronized
    fun recordPayment(payerUUID: String, winnerUUID: String, amount: Double) {
        if (!amount.isFinite() || amount <= 0.0 || payerUUID == winnerUUID) return
        val config = load()
        val path = paymentPath(payerUUID, winnerUUID)
        config.set(path, config.getDouble(path, 0.0) + amount)
        save(config)
    }

    @Synchronized
    fun pendingPayment(payerUUID: String, winnerUUID: String): Double =
        load().getDouble(paymentPath(payerUUID, winnerUUID), 0.0)

    @Synchronized
    fun totalPending(): Double {
        val config = load()
        val refunds = config.getConfigurationSection(ROOT)
            ?.getKeys(false)
            ?.sumOf { config.getDouble("$ROOT.$it", 0.0) }
            ?: 0.0
        val payments = config.getConfigurationSection(PAYMENTS_ROOT)
            ?.getKeys(false)
            ?.sumOf { payer ->
                config.getConfigurationSection("$PAYMENTS_ROOT.$payer")
                    ?.getKeys(false)
                    ?.sumOf { winner -> config.getDouble("$PAYMENTS_ROOT.$payer.$winner", 0.0) }
                    ?: 0.0
            }
            ?: 0.0
        return refunds + payments
    }

    @Synchronized
    fun hasPending(): Boolean = totalPending() > 0.0

    private fun load(): YamlConfiguration =
        if (file.exists()) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()

    private fun save(config: YamlConfiguration) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            config.save(temporary)
            try {
                java.nio.file.Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }

    private fun path(playerUUID: String): String = "$ROOT.$playerUUID"

    private fun paymentPath(payerUUID: String, winnerUUID: String): String =
        "$PAYMENTS_ROOT.$payerUUID.$winnerUUID"

    companion object {
        private const val ROOT = "pending-refunds"
        private const val PAYMENTS_ROOT = "pending-payments"
    }
}
