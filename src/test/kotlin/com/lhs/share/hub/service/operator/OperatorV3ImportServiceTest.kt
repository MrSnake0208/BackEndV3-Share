package com.lhs.share.hub.service.operator

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.hub.controller.operator.response.OperatorCurrentEntryDto
import com.lhs.share.hub.controller.operator.response.OperatorScanImportEvent
import com.lhs.share.hub.repository.OperatorCatalogRepository
import com.lhs.share.hub.repository.OperatorCorrectionRecordRepository
import com.lhs.share.hub.repository.OperatorCurrentRepository
import com.lhs.share.hub.repository.OperatorRecordRepository
import com.lhs.share.hub.repository.OperatorV3ImportRecordRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.OperatorCatalogEntity
import com.lhs.share.hub.repository.entity.OperatorCombatStats
import com.lhs.share.hub.repository.entity.OperatorCorrectionRecord
import com.lhs.share.hub.repository.entity.OperatorCurrent
import com.lhs.share.hub.repository.entity.OperatorEntry
import com.lhs.share.hub.repository.entity.OperatorV3ImportRecord
import com.lhs.share.hub.repository.entity.SubAccount
import com.lhs.share.hub.service.account.AccountEventService
import com.lhs.share.hub.service.account.SubAccountService
import com.lhs.share.openapi.OpenApiOperatorController
import com.lhs.share.openapi.OpenApiPermission
import com.lhs.share.openapi.OpenApiPrincipal
import com.lhs.share.openapi.OpenApiTokenService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate

class OperatorV3ImportServiceTest {
    private val mapper = jacksonObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
    private val accountRepository = mockk<SubAccountRepository>()
    private val catalogService = mockk<OperatorCatalogService>()
    private val operatorService = mockk<OperatorService>()
    private val importRecordRepository = mockk<OperatorV3ImportRecordRepository>()
    private val accountEventService = mockk<AccountEventService>()
    private val service = OperatorV3ImportService(
        mapper,
        OperatorV3SchemaValidator(mapper),
        accountRepository,
        catalogService,
        operatorService,
        importRecordRepository,
        accountEventService,
    )

    @BeforeEach
    fun setUp() {
        every { accountRepository.findByUserIdAndAccountId("u1", "acc1") } returns
            SubAccount(userId = "u1", accountId = "acc1", name = "账号", game = "如鸢")
        every { catalogService.getOperator("op1") } returns catalog()
        every { operatorService.current("u1", "acc1", "如鸢") } returns emptyList()
        every { operatorService.completeFullImport(any(), any(), any(), any(), any()) } just runs
        every { importRecordRepository.findByUserIdAndAccountIdAndRecordId("u1", "acc1", "scan:1") } returns null
        every { importRecordRepository.save(any()) } answers { firstArg<OperatorV3ImportRecord>() }
        every { accountEventService.publish(any(), any(), any(), any(), any()) } just runs
    }

