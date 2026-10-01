package com.lhs.share.hub.service.recruitment

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.hub.controller.recruitment.request.RecruitmentCatalogWriteRequest
import com.lhs.share.hub.repository.OperatorCatalogRepository
import com.lhs.share.hub.repository.RecruitmentCatalogRepository
import com.lhs.share.hub.repository.entity.OperatorCatalogEntity
import com.lhs.share.hub.repository.entity.RecruitmentCatalogPool
import com.lhs.share.hub.repository.entity.RecruitmentUpAgent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import java.time.LocalDate

class RecruitmentCatalogTest {
    private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val repository = mockk<RecruitmentCatalogRepository>()
    private val operators = mockk<OperatorCatalogRepository>()
    private val saved = mutableMapOf<String, RecruitmentCatalogPool>()
    private val catalog = RecruitmentCatalog(mapper, repository, operators)

    init {
        every { repository.all() } answers { saved.values.toList() }
        every { repository.find(any()) } answers { saved[firstArg()] }
        every { repository.save(any()) } answers {
            val next = firstArg<RecruitmentCatalogPool>()
            if ((saved[next.poolId]?.revision ?: 0) != next.revision - 1) {
                false
            } else {
                saved[next.poolId] = next
                true
            }
        }
        every { operators.findByOperatorId(any()) } returns null
        every { operators.findAllByOrderByOperatorIdAsc() } returns emptyList()
        every { operators.count() } returns 0L
    }

    @Test fun `versioned catalog is pure scoped and keeps honest seed metadata and pool owned identities`() {
        val response = catalog.catalog("代号鸢")
        val pools = response.path("pools")
        assertEquals(58, pools.size())
        assertEquals(58, pools.map { it.path("pool_id").asText() }.toSet().size)
        assertTrue(response.path("catalog_revision").asText().isNotEmpty())
        assertTrue(catalog.catalog("如鸢").path("pools").isEmpty)
        assertEquals(2, pools.count { it.path("up_status").asText() == "selection" })
        val seed = ClassPathResource("operator/operators.json").inputStream.use(mapper::readTree).associateBy { it.path("id").asText() }
        pools.forEach { pool ->
            val start = pool["start_date"]?.takeUnless { it.isNull }?.asText()?.let(LocalDate::parse)
            val end = pool["end_date"]?.takeUnless { it.isNull }?.asText()?.let(LocalDate::parse)
            assertFalse(start != null && end != null && start > end)
            pool.path("up_agent_ids").forEach {
                val operator = seed.getValue(it.asText())
                assertEquals(5, operator.path("rarity").asInt())
                assertTrue(operator.path("games").any { game -> game.asText() == "代号鸢" })
            }
            val id = pool.path("pool_id").asText()
            assertTrue(pool.path("up_agents").all { it.path("id").asText().startsWith("$id:up:") })
            val snapshot = catalog.snapshot("代号鸢", id)
            assertEquals(pool.path("up_status").asText(), snapshot.upStatus)
            assertEquals(pool.path("name").asText(), snapshot.name)
            assertTrue(pool.path("source_url").asText().isNotEmpty())
        }
        repeat(2) { catalog.listForAdmin() }
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { operators.save(any()) }
        verify(exactly = 4) { operators.findAllByOrderByOperatorIdAsc() }
        assertThrows(RecruitmentApiException::class.java) { catalog.catalog("other") }
        assertThrows(RecruitmentApiException::class.java) { catalog.snapshot("如鸢", pools.first().path("pool_id").asText()) }
    }

