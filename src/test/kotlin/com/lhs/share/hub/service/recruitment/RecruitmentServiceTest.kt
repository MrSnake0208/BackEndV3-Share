package com.lhs.share.hub.service.recruitment

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.hub.controller.recruitment.request.RecruitmentCommandRequest
import com.lhs.share.hub.repository.OperatorCatalogRepository
import com.lhs.share.hub.repository.RecruitmentCatalogRepository
import com.lhs.share.hub.repository.RecruitmentRepository
import com.lhs.share.hub.repository.RecruitmentTotals
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.OperatorCatalogEntity
import com.lhs.share.hub.repository.entity.RecruitmentArchive
import com.lhs.share.hub.repository.entity.RecruitmentBatch
import com.lhs.share.hub.repository.entity.RecruitmentCatalogPool
import com.lhs.share.hub.repository.entity.RecruitmentEvent
import com.lhs.share.hub.repository.entity.RecruitmentPool
import com.lhs.share.hub.repository.entity.RecruitmentPoolSnapshot
import com.lhs.share.hub.repository.entity.RecruitmentRequestRecord
import com.lhs.share.hub.repository.entity.RecruitmentTemporaryAgent
import com.lhs.share.hub.repository.entity.RecruitmentUpAgent
import com.lhs.share.hub.repository.entity.SubAccount
import com.lhs.share.hub.service.account.AccountEventService
import com.lhs.share.hub.service.account.SubAccountService
import com.lhs.share.hub.service.inventory.InventoryApiException
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

class RecruitmentServiceTest {
    private val mapper = jacksonObjectMapper().registerModule(
        JavaTimeModule(),
    ).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
    private val store = mockk<RecruitmentRepository>()
    private val operators = mockk<OperatorCatalogRepository>()
    private val catalogStore = mockk<RecruitmentCatalogRepository>()
    private val managed = RecruitmentCatalogPool(
        "catalog_p",
        "如鸢",
        "管理员池",
        upAgents = listOf(
            RecruitmentUpAgent("catalog_p:up:A", "A"),
            RecruitmentUpAgent("catalog_p:up:B", "B"),
            RecruitmentUpAgent("catalog_p:up:C", "C"),
        ),
    )
    private val catalog = RecruitmentCatalog(mapper, catalogStore, operators)
    private val mutation = RecruitmentMutation(store, catalog, mapper)
    private val events = mutableMapOf<String, RecruitmentEvent>()
    private val batches = mutableMapOf<String, RecruitmentBatch>()
    private val now = Instant.parse("2026-10-01T00:00:00Z")
    private var state =
        RecruitmentArchive(
            "u:a",
            "u",
            "a",
            "如鸢",
            pools = listOf(RecruitmentPool("p", RecruitmentPoolSnapshot("管理员池", "如鸢", catalogPoolId = "catalog_p"), progress = 0)),
        )

    init {
        every { catalogStore.find(any()) } answers { managed.takeIf { it.poolId == firstArg<String>() } }
        every { catalogStore.all() } returns listOf(managed)
        every { operators.findByOperatorId(any()) } returns null
        every { operators.count() } returns 0L
        every { operators.findAllByOrderByOperatorIdAsc() } returns emptyList()
        every { store.event("u", "a", any()) } answers { events[thirdArg()] }
        every { store.insertEvent(any()) } answers {
            firstArg<RecruitmentEvent>().let { events[it.eventId] = it }
            Unit
        }
        every { store.saveEvent(any()) } answers {
            firstArg<RecruitmentEvent>().let { events[it.eventId] = it }
            Unit
        }
        every { store.batch("u", "a", any()) } answers { batches[thirdArg()] }
        every { store.insertBatch(any()) } answers {
            firstArg<RecruitmentBatch>().let { batches[it.batchId] = it }
            Unit
        }
        every { store.saveBatch(any()) } answers {
            firstArg<RecruitmentBatch>().let { batches[it.batchId] = it }
            Unit
        }
        every { store.batchEvents("u", "a", any()) } answers { events.values.filter { it.batchId == thirdArg<String>() } }
        every { store.poolEvents("u", "a", "p", any()) } answers { events.values.filter { it.deletedAt == null }.sortedBy { it.sortOrder } }
    }

