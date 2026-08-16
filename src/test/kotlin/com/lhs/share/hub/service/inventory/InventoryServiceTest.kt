package com.lhs.share.hub.service.inventory

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.hub.controller.inventory.request.InventoryEntryRequest
import com.lhs.share.hub.controller.inventory.request.InventoryImportRequest
import com.lhs.share.hub.controller.inventory.request.InventoryRecordRequest
import com.lhs.share.hub.controller.inventory.request.ProducerDto
import com.lhs.share.hub.controller.inventory.response.InventoryCatalogResponse
import com.lhs.share.hub.repository.InventoryCurrentRepository
import com.lhs.share.hub.repository.InventoryRecordRepository
import com.lhs.share.hub.repository.entity.InventoryCurrent
import com.lhs.share.hub.repository.entity.InventoryRecord
import com.lhs.share.hub.repository.entity.ProducerInfo
import com.lhs.share.hub.repository.entity.RecordEntry
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

class InventoryServiceTest {
    private val currentRepository = mockk<InventoryCurrentRepository>()
    private val recordRepository = mockk<InventoryRecordRepository>()
    private val catalogService = mockk<EntityCatalogService>()
    private val mongoTemplate = mockk<MongoTemplate>()
    private val currents = mutableMapOf<Pair<String, String>, InventoryCurrent>()
    private val records = mutableMapOf<Pair<String, String>, InventoryRecord>()
    private var nextRecordId = 1

    private val transactionTemplate = TransactionTemplate(
        object : PlatformTransactionManager {
            override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()

            override fun commit(status: TransactionStatus) = Unit

            override fun rollback(status: TransactionStatus) = Unit
        },
    )

    private lateinit var service: InventoryService

    @BeforeEach
    fun setUp() {
        currents.clear()
        records.clear()
        nextRecordId = 1
        every { catalogService.exists(any(), any()) } returns true
        every { catalogService.catalog() } returns InventoryCatalogResponse(catalogVersion = "2026-08-16", entities = emptyList())
        every { currentRepository.findByUserIdAndEntityType(any(), any()) } answers {
            currents[firstArg<String>() to secondArg<String>()]
        }
        every { currentRepository.findByUserIdOrderByUpdatedAtDesc(any()) } answers {
            val userId = firstArg<String>()
            currents.values.filter { it.userId == userId }.sortedByDescending { it.updatedAt }
        }
        every { currentRepository.save(any()) } answers {
            val value = firstArg<InventoryCurrent>()
            val saved = if (value.id == null) value.copy(id = "current:${value.userId}:${value.entityType}") else value
            currents[saved.userId to saved.entityType] = saved
            saved
        }
        every { recordRepository.findByUserIdAndRecordId(any(), any()) } answers {
            records[firstArg<String>() to secondArg<String>()]
        }
        every { recordRepository.save(any()) } answers {
            val value = firstArg<InventoryRecord>()
            val saved = if (value.id == null) value.copy(id = "%024x".format(nextRecordId++)) else value
            records[saved.userId to saved.recordId] = saved
            saved
        }
        every { recordRepository.findByUserIdOrderByEffectiveAtAsc(any()) } answers {
            val userId = firstArg<String>()
            records.values.filter { it.userId == userId }.sortedBy { it.effectiveAt }
        }
        every { recordRepository.findByUserIdOrderByEffectiveAtDesc(any()) } answers {
            val userId = firstArg<String>()
            records.values.filter { it.userId == userId }.sortedByDescending { it.effectiveAt }
        }
        service = InventoryService(currentRepository, recordRepository, catalogService, mongoTemplate, transactionTemplate)
    }

    @Test
    fun `retransmission is idempotent and changed body conflicts`() {
        val request = document(reward("reward-1", "2026-08-16T10:00:00+08:00", "baijinbi", 5))

        assertEquals(1, service.import("u1", request).accepted)
        assertEquals(1, service.import("u1", request).duplicates)
        assertEquals(5, count("u1", "baijinbi"))
        assertEquals(1, records.size)

        val conflict = assertThrows(InventoryApiException::class.java) {
            service.import("u1", document(reward("reward-1", "2026-08-16T10:00:00+08:00", "baijinbi", 6)))
        }
        assertEquals(409, conflict.status.value())
        assertEquals("record_conflict", conflict.code)
        assertEquals(5, count("u1", "baijinbi"))
    }