    @Test fun `administrator can bind placeholders retire missing slots and edit with CAS without changing slot identity`() {
        val request = RecruitmentCatalogWriteRequest(
            "test_pool",
            "如鸢",
            "新池",
            0,
            upAgents = listOf(RecruitmentUpAgent("test_pool:up:1", "占位1"), RecruitmentUpAgent("test_pool:up:2", "占位2")),
        )
        val created = catalog.create("admin", request)
        val beforeVersion = catalog.catalog("如鸢").path("catalog_revision").asText()
        assertEquals(1L, created.revision)
        assertNull(created.upAgents.first().operatorId)
        val bound = catalog.update(
            "admin",
            "test_pool",
            request.copy(
                expectedRevision = 1,
                upAgents = listOf(request.upAgents.first().copy(operatorId = "char_001_yangxiu")),
            ),
        )
        assertEquals("test_pool:up:1", bound.upAgents.first().id)
        assertEquals("杨修", bound.upAgents.first().name)
        assertEquals("char_001_yangxiu", bound.upAgents.first().operatorId)
        assertFalse(bound.upAgents.last().active)
        assertEquals(listOf("char_001_yangxiu"), catalog.snapshot("如鸢", "test_pool").upAgentIds)
        assertTrue(beforeVersion != catalog.catalog("如鸢").path("catalog_revision").asText())
        assertEquals(
            409,
            assertThrows(RecruitmentApiException::class.java) {
                catalog.update("admin", "test_pool", request.copy(expectedRevision = 1))
            }.status.value(),
        )
        assertThrows(RecruitmentApiException::class.java) { catalog.slot("如鸢", "test_pool", "test_pool:up:2") }
        assertNull(catalog.slot("如鸢", "test_pool", "another_pool:up:1"))
        val disabled = catalog.update("admin", "test_pool", request.copy(expectedRevision = 2, enabled = false, upAgents = bound.upAgents))
        assertFalse(disabled.enabled)
        assertThrows(RecruitmentApiException::class.java) { catalog.snapshot("如鸢", "test_pool") }
        assertEquals(2, catalog.snapshot("如鸢", "test_pool", requireEnabled = false).upAgents.size)
        assertFalse(catalog.catalog("如鸢").path("pools").first().path("enabled").asBoolean())
    }

    @Test fun `catalog rejects wrong pool slots duplicate active bindings game mismatch and unavailable dictionary agents`() {
        val valid = RecruitmentCatalogWriteRequest(
            "pool",
            "如鸢",
            "池",
            0,
            upAgents = listOf(RecruitmentUpAgent("pool:up:1", "占位")),
        )
        val wrongGame = OperatorCatalogEntity(
            operatorId = "other", name = "其他游戏", rarity = 5, games = listOf("代号鸢"),
            prof = emptyList(), subProf = emptyList(), discs = emptyList(), starStones = emptyList(), catalogVersion = "v",
        )
        every { operators.findByOperatorId("other") } returns wrongGame
        listOf(
            valid.copy(upAgents = listOf(RecruitmentUpAgent("another:up:1", "串池"))),
            valid.copy(upAgents = listOf(valid.upAgents.first().copy(operatorId = "other"))),
            valid.copy(
                upAgents = listOf(
                    valid.upAgents.first().copy(operatorId = "char_001_yangxiu"),
                    RecruitmentUpAgent("pool:up:2", "重复", "char_001_yangxiu"),
                ),
            ),
            valid.copy(upAgents = listOf(valid.upAgents.first().copy(operatorId = "missing"))),
            valid.copy(startDate = LocalDate.parse("2026-10-02"), endDate = LocalDate.parse("2026-10-01")),
        ).forEach { input -> assertThrows(RecruitmentApiException::class.java) { catalog.create("admin", input) } }
        verify(exactly = 0) { repository.save(any()) }
        catalog.create("admin", valid)
        assertThrows(RecruitmentApiException::class.java) {
            catalog.update("admin", "pool", valid.copy(expectedRevision = 1, game = "代号鸢"))
        }
        every { operators.count() } returns 1L
        assertThrows(RecruitmentApiException::class.java) { catalog.operator("如鸢", "char_001_yangxiu") }
        verify(exactly = 0) { operators.save(any()) }
    }
}
