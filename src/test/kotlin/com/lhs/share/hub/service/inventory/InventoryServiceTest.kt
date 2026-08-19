package com.lhs.share.hub.service.inventory

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.hub.controller.inventory.request.InventoryEntryRequest
import com.lhs.share.hub.controller.inventory.request.InventoryExchangeAccountDto
import com.lhs.share.hub.controller.inventory.request.InventoryImportRequest
import com.lhs.share.hub.controller.inventory.request.InventoryRecordRequest
import com.lhs.share.hub.controller.inventory.request.ProducerDto
import com.lhs.share.hub.controller.inventory.response.InventoryCatalogResponse
import com.lhs.share.hub.repository.InventoryAccountRepository
import com.lhs.share.hub.repository.InventoryCurrentRepository
import com.lhs.share.hub.repository.InventoryRecordRepository
import com.lhs.share.hub.repository.entity.InventoryAccount
import com.lhs.share.hub.repository.entity.InventoryCurrent
import com.lhs.share.hub.repository.entity.InventoryRecord
import com.lhs.share.hub.repository.entity.ProducerInfo
import com.lhs.share.hub.repository.entity.RecordEntry
import com.mongodb.client.AggregateIterable
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoCursor
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

class InventoryServiceTest {
    private val accountRepository = mockk<InventoryAccountRepository>()
    private val currentRepository = mockk<InventoryCurrentRepository>()
    private val recordRepository = mockk<InventoryRecordRepository>()
    private val catalogService = mockk<EntityCatalogService>()
    private val mongoTemplate = mockk<MongoTemplate>()
    private val accounts = mutableMapOf<Pair<String, String>, InventoryAccount>()
    private val currents = mutableMapOf<Triple<String, String, String>, InventoryCurrent>()
    private val records = mutableMapOf<Triple<String, String, String>, InventoryRecord>()
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
        accounts.clear()
        accounts["u1" to "main"] = InventoryAccount(id = "a1", userId = "u1", accountId = "main", name = "大号")
        accounts["u1" to "alt"] = InventoryAccount(id = "a2", userId = "u1", accountId = "alt", name = "小号")
        accounts["u2" to "main"] = InventoryAccount(id = "a3", userId = "u2", accountId = "main", name = "导入账号")
        accounts["u2" to "foreign"] = InventoryAccount(id = "a4", userId = "u2", accountId = "foreign", name = "他人账号")
        nextRecordId = 1
        every { catalogService.exists(any(), any()) } returns true
        every { catalogService.catalog() } returns InventoryCatalogResponse(catalogVersion = "2026-08-16", entities = emptyList())
        every { accountRepository.findByUserIdAndAccountId(any(), any()) } answers {
            accounts[firstArg<String>() to secondArg<String>()]
        }
        every { accountRepository.findAllByUserIdAndAccountIdIn(any(), any()) } answers {
            val userId = firstArg<String>()
            val ids = secondArg<Collection<String>>()
            accounts.values.filter { it.userId == userId && it.accountId in ids }
        }
        every { accountRepository.findAllByUserIdOrderByCreatedAtAsc(any()) } answers {
            val userId = firstArg<String>()
            accounts.values.filter { it.userId == userId }.sortedBy { it.createdAt }
        }
        every { currentRepository.findByUserIdAndAccountIdAndEntityType(any(), any(), any()) } answers {
            currents[Triple(firstArg(), secondArg(), thirdArg())]
        }
        every { currentRepository.findByUserIdAndAccountIdOrderByUpdatedAtDesc(any(), any()) } answers {
            val userId = firstArg<String>()
            val accountId = secondArg<String>()
            currents.values.filter { it.userId == userId && it.accountId == accountId }.sortedByDescending { it.updatedAt }
        }
        every { currentRepository.save(any()) } answers {
            val value = firstArg<InventoryCurrent>()
            val saved = if (value.id == null) value.copy(id = "current:${value.userId}:${value.accountId}:${value.entityType}") else value
            currents[Triple(saved.userId, saved.accountId, saved.entityType)] = saved
            saved
        }
        every { currentRepository.deleteById(any()) } answers {
            val id = firstArg<String>()
            currents.entries.removeIf { it.value.id == id }
        }
        every { recordRepository.findByUserIdAndAccountIdAndRecordId(any(), any(), any()) } answers {
            records[Triple(firstArg(), secondArg(), thirdArg())]
        }
        every { recordRepository.save(any()) } answers {
            val value = firstArg<InventoryRecord>()
            val saved = if (value.id == null) value.copy(id = "%024x".format(nextRecordId++)) else value
            records[Triple(saved.userId, saved.accountId, saved.recordId)] = saved
            saved
        }
        every { recordRepository.deleteById(any()) } answers {
            val id = firstArg<String>()
            records.entries.removeIf { it.value.id == id }
        }
        every { recordRepository.findByUserIdAndAccountIdOrderByEffectiveAtAsc(any(), any()) } answers {
            val userId = firstArg<String>()
            val accountId = secondArg<String>()
            records.values.filter { it.userId == userId && it.accountId == accountId }.sortedBy { it.effectiveAt }
        }
        every { recordRepository.findByUserIdAndAccountIdOrderByEffectiveAtDesc(any(), any()) } answers {
            val userId = firstArg<String>()
            val accountId = secondArg<String>()
            records.values.filter { it.userId == userId && it.accountId == accountId }.sortedByDescending { it.effectiveAt }
        }
        service = InventoryService(
            accountRepository,
            currentRepository,
            recordRepository,
            catalogService,
            mongoTemplate,
            transactionTemplate,
        )
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
    fun `dispatch reward requires and persists stamina cost`() {
        val dispatch = reward("dispatch", "2026-08-16T10:00:00Z", "baijinbi", 1).copy(
            acquisitionChannel = "自动派遣",
            staminaCost = 80,
        )

        service.import("u1", document(dispatch))

        assertEquals(80, records[Triple("u1", "main", "dispatch")]?.staminaCost)
        val conflict = assertThrows(InventoryApiException::class.java) {
            service.import("u1", document(dispatch.copy(staminaCost = 40)))
        }
        assertEquals("record_conflict", conflict.code)
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
        assertEquals(5, records[Triple("u1", "main", "r1")]!!.entries.single().count)
    }

