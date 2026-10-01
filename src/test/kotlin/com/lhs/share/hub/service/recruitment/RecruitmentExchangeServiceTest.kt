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
import com.lhs.share.hub.repository.entity.RecruitmentUpAgent
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
    private val slot = RecruitmentUpAgent("catalog:up:A", "占位A")
    private val snapshot = RecruitmentPoolSnapshot("公共池", "如鸢", catalogPoolId = "catalog", poolType = "限时", upAgents = listOf(slot))
    private val state = RecruitmentArchive(
        "u:a", "u", "a", "如鸢", archiveRevision = 10, baseline = 100, currentPoolId = "p",
        pools = listOf(RecruitmentPool("p", snapshot, 7)),
        nextEventOrder = 11,
    )
    private fun record(id: String = "A", span: Long? = 17, order: Long = 1) = RecruitmentEvent(
        "u:a:$id", "u", "a", id, "p", snapshot, RecruitmentAgentSnapshot("catalog:up:A", "占位A"), span, order,
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

    @Test fun `empty target restores managed slot snapshots unknown progress batch and tombstones`() {
        val batch = RecruitmentBatch("u:a:B", "u", "a", "B", "p", 80, now)
        val dead = record("D", null, 2).copy(deletedAt = now, deletedRevision = 9)
        val source = exchangeDocument(
            state.copy(
                pools = listOf(RecruitmentPool("p", snapshot, null)),
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
            state.copy(baseline = 0, pools = listOf(RecruitmentPool("p", snapshot.copy(catalogPoolId = null), 0))),
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
            source.copy(poolSnapshot = snapshot.copy(catalogPoolId = "another")),
            source.copy(agentSnapshot = source.agentSnapshot.copy(agentId = "another_slot")),
            source.copy(deletedAt = now, deletedRevision = 10),
        ).forEach { changed ->
            val result =
                plan(records = listOf(source), backup = document(changed), options = RecruitmentImportOptions(confirmCountChange = true))
            assertEquals("conflict", result.items.single { it.entityType == "event" }.status)
            assertTrue(result.eventsToAdd.isEmpty())
        }
    }

    @Test fun `administrator binding and display changes keep managed references duplicate without changing personal facts`() {
        val source = record()
        val mappedSlot = slot.copy(name = "正式绝密A", operatorId = "official_A", active = false)
        val changedSnapshot = snapshot.copy(name = "新池名称", catalogRevision = "new", upAgents = listOf(mappedSlot))
        val current = state.copy(pools = listOf(RecruitmentPool("p", changedSnapshot, 7)))
        val changedDisplay = source.copy(
            poolSnapshot = changedSnapshot,
            agentSnapshot = source.agentSnapshot.copy(name = "正式绝密A", catalogRevision = "new"),
        )
        val result = plan(current = current, records = listOf(changedDisplay))
        assertEquals("duplicate", result.items.single { it.entityType == "pool" }.status)
        assertEquals("duplicate", result.items.single { it.entityType == "event" }.status)
        assertEquals(current.pools, result.archive.pools)
        assertTrue(result.eventsToAdd.isEmpty())
        assertEquals(124L, result.candidateKnownTotal)

        val legacySnapshot = snapshot.copy(catalogPoolId = null, upAgents = emptyList())
        val legacy = source.copy(poolSnapshot = legacySnapshot, agentSnapshot = RecruitmentAgentSnapshot("tmp_A", "A", true))
        val legacyState = state.copy(
            pools = listOf(RecruitmentPool("p", legacySnapshot, 7)),
            temporaryAgents = listOf(RecruitmentTemporaryAgent("tmp_A", "A")),
        )
        val renamed = legacy.copy(agentSnapshot = legacy.agentSnapshot.copy(name = "用户改名"))
        val legacyResult = plan(
            current = legacyState,
            records = listOf(legacy),
            backup = exchangeDocument(legacyState, listOf(renamed), emptyList(), now),
        )
        assertEquals("conflict", legacyResult.items.single { it.entityType == "event" }.status)
    }

    @Test fun `backup cannot manufacture pools private agents cross-pool slots or mismatched catalog references`() {
        val fixture = fixture()
        val source = document(record())
        val custom = snapshot.copy(catalogPoolId = null, upAgents = emptyList())
        val invalid = listOf(
            source.copy(pools = listOf(RecruitmentPool("custom", custom, 0)), currentPoolId = "custom", events = emptyList()),
            source.copy(temporaryAgents = listOf(RecruitmentTemporaryAgent("tmp_forged", "用户自填"))),
            source.copy(pools = listOf(RecruitmentPool("p", snapshot.copy(catalogPoolId = "forged"), 7))),
            source.copy(events = listOf(source.events[0].copy(poolSnapshot = snapshot.copy(catalogPoolId = "another_pool")))),
            source.copy(events = listOf(source.events[0].copy(agentSnapshot = RecruitmentAgentSnapshot("other_pool_slot", "其他池占位")))),
            source.copy(pools = listOf(RecruitmentPool("p", snapshot.copy(upAgents = listOf(slot.copy(id = "forged_slot"))), 7))),
            source.copy(events = listOf(source.events[0].copy(agentSnapshot = RecruitmentAgentSnapshot("wrong_game_or_rarity", "非本游戏绝密")))),
        )
        invalid.forEach { backup ->
            assertEquals(
                422,
                assertThrows(RecruitmentApiException::class.java) {
                    fixture.service.preview("u", RecruitmentImportPreviewRequest("a", mapper.valueToTree(backup)))
                }.status.value(),
            )
        }
        val nonUp = source.copy(events = listOf(source.events[0].copy(agentSnapshot = RecruitmentAgentSnapshot("official_A", "绝密A"))))
        val allowed = fixture.service.preview(
            "u",
            RecruitmentImportPreviewRequest("a", mapper.valueToTree(nonUp), RecruitmentImportOptions(confirmCountChange = true)),
        )
        assertEquals("add", allowed.items.single { it.entityType == "event" }.status)
        verify(exactly = 0) {
            fixture.store.saveArchive(any(), any())
            fixture.store.insertEvent(any())
            fixture.fence.fenceRecruitmentWrite(any(), any(), any())
            fixture.publisher.publishChange(any(), any(), any(), any())
        }
    }

    @Test fun `disabled pool retired placeholder backup restores historical identity without accepting snapshot definitions`() {
        val fixture = fixture()
        every { fixture.catalog.findPool("如鸢", "catalog") } returns mockk(relaxed = true) {
            every { enabled } returns false
            every { upAgents } returns listOf(slot.copy(name = "正式绝密A", operatorId = "official_A", active = false))
        }
        val incoming = document(record())
        val preview = fixture.service.preview(
            "u",
            RecruitmentImportPreviewRequest("a", mapper.valueToTree(incoming), RecruitmentImportOptions(confirmCountChange = true)),
        )
        assertEquals("add", preview.items.single { it.entityType == "event" }.status)
        assertEquals(124L, preview.candidateKnownTotal)
        assertTrue(preview.canCommit)
        verify(exactly = 0) { fixture.catalog.operator(any(), any()) }
    }

    @Test fun `import validates each distinct pool and official agent once and commit rechecks directory references`() {
        val fixture = fixture()
        val repeatedOfficial = document(
            *(1..20).map {
                record("E$it", order = it.toLong()).copy(agentSnapshot = RecruitmentAgentSnapshot("official_A", "绝密A"))
            }.toTypedArray(),
        )
        val node = mapper.valueToTree<JsonNode>(repeatedOfficial)
        val options = RecruitmentImportOptions(confirmCountChange = true)
        val preview = fixture.service.preview("u", RecruitmentImportPreviewRequest("a", node, options))
        assertEquals(20, preview.items.count { it.entityType == "event" && it.status == "add" })
        verify(exactly = 1) { fixture.catalog.findPool("如鸢", "catalog") }
        verify(exactly = 1) { fixture.catalog.operator("如鸢", "official_A") }
        every { fixture.catalog.findPool("如鸢", "catalog") } returns null
        val input = RecruitmentImportCommitRequest(
            "a",
            node,
            options,
            preview.previewToken,
            preview.documentHash,
            preview.targetRevision,
            "r",
        )
        assertThrows(RecruitmentApiException::class.java) { fixture.service.commit("u", input) }
        verify(exactly = 0) {
            fixture.store.saveArchive(any(), any())
            fixture.store.insertEvent(any())
            fixture.fence.fenceRecruitmentWrite(any(), any(), any())
            fixture.publisher.publishChange(any(), any(), any(), any())
        }
    }

    @Test fun `existing private facts export and dedupe but backup cannot append new legacy results or batches`() {
        val fixture = fixture()
        val legacySnapshot = snapshot.copy(catalogPoolId = null, upAgents = emptyList())
        val legacyState = state.copy(
            pools = listOf(RecruitmentPool("p", legacySnapshot, 7)),
            temporaryAgents = listOf(RecruitmentTemporaryAgent("tmp_A", "A")),
        )
        val old = record().copy(poolSnapshot = legacySnapshot, agentSnapshot = RecruitmentAgentSnapshot("tmp_A", "A", true))
        every { fixture.store.archive("u", "a") } returns legacyState
        every { fixture.store.exchangeEvents("u", "a", 20_001) } returns listOf(old)
        val exported = fixture.service.export("u", "a")
        assertEquals(legacyState.pools, exported.pools)
        assertEquals(legacyState.temporaryAgents, exported.temporaryAgents)
        val duplicate = fixture.service.preview("u", RecruitmentImportPreviewRequest("a", mapper.valueToTree(exported)))
        assertEquals("duplicate", duplicate.items.single { it.entityType == "event" }.status)
        listOf(
            exported.copy(events = exported.events + exported.events[0].copy(eventId = "new", sortOrder = 2)),
            exported.copy(batches = listOf(RecruitmentBatch("u:a:B", "u", "a", "B", "p", 40, now).exchange())),
        ).forEach {
            assertThrows(RecruitmentApiException::class.java) {
                fixture.service.preview("u", RecruitmentImportPreviewRequest("a", mapper.valueToTree(it)))
            }
        }
        verify(exactly = 0) {
            fixture.store.saveArchive(any(), any())
            fixture.store.insertEvent(any())
            fixture.store.insertBatch(any())
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

    @Test fun `export includes current UP binding while preserving every original personal snapshot and revision`() {
        val fixture = fixture()
        val original = record()
        val rebound = slot.copy(name = "正式绝密A", operatorId = "official_A")
        every { fixture.store.exchangeEvents("u", "a", 20_001) } returns listOf(original)
        every { fixture.catalog.projectPools(state.pools) } returns listOf(
            state.pools.single().copy(snapshot = snapshot.copy(name = "管理员改名", upAgents = listOf(rebound))),
        )
        val exported = fixture.service.export("u", "a")
        assertEquals("official_A", exported.pools.single().snapshot.upAgents.single().operatorId)
        assertEquals(snapshot.name, exported.pools.single().snapshot.name)
        assertEquals(state.archiveRevision, exported.archiveRevision)
        assertEquals(state.baseline, exported.baseline)
        assertEquals(state.pools.single().progress, exported.pools.single().progress)
        assertEquals(original.agentSnapshot, exported.events.single().agentSnapshot)
        assertEquals(original.poolSnapshot, exported.events.single().poolSnapshot)
        assertEquals(original.pullSpan, exported.events.single().pullSpan)
        assertEquals(original.acquiredDate, exported.events.single().acquiredDate)
        assertEquals(original.note, exported.events.single().note)
        assertEquals("duplicate", plan(records = listOf(original), backup = exported).items.single { it.entityType == "event" }.status)
        assertEquals("duplicate", plan(records = listOf(original), backup = exported).items.single { it.entityType == "pool" }.status)
        assertEquals(null, state.pools.single().snapshot.upAgents.single().operatorId)
        verify(exactly = 0) {
            fixture.store.saveArchive(any(), any())
            fixture.store.insertEvent(any())
            fixture.publisher.publishChange(any(), any(), any(), any())
        }
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
                fixture.catalog,
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
        val catalog = mockk<RecruitmentCatalog>()
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
        every { catalog.findPool("如鸢", "catalog") } returns mockk(relaxed = true) {
            every { upAgents } returns listOf(slot)
        }
        every { catalog.findPool("如鸢", neq("catalog")) } returns null
        every { catalog.projectPools(any()) } answers { firstArg() }
        every { catalog.operator(any(), any()) } throws recruitmentInvalid("密探不存在或不属于该游戏绝密图鉴")
        every { catalog.operator("如鸢", "official_A") } returns RecruitmentAgentSnapshot("official_A", "绝密A")
        val service = RecruitmentExchangeService(store, accounts, fence, recruitment, catalog, publisher, mapper, tx)
        service.clock = Clock.fixed(now, ZoneOffset.UTC)
        return Fixture(store, accounts, fence, recruitment, catalog, publisher, tx, service)
    }
    private data class Fixture(
        val store: RecruitmentRepository,
        val accounts: SubAccountService,
        val fence: SubAccountRepository,
        val recruitment: RecruitmentService,
        val catalog: RecruitmentCatalog,
        val publisher: AccountEventService,
        val tx: TransactionTemplate,
        val service: RecruitmentExchangeService,
    )
}
