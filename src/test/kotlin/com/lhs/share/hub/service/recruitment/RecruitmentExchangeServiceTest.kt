package com.lhs.share.hub.service.recruitment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.hub.controller.recruitment.request.RecruitmentExchangeDocument
import com.lhs.share.hub.controller.recruitment.request.RecruitmentImportCommitRequest
import com.lhs.share.hub.controller.recruitment.request.RecruitmentImportOptions
import com.lhs.share.hub.controller.recruitment.request.RecruitmentImportPreviewRequest
import com.lhs.share.hub.controller.recruitment.response.RecruitmentCommandResponse
import com.lhs.share.hub.repository.RecruitmentRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.RecruitmentAgentSnapshot
import com.lhs.share.hub.repository.entity.RecruitmentArchive
import com.lhs.share.hub.repository.entity.RecruitmentBatch
import com.lhs.share.hub.repository.entity.RecruitmentEvent
import com.lhs.share.hub.repository.entity.RecruitmentPool
import com.lhs.share.hub.repository.entity.RecruitmentPoolSnapshot
import com.lhs.share.hub.repository.entity.RecruitmentTemporaryAgent
import com.lhs.share.hub.repository.entity.SubAccount
import com.lhs.share.hub.service.account.AccountEventService
import com.lhs.share.hub.service.account.SubAccountService
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class RecruitmentExchangeServiceTest {
    private val mapper = jacksonObjectMapper().registerModule(
        JavaTimeModule(),
    ).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
    private val now = Instant.parse("2026-10-01T00:00:00Z")
    private val snapshot = RecruitmentPoolSnapshot("临时池", "如鸢", poolType = "限时")
    private val state = RecruitmentArchive(
        "u:a", "u", "a", "如鸢", archiveRevision = 10, baseline = 100, currentPoolId = "p",
        pools = listOf(RecruitmentPool("p", snapshot, 7)), temporaryAgents = listOf(RecruitmentTemporaryAgent("tmp_A", "A")),
        nextEventOrder = 11,
    )
    private fun record(id: String = "A", span: Long? = 17, order: Long = 1) = RecruitmentEvent(
        "u:a:$id", "u", "a", id, "p", snapshot, RecruitmentAgentSnapshot("tmp_A", "A", true), span, order,
        acquiredDate = LocalDate.parse("2026-09-30"), createdAt = now, updatedAt = now,
    )
    private fun document(vararg records: RecruitmentEvent) = exchangeDocument(state, records.toList(), emptyList(), now)
    private fun plan(
        current: RecruitmentArchive = state,
        records: List<RecruitmentEvent> = emptyList(),
        batches: List<RecruitmentBatch> = emptyList(),
        backup: RecruitmentExchangeDocument = document(record()),
        options: RecruitmentImportOptions = RecruitmentImportOptions(),
    ) = recruitmentExchangePlan(current, records, batches, backup, options)

    @Test fun `empty target restores full logic snapshots unknown progress batch and tombstones`() {
        val batch = RecruitmentBatch("u:a:B", "u", "a", "B", "p", 80, now)
        val dead = record("D", null, 2).copy(deletedAt = now, deletedRevision = 9)
        val source = exchangeDocument(
            state.copy(
                pools = listOf(RecruitmentPool("p", snapshot, null, snapshot.copy(catalogPoolId = "offline_pool", name = "已下线池"))),
                temporaryAgents = listOf(RecruitmentTemporaryAgent("tmp_A", "A", "offline_A", "已下线绝密A")),
            ),
            listOf(record().copy(batchId = "B"), dead),
            listOf(batch),
            now,
        )
        val result = plan(RecruitmentArchive("u:b", "u", "b", "如鸢"), backup = source)
        assertEquals(source.baseline, result.archive.baseline)
        assertEquals(source.pools, result.archive.pools)
        assertEquals(source.temporaryAgents, result.archive.temporaryAgents)
        assertEquals(source.events, result.eventsToAdd)
        assertEquals(source.batches, result.batchesToAdd)
        assertEquals(180L, result.candidateKnownTotal)
        assertTrue(result.canCommit)
    }

    @Test fun `unknown progress and temporary pool are substantive and cannot silently restore backup state`() {
        listOf(
            state.copy(baseline = 0, temporaryAgents = emptyList(), pools = listOf(RecruitmentPool("p", snapshot, 0))),
            state.copy(
                baseline = 0,
                temporaryAgents = emptyList(),
                pools = listOf(RecruitmentPool("p", snapshot.copy(catalogPoolId = "catalog"), null)),
            ),
        ).forEach { current ->
            val result = plan(current)
            assertEquals(0L, result.archive.baseline)
            assertEquals(current.pools.first().progress, result.archive.pools.first().progress)
            assertTrue(result.eventsToAdd.isEmpty())
        }
    }

    @Test fun `default skips count overlaps and explicit confirmation requires a new state preview`() {
        val kept = plan()
        assertEquals(107L, kept.currentKnownTotal)
        assertEquals(124L, kept.backupKnownTotal)
        assertEquals(107L, kept.candidateKnownTotal)
        assertTrue(kept.eventsToAdd.isEmpty())
        assertEquals("count_overlap", kept.items.single { it.entityType == "event" }.status)
        val confirmed = plan(options = RecruitmentImportOptions(confirmCountChange = true))
        assertEquals(124L, confirmed.candidateKnownTotal)
        assertEquals(listOf("A"), confirmed.eventsToAdd.map { it.eventId })
        val changedSource = document(record()).copy(baseline = 50)
        val unconfirmedState = plan(backup = changedSource, options = RecruitmentImportOptions("use_backup"))
        assertFalse(unconfirmedState.canCommit)
        val replacement = plan(backup = changedSource, options = RecruitmentImportOptions("use_backup", true))
        assertTrue(replacement.canCommit)
        assertEquals(74L, replacement.candidateKnownTotal)
        assertEquals(50L, replacement.archive.baseline)
    }

    @Test fun `dedupe excludes import metadata target sorting and clock fields but includes every business fact`() {
        val source = record()
        val imported = source.copy(
            sortOrder = 10,
            createdAt = now.plusSeconds(1),
            updatedAt = now.plusSeconds(2),
            importBatchId = "i",
            importedAt = now,
        )
        assertEquals("duplicate", plan(records = listOf(imported)).items.single { it.entityType == "event" }.status)
        listOf(
            source.copy(pullSpan = 18),
            source.copy(acquiredDate = LocalDate.parse("2026-10-01")),
            source.copy(note = "备注"),
            source.copy(source = "legacy"),
            source.copy(upStatus = "up"),
            source.copy(poolSnapshot = snapshot.copy(name = "不同")),
            source.copy(agentSnapshot = source.agentSnapshot.copy(name = "不同")),
            source.copy(deletedAt = now, deletedRevision = 10),
        ).forEach { changed ->
            val result =
                plan(records = listOf(source), backup = document(changed), options = RecruitmentImportOptions(confirmCountChange = true))
            assertEquals("conflict", result.items.single { it.entityType == "event" }.status)
            assertTrue(result.eventsToAdd.isEmpty())
        }
    }

    @Test fun `current tombstones never resurrect and backup tombstones never delete live records`() {
        val dead = record().copy(deletedAt = now, deletedRevision = 10)
        listOf(listOf(dead) to document(record()), listOf(record()) to document(dead)).forEach { (current, source) ->
            val result = plan(records = current, backup = source, options = RecruitmentImportOptions(confirmCountChange = true))
            assertTrue(result.eventsToAdd.isEmpty())
            assertEquals("conflict", result.items.single { it.entityType == "event" }.status)
        }
        val anotherTombstone = dead.copy(deletedRevision = 0, importBatchId = "import_x", importedAt = now.plusSeconds(3))
        assertEquals(
            "duplicate",
            plan(records = listOf(anotherTombstone), backup = document(dead)).items.single {
                it.entityType == "event"
            }.status,
        )
    }

    @Test fun `duplicate batch permits only nodes that fit existing total and never counts its total twice`() {
        val batch = RecruitmentBatch("u:a:B", "u", "a", "B", "p", 30, now)
        val old = record("old", 20).copy(batchId = "B")
        val added = record("new", 17, 2).copy(batchId = "B")
        val backup = exchangeDocument(state, listOf(added), listOf(batch), now)
        val result =
            plan(
                records = listOf(old),
                batches = listOf(batch),
                backup = backup,
                options = RecruitmentImportOptions(confirmCountChange = true),
            )
        assertTrue(result.batchesToAdd.isEmpty())
        assertTrue(result.eventsToAdd.isEmpty())
        assertEquals(137L, result.candidateKnownTotal)
        assertEquals("count_overlap", result.items.single { it.entityType == "event" }.status)
    }

    @Test fun `strict document validation rejects IDs links ordering game tombstones counts and oversize whole document`() {
        val validation = RecruitmentExchangeValidation(mapper)
        val good = document(record())
        validation.read(mapper.valueToTree(good))
        val invalid = listOf(
            good.copy(schema = "unsupported"), good.copy(game = "wrong"), good.copy(baseline = -1),
            good.copy(currentPoolId = "missing"), good.copy(events = listOf(good.events[0], good.events[0])),
            good.copy(events = listOf(good.events[0].copy(poolId = "missing"))),
            good.copy(events = listOf(good.events[0].copy(batchId = "missing"))),
            good.copy(events = listOf(good.events[0].copy(agentSnapshot = good.events[0].agentSnapshot.copy(rarity = 4)))),
            good.copy(events = listOf(good.events[0].copy(deletedAt = now))),
            good.copy(events = listOf(good.events[0].copy(pullSpan = 0))),
            good.copy(events = listOf(good.events[0].copy(sortOrder = 0))),
        )
        invalid.forEach { assertThrows(RecruitmentApiException::class.java) { validation.read(mapper.valueToTree(it)) } }
        listOf<(ObjectNode) -> Unit>(
            { it.put("extra", true) },
            { it.put("baseline", 1.5) },
            { it.put("baseline", "1") },
            { (it.path("pools")[0] as ObjectNode).remove("progress") },
            { (it.path("events")[0].path("agent_snapshot") as ObjectNode).remove("rarity") },
            { it.put("exported_at", 1) },
            { (it.path("events")[0] as ObjectNode).set<JsonNode>("acquired_date", mapper.readTree("[2026,9,30]")) },
        ).forEach { mutate ->
            val node = mapper.valueToTree<ObjectNode>(good)
            mutate(node)
            assertThrows(RecruitmentApiException::class.java) { validation.read(node) }
        }
        val oversized = mapper.createObjectNode().put("data", "a".repeat(RECRUITMENT_EXCHANGE_MAX_BYTES))
        assertThrows(RecruitmentApiException::class.java) { validation.read(oversized) }
        val tooMany = good.copy(events = (1..20_001).map { good.events[0].copy(eventId = "e$it", sortOrder = it.toLong()) })
        assertThrows(RecruitmentApiException::class.java) { validation.validate(tooMany) }
    }

    @Test fun `export and preview read complete tombstones without persistence or SSE writes`() {
        val fixture = fixture()
        val dead = record().copy(deletedAt = now, deletedRevision = 10)
        every { fixture.store.exchangeEvents("u", "a", 20_001) } returns listOf(dead)
        val exported = fixture.service.export("u", "a")
        assertEquals(listOf("A"), exported.events.map { it.eventId })
        val preview = fixture.service.preview("u", RecruitmentImportPreviewRequest("a", mapper.valueToTree(document(record()))))
        assertEquals(1, preview.stats.conflicts)
        verify(exactly = 3) { fixture.accounts.requireAccount("u", "a") }
        verify(exactly = 2) { fixture.store.archive("u", "a") }
        verify(exactly = 2) { fixture.store.exchangeEvents("u", "a", 20_001) }
        verify(exactly = 2) { fixture.store.exchangeBatches("u", "a", 20_001) }
        verify {
            fixture.fence wasNot io.mockk.Called
            fixture.publisher wasNot io.mockk.Called
        }
        confirmVerified(fixture.store)
    }

    @Test fun `preview binding rejects changed document options account revision expiry and restart`() {
        val fixture = fixture()
        val node = mapper.valueToTree<JsonNode>(document(record()))
        val preview = fixture.service.preview("u", RecruitmentImportPreviewRequest("a", node))
        val input =
            RecruitmentImportCommitRequest(
                "a",
                node,
                previewToken = preview.previewToken,
                documentHash = preview.documentHash,
                expectedRevision = 10,
                requestId = "r",
            )
        listOf(
            input.copy(documentHash = "wrong"),
            input.copy(document = mapper.valueToTree(document(record().copy(note = "changed")))),
            input.copy(options = RecruitmentImportOptions(confirmCountChange = true)),
            input.copy(expectedRevision = 9),
            input.copy(accountId = "b"),
        ).forEach { changed ->
            assertEquals(
                "recruitment_preview_mismatch",
                assertThrows(RecruitmentApiException::class.java) {
                    fixture.service.commit("u", changed)
                }.code,
            )
        }
        fixture.service.clock = Clock.fixed(now.plusSeconds(601), ZoneOffset.UTC)
        assertEquals(
            "recruitment_preview_expired",
            assertThrows(RecruitmentApiException::class.java) {
                fixture.service.commit("u", input)
            }.code,
        )
        val restarted =
            RecruitmentExchangeService(
                fixture.store,
                fixture.accounts,
                fixture.fence,
                fixture.recruitment,
                fixture.publisher,
                mapper,
                fixture.tx,
            )
        assertEquals("recruitment_preview_expired", assertThrows(RecruitmentApiException::class.java) { restarted.commit("u", input) }.code)
        verify(exactly = 0) {
            fixture.store.saveArchive(any(), any())
            fixture.fence.fenceRecruitmentWrite(any(), any(), any())
            fixture.publisher.publishChange(any(), any(), any(), any())
        }
    }

    @Test fun `merged document size includes current history and complete imported metadata`() {
        val fixture = fixture()
        val currentRecords = (1..1800).map { record("old$it", order = it.toLong()).copy(note = "a".repeat(1000)) }
        val incomingRecords = (1..1800).map { record("new$it", order = it.toLong()).copy(note = "b".repeat(1000)) }
        every { fixture.store.archive("u", "a") } returns state.copy(nextEventOrder = 1801)
        every { fixture.store.exchangeEvents("u", "a", 20_001) } returns currentRecords
        val source = exchangeDocument(state.copy(nextEventOrder = 1801), incomingRecords, emptyList(), now)
        val node = mapper.valueToTree<JsonNode>(source)
        assertTrue(mapper.writeValueAsBytes(node).size < RECRUITMENT_EXCHANGE_MAX_BYTES)
        assertEquals(
            422,
            assertThrows(RecruitmentApiException::class.java) {
                fixture.service.preview(
                    "u",
                    RecruitmentImportPreviewRequest("a", node, RecruitmentImportOptions(confirmCountChange = true)),
                )
            }.status.value(),
        )
        verify(exactly = 0) {
            fixture.store.saveArchive(any(), any())
            fixture.store.insertEvent(any())
            fixture.publisher.publishChange(any(), any(), any(), any())
        }
    }

    @Test fun `successful durable retry precedes stale or expired preview and returns no duplicate writes`() {
        val fixture = fixture()
        val result = RecruitmentCommandResponse(11, listOf("A"))
        every { fixture.recruitment.retry("u", "a", "done", any()) } returns result
        val input =
            RecruitmentImportCommitRequest(
                "a",
                mapper.createObjectNode(),
                previewToken = "expired",
                documentHash = "hash",
                expectedRevision = 0,
                requestId = "done",
            )
        assertEquals(result, fixture.service.commit("u", input))
        verify(exactly = 1) { fixture.accounts.requireAccount("u", "a") }
        verify(exactly = 1) { fixture.recruitment.retry("u", "a", "done", any()) }
        verify {
            fixture.store wasNot io.mockk.Called
            fixture.fence wasNot io.mockk.Called
            fixture.publisher wasNot io.mockk.Called
        }
    }

    private fun fixture(): Fixture {
        val store = mockk<RecruitmentRepository>()
        val accounts = mockk<SubAccountService>()
        val fence = mockk<SubAccountRepository>()
        val recruitment = mockk<RecruitmentService>()
        val publisher = mockk<AccountEventService>()
        val manager = mockk<PlatformTransactionManager>()
        every { manager.getTransaction(any()) } returns SimpleTransactionStatus()
        every { manager.commit(any()) } returns Unit
        every { manager.rollback(any()) } returns Unit
        val tx = TransactionTemplate(manager)
        every { accounts.requireAccount("u", any()) } answers { SubAccount(userId = "u", accountId = secondArg(), name = "A", game = "如鸢") }
        every { store.archive("u", "a") } returns state
        every { store.exchangeEvents("u", "a", 20_001) } returns emptyList()
        every { store.exchangeBatches("u", "a", 20_001) } returns emptyList()
        every { recruitment.retry("u", any(), any(), any()) } returns null
        every { recruitment.hash(any()) } answers { mapper.writeValueAsString(firstArg<JsonNode>()).hashCode().toString() }
        val service = RecruitmentExchangeService(store, accounts, fence, recruitment, publisher, mapper, tx)
        service.clock = Clock.fixed(now, ZoneOffset.UTC)
        return Fixture(store, accounts, fence, recruitment, publisher, tx, service)
    }
    private data class Fixture(
        val store: RecruitmentRepository,
        val accounts: SubAccountService,
        val fence: SubAccountRepository,
        val recruitment: RecruitmentService,
        val publisher: AccountEventService,
        val tx: TransactionTemplate,
        val service: RecruitmentExchangeService,
    )
}
