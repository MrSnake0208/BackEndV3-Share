package com.lhs.share.hub.service.operator

import com.lhs.share.hub.repository.entity.OperatorCatalogEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OperatorResetRulesTest {
    private val catalog =
        OperatorCatalogEntity(
            operatorId = "op", name = "密探", rarity = 5,
            prof = listOf(
                "火",
            ),
            subProf = listOf("pojun"), games = listOf("如鸢"), discs = emptyList(), starStones = emptyList(), catalogVersion = "test",
        )

    @Test
    fun `level forty refund matches game preview without rounding up alternative books`() {
        val result = OperatorResetRules.refund(40, 2, 3, catalog)
        assertEquals(65600L, result.experience)
        assertEquals(180000L, result.money)
        assertEquals(
            mapOf(
                "jianjia" to 24L,
                "yuanyu" to 10L,
                "juanshan" to 20L,
                "bingshuquanjuan" to 65L,
                "bingshucanjuan" to 6L,
                "liutaobingshu" to 0L,
            ),
            result.suggested,
        )
        assertFalse(result.suggested.containsKey("zhuangjinboli"))
    }

    @Test
    fun `completed boundary refunds its materials and element groups remain distinct`() {
        val unbroken = OperatorResetRules.refund(40, 2, 3, catalog)
        val broken = OperatorResetRules.refund(40, 2, 4, catalog)
        assertEquals(unbroken.suggested.getValue("jianjia") + 40, broken.suggested["jianjia"])
        assertEquals(unbroken.experience, broken.experience)
        assertEquals(20L, OperatorResetRules.refund(40, 2, 3, catalog.copy(prof = listOf("地"))).suggested["zhuojiu"])
        assertEquals(20L, OperatorResetRules.refund(40, 2, 3, catalog.copy(prof = listOf("阳"))).suggested["tongjing"])
    }

    @Test
    fun `level hundred does not invent unknown experience or lose level breakthrough refunds`() {
        val result = OperatorResetRules.refund(100, 17, 9, catalog)
        assertNull(result.experience)
        assertEquals(160L, result.suggested["ziyunying"])
        assertEquals(120L, result.suggested["qingtingyan"])
        assertEquals(2100L, result.suggested["beihuifengshan"])
    }
}