    @Test
    fun `agent full and listed snapshots follow inventory semantics`() {
        service.import(
            "u1",
            document(
                agentSnapshot(
                    "agent-full-1",
                    "2026-08-16T10:00:00Z",
                    "full",
                    "main",
                    entry("char_038_luxun", 4),
                    entry("char_102_jianyong", 8),
                ),
                agentSnapshot(
                    "agent-listed",
                    "2026-08-16T11:00:00Z",
                    "listed",
                    "main",
                    entry("char_102_jianyong", 12),
                ),
            ),
        )

        assertEquals(4, count("u1", "char_038_luxun", entityType = "agent"))
        assertEquals(12, count("u1", "char_102_jianyong", entityType = "agent"))

        service.import(
            "u1",
            document(
                agentSnapshot(
                    "agent-full-2",
                    "2026-08-16T12:00:00Z",
                    "full",
                    "main",
                    entry("char_102_jianyong", 3),
                ),
            ),
        )

        assertEquals(0, count("u1", "char_038_luxun", entityType = "agent"))
        assertEquals(3, count("u1", "char_102_jianyong", entityType = "agent"))
        assertEquals(
            3,
            service.current("u1", "main", "agent").single().entries.getValue("char_102_jianyong").count,
        )
    }

    @Test
    fun `agent snapshots do not affect items or another account`() {
        service.import(
            "u1",
            document(
                snapshot("item-full", "2026-08-16T10:00:00Z", "full", entry("baijinbi", 9)),
                agentSnapshot(
                    "agent-main",
                    "2026-08-16T10:00:00Z",
                    "full",
                    "main",
                    entry("char_102_jianyong", 5),
                ),
                agentSnapshot(
                    "agent-alt",
                    "2026-08-16T10:00:00Z",
                    "full",
                    "alt",
                    entry("char_102_jianyong", 17),
                ),
            ),
        )

        assertEquals(9, count("u1", "baijinbi"))
        assertEquals(5, count("u1", "char_102_jianyong", "main", "agent"))
        assertEquals(17, count("u1", "char_102_jianyong", "alt", "agent"))
    }

    @Test
    fun `agent snapshot name remains record display data only`() {
        service.import(
            "u1",
            document(
                agentSnapshot(
                    "agent-forged-name",
                    "2026-08-16T10:00:00Z",
                    "full",
                    "main",
                    InventoryEntryRequest("char_102_jianyong", "伪造名称", 12),
                ),
            ),
        )

        assertEquals(12, count("u1", "char_102_jianyong", entityType = "agent"))
        assertEquals("伪造名称", records[Triple("u1", "main", "agent-forged-name")]!!.entries.single().name)
        verify(exactly = 1) { catalogService.exists("agent", "char_102_jianyong") }
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
        assertEquals("history_only", records[Triple("u1", "main", "late")]?.stockEffect)
        assertEquals(3, count("u1", "baijinbi"))
        assertEquals(9, count("u1", "baimozhijiu"))
    }