    @Test
    fun `reward full and listed snapshots follow inventory semantics`() {
        service.import(
            "u1",
            document(
                reward("r1", "2026-08-16T10:00:00Z", "baimozhijiu", 5),
                snapshot("full-1", "2026-08-16T11:00:00Z", "full", entry("baijinbi", 10)),
                snapshot("listed-1", "2026-08-16T12:00:00Z", "listed", entry("baimozhijiu", 7)),
                reward("r2", "2026-08-16T13:00:00Z", "baijinbi", 2),
            ),
        )

        assertEquals(12, count("u1", "baijinbi"))
        assertEquals(7, count("u1", "baimozhijiu"))
        assertEquals(5, records["u1" to "r1"]!!.entries.single().count)
    }

    @Test
    fun `delayed reward is history only and newer listed value survives older full snapshot`() {
        service.import("u1", document(snapshot("full-1", "2026-08-16T11:00:00Z", "full", entry("baijinbi", 10))))
        val delayed = service.import("u1", document(reward("late", "2026-08-16T10:00:00Z", "baijinbi", 4)))
        service.import("u1", document(snapshot("listed", "2026-08-16T13:00:00Z", "listed", entry("baimozhijiu", 9))))
        service.import(
            "u1",
            document(
                snapshot(
                    "older-full",
                    "2026-08-16T12:00:00Z",
                    "full",
                    entry("baijinbi", 3),
                    entry("baimozhijiu", 1),
                ),
            ),
        )

        assertEquals(1, delayed.historyOnly)
        assertEquals("history_only", records["u1" to "late"]?.stockEffect)
        assertEquals(3, count("u1", "baijinbi"))
        assertEquals(9, count("u1", "baimozhijiu"))
    }

    @Test
    fun `empty full snapshot clears inventory`() {
        service.import("u1", document(snapshot("full-1", "2026-08-16T11:00:00Z", "full", entry("baijinbi", 10))))
        service.import("u1", document(snapshot("full-empty", "2026-08-16T12:00:00Z", "full")))

        assertTrue(currents["u1" to "item"]!!.entries.isEmpty())
    }

    @Test
    fun `same timestamp applies reward before snapshot`() {
        service.import(
            "u1",
            document(
                snapshot("snapshot", "2026-08-16T10:00:00Z", "full", entry("baijinbi", 4)),
                reward("reward", "2026-08-16T10:00:00Z", "baijinbi", 3),
            ),
        )

        assertEquals(4, count("u1", "baijinbi"))
    }

    @Test
    fun `entire document is validated before any write`() {
        every { catalogService.exists("item", "unknown") } returns false
        val error = assertThrows(InventoryApiException::class.java) {
            service.import(
                "u1",
                document(
                    reward("valid", "2026-08-16T10:00:00Z", "baijinbi", 1),
                    reward("invalid", "2026-08-16T11:00:00Z", "unknown", 1),
                ),
            )
        }

        assertEquals("unknown_entity_id", error.code)
        assertEquals("invalid", error.recordId)
        assertTrue(records.isEmpty())
        assertTrue(currents.isEmpty())
    }

    @Test
    fun `invalid times ids enums and snapshot scope are rejected`() {
        val invalid = listOf(
            document(reward("exported-time", "2026-08-16T10:00:00Z", "baijinbi", 1)).copy(
                exportedAt = "2026-08-16T09:00:00",
            ),
            document(reward("time", "2026-08-16T10:00:00", "baijinbi", 1)),
            document(reward("id", "2026-08-16T10:00:00Z", "", 1)),
            document(reward("count", "2026-08-16T10:00:00Z", "baijinbi", 0)),
            document(reward("channel", "2026-08-16T10:00:00Z", "baijinbi", 1).copy(acquisitionChannel = "")),
            document(
                reward("duplicate", "2026-08-16T10:00:00Z", "baijinbi", 1).copy(
                    entries = listOf(entry("baijinbi", 1), entry("baijinbi", 2)),
                ),
            ),
            document(reward("enum", "2026-08-16T10:00:00Z", "baijinbi", 1).copy(entityType = "equipment")),
            document(reward("scope", "2026-08-16T10:00:00Z", "baijinbi", 1).copy(snapshotScope = "full")),
            document(snapshot("listed", "2026-08-16T10:00:00Z", "listed")),
            document(reward("producer", "2026-08-16T10:00:00Z", "baijinbi", 1)).copy(producer = ProducerDto("")),
        )

        invalid.forEach { request ->
            assertEquals(
                "schema_validation_failed",
                assertThrows(InventoryApiException::class.java) { service.import("u1", request) }.code,
            )
        }
        assertTrue(records.isEmpty())
    }

