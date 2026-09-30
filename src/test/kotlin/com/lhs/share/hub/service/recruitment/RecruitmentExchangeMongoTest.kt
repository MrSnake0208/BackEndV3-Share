package com.lhs.share.hub.service.recruitment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.hub.controller.recruitment.request.RecruitmentCommandRequest
import com.lhs.share.hub.controller.recruitment.request.RecruitmentImportCommitRequest
import com.lhs.share.hub.controller.recruitment.request.RecruitmentImportOptions
import com.lhs.share.hub.controller.recruitment.request.RecruitmentImportPreviewRequest
import com.lhs.share.hub.repository.OperatorCatalogRepository
import com.lhs.share.hub.repository.RecruitmentRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.SubAccountRepositoryImpl
import com.lhs.share.hub.repository.entity.RecruitmentArchive
import com.lhs.share.hub.repository.entity.RecruitmentBatch
import com.lhs.share.hub.repository.entity.RecruitmentEvent
import com.lhs.share.hub.repository.entity.RecruitmentRequestRecord
import com.lhs.share.hub.repository.entity.SubAccount
import com.lhs.share.hub.service.account.AccountEventService
import com.lhs.share.hub.service.account.SubAccountService
import com.lhs.share.hub.service.inventory.InventoryApiException
import com.lhs.share.testinfra.TestMongo
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Only owned TestMongo replica-set connections; no application context or development data. */
@Tag("integration")
class RecruitmentExchangeMongoTest {
    private val database = TestMongo.database("recruitment_exchange")
    private val client = TestMongo.client()
    private val template = MongoTemplate(SimpleMongoClientDatabaseFactory(client, database))
    private val accounts = MongoRepositoryFactory(template).getRepository(
        SubAccountRepository::class.java,
        RepositoryFragments.just(SubAccountRepositoryImpl(template)),
    )
    private val store = RecruitmentRepository(template)
    private val mapper = jacksonObjectMapper().registerModule(
        JavaTimeModule(),
    ).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
    private val tx = TransactionTemplate(MongoTransactionManager(template.mongoDatabaseFactory))
    private val publisher = spyk(AccountEventService(mockk(relaxed = true)))
    private val accountService = SubAccountService(
        accounts, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), tx, recruitmentRepository = store, accountEvents = publisher,
    )
    private val mutation = RecruitmentMutation(store, mockk<OperatorCatalogRepository>(), RecruitmentCatalog(mapper), mapper)
    private val core = RecruitmentService(store, accountService, accounts, mutation, publisher, mapper, tx)
    private val service = RecruitmentExchangeService(store, accountService, accounts, core, publisher, mapper, tx)

    @BeforeEach fun setup() {
        listOf(
            SubAccount::class.java,
            RecruitmentArchive::class.java,
            RecruitmentEvent::class.java,
            RecruitmentBatch::class.java,
            RecruitmentRequestRecord::class.java,
        ).forEach { type ->
            template.createCollection(type)
            MongoPersistentEntityIndexResolver(template.converter.mappingContext).resolveIndexFor(type).forEach {
                template.indexOps(type).ensureIndex(it)
            }
        }
        listOf("a", "b").forEach { accounts.insert(SubAccount(userId = "u", accountId = it, name = it, game = "如鸢")) }
    }

    @AfterEach fun cleanup() {
        TestMongo.dropDatabase(client, database)
        client.close()
    }
    private fun command(accountId: String, operation: String, json: String) = core.command(
        "u",
        RecruitmentCommandRequest(
            accountId,
            store.archive("u", accountId)?.archiveRevision ?: 0,
            UUID.randomUUID().toString(),
            operation,
            mapper.readTree(json),
        ),
    )
    private fun initialize(accountId: String = "a") {
        command(accountId, "pool_create", """{"pool_id":"p","name":"临时池","progress":0}""")
        command(accountId, "temporary_agent_create", """{"agent_id":"tmp_A","name":"绝密A"}""")
    }
    private fun historical(accountId: String = "a", id: String = "E", span: Int = 17) = command(
        accountId,
        "event_create",
        """{"pool_id":"p","mode":"historical","entries":[{"event_id":"$id","agent_id":"tmp_A","pull_span":$span,"acquired_date":"2026-09-30","note":"原始备注"}]}""",
    )
    private fun backup(): JsonNode = mapper.valueToTree(service.export("u", "a"))
    private fun prepared(
        node: JsonNode,
        target: String = "b",
        options: RecruitmentImportOptions = RecruitmentImportOptions(),
    ): RecruitmentImportCommitRequest {
        val preview = service.preview("u", RecruitmentImportPreviewRequest(target, node, options))
        return RecruitmentImportCommitRequest(
            target,
            node,
            options,
            preview.previewToken,
            preview.documentHash,
            preview.targetRevision,
            UUID.randomUUID().toString(),
        )
    }

    @Test fun `roundtrip preserves all logic and original provenance while repeated file and durable retries do not duplicate`() {
        initialize()
        command("a", "baseline_set", """{"baseline":100}""")
        command("a", "progress_set", """{"pool_id":"p","progress":13}""")
        historical()
        historical(id = "D", span = 31)
        command("a", "event_delete", """{"event_id":"D"}""")
        command(
            "a",
            "batch_create",
            """{"pool_id":"p","mode":"historical","batch_id":"B","total_pull_count":80,"entries":[{"event_id":"N","agent_id":"tmp_A","pull_span":null}]}""",
        )
        command(
            "a",
            "batch_create",
            """{"pool_id":"p","mode":"historical","batch_id":"BD","total_pull_count":40,"entries":[{"event_id":"ND","agent_id":"tmp_A","pull_span":10}]}""",
        )
        command("a", "batch_delete", """{"batch_id":"BD","confirm_total_pull_count":40}""")
        val source = service.export("u", "a")
        val body = mapper.valueToTree<JsonNode>(source)
        val input = prepared(body)
        val first = service.commit("u", input)
        val restored = service.export("u", "b")
        assertEquals(1L, restored.archiveRevision)
        assertEquals(source.baseline, restored.baseline)
        assertEquals(source.pools, restored.pools)
        assertEquals(source.temporaryAgents, restored.temporaryAgents)
        assertEquals(source.currentPoolId, restored.currentPoolId)
        assertEquals(source.events.map { it.eventId }, restored.events.map { it.eventId })
        assertEquals(source.events.map { it.source }, restored.events.map { it.source })
        assertEquals(source.events.map { it.poolSnapshot }, restored.events.map { it.poolSnapshot })
        assertEquals(source.events.map { it.agentSnapshot }, restored.events.map { it.agentSnapshot })
        assertEquals(source.events.map { it.acquiredDate }, restored.events.map { it.acquiredDate })
        assertEquals(source.events.map { it.pullSpan }, restored.events.map { it.pullSpan })
        assertEquals(source.events.map { it.sortOrder }, restored.events.map { it.sortOrder })
        assertEquals(source.batches.map { it.totalPullCount }, restored.batches.map { it.totalPullCount })
        assertTrue(restored.events.all { it.importBatchId != null && it.importedAt != null })
        assertEquals(0L, restored.events.single { it.eventId == "D" }.deletedRevision)
        assertEquals(0L, restored.batches.single { it.batchId == "BD" }.deletedRevision)
        assertNotNull(restored.batches.single { it.batchId == "BD" }.deletedAt)
        assertNotNull(restored.events.single { it.eventId == "ND" }.deletedAt)
        assertEquals(210L, core.archive("u", "b").summary.knownTotalPulls)
        assertThrows(RecruitmentApiException::class.java) { command("b", "event_restore", """{"event_id":"D"}""") }
        val again = prepared(body)
        val repeated = service.commit("u", again)
        assertTrue(repeated.eventIds.isEmpty())
        assertEquals(2L, repeated.archiveRevision)
        assertEquals(4, store.exchangeEvents("u", "b", 20_001).size)
        val restarted = RecruitmentExchangeService(store, accountService, accounts, core, publisher, mapper, tx)
        assertEquals(first, restarted.commit("u", input))
        assertEquals(2L, store.archive("u", "b")!!.archiveRevision)
        verify(exactly = 1) {
            publisher.publish(
                "u",
                "b",
                "recruitment_changed",
                any(),
                match { payload ->
                    val metadata = payload as Map<*, *>
                    metadata.entries.any { entry -> entry.key == "archive_revision" && entry.value == 1L }
                },
            )
        }
    }

    @Test fun `live and deleted conflicts preserve target with no resurrection or deletion`() {
        initialize()
        historical()
        val live = backup()
        service.commit("u", prepared(live))
        command("a", "event_delete", """{"event_id":"E"}""")
        val dead = backup()
        val preview = service.preview("u", RecruitmentImportPreviewRequest("b", dead))
        assertEquals("conflict", preview.items.single { it.entityType == "event" }.status)
        service.commit("u", prepared(dead))
        assertNull(store.event("u", "b", "E")!!.deletedAt)
        command("b", "event_delete", """{"event_id":"E"}""")
        val original = service.preview("u", RecruitmentImportPreviewRequest("b", live))
        assertEquals("conflict", original.items.single { it.entityType == "event" }.status)
        service.commit("u", prepared(live))
        assertNotNull(store.event("u", "b", "E")!!.deletedAt)
    }

    @Test fun `same IDs with changed facts conflict and reimport after target reorder remains duplicate`() {
        initialize()
        historical(id = "E1")
        historical(id = "E2", span = 31)
        val node = backup()
        service.commit("u", prepared(node))
        command("b", "event_reorder", """{"pool_id":"p","event_ids":["E2","E1"]}""")
        val repeat = service.preview("u", RecruitmentImportPreviewRequest("b", node))
        assertEquals(2, repeat.items.count { it.entityType == "event" && it.status == "duplicate" })
        command(
            "a",
            "event_update",
            """{"event_id":"E1","entry":{"agent_id":"tmp_A","pull_span":18,"acquired_date":"2026-09-30","note":"原始备注"}}""",
        )
        val changed = service.preview("u", RecruitmentImportPreviewRequest("b", backup()))
        assertEquals("conflict", changed.items.single { it.id == "E1" }.status)
        assertEquals(17L, store.event("u", "b", "E1")!!.pullSpan)
    }

    @Test fun `stale hash choice wrong owner account game and expired preview cannot mutate target`() {
        initialize()
        historical()
        val node = backup()
        val input = prepared(node)
        assertThrows(InventoryApiException::class.java) { service.commit("foreign", input) }
        listOf(
            input.copy(documentHash = "wrong"),
            input.copy(accountId = "a"),
            input.copy(options = RecruitmentImportOptions(confirmCountChange = true)),
        ).forEach {
            assertEquals("recruitment_preview_mismatch", assertThrows(RecruitmentApiException::class.java) { service.commit("u", it) }.code)
        }
        accounts.save(accounts.findByUserIdAndAccountId("u", "b")!!.copy(game = "代号鸢"))
        assertEquals("recruitment_game_mismatch", assertThrows(RecruitmentApiException::class.java) { service.commit("u", input) }.code)
        accounts.save(accounts.findByUserIdAndAccountId("u", "b")!!.copy(game = "如鸢"))
        command("b", "baseline_set", """{"baseline":1}""")
        assertEquals("recruitment_revision_conflict", assertThrows(RecruitmentApiException::class.java) { service.commit("u", input) }.code)
        val before = store.archive("u", "b")
        service.clock = Clock.fixed(Instant.now().plusSeconds(601), ZoneOffset.UTC)
        assertEquals("recruitment_preview_expired", assertThrows(RecruitmentApiException::class.java) { service.commit("u", input) }.code)
        assertEquals(before, store.archive("u", "b"))
        assertTrue(store.exchangeEvents("u", "b", 20_001).isEmpty())
    }

    @Test fun `overlap default skips additions while confirmed replacement never adds two baselines`() {
        initialize()
        historical()
        command("a", "baseline_set", """{"baseline":50}""")
        initialize("b")
        command("b", "baseline_set", """{"baseline":100}""")
        val node = backup()
        val initial = service.preview("u", RecruitmentImportPreviewRequest("b", node))
        assertEquals(100L, initial.candidateKnownTotal)
        assertEquals("count_overlap", initial.items.single { it.entityType == "event" }.status)
        service.commit("u", prepared(node))
        assertEquals(100L, core.archive("u", "b").summary.knownTotalPulls)
        assertTrue(store.exchangeEvents("u", "b", 20_001).isEmpty())
        val options = RecruitmentImportOptions("use_backup", true)
        val chosen = service.preview("u", RecruitmentImportPreviewRequest("b", node, options))
        assertEquals(67L, chosen.candidateKnownTotal)
        service.commit("u", prepared(node, options = options))
        assertEquals(50L, store.archive("u", "b")!!.baseline)
        assertEquals(67L, core.archive("u", "b").summary.knownTotalPulls)
    }

    @Test fun `failure after archive batches and events rolls back all collections fence and SSE`() {
        initialize()
        historical()
        command(
            "a",
            "batch_create",
            """{"pool_id":"p","mode":"historical","batch_id":"B","total_pull_count":80,"entries":[{"event_id":"N","agent_id":"tmp_A","pull_span":null}]}""",
        )
        val input = prepared(backup())
        val fence = accounts.findByUserIdAndAccountId("u", "b")!!.recruitmentFence
        val failing = spyk(store)
        every { failing.insertRequest(any()) } throws IllegalStateException("synthetic post-write failure")
        val broken = RecruitmentExchangeService(failing, accountService, accounts, core, publisher, mapper, tx)
        val preview = broken.preview("u", RecruitmentImportPreviewRequest("b", input.document))
        val brokenInput = input.copy(previewToken = preview.previewToken, documentHash = preview.documentHash)
        assertThrows(IllegalStateException::class.java) { broken.commit("u", brokenInput) }
        assertNull(store.archive("u", "b"))
        assertTrue(store.exchangeEvents("u", "b", 20_001).isEmpty())
        assertTrue(store.exchangeBatches("u", "b", 20_001).isEmpty())
        assertNull(store.request("u", "b", brokenInput.requestId))
        assertEquals(fence, accounts.findByUserIdAndAccountId("u", "b")!!.recruitmentFence)
        verify(exactly = 0) { publisher.publish("u", "b", "recruitment_changed", any(), any()) }
        every { failing.insertRequest(any()) } answers { callOriginal() }
        assertEquals(1L, broken.commit("u", brokenInput.copy(requestId = "retry_failure")).archiveRevision)
    }

    @Test fun `two valid previews at the same target revision commit exactly one complete winner`() {
        initialize()
        historical()
        val node = backup()
        val barrier = CountDownLatch(2)
        val competing = object : SubAccountRepository by accounts {
            override fun fenceRecruitmentWrite(userId: String, accountId: String, expectedGame: String): Boolean {
                barrier.countDown()
                check(barrier.await(10, TimeUnit.SECONDS))
                return accounts.fenceRecruitmentWrite(userId, accountId, expectedGame)
            }
        }
        val racing = RecruitmentExchangeService(store, accountService, competing, core, publisher, mapper, tx)
        val inputs = (1..2).map {
            val preview = racing.preview("u", RecruitmentImportPreviewRequest("b", node))
            RecruitmentImportCommitRequest(
                "b",
                node,
                previewToken = preview.previewToken,
                documentHash = preview.documentHash,
                expectedRevision = 0,
                requestId = "race$it",
            )
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = inputs.map {
                executor.submit(Callable { runCatching { racing.commit("u", it) } })
            }.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull() is RecruitmentApiException })
            assertEquals(1L, store.archive("u", "b")!!.archiveRevision)
            assertEquals(1, store.exchangeEvents("u", "b", 20_001).size)
            assertEquals(1, inputs.count { store.request("u", "b", it.requestId) != null })
            assertEquals(17L, core.archive("u", "b").summary.knownTotalPulls)
        } finally {
            executor.shutdownNow()
        }
    }
}