    private fun command(operation: String, data: String) = mutation.apply(state, operation, mapper.readTree(data), now).also {
        state =
            it.archive
    }
    private fun count() = state.baseline + state.pools.sumOf { it.progress ?: 0 } +
        events.values.filter { it.deletedAt == null && it.batchId == null }.sumOf { it.pullSpan ?: 0 } +
        batches.values.filter { it.deletedAt == null }.sumOf { it.totalPullCount }
    private fun historical() = command(
        "event_create",
        """{"pool_id":"p","mode":"historical","entries":[{"event_id":"A","agent_id":"catalog_p:up:A","pull_span":17},{"event_id":"B","agent_id":"catalog_p:up:B","pull_span":31},{"event_id":"C","agent_id":"catalog_p:up:C","pull_span":17}]}""",
    )

    @Test fun `pool dialog updates existing spans adds repeated agents removes one result and replaces progress`() {
        historical()
        command("progress_set", """{"pool_id":"p","progress":21}""")
        command(
            "pool_records_save",
            """{"pool_id":"p","remaining_pulls":19,"entries":[{"event_id":"A","agent_id":"catalog_p:up:A","pull_span":20},{"event_id":"new","agent_id":"catalog_p:up:A","pull_span":12}],"deleted_event_ids":["B"]}""",
        )
        assertEquals(20L, events.getValue("A").pullSpan)
        assertEquals(12L, events.getValue("new").pullSpan)
        assertEquals("catalog_p:up:A", events.getValue("new").agentSnapshot.agentId)
        assertTrue(events.getValue("B").deletedAt != null)
        assertEquals(17L, events.getValue("C").pullSpan)
        assertEquals(70L, count())
        assertEquals(21L, state.pools.single().progress)
        val data = """{"pool_id":"p","remaining_pulls":19,"entries":[{"event_id":"new","agent_id":"catalog_p:up:A","pull_span":12}]}"""
        repeat(2) { command("pool_records_save", data) }
        assertEquals(4, events.size)
        assertEquals(70L, count())
        assertEquals(5L, state.nextEventOrder)
    }

    @Test fun `dialog batches retain their total and reject edited spans beyond that total before writing`() {
        command(
            "batch_create",
            """{"pool_id":"p","mode":"historical","batch_id":"b","total_pull_count":40,"entries":[{"event_id":"A","agent_id":"catalog_p:up:A","pull_span":17},{"event_id":"B","agent_id":"catalog_p:up:B","pull_span":20}]}""",
        )
        val before = events.toMap()
        assertThrows(RecruitmentApiException::class.java) {
            command(
                "pool_records_save",
                """{"pool_id":"p","remaining_pulls":40,"entries":[{"event_id":"A","agent_id":"catalog_p:up:A","pull_span":25}]}""",
            )
        }
        assertEquals(before, events)
        command(
            "pool_records_save",
            """{"pool_id":"p","remaining_pulls":40,"entries":[{"event_id":"A","agent_id":"catalog_p:up:A","pull_span":25}],"deleted_event_ids":["B"]}""",
        )
        assertEquals(40L, count())
        assertEquals("b", events.getValue("A").batchId)
    }