    @Test
    fun `empty full snapshot clears inventory`() {
        service.import("u1", document(snapshot("full-1", "2026-08-16T11:00:00Z", "full", entry("baijinbi", 10))))
        service.import("u1", document(snapshot("full-empty", "2026-08-16T12:00:00Z", "full")))

        assertTrue(currents[Triple("u1", "main", "item")]!!.entries.isEmpty())
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
    fun `accounts isolate idempotency baselines and delayed rewards`() {
        service.import(
            "u1",
            document(snapshot("baseline", "2026-08-16T12:00:00Z", "full", entry("baijinbi", 10))),
        )
        val result = service.import(
            "u1",
            document(
                reward("same-id", "2026-08-16T10:00:00Z", "baijinbi", 3),
                reward("same-id", "2026-08-16T10:00:00Z", "baijinbi", 4, accountId = "alt"),
            ),
        )

        assertEquals(2, result.accepted)
        assertEquals(1, result.historyOnly)
        assertEquals(10, count("u1", "baijinbi", "main"))
        assertEquals(4, count("u1", "baijinbi", "alt"))
        assertEquals(2, records.values.count { it.recordId == "same-id" })
    }

    @Test
    fun `automatic imports accept omitted accounts id-only accounts and stale names`() {
        val withoutAccounts = service.import(
            "u1",
            document(reward("without-directory", "2026-08-16T10:00:00Z", "baijinbi", 1)),
        )
        val idOnly = service.import(
            "u1",
            document(reward("id-only", "2026-08-16T11:00:00Z", "baijinbi", 1)).copy(
                accounts = listOf(InventoryExchangeAccountDto("main")),
            ),
        )
        val staleName = service.import(
            "u1",
            document(reward("stale-name", "2026-08-16T12:00:00Z", "baijinbi", 1, "alt")).copy(
                accounts = listOf(InventoryExchangeAccountDto("alt", "客户端旧名称")),
            ),
        )

        assertEquals(1, withoutAccounts.accepted)
        assertEquals(1, idOnly.accepted)
        assertEquals(1, staleName.accepted)
        assertEquals(2, count("u1", "baijinbi"))
        assertEquals(1, count("u1", "baijinbi", "alt"))
        verify(exactly = 0) { accountRepository.save(any()) }
    }

    @Test
    fun `unknown and other-user account ids reject the entire document without name matching`() {
        val unknown = assertThrows(InventoryApiException::class.java) {
            service.import(
                "u1",
                document(
                    reward("valid", "2026-08-16T10:00:00Z", "baijinbi", 1),
                    reward("unknown", "2026-08-16T11:00:00Z", "baijinbi", 1, "ghost"),
                ).copy(accounts = listOf(InventoryExchangeAccountDto("ghost", "大号"))),
            )
        }
        val foreign = assertThrows(InventoryApiException::class.java) {
            service.import("u1", document(reward("foreign", "2026-08-16T10:00:00Z", "baijinbi", 1, "foreign")))
        }

        assertEquals("unknown_account_id", unknown.code)
        assertEquals("unknown_account_id", foreign.code)
        assertTrue(records.isEmpty())
        assertTrue(currents.isEmpty())
        verify(exactly = 0) { accountRepository.save(any()) }
    }

    @Test
    fun `account-bound import rejects records for another owned account`() {
        val error = assertThrows(InventoryApiException::class.java) {
            service.import(
                "u1",
                "main",
                document(reward("wrong-account", "2026-08-16T10:00:00Z", "baijinbi", 1, accountId = "alt")),
            )
        }

        assertEquals(403, error.status.value())
        assertEquals("account_scope_mismatch", error.code)
        assertTrue(records.isEmpty())
        assertTrue(currents.isEmpty())
    }

    @Test
    fun `deleting a record replays only its account`() {
        service.import(
            "u1",
            document(
                reward("remove", "2026-08-16T10:00:00Z", "baijinbi", 3),
                reward("keep", "2026-08-16T10:00:00Z", "baijinbi", 7, accountId = "alt"),
            ),
        )

        service.deleteRecord("u1", "main", "remove")

        assertEquals(0, count("u1", "baijinbi", "main"))
        assertEquals(7, count("u1", "baijinbi", "alt"))
        assertTrue(records.containsKey(Triple("u1", "alt", "keep")))
    }

    @Test
    fun `acquired aggregation is scoped to one account`() {
        val collection = mockk<MongoCollection<Document>>()
        val aggregate = mockk<AggregateIterable<Document>>()
        val cursor = mockk<MongoCursor<Document>>()
        val pipeline = mutableListOf<List<Bson>>()
        every { mongoTemplate.getCollection("inventory_records") } returns collection
        every { collection.aggregate(capture(pipeline)) } returns aggregate
        every { aggregate.iterator() } returns cursor
        every { cursor.hasNext() } returns false

        val response = service.acquired(
            "u1",
            "alt",
            "item",
            Instant.parse("2026-08-01T00:00:00Z"),
            Instant.parse("2026-09-01T00:00:00Z"),
        )
        val match = (pipeline.single().first() as Document).get("\$match", Document::class.java)

        assertEquals("alt", response.accountId)
        assertEquals("u1", match.getString("userId"))
        assertEquals("alt", match.getString("accountId"))
        assertEquals("reward_delta", match.getString("recordType"))
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
            document(reward("dispatch-missing", "2026-08-16T10:00:00Z", "baijinbi", 1).copy(acquisitionChannel = "派遣")),
            document(reward("non-dispatch", "2026-08-16T10:00:00Z", "baijinbi", 1).copy(staminaCost = 80)),
            document(
                reward("negative-stamina", "2026-08-16T10:00:00Z", "baijinbi", 1).copy(
                    acquisitionChannel = "派遣",
                    staminaCost = -1,
                ),
            ),
            document(snapshot("snapshot-stamina", "2026-08-16T10:00:00Z", "full").copy(staminaCost = 80)),
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
        service.import(
            "u1",
            document(
                reward("reward", "2026-08-16T11:00:00Z", "baijinbi", 2).copy(
                    acquisitionChannel = "派遣",
                    staminaCost = 80,
                ),
            ),
        )

        val exported = service.export("u1", accountId = "main", scope = null, includeRewards = true, from = null, to = null)
        val mapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val json = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(exported)
        val schemaDocument = mapper.readTree(checkNotNull(javaClass.getResourceAsStream("/inventory-exchange-v2.schema.json")))
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaDocument)
        val request = mapper.treeToValue(json, InventoryImportRequest::class.java)
        val sameUser = service.import("u1", request)
        val imported = service.import("u2", request)

        assertEquals("myshare-inventory-exchange", exported.format)
        assertEquals("myshare", exported.producer.platform)
        assertEquals(listOf("main"), exported.accounts.map { it.id })
        assertEquals("大号", exported.accounts.single().name)
        assertTrue(exported.records.all { it.accountId == "main" })
        assertEquals(setOf("item", "agent"), exported.records.map { it.entityType }.toSet())
        assertEquals(80, exported.records.single { it.recordId == "reward" }.staminaCost)
        assertTrue(schema.validate(json).isEmpty())
        assertEquals(1, sameUser.duplicates)
        assertEquals(10, count("u1", "baijinbi"))
        assertEquals(3, imported.accepted)
        assertEquals(10, count("u2", "baijinbi"))
    }

