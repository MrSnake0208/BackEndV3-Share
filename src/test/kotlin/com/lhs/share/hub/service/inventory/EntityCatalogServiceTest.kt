package com.lhs.share.hub.service.inventory

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.hub.repository.EntityCatalogRepository
import com.lhs.share.hub.repository.entity.EntityCatalogEntity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource

class EntityCatalogServiceTest {
    private val repository = mockk<EntityCatalogRepository>()
    private val rows = linkedMapOf<Pair<String, String>, EntityCatalogEntity>()
    private val service = EntityCatalogService(repository, jacksonObjectMapper())

    @BeforeEach
    fun setUp() {
        rows.clear()
        every { repository.findByEntityTypeAndEntityId(any(), any()) } answers {
            rows[firstArg<String>() to secondArg<String>()]
        }
        every { repository.save(any()) } answers {
            val entity = firstArg<EntityCatalogEntity>()
            rows[entity.entityType to entity.entityId] = entity
            entity
        }
    }

    @Test
    fun `packaged agent catalog is valid unique and contains frontend agents`() {
        service.seedFromResources(
            ClassPathResource("inventory/items.json"),
            ClassPathResource("inventory/operators.json"),
        )

        val agentIds = rows.values.filter { it.entityType == "agent" }.map { it.entityId }
        assertEquals(agentIds.size, agentIds.toSet().size)
        assertTrue("char_102_jianyong" in agentIds)
        assertTrue("char_125_zhaoyun" in agentIds)
    }

    @Test
    fun `failed catalog refresh preserves the previous valid catalog`() {
        val existing = EntityCatalogEntity(
            entityType = "agent",
            entityId = "char_102_jianyong",
            name = "简雍",
            catalogVersion = "previous",
        )
        rows[existing.entityType to existing.entityId] = existing
        val invalidAgents = resource("""[{"id":"bad-id","name":"伪造"}]""")

        assertThrows(IllegalStateException::class.java) {
            service.seedFromResources(resource("""[{"id":"new_item","name":"新物品"}]"""), invalidAgents)
        }

        assertEquals(existing, rows["agent" to "char_102_jianyong"])
        assertTrue(("item" to "new_item") !in rows)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `duplicate agent ids reject the complete catalog batch`() {
        val duplicateAgents = resource(
            """
            [
              {"id":"char_102_jianyong","name":"简雍"},
              {"id":"char_102_jianyong","name":"重复"}
            ]
            """.trimIndent(),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            service.seedFromResources(null, duplicateAgents)
        }

        assertTrue(error.message.orEmpty().contains("Duplicate agent catalog id"))
        assertTrue(rows.isEmpty())
        verify(exactly = 0) { repository.save(any()) }
    }

    private fun resource(json: String) = ByteArrayResource(json.toByteArray())
}