    @Test fun `stopped pool permits existing count edits removals and progress but rejects additions`() {
        historical()
        every { catalogStore.find("catalog_p") } returns managed.copy(enabled = false)
        command(
            "pool_records_save",
            """{"pool_id":"p","remaining_pulls":19,"entries":[{"event_id":"A","agent_id":"catalog_p:up:A","pull_span":20}],"deleted_event_ids":["B"]}""",
        )
        assertEquals(58L, count())
        assertThrows(RecruitmentApiException::class.java) {
            command(
                "pool_records_save",
                """{"pool_id":"p","remaining_pulls":19,"entries":[{"event_id":"new","agent_id":"catalog_p:up:A","pull_span":17}]}""",
            )
        }
        assertTrue("new" !in events)
    }

    @Test fun `dialog rejects bad integers duplicated IDs cross pool or deleted updates without writing`() {
        historical()
        val before = state
        val records = events.toMap()
        listOf(
            """{"pool_id":"p","remaining_pulls":0,"entries":[]}""",
            """{"pool_id":"p","remaining_pulls":41,"entries":[]}""",
            """{"pool_id":"p","remaining_pulls":1.5,"entries":[]}""",
            """{"pool_id":"p","remaining_pulls":"19","entries":[]}""",
            """{"pool_id":"p","remaining_pulls":null,"entries":[]}""",
            """{"pool_id":"p","entries":[]}""",
            """{"pool_id":"p","remaining_pulls":19,"entries":[{"event_id":"new","agent_id":"foreign:up:A","pull_span":17}]}""",
            """{"pool_id":"p","remaining_pulls":19,"entries":[{"event_id":"new","agent_id":"catalog_p:up:A","pull_span":0}]}""",
            """{"pool_id":"p","remaining_pulls":19,"entries":[{"event_id":"A","agent_id":"catalog_p:up:A","pull_span":17}],"deleted_event_ids":["A"]}""",
            """{"pool_id":"p","remaining_pulls":19,"entries":[],"deleted_event_ids":["B","B"]}""",
        ).forEach {
            assertThrows(RecruitmentApiException::class.java) { command("pool_records_save", it) }
            assertEquals(before, state)
            assertEquals(records, events)
        }
        events["A"] = events.getValue("A").copy(poolId = "foreign-pool")
        assertThrows(RecruitmentApiException::class.java) {
            command(
                "pool_records_save",
                """{"pool_id":"p","remaining_pulls":19,"entries":[{"event_id":"A","agent_id":"catalog_p:up:A","pull_span":17}]}""",
            )
        }
        assertThrows(RecruitmentApiException::class.java) {
            command("pool_records_save", """{"pool_id":"p","remaining_pulls":19,"entries":[],"deleted_event_ids":["A"]}""")
        }
        events["A"] = records.getValue("A").copy(deletedAt = now, deletedRevision = state.archiveRevision)
        assertThrows(RecruitmentApiException::class.java) {
            command(
                "pool_records_save",
                """{"pool_id":"p","remaining_pulls":19,"entries":[{"event_id":"A","agent_id":"catalog_p:up:A","pull_span":17}]}""",
            )
        }
    }

    @Test fun `new pool progress must be explicitly unknown zero or a validated known value`() {
        assertNull(RecruitmentPool("default", RecruitmentPoolSnapshot("默认", "如鸢")).progress)
        listOf(
            """{"pool_id":"bad","catalog_pool_id":"catalog_p"}""",
            """{"pool_id":"bad","catalog_pool_id":"catalog_p","progress":-1}""",
            """{"pool_id":"bad","catalog_pool_id":"catalog_p","progress":1.5}""",
            """{"pool_id":"bad","catalog_pool_id":"catalog_p","progress":"0"}""",
            """{"pool_id":"bad","catalog_pool_id":"catalog_p","progress":1000000001}""",
        ).forEach { input ->
            assertEquals(422, assertThrows(RecruitmentApiException::class.java) { command("pool_create", input) }.status.value())
        }
        command("pool_create", """{"pool_id":"unknown","catalog_pool_id":"catalog_p","progress":null}""")
        assertNull(state.pools.single { it.poolId == "unknown" }.progress)
        assertThrows(RecruitmentApiException::class.java) {
            command(
                "event_create",
                """{"pool_id":"unknown","mode":"current","tail_progress":0,"entries":[{"agent_id":"catalog_p:up:A","pull_span":17}]}""",
            )
        }
        command("pool_create", """{"pool_id":"zero","catalog_pool_id":"catalog_p","progress":0}""")
        assertEquals(0L, state.pools.single { it.poolId == "zero" }.progress)
        command("pool_create", """{"pool_id":"known","catalog_pool_id":"catalog_p","progress":13}""")
        assertEquals(13L, state.pools.single { it.poolId == "known" }.progress)
    }