    @Test
    fun `checked-in import example conforms to protocol v2 schema`() {
        val mapper = jacksonObjectMapper()
        val example = mapper.readTree(java.io.File("docs/inventory-import-example.json"))
        val schemaDocument = mapper.readTree(checkNotNull(javaClass.getResourceAsStream("/inventory-exchange-v2.schema.json")))
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaDocument)

        assertTrue(schema.validate(example).isEmpty())
        val automaticReport = example.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply { remove("accounts") }
        val idOnlyDirectory = example.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply {
            (this["accounts"][0] as com.fasterxml.jackson.databind.node.ObjectNode).remove("name")
        }
        assertTrue(schema.validate(automaticReport).isEmpty())
        assertTrue(schema.validate(idOnlyDirectory).isEmpty())

        val dispatchWithoutStamina = example.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply {
            (this["records"][0] as com.fasterxml.jackson.databind.node.ObjectNode).remove("stamina_cost")
        }
        val nonDispatchWithStamina = example.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply {
            (this["records"][3] as com.fasterxml.jackson.databind.node.ObjectNode).put("stamina_cost", 80)
        }
        assertTrue(schema.validate(dispatchWithoutStamina).isNotEmpty())
        assertTrue(schema.validate(nonDispatchWithStamina).isNotEmpty())
    }

    @Test
    fun `all-account export preserves ownership and reimports without mixing`() {
        service.import(
            "u1",
            document(
                snapshot("main-stock", "2026-08-16T10:00:00Z", "full", entry("baijinbi", 8)),
                snapshotForAccount("alt-stock", "2026-08-16T10:00:00Z", "full", "alt", entry("baijinbi", 21)),
            ),
        )

        val exported = service.export("u1", accountId = null, scope = "all", includeRewards = false, from = null, to = null)
        val mapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val json = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(exported)
        val schemaDocument = mapper.readTree(checkNotNull(javaClass.getResourceAsStream("/inventory-exchange-v2.schema.json")))
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaDocument)

        assertTrue(schema.validate(json).isEmpty())
        assertEquals(setOf("main", "alt"), exported.accounts.map { it.id }.toSet())
        assertEquals(mapOf("main" to "大号", "alt" to "小号"), exported.accounts.associate { it.id to it.name })
        assertEquals(setOf("main", "alt"), exported.records.map { it.accountId }.toSet())
        service.import("u1", mapper.treeToValue(json, InventoryImportRequest::class.java))
        assertEquals(8, count("u1", "baijinbi", "main"))
        assertEquals(21, count("u1", "baijinbi", "alt"))
    }

    @Test
    fun `record cursor pages have no duplicates or omissions`() {
        val all = listOf(
            storedRecord("000000000000000000000003", "r3", "2026-08-16T12:00:00Z"),
            storedRecord("000000000000000000000002", "r2", "2026-08-16T12:00:00Z"),
            storedRecord("000000000000000000000001", "r1", "2026-08-16T11:00:00Z"),
        )
        val queries = mutableListOf<Query>()
        every { mongoTemplate.find(capture(queries), InventoryRecord::class.java, "inventory_records") } returnsMany
            listOf(all, listOf(all[2]))

        val first = service.listRecords("u1", "main", null, null, null, null, 2)
        val second = service.listRecords("u1", "main", null, null, null, checkNotNull(first.nextCursor), 2)
        val ids = first.items.map { it.recordId } + second.items.map { it.recordId }

        assertEquals(listOf("r3", "r2", "r1"), ids)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(null, second.nextCursor)
        assertTrue(queries.all { it.queryObject.getString("accountId") == "main" })
    }

    private fun document(vararg records: InventoryRecordRequest) = InventoryImportRequest(
        format = "myshare-inventory-exchange",
        version = 2,
        exportedAt = "2026-08-16T09:00:00Z",
        producer = ProducerDto("test", "1"),
        records = records.toList(),
    )

    private fun reward(id: String, at: String, entityId: String, count: Long, accountId: String = "main") = InventoryRecordRequest(
        accountId = accountId,
        recordId = id,
        recordType = "reward_delta",
        entityType = "item",
        effectiveAt = at,
        entries = listOf(entry(entityId, count)),
    )

    private fun snapshot(id: String, at: String, scope: String, vararg entries: InventoryEntryRequest) =
        snapshotForAccount(id, at, scope, "main", *entries)

    private fun snapshotForAccount(
        id: String,
        at: String,
        scope: String,
        accountId: String,
        vararg entries: InventoryEntryRequest,
    ): InventoryRecordRequest = InventoryRecordRequest(
        accountId = accountId,
        recordId = id,
        recordType = "stock_snapshot",
        entityType = "item",
        effectiveAt = at,
        snapshotScope = scope,
        entries = entries.toList(),
    )

    private fun agentSnapshot(
        id: String,
        at: String,
        scope: String,
        accountId: String,
        vararg entries: InventoryEntryRequest,
    ): InventoryRecordRequest = InventoryRecordRequest(
        accountId = accountId,
        recordId = id,
        recordType = "stock_snapshot",
        entityType = "agent",
        effectiveAt = at,
        snapshotScope = scope,
        entries = entries.toList(),
    )

    private fun entry(id: String, count: Long) = InventoryEntryRequest(id, null, count)

    private fun count(userId: String, entityId: String, accountId: String = "main", entityType: String = "item"): Long =
        currents[Triple(userId, accountId, entityType)]?.entries?.get(entityId)?.count ?: 0

    private fun storedRecord(id: String, recordId: String, effectiveAt: String) = InventoryRecord(
        id = id,
        recordId = recordId,
        userId = "u1",
        accountId = "main",
        recordType = "reward_delta",
        entityType = "item",
        effectiveAt = Instant.parse(effectiveAt),
        producer = ProducerInfo("test"),
        entries = listOf(RecordEntry("baijinbi", count = 1)),
    )
}