    @Test
    fun `listed scan preview validates and never writes`() {
        val patch = slot<com.fasterxml.jackson.databind.node.ObjectNode>()
        every { operatorService.previewCurrentPatch("u1", "acc1", "如鸢", "op1", capture(patch)) } returns
            OperatorCurrentPatchPreview(null, entry(level = 90, revision = 1), stale = false)

        val result = service.previewBrowser("u1", wrappedDocument())

        assertEquals(1, result.accepted)
        assertEquals(1, result.items.single().targetRevision)
        assertEquals(90, patch.captured.path("level").intValue())
        assertEquals("scan", patch.captured.path("combat_stats").path("source").asText())
        verify(exactly = 0) { operatorService.patchCurrent(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { importRecordRepository.save(any()) }
    }

    @Test
    fun `commit creates revision one and writes an idempotency audit`() {
        every { operatorService.previewCurrentPatch("u1", "acc1", "如鸢", "op1", any()) } returns
            OperatorCurrentPatchPreview(null, entry(level = 90, revision = 1), stale = false)
        every { operatorService.patchCurrent("u1", "acc1", "如鸢", "op1", any()) } returns entry(level = 90, revision = 1)
        val result = service.commitBrowser("u1", wrappedDocument())

        assertEquals(1, result.accepted)
        assertEquals(1, result.items.single().revision)
        verify(exactly = 1) { operatorService.patchCurrent("u1", "acc1", "如鸢", "op1", any()) }
        verify(exactly = 1) {
            importRecordRepository.save(match { it.recordId == "scan:1" && it.revisions == mapOf("op1" to 1L) })
        }
        verify(exactly = 0) { accountEventService.publish(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `OpenAPI commit publishes one account scoped event after each entry`() {
        every { operatorService.previewCurrentPatch("u1", "acc1", "如鸢", "op1", any()) } returns
            OperatorCurrentPatchPreview(null, entry(level = 90, revision = 1), stale = false)
        every { operatorService.patchCurrent("u1", "acc1", "如鸢", "op1", any()) } returns entry(level = 90, revision = 1)

        service.commitScan("u1", "acc1", document())

        verify(exactly = 1) {
            accountEventService.publish(
                "u1",
                "acc1",
                OperatorV3ImportService.SCAN_EVENT_NAME,
                any(),
                match<OperatorScanImportEvent> {
                    it.operatorId == "op1" && it.status == "accepted" && it.revision == 1L
                },
            )
        }
    }

    @Test
    fun `OpenAPI scan endpoints keep a signatureless observation valid through current GET`() {
        val currentRepository = mockk<OperatorCurrentRepository>()
        val recordRepository = mockk<OperatorRecordRepository>()
        val catalogRepository = mockk<OperatorCatalogRepository>()
        val correctionRepository = mockk<OperatorCorrectionRecordRepository>()
        var current = OperatorCurrent(
            id = "current-1",
            userId = "u1",
            accountId = "acc1",
            game = "如鸢",
            entries = mapOf(
                "op1" to OperatorEntry(
                    elite = 1,
                    starLevel = 1,
                    level = 10,
                    combatStats = OperatorCombatStats(
                        observedAttack = 900,
                        observedHp = 5000,
                        source = "scan",
                        observedStatus = "valid",
                        combatInputSignature = "old-signature",
                    ),
                    revision = 7,
                ),
            ),
        )
        every { currentRepository.findByUserIdAndAccountIdAndGame("u1", "acc1", "如鸢") } answers { current }
        every { currentRepository.findByUserIdAndAccountIdAndGame("u1", "acc1", "universal") } returns null
        every { currentRepository.findByUserIdAndAccountIdAndGame("u1", "acc1", "*") } returns null
        every { currentRepository.findByUserIdAndAccountIdOrderByUpdatedAtDesc("u1", "acc1") } answers { listOf(current) }
        every { currentRepository.compareAndSetEntries(any(), any(), any(), any(), any(), any(), any()) } answers {
            current = current.copy(entries = current.entries + arg<Map<String, OperatorEntry>>(5), updatedAt = arg(6))
            current
        }
        every { currentRepository.save(any()) } answers { firstArg<OperatorCurrent>().also { current = it } }
        every { correctionRepository.save(any()) } answers { firstArg<OperatorCorrectionRecord>() }
        every { catalogService.spFormsOf(any()) } returns emptyList()
        val transactionTemplate = TransactionTemplate(
            object : PlatformTransactionManager {
                override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
                override fun commit(status: TransactionStatus) = Unit
                override fun rollback(status: TransactionStatus) = Unit
            },
        )
        val realOperatorService = OperatorService(
            accountRepository,
            currentRepository,
            recordRepository,
            catalogRepository,
            catalogService,
            transactionTemplate,
            correctionRepository,
        )
        val realImportService = OperatorV3ImportService(
            mapper,
            OperatorV3SchemaValidator(mapper),
            accountRepository,
            catalogService,
            realOperatorService,
            importRecordRepository,
            accountEventService,
        )
        val tokenService = mockk<OpenApiTokenService>()
        every { tokenService.validateAuthorization("Bearer scan", OpenApiPermission.OPERATOR_SCAN_WRITE) } returns
            OpenApiPrincipal("u1", "acc1")
        every { tokenService.validateAuthorization("Bearer read", OpenApiPermission.OPERATOR_READ) } returns
            OpenApiPrincipal("u1", "acc1")
        val controller = OpenApiOperatorController(
            tokenService,
            realOperatorService,
            mockk<SubAccountService>(),
            realImportService,
        )
        val event = slot<OperatorScanImportEvent>()
        every {
            accountEventService.publish(
                "u1",
                "acc1",
                OperatorV3ImportService.SCAN_EVENT_NAME,
                any(),
                capture(event),
            )
        } just runs
        val request = document().also { root ->
            val entry = root.path("records").get(0).path("entries").get(0) as com.fasterxml.jackson.databind.node.ObjectNode
            entry.put("level", 93).put("elite", 14).put("star_level", 3)
            entry.set<com.fasterxml.jackson.databind.JsonNode>(
                "equipped_star_stones",
                mapper.readTree("""[{"type":"main1","name":"攻击力","level":60}]"""),
            )
            entry.set<com.fasterxml.jackson.databind.JsonNode>(
                "combat_stats",
                mapper.readTree(
                    """
                    {
                      "observed_attack":4001,
                      "observed_hp":20245,
                      "source":"scan",
                      "observed_status":"valid",
                      "oddities":{
                        "attack":{"current":0},
                        "hp":{"current":0},
                        "special":{"current":15}
                      }
                    }
                    """.trimIndent(),
                ),
            )
            entry.set<com.fasterxml.jackson.databind.JsonNode>(
                "section_status",
                mapper.readTree(
                    """{"basic":"ready","huaji":"ready","equipment":"ready","combat_stats":"ready","oddities":"ready"}""",
                ),
            )
        }

        val preview = checkNotNull(controller.previewScanImport("Bearer scan", request).data)
        val committed = checkNotNull(controller.commitScanImport("Bearer scan", request).data)
        val saved = checkNotNull(controller.current("Bearer read", "如鸢").data).single().entries.getValue("op1")

        assertEquals(false, preview.items.single().stale)
        assertEquals("valid", preview.items.single().observedStatus)
        assertEquals("valid", committed.items.single().observedStatus)
        assertEquals(false, event.captured.stale)
        assertEquals("valid", event.captured.observedStatus)
        assertEquals("valid", saved.combatStats?.observedStatus)
        assertEquals(93, saved.combatStats?.observedInputs?.level)
        assertEquals(true, saved.combatStats?.combatInputSignature?.startsWith("sha256:"))
        assertEquals(4001, saved.combatStats?.manualAttack)
        assertEquals(20245, saved.combatStats?.manualHp)
        assertEquals("manual", saved.combatStats?.displayMode?.attack)
        assertEquals("manual", saved.combatStats?.displayMode?.hp)
    }

    @Test
    fun `OpenAPI rejects source kind and scope outside listed scan`() {
        val invalid = document().also { root ->
            (root.path("records").get(0) as com.fasterxml.jackson.databind.node.ObjectNode)
                .put("source_kind", "backup")
                .put("snapshot_scope", "full")
        }

        val error = assertThrows(OperatorApiException::class.java) {
            service.previewScan("u1", "acc1", invalid)
        }

        assertEquals("scan_scope_not_allowed", error.code)
        verify(exactly = 0) { operatorService.previewCurrentPatch(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `same record is unchanged and changed record idempotency conflicts`() {
        val rawRecord = document().path("records").get(0)
        val audit = OperatorV3ImportRecord(
            userId = "u1",
            accountId = "acc1",
            sourceAccountId = "local",
            recordId = "scan:1",
            game = "如鸢",
            sourceKind = "scan",
            snapshotScope = "listed",
            payload = mapper.writeValueAsString(rawRecord),
            revisions = mapOf("op1" to 4L),
        )
        every { importRecordRepository.findByUserIdAndAccountIdAndRecordId("u1", "acc1", "scan:1") } returns audit

        val duplicate = service.previewBrowser("u1", wrappedDocument())
        assertEquals(1, duplicate.unchanged)
        assertEquals(4, duplicate.items.single().revision)

        val changed = wrappedDocument().also {
            (it.path("document").path("records").get(0).path("entries").get(0) as com.fasterxml.jackson.databind.node.ObjectNode)
                .put("level", 91)
        }
        val conflict = service.previewBrowser("u1", changed)
        assertEquals(1, conflict.rejected)
        assertEquals("idempotency_conflict", conflict.items.single().blockingErrors.single().code)
    }

    @Test
    fun `browser mapping cannot target an account outside the current user`() {
        every { accountRepository.findByUserIdAndAccountId("u1", "acc2") } returns null
        val request = wrappedDocument().also {
            (it.path("account_mapping") as com.fasterxml.jackson.databind.node.ObjectNode).put("local", "acc2")
        }

        val error = assertThrows(OperatorApiException::class.java) {
            service.previewBrowser("u1", request)
        }

        assertEquals("account_scope_mismatch", error.code)
        verify(exactly = 0) { operatorService.previewCurrentPatch(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `OpenAPI maps arbitrary source account to the token bound account and rejects preferences`() {
        val valid = document().also {
            (it.path("accounts").get(0) as com.fasterxml.jackson.databind.node.ObjectNode).put("id", "acc_other")
            (it.path("records").get(0) as com.fasterxml.jackson.databind.node.ObjectNode).put("account_id", "acc_other")
        }
        every { operatorService.previewCurrentPatch("u1", "acc1", "如鸢", "op1", any()) } returns
            OperatorCurrentPatchPreview(null, entry(level = 90, revision = 1), stale = false)

        assertEquals(1, service.previewScan("u1", "acc1", valid).accepted)
        verify { accountRepository.findByUserIdAndAccountId("u1", "acc1") }

        val preference = valid.also {
            (it.path("records").get(0).path("entries").get(0).path("combat_stats") as com.fasterxml.jackson.databind.node.ObjectNode)
                .set<com.fasterxml.jackson.databind.JsonNode>("display_mode", mapper.createObjectNode().put("attack", "auto"))
        }
        val rejected = service.previewScan("u1", "acc1", preference)
        assertEquals(1, rejected.rejected)
        assertEquals("scan_field_not_allowed", rejected.items.single().blockingErrors.single().code)
    }

    @Test
    fun `full commit completes baseline while listed commit never removes outside entries`() {
        every { operatorService.previewCurrentPatch("u1", "acc1", "如鸢", "op1", any()) } returns
            OperatorCurrentPatchPreview(null, entry(level = 90, revision = 1), stale = false)
        every { operatorService.patchCurrent("u1", "acc1", "如鸢", "op1", any()) } returns entry(level = 90, revision = 1)

        service.commitBrowser("u1", wrappedDocument())
        verify(exactly = 0) { operatorService.completeFullImport(any(), any(), any(), any(), any()) }

        val full = wrappedDocument().also {
            (it.path("document").path("records").get(0) as com.fasterxml.jackson.databind.node.ObjectNode)
                .put("record_id", "full:1")
                .put("source_kind", "backup")
                .put("snapshot_scope", "full")
        }
        every { importRecordRepository.findByUserIdAndAccountIdAndRecordId("u1", "acc1", "full:1") } returns null
        service.commitBrowser("u1", full)

        verify(exactly = 1) {
            operatorService.completeFullImport("u1", "acc1", "如鸢", setOf("op1"), any())
        }
    }

    @Test
    fun `catalog invalid oddity is rejected even when section awaits review`() {
        val request = wrappedDocument().also {
            val entry = it.path("document").path("records").get(0).path("entries").get(0)
            (entry.path("section_status") as com.fasterxml.jackson.databind.node.ObjectNode).put("oddities", "review")
            (entry.path("combat_stats") as com.fasterxml.jackson.databind.node.ObjectNode).set<com.fasterxml.jackson.databind.JsonNode>(
                "oddities",
                mapper.readTree("""{"attack":{"current":501}}"""),
            )
        }

        val result = service.previewBrowser("u1", request)

        assertEquals(1, result.rejected)
        assertEquals("invalid_combat_stats", result.items.single().blockingErrors.single().code)
        verify(exactly = 0) { operatorService.previewCurrentPatch(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `universal record materializes through the account game`() {
        val request = wrappedDocument().also {
            (it.path("document").path("records").get(0) as com.fasterxml.jackson.databind.node.ObjectNode).put("game", "universal")
        }
        every { operatorService.previewCurrentPatch("u1", "acc1", "如鸢", "op1", any()) } returns
            OperatorCurrentPatchPreview(null, entry(level = 90, revision = 1), stale = false)

        assertEquals(1, service.previewBrowser("u1", request).accepted)
        verify { operatorService.previewCurrentPatch("u1", "acc1", "如鸢", "op1", any()) }
    }

    private fun wrappedDocument() = mapper.createObjectNode().apply {
        set<com.fasterxml.jackson.databind.JsonNode>("document", document())
        set<com.fasterxml.jackson.databind.JsonNode>(
            "account_mapping",
            mapper.createObjectNode().put("local", "acc1"),
        )
    }

    private fun document() = mapper.readTree(
        """
        {
          "format":"myshare-operator-exchange",
          "version":3,
          "exported_at":"2026-08-21T10:30:00+08:00",
          "producer":{"platform":"collector","version":"1"},
          "accounts":[{"id":"local","name":"本地账号","game_scope":"如鸢"}],
          "records":[{
            "account_id":"local",
            "record_id":"scan:1",
            "record_type":"operator_snapshot",
            "game":"如鸢",
            "effective_at":"2026-08-21T10:30:00+08:00",
            "snapshot_scope":"listed",
            "source_kind":"scan",
            "entries":[{
              "operator_id":"op1",
              "name":"密探",
              "level":90,
              "combat_stats":{"observed_attack":1000,"source":"scan","observed_status":"valid"},
              "section_status":{"basic":"ready","combat_stats":"ready"}
            }]
          }]
        }
        """.trimIndent(),
    ) as com.fasterxml.jackson.databind.node.ObjectNode

    private fun catalog() = OperatorCatalogEntity(
        operatorId = "op1",
        name = "密探",
        rarity = 5,
        specialOddityName = "增伤值",
        prof = emptyList(),
        subProf = emptyList(),
        games = listOf("如鸢"),
        discs = emptyList(),
        starStones = emptyList(),
        catalogVersion = "v1",
    )

    private fun entry(level: Int, revision: Long): OperatorCurrentEntryDto = OperatorCurrentEntryDto.of(
        OperatorEntry(elite = 0, starLevel = 0, level = level, revision = revision),
    )
}