    @Test fun `automatic pools can be calibrated selected and recorded without a pool create command`() {
        state = state.copy(pools = emptyList(), currentPoolId = null)
        val id = "catalog:catalog_p"
        assertThrows(RecruitmentApiException::class.java) {
            command(
                "event_create",
                """{"pool_id":"$id","mode":"current","tail_progress":0,"entries":[{"agent_id":"catalog_p:up:A","pull_span":17}]}""",
            )
        }
        assertTrue(state.pools.isEmpty())
        assertTrue(events.isEmpty())
        command("set_current_pool", """{"pool_id":"$id"}""")
        assertNull(state.pools.single().progress)
        command("progress_set", """{"pool_id":"$id","progress":0}""")
        command("set_current_pool", """{"pool_id":"$id"}""")
        command(
            "event_create",
            """{"pool_id":"$id","mode":"current","tail_progress":4,"entries":[{"agent_id":"catalog_p:up:A","pull_span":17}]}""",
        )
        assertEquals(id, state.currentPoolId)
        assertEquals(id, state.pools.single().poolId)
        assertEquals(4L, state.pools.single().progress)
        assertEquals(21L, count())
    }

    @Test fun `first historical batch saves only its automatic target pool and leaves progress unknown`() {
        state = state.copy(pools = emptyList(), currentPoolId = null)
        command(
            "batch_create",
            """{"pool_id":"catalog:catalog_p","mode":"historical","batch_id":"batch-new","total_pull_count":120,"entries":[{"agent_id":"catalog_p:up:A","pull_span":null}]}""",
        )
        assertEquals("catalog:catalog_p", state.pools.single().poolId)
        assertNull(state.pools.single().progress)
        assertEquals(120L, count())
    }

