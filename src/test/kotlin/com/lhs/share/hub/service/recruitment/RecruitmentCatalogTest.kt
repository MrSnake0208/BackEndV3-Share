package com.lhs.share.hub.service.recruitment

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import java.time.LocalDate

class RecruitmentCatalogTest {
    private val mapper = jacksonObjectMapper()
    private val catalog = RecruitmentCatalog(mapper)

    @Test fun `versioned catalog is game scoped and carries honest selection and incomplete UP metadata`() {
        val response = catalog.catalog("代号鸢")
        val pools = response.path("pools")
        assertEquals(58, pools.size())
        assertEquals(58, pools.map { it.path("pool_id").asText() }.toSet().size)
        assertTrue(response.path("catalog_revision").asText().isNotEmpty())
        assertTrue(catalog.catalog("如鸢").path("pools").isEmpty)
        assertEquals(2, pools.count { it.path("up_status").asText() == "selection" })
        val operators = ClassPathResource("operator/operators.json").inputStream.use(mapper::readTree).associateBy {
            it.path("id").asText()
        }
        pools.forEach { pool ->
            val start = pool["start_date"]?.takeUnless { it.isNull }?.asText()?.let(LocalDate::parse)
            val end = pool["end_date"]?.takeUnless { it.isNull }?.asText()?.let(LocalDate::parse)
            assertFalse(start != null && end != null && start > end)
            pool.path("up_agent_ids").forEach {
                val operator = operators.getValue(it.asText())
                assertEquals(5, operator.path("rarity").asInt())
                assertTrue(operator.path("games").any { game -> game.asText() == "代号鸢" })
            }
            val snapshot = catalog.snapshot("代号鸢", pool.path("pool_id").asText())
            assertEquals(pool.path("up_status").asText(), snapshot.upStatus)
            assertEquals(pool.path("name").asText(), snapshot.name)
        }
        assertThrows(RecruitmentApiException::class.java) { catalog.catalog("other") }
        assertThrows(RecruitmentApiException::class.java) { catalog.snapshot("如鸢", pools.first().path("pool_id").asText()) }
    }
}
