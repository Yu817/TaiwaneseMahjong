package com.mahjongplay.game

import kotlin.test.Test
import kotlin.test.assertEquals

class TaiwanPaymentTest {
    @Test
    fun `dealer liability is converted from tai using the table rate`() {
        assertEquals(
            1_600,
            TaiwanPayment.calculate(
                settlementScore = 1_000,
                dealerLiabilityTai = 3,
                pointsPerTai = 200,
            ),
        )
    }

    @Test
    fun `non dealer pays only the winning hand settlement`() {
        assertEquals(1_000, TaiwanPayment.calculate(1_000, 0, 200))
    }

    @Test
    fun `payment overflow saturates instead of wrapping negative`() {
        assertEquals(Int.MAX_VALUE, TaiwanPayment.calculate(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
