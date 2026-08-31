package com.mahjongplay.economy

import com.mahjongplay.game.BotDifficulty
import com.mahjongplay.game.MahjongBot
import com.mahjongplay.game.MahjongPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory

class EconomyTransferServiceTest {
    @Test
    fun `only payments between two real player seats use real currency`() {
        val payer = MahjongPlayer("payer", "Payer")
        val winner = MahjongPlayer("winner", "Winner")
        val bot = MahjongBot("Bot")

        assertEquals(500, RealMoneyPolicy.obligation(payer, winner, 500)?.amount)
        assertNull(RealMoneyPolicy.obligation(payer, bot, 500))
        assertNull(RealMoneyPolicy.obligation(bot, winner, 500))
    }

    @Test
    fun `bot takeover remains a real-money player seat`() {
        val payer = MahjongPlayer("payer", "Payer").apply { activateBotTakeover(BotDifficulty.HIGH) }
        val winner = MahjongPlayer("winner", "Winner")

        assertTrue(payer.isBotTakeover)
        assertEquals(800, RealMoneyPolicy.obligation(payer, winner, 800)?.amount)
    }

    @Test
    fun `insufficient balance transfers only available funds without creating currency`() {
        val gateway = InMemoryEconomyGateway(mutableMapOf("payer" to 300.0, "winner" to 40.0))
        val result = EconomyTransferService(gateway).transfer(EconomyObligation("payer", "winner", 1_000))

        assertTrue(result.success)
        assertEquals(300.0, result.paidAmount)
        assertEquals(700.0, result.shortfall)
        assertEquals(0.0, gateway.balance("payer"))
        assertEquals(340.0, gateway.balance("winner"))
    }

    @Test
    fun `failed winner deposit refunds the payer`() {
        val gateway = InMemoryEconomyGateway(
            mutableMapOf("payer" to 1_000.0, "winner" to 0.0),
            failingDeposits = setOf("winner"),
        )
        val result = EconomyTransferService(gateway).transfer(EconomyObligation("payer", "winner", 600))

        assertFalse(result.success)
        assertEquals(1_000.0, gateway.balance("payer"))
        assertEquals(0.0, gateway.balance("winner"))
    }

    @Test
    fun `throwing winner deposit still refunds the payer`() {
        val gateway = InMemoryEconomyGateway(
            mutableMapOf("payer" to 1_000.0, "winner" to 0.0),
            throwingDeposits = setOf("winner"),
        )

        val result = EconomyTransferService(gateway).transfer(EconomyObligation("payer", "winner", 600))

        assertFalse(result.success)
        assertEquals(0.0, result.unrefundedAmount)
        assertEquals(1_000.0, gateway.balance("payer"))
    }

    @Test
    fun `failed refund exposes exact amount requiring durable compensation`() {
        val gateway = InMemoryEconomyGateway(
            mutableMapOf("payer" to 1_000.0, "winner" to 0.0),
            failingDeposits = setOf("winner", "payer"),
        )

        val result = EconomyTransferService(gateway).transfer(EconomyObligation("payer", "winner", 600))

        assertFalse(result.success)
        assertEquals(600.0, result.unrefundedAmount)
        assertEquals(400.0, gateway.balance("payer"))
    }

    @Test
    fun `unrefunded debit survives reload for manual reconciliation`() {
        val folder = createTempDirectory("mahjong-economy-test").toFile()
        val file = folder.resolve("economy-recovery.yml")
        EconomyRecoveryStore(file).record("payer", 600.0)

        val reloaded = EconomyRecoveryStore(file)

        assertEquals(600.0, reloaded.pendingAmount("payer"))
        assertEquals(600.0, reloaded.totalPending())
        assertTrue(reloaded.hasPending())
        folder.deleteRecursively()
    }

    @Test
    fun `unpaid winner amount survives reload for manual reconciliation`() {
        val folder = createTempDirectory("mahjong-economy-payment-test").toFile()
        val file = folder.resolve("economy-recovery.yml")
        EconomyRecoveryStore(file).recordPayment("payer", "winner", 700.0)

        val reloaded = EconomyRecoveryStore(file)

        assertEquals(700.0, reloaded.pendingPayment("payer", "winner"))
        assertEquals(700.0, reloaded.totalPending())
        assertTrue(reloaded.hasPending())
        folder.deleteRecursively()
    }

    private class InMemoryEconomyGateway(
        private val balances: MutableMap<String, Double>,
        private val failingDeposits: Set<String> = emptySet(),
        private val throwingDeposits: Set<String> = emptySet(),
    ) : EconomyGateway {
        override fun balance(playerUUID: String): Double = balances[playerUUID] ?: 0.0

        override fun withdraw(playerUUID: String, amount: Double): Boolean {
            val current = balance(playerUUID)
            if (amount < 0.0 || current < amount) return false
            balances[playerUUID] = current - amount
            return true
        }

        override fun deposit(playerUUID: String, amount: Double): Boolean {
            if (playerUUID in throwingDeposits) error("provider failure")
            if (playerUUID in failingDeposits || amount < 0.0) return false
            balances[playerUUID] = balance(playerUUID) + amount
            return true
        }
    }
}