    @Test
    fun `export is a complete protocol document and can be reimported unchanged`() {
        service.import("u1", document(snapshot("full", "2026-08-16T10:00:00Z", "full", entry("baijinbi", 8))))
        service.import("u1", document(reward("reward", "2026-08-16T11:00:00Z", "baijinbi", 2)))

        val exported = service.export("u1", includeRewards = true, from = null, to = null)
        val mapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val json = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(exported)
        val schemaDocument = mapper.readTree(checkNotNull(javaClass.getResourceAsStream("/inventory-exchange-v1.schema.json")))
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaDocument)
        val request = mapper.treeToValue(json, InventoryImportRequest::class.java)
        val sameUser = service.import("u1", request)
        val imported = service.import("u2", request)

        assertEquals("myshare-inventory-exchange", exported.format)
        assertEquals("myshare", exported.producer.platform)
        assertEquals(setOf("item", "agent"), exported.records.map { it.entityType }.toSet())
        assertTrue(schema.validate(json).isEmpty())
        assertEquals(1, sameUser.duplicates)
        assertEquals(10, count("u1", "baijinbi"))
        assertEquals(3, imported.accepted)
        assertEquals(10, count("u2", "baijinbi"))
    }

    @Test
    fun `record cursor pages have no duplicates or omissions`() {
        val all = listOf(
            storedRecord("000000000000000000000003", "r3", "2026-08-16T12:00:00Z"),
            storedRecord("000000000000000000000002", "r2", "2026-08-16T12:00:00Z"),
            storedRecord("000000000000000000000001", "r1", "2026-08-16T11:00:00Z"),
        )
        every { mongoTemplate.find(any(), InventoryRecord::class.java, "inventory_records") } returnsMany
            listOf(all, listOf(all[2]))

        val first = service.listRecords("u1", null, null, null, null, 2)
        val second = service.listRecords("u1", null, null, null, checkNotNull(first.nextCursor), 2)
        val ids = first.items.map { it.recordId } + second.items.map { it.recordId }

        assertEquals(listOf("r3", "r2", "r1"), ids)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(null, second.nextCursor)
    }

    private fun document(vararg records: InventoryRecordRequest) = InventoryImportRequest(
        format = "myshare-inventory-exchange",
        version = 1,
        exportedAt = "2026-08-16T09:00:00Z",
        producer = ProducerDto("test", "1"),
        records = records.toList(),
    )

    private fun reward(id: String, at: String, entityId: String, count: Long) = InventoryRecordRequest(
        recordId = id,
        recordType = "reward_delta",
        entityType = "item",
        effectiveAt = at,
        entries = listOf(entry(entityId, count)),
    )

    private fun snapshot(id: String, at: String, scope: String, vararg entries: InventoryEntryRequest) = InventoryRecordRequest(
        recordId = id,
        recordType = "stock_snapshot",
        entityType = "item",
        effectiveAt = at,
        snapshotScope = scope,
        entries = entries.toList(),
    )

    private fun entry(id: String, count: Long) = InventoryEntryRequest(id, null, count)

    private fun count(userId: String, entityId: String): Long = currents[userId to "item"]?.entries?.get(entityId)?.count ?: 0

    private fun storedRecord(id: String, recordId: String, effectiveAt: String) = InventoryRecord(
        id = id,
        recordId = recordId,
        userId = "u1",
        recordType = "reward_delta",
        entityType = "item",
        effectiveAt = Instant.parse(effectiveAt),
        producer = ProducerInfo("test"),
        entries = listOf(RecordEntry("baijinbi", count = 1)),
    )
}