    @Test fun `disabled and foreign game automatic pools cannot accept new results`() {
        state = state.copy(pools = emptyList(), currentPoolId = null)
        val disabled = managed.copy(enabled = false)
        every { catalogStore.all() } returns listOf(disabled)
        every { catalogStore.find("catalog_p") } returns disabled
        assertEquals(
            422,
            assertThrows(RecruitmentApiException::class.java) {
                command(
                    "event_create",
                    """{"pool_id":"catalog:catalog_p","mode":"historical","entries":[{"agent_id":"catalog_p:up:A","pull_span":17}]}""",
                )
            }.status.value(),
        )
        assertTrue(catalog.projectPools(emptyList(), "代号鸢").isEmpty())
        val foreignPool = managed.copy(game = "代号鸢")
        every { catalogStore.all() } returns listOf(foreignPool)
        every { catalogStore.find("catalog_p") } returns foreignPool
        val foreign = catalog.projectPools(emptyList(), "代号鸢").single().poolId
        assertEquals(
            404,
            assertThrows(RecruitmentApiException::class.java) {
                command("progress_set", """{"pool_id":"$foreign","progress":0}""")
            }.status.value(),
        )
        assertTrue(state.pools.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test fun `only this game absolute catalog agents are accepted and users cannot replace administrator mappings`() {
        fun operator(id: String, rarity: Int, game: String) = OperatorCatalogEntity(
            operatorId = id, name = id, rarity = rarity, games = listOf(game), prof = emptyList(), subProf = emptyList(),
            discs = emptyList(), starStones = emptyList(), catalogVersion = "test-v1",
        )
        every { operators.findByOperatorId("wrong-rarity") } returns operator("wrong-rarity", 3, "如鸢")
        every { operators.findByOperatorId("wrong-game") } returns operator("wrong-game", 5, "代号鸢")
        every { operators.findByOperatorId("valid") } returns operator("valid", 5, "如鸢")
        every { operators.findByOperatorId("missing") } returns null
        listOf("wrong-rarity", "wrong-game", "missing").forEach { id ->
            assertEquals(
                422,
                assertThrows(RecruitmentApiException::class.java) {
                    command("event_create", """{"pool_id":"p","mode":"historical","entries":[{"agent_id":"$id","pull_span":17}]}""")
                }.status.value(),
            )
        }
        assertTrue(events.isEmpty())
        historical()
        val original = events.getValue("A").agentSnapshot
        assertEquals(
            422,
            assertThrows(RecruitmentApiException::class.java) {
                command("agent_map", """{"agent_id":"catalog_p:up:A","catalog_agent_id":"valid"}""")
            }.status.value(),
        )
        assertEquals(original, events.getValue("A").agentSnapshot)
        command(
            "event_create",
            """{"pool_id":"p","mode":"historical","entries":[{"event_id":"valid-event","agent_id":"valid","pull_span":17}]}""",
        )
        assertEquals(5, events.getValue("valid-event").agentSnapshot.rarity)
        assertEquals("test-v1", events.getValue("valid-event").agentSnapshot.catalogRevision)
    }

    @Test fun `new mapped UP references use the stable slot without changing explicit user UP facts`() {
        every { catalogStore.find("catalog_p") } returns managed.copy(
            upAgents = managed.upAgents.map {
                if (it.id ==
                    "catalog_p:up:A"
                ) {
                    it.copy(operatorId = "char_001_yangxiu", name = "杨修")
                } else {
                    it
                }
            },
        )
        command(
            "event_create",
            """{"pool_id":"p","mode":"historical","entries":[{"event_id":"mapped","agent_id":"char_001_yangxiu","pull_span":17,"up_status":"non_up"}]}""",
        )
        assertEquals("catalog_p:up:A", events.getValue("mapped").agentSnapshot.agentId)
        assertEquals("杨修", events.getValue("mapped").agentSnapshot.name)
        assertEquals("non_up", events.getValue("mapped").upStatus)
        assertEquals(17L, count())
        command("event_update", """{"event_id":"mapped","entry":{"agent_id":"catalog_p:up:A","pull_span":17,"up_status":"unknown"}}""")
        assertEquals("catalog_p:up:A", events.getValue("mapped").agentSnapshot.agentId)
        assertEquals("unknown", events.getValue("mapped").upStatus)
        command(
            "event_create",
            """{"pool_id":"p","mode":"historical","entries":[{"event_id":"changed","agent_id":"char_002_jiaxu","pull_span":18}]}""",
        )
        command(
            "event_update",
            """{"event_id":"changed","entry":{"agent_id":"char_001_yangxiu","pull_span":18,"up_status":"non_up"}}""",
        )
        assertEquals("catalog_p:up:A", events.getValue("changed").agentSnapshot.agentId)
        assertEquals("non_up", events.getValue("changed").upStatus)
    }

    @Test fun `users cannot create custom pools agents or mappings and legacy facts remain maintainable`() {
        listOf(
            "pool_create" to """{"name":"自定义","progress":0}""",
            "pool_create" to """{"catalog_pool_id":"missing","progress":0}""",
            "temporary_agent_create" to """{"agent_id":"tmp_new","name":"私人占位"}""",
            "pool_map" to """{"pool_id":"p","catalog_pool_id":"catalog_p"}""",
            "agent_map" to """{"agent_id":"tmp_old","catalog_agent_id":"char_001_yangxiu"}""",
        ).forEach { (operation, input) -> assertThrows(RecruitmentApiException::class.java) { command(operation, input) } }
        assertEquals(0L, state.archiveRevision)
        state = state.copy(temporaryAgents = listOf(RecruitmentTemporaryAgent("tmp_old", "旧占位")))
        historical()
        val managedEvent = events.getValue("A")
        val managedRevision = state.archiveRevision
        assertThrows(RecruitmentApiException::class.java) {
            command("event_update", """{"event_id":"A","entry":{"agent_id":"tmp_old","pull_span":17}}""")
        }
        assertEquals(managedEvent, events.getValue("A"))
        assertEquals(managedRevision, state.archiveRevision)
        events.clear()
        state = state.copy(
            pools = listOf(RecruitmentPool("p", RecruitmentPoolSnapshot("旧临时池", "如鸢"), 3)),
            temporaryAgents = listOf(RecruitmentTemporaryAgent("tmp_old", "旧占位")),
        )
        events["old"] = RecruitmentEvent(
            "u:a:old", "u", "a", "old", "p", state.pools.single().snapshot,
            com.lhs.share.hub.repository.entity.RecruitmentAgentSnapshot("tmp_old", "旧占位", temporary = true), 17, 1, createdAt = now,
        )
        command("event_update", """{"event_id":"old","entry":{"agent_id":"tmp_old","pull_span":18,"note":"维护旧记录"}}""")
        assertEquals(21L, count())
        command("event_delete", """{"event_id":"old"}""")
        assertEquals(3L, count())
        command("event_restore", """{"event_id":"old"}""")
        assertEquals(21L, count())
        assertThrows(RecruitmentApiException::class.java) {
            command("event_create", """{"pool_id":"p","mode":"historical","entries":[{"agent_id":"tmp_old","pull_span":17}]}""")
        }
    }

    @Test fun `deleting B31 changes 65 to 34 and undo restores same ID without touching C or progress`() {
        historical()
        assertEquals(65L, count())
        command("progress_set", """{"pool_id":"p","progress":8}""")
        command("event_delete", """{"event_id":"B"}""")
        assertEquals(42L, count())
        assertEquals(17L, events.getValue("C").pullSpan)
        assertEquals(8L, state.pools.single().progress)
        val deletedId = events.getValue("B").id
        command("event_restore", """{"event_id":"B"}""")
        assertEquals(deletedId, events.getValue("B").id)
        assertEquals(73L, count())
        command("event_delete", """{"event_id":"B"}""")
        command("baseline_set", """{"baseline":1}""")
        assertEquals(
            409,
            assertThrows(RecruitmentApiException::class.java) {
                command("event_restore", """{"event_id":"B"}""")
            }.status.value(),
        )
    }

    @Test fun `current exact save consumes old progress and historical extraction preserves cumulative total`() {
        command("baseline_set", """{"baseline":100}""")
        command("progress_set", """{"pool_id":"p","progress":21}""")
        command(
            "event_create",
            """{"pool_id":"p","mode":"current","tail_progress":0,"entries":[{"agent_id":"catalog_p:up:A","pull_span":27}]}""",
        )
        assertEquals(127L, count())
        assertEquals(0L, state.pools.single().progress)
        command(
            "event_create",
            """{"pool_id":"p","mode":"historical","extract_from_baseline":true,"entries":[{"agent_id":"catalog_p:up:B","pull_span":31}]}""",
        )
        assertEquals(69L, state.baseline)
        assertEquals(127L, count())
        command(
            "event_create",
            """{"pool_id":"p","mode":"current","tail_progress":null,"entries":[{"agent_id":"catalog_p:up:C","pull_span":17}]}""",
        )
        assertNull(state.pools.single().progress)
        assertThrows(RecruitmentApiException::class.java) {
            command(
                "event_create",
                """{"pool_id":"p","mode":"current","tail_progress":0,"entries":[{"agent_id":"catalog_p:up:C","pull_span":1}]}""",
            )
        }
    }

    @Test fun `unknown-position batch counts once and deleted node stays deleted across batch undo`() {
        command(
            "batch_create",
            """{"batch_id":"b","pool_id":"p","mode":"historical","total_pull_count":10,"entries":[{"event_id":"A","agent_id":"catalog_p:up:A","pull_span":null},{"event_id":"B","agent_id":"catalog_p:up:B","pull_span":null}]}""",
        )
        assertEquals(10L, count())
        command("event_update", """{"event_id":"A","entry":{"agent_id":"catalog_p:up:A","pull_span":7}}""")
        assertEquals(10L, count())
        command("event_delete", """{"event_id":"A"}""")
        command("event_update", """{"event_id":"B","entry":{"agent_id":"catalog_p:up:B","pull_span":10}}""")
        assertEquals(10L, count())
        command("batch_delete", """{"batch_id":"b","confirm_total_pull_count":10}""")
        assertEquals(0L, count())
        command("batch_restore", """{"batch_id":"b"}""")
        assertEquals(10L, count())
        assertTrue(events.getValue("A").deletedAt != null)
        assertNull(events.getValue("B").deletedAt)
    }

    @Test fun `reordering changes only ordering and unknown historical span remains unknown`() {
        historical()
        command("event_reorder", """{"pool_id":"p","event_ids":["C","A","B"]}""")
        assertEquals(65L, count())
        assertEquals(listOf("C", "A", "B"), events.values.sortedBy { it.sortOrder }.map { it.eventId })
        command("event_create", """{"pool_id":"p","mode":"historical","entries":[{"agent_id":"catalog_p:up:A","pull_span":null}]}""")
        assertEquals(65L, count())
        assertNull(events.values.last().pullSpan)
    }

    @Test fun `validation rejects null primitive unknown fields fractions negative counts and duplicate stable IDs`() {
        listOf("""{"baseline":null}""", """{"baseline":1,"extra":true}""", """{"baseline":1.2}""", """{"baseline":-1}""").forEach {
            assertEquals(422, assertThrows(RecruitmentApiException::class.java) { command("baseline_set", it) }.status.value())
        }
        historical()
        assertEquals(
            409,
            assertThrows(RecruitmentApiException::class.java) {
                command(
                    "event_create",
                    """{"pool_id":"p","mode":"historical","entries":[{"event_id":"A","agent_id":"catalog_p:up:A","pull_span":17}]}""",
                )
            }.status.value(),
        )
        assertThrows(RecruitmentApiException::class.java) { command("baseline_set", """{"baseline":1000000001}""") }
    }

    @Test fun `empty repeated reads return revision zero and perform only owner scoped reads`() {
        val accountService = mockk<SubAccountService>()
        val accounts = mockk<SubAccountRepository>()
        val publisher = mockk<AccountEventService>()
        val tx = TransactionTemplate(object : PlatformTransactionManager {
            override fun getTransaction(definition: TransactionDefinition?) = SimpleTransactionStatus()
            override fun commit(status: TransactionStatus) = Unit
            override fun rollback(status: TransactionStatus) = Unit
        })
        val readStore = mockk<RecruitmentRepository>()
        every { accountService.requireAccount("u", "a") } returns SubAccount(userId = "u", accountId = "a", name = "test", game = "如鸢")
        every { readStore.archive("u", "a") } returns null
        every { readStore.totals("u", "a") } returns RecruitmentTotals(0, 0, 0, 0, 0)
        every { readStore.poolTotals("u", "a") } returns emptyMap()
        every { readStore.poolAgentCounts("u", "a") } returns emptyMap()
        val service = RecruitmentService(readStore, accountService, accounts, mutation, publisher, mapper, tx)
        repeat(3) {
            val visible = service.archive("u", "a")
            assertEquals(0L, visible.archiveRevision)
            assertEquals("catalog_p", visible.pools.single().snapshot.catalogPoolId)
            assertNull(visible.pools.single().progress)
            assertEquals(0, visible.summary.unknownProgressCount)
            assertEquals(managed.upAgents.associate { it.id to 0L }, visible.poolSummaries.values.single().upAgentCounts)
            assertNull(visible.summary.upAgentCounts)
            assertEquals(0L, service.archive("u", "a").summary.knownTotalPulls)
        }
        every { readStore.archive("u", "a") } returns state
        every { catalogStore.all() } returns listOf(
            managed.copy(
                upAgents = listOf(
                    managed.upAgents[0].copy(operatorId = "formal"),
                    managed.upAgents[1],
                    managed.upAgents[2].copy(active = false),
                ),
            ),
        )
        every { readStore.poolAgentCounts("u", "a") } returns mapOf(
            "p" to mapOf("catalog_p:up:A" to 2L, "formal" to 1L, "catalog_p:up:C" to 8L, "off" to 9L),
            "other-pool" to mapOf("catalog_p:up:A" to 20L),
        )
        assertEquals(
            mapOf("catalog_p:up:A" to 3L, "catalog_p:up:B" to 0L),
            service.archive("u", "a").poolSummaries.getValue("p").upAgentCounts,
        )
        // A missing directory keeps the stored snapshot authoritative, including placeholders.
        every { catalogStore.all() } returns emptyList()
        every { readStore.archive("u", "a") } returns state.copy(
            pools = listOf(state.pools.single().copy(snapshot = state.pools.single().snapshot.copy(upAgents = managed.upAgents))),
        )
        assertEquals(2L, service.archive("u", "a").poolSummaries.getValue("p").upAgentCounts?.get("catalog_p:up:A"))
        verify(exactly = 8) { accountService.requireAccount("u", "a") }
        verify(exactly = 8) { readStore.archive("u", "a") }
        verify(exactly = 8) { readStore.totals("u", "a") }
        verify(exactly = 8) { readStore.poolTotals("u", "a") }
        verify(exactly = 8) { readStore.poolAgentCounts("u", "a") }
        confirmVerified(readStore, accountService)
        verify {
            publisher wasNot io.mockk.Called
            accounts wasNot io.mockk.Called
        }
    }

    @Test fun `successful old revision retry returns cached result before checking revision and cannot serve foreign owner`() {
        val accountService = mockk<SubAccountService>()
        val accounts = mockk<SubAccountRepository>()
        val publisher = mockk<AccountEventService>()
        val tx = mockk<TransactionTemplate>()
        val service = RecruitmentService(store, accountService, accounts, mutation, publisher, mapper, tx)
        val request = RecruitmentCommandRequest("a", 0, "r", "baseline_set", mapper.readTree("""{"baseline":5}"""))
        every { accountService.requireAccount("u", "a") } returns SubAccount(userId = "u", accountId = "a", name = "test")
        every { store.request("u", "a", "r") } returns
            RecruitmentRequestRecord("u:a:r", "u", "a", "r", service.hash(mapper.valueToTree(request)), 1)
        assertEquals(1L, service.command("u", request).archiveRevision)
        assertEquals(
            409,
            assertThrows(RecruitmentApiException::class.java) {
                service.command("u", request.copy(data = mapper.readTree("""{"baseline":6}""")))
            }.status.value(),
        )
        every { accountService.requireAccount("foreign", "a") } throws
            InventoryApiException(HttpStatus.NOT_FOUND, "account_not_found", "Account not found")
        assertThrows(InventoryApiException::class.java) { service.command("foreign", request) }
        verify(exactly = 0) { store.request("foreign", any(), any()) }
        verify {
            tx wasNot io.mockk.Called
            publisher wasNot io.mockk.Called
            accounts wasNot io.mockk.Called
        }
    }
}
