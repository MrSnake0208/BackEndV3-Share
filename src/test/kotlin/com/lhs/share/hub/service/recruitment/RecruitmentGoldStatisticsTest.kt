package com.lhs.share.hub.service.recruitment

import com.lhs.share.hub.repository.RecruitmentStatisticEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RecruitmentGoldStatisticsTest {
    private fun event(status: String?, span: Long? = null, rarity: Int? = 5) = RecruitmentStatisticEvent("p", rarity, span, status)
    private fun stats(vararg events: RecruitmentStatisticEvent) = RecruitmentGoldStatistics().also { stats -> events.forEach(stats::add) }

    @Test fun `average uses only known positive gold spans even when pity is invalid`() {
        val stats = stats(event("unknown", 39), event("up", 38), event("up"), event("up", 1, 4), event("up", 0))
        assertEquals(38.5, stats.averageGoldPulls)
        assertNull(stats.smallPityWinRate)
        assertNull(stats(event("up"), event("up", 0), event("up", 5, 4)).averageGoldPulls)
        assertEquals(1.3, stats(event("up", 1), event("up", 1), event("up", 2)).averageGoldPulls)
    }

    @Test fun `account average rounds original spans to two decimals while pool keeps one`() {
        val stats = stats(event("unknown", 1), event("up", 1), event("up", 2), event("up"), event("up", 99, 4), event("up", 0))
        assertEquals(1.33, stats.accountAverageGoldPulls)
        assertEquals(1.3, stats.averageGoldPulls)
        assertEquals(1.67, stats(event("up", 1), event("up", 2), event("up", 2)).accountAverageGoldPulls)
        val half = stats(*Array(8) { event("up", if (it == 0) 2L else 1L) })
        assertEquals(1.13, half.accountAverageGoldPulls)
        assertEquals(1.1, half.averageGoldPulls)
        assertEquals(12.0, stats(event("up", 12)).accountAverageGoldPulls)
        assertNull(stats().accountAverageGoldPulls)
        assertNull(stats(event("up"), event("up", 0), event("up", 5, 4)).accountAverageGoldPulls)
    }

    @Test fun `first gold assumes small pity and guaranteed UP is excluded regardless of spans`() {
        assertEquals(66.7, stats(event("up"), event("non_up", 1), event("up", 40), event("up")).smallPityWinRate)
        assertEquals(0.0, stats(event("non_up")).smallPityWinRate)
        assertEquals(0.0, stats(event("non_up"), event("up")).smallPityWinRate)
        assertEquals(100.0, stats(event("up"), event("up")).smallPityWinRate)
        assertEquals(50.0, stats(event("non_up"), event("unknown", 1, 4), event("up"), event("up")).smallPityWinRate)
    }

    @Test fun `empty unknown or inconsistent pity stays unknown and order matters`() {
        assertNull(stats().smallPityWinRate)
        assertNull(stats(event("up", rarity = 4)).smallPityWinRate)
        assertNull(stats(event("unknown"), event("up")).smallPityWinRate)
        assertNull(stats(event(null)).smallPityWinRate)
        assertNull(stats(event("up", rarity = null)).smallPityWinRate)
        assertNull(stats(event("non_up"), event("non_up")).smallPityWinRate)
        assertEquals(66.7, stats(event("up"), event("up"), event("non_up")).smallPityWinRate)
        assertEquals(50.0, stats(event("non_up"), event("up"), event("up")).smallPityWinRate)
    }
}
