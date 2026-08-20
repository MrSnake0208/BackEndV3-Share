package com.lhs.share.hub.service.operator

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.hub.controller.inventory.request.ProducerDto
import com.lhs.share.hub.controller.operator.request.OperatorDiscRequest
import com.lhs.share.hub.controller.operator.request.OperatorEntryRequest
import com.lhs.share.hub.controller.operator.request.OperatorExchangeAccountDto
import com.lhs.share.hub.controller.operator.request.OperatorImportRequest
import com.lhs.share.hub.controller.operator.request.OperatorRecordRequest
import com.lhs.share.hub.controller.operator.response.OperatorCurrentResponse
import com.lhs.share.hub.repository.OperatorCatalogRepository
import com.lhs.share.hub.repository.OperatorCorrectionRecordRepository
import com.lhs.share.hub.repository.OperatorCurrentRepository
import com.lhs.share.hub.repository.OperatorRecordRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.OperatorCatalogEntity
import com.lhs.share.hub.repository.entity.OperatorCombatStats
import com.lhs.share.hub.repository.entity.OperatorCorrectionRecord
import com.lhs.share.hub.repository.entity.OperatorCurrent
import com.lhs.share.hub.repository.entity.OperatorDisc
import com.lhs.share.hub.repository.entity.OperatorDiscCatalog
import com.lhs.share.hub.repository.entity.OperatorDiscLoadout
import com.lhs.share.hub.repository.entity.OperatorEntry
import com.lhs.share.hub.repository.entity.OperatorOddityValue
import com.lhs.share.hub.repository.entity.OperatorRecord
import com.lhs.share.hub.repository.entity.OperatorRecordEntry
import com.lhs.share.hub.repository.entity.OperatorStarStone
import com.lhs.share.hub.repository.entity.ProducerInfo
import com.lhs.share.hub.repository.entity.SubAccount
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

class OperatorCurrentFoundationServiceTest {
    private val accountRepository = mockk<SubAccountRepository>()
    private val currentRepository = mockk<OperatorCurrentRepository>()
    private val recordRepository = mockk<OperatorRecordRepository>()
    private val catalogRepository = mockk<OperatorCatalogRepository>()
    private val catalogService = mockk<OperatorCatalogService>()
    private val correctionRepository = mockk<OperatorCorrectionRecordRepository>()
    private val transactionTemplate = TransactionTemplate(
        object : PlatformTransactionManager {
            override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
            override fun commit(status: TransactionStatus) = Unit
            override fun rollback(status: TransactionStatus) = Unit
        },
    )
    private val service = OperatorService(
        accountRepository,
        currentRepository,
        recordRepository,
        catalogRepository,
        catalogService,
        transactionTemplate,
        correctionRepository,
    )
    private val mapper = jacksonObjectMapper()
    private lateinit var stored: OperatorCurrent
    private var rarity = 5
    private var spOf: String? = null

    @BeforeEach
    fun setUp() {
        stored = OperatorCurrent(
            id = "current-1",
            userId = "u1",
            accountId = "acc1",
            game = "代号鸢",
            entries = mapOf("op1" to baseEntry()),
        )
        every { accountRepository.findByUserIdAndAccountId("u1", "acc1") } returns
            SubAccount(userId = "u1", accountId = "acc1", name = "账号", game = "代号鸢")
        every { currentRepository.findByUserIdAndAccountIdAndGame("u1", "acc1", "代号鸢") } answers { stored }
        every { currentRepository.findByUserIdAndAccountIdAndGame("u1", "acc1", "*") } returns null
        every { currentRepository.compareAndSetEntries(any(), any(), any(), any(), any(), any(), any()) } answers {
            val updates = arg<Map<String, OperatorEntry>>(5)
            stored = stored.copy(entries = stored.entries + updates, updatedAt = arg(6))
            stored
        }
        every { currentRepository.save(any()) } answers { firstArg<OperatorCurrent>().also { stored = it } }
        every { correctionRepository.save(any()) } answers { firstArg<OperatorCorrectionRecord>() }
        every { catalogService.getOperator("op1") } answers { catalog("op1", rarity, spOf) }
        every { catalogService.spFormsOf(any()) } returns emptyList()
    }

    @Test
    fun `legacy current entry derives first loadout and new defaults`() {
        val legacy = OperatorCurrent(
            userId = "u1",
            accountId = "acc1",
            game = "代号鸢",
            entries = mapOf(
                "op1" to OperatorEntry(
                    elite = 1,
                    starLevel = 2,
                    level = 10,
                    discs = listOf(OperatorDisc("盘A")),
                ),
            ),
        )

        val entry = OperatorCurrentResponse.of(legacy).entries.getValue("op1")

        assertEquals("命盘一", entry.discLoadouts.single().name)
        assertEquals(entry.discs, entry.discLoadouts.single().discs)
        assertNull(entry.combatStats)
        assertEquals(0, entry.revision)
        assertNull(entry.updatedAt)
    }

    @Test
    fun `patch merges present fields normalizes loadouts and clears manual value with null`() {
        val request = mapper.readTree(
            """
            {
              "disc_loadouts": [
                {"id":"one","name":"  主力  ","discs":[{"ot_name":"盘A"}]},
                {"id":"two","name":null,"discs":[]}
              ],
              "combat_stats": {
                "manual_attack": null,
                "oddities": {"attack":{"current":500,"max":999999}}
              },
              "expected_revision": 7,
              "reason": "manual_correction"
            }
            """.trimIndent(),
        ) as com.fasterxml.jackson.databind.node.ObjectNode

        val result = service.patchCurrent("u1", "acc1", "代号鸢", "op1", request)

        assertEquals(8, result.revision)
        assertEquals("主力", result.discLoadouts[0].name)
        assertEquals("命盘二", result.discLoadouts[1].name)
        assertEquals(result.discs, result.discLoadouts[0].discs)
        assertNull(result.combatStats?.manualAttack)
        assertEquals(500, result.combatStats?.oddities?.get("attack")?.current)
        assertEquals("stale", result.combatStats?.observedStatus)
    }

    @Test
    fun `patch rejects active semantics null loadouts and stale revision`() {
        val active = patch(""""disc_loadouts":[],"active_disc_loadout_id":"one"""")
        val nullLoadouts = patch(""""disc_loadouts":null""")
        val stale = patch(""""level":20,"expected_revision":6""", includeRevision = false)

        val activeError = assertThrows(OperatorApiException::class.java) {
            service.patchCurrent("u1", "acc1", "代号鸢", "op1", active)
        }
        val nullError = assertThrows(OperatorApiException::class.java) {
            service.patchCurrent("u1", "acc1", "代号鸢", "op1", nullLoadouts)
        }
        val staleError = assertThrows(OperatorApiException::class.java) {
            service.patchCurrent("u1", "acc1", "代号鸢", "op1", stale)
        }

        assertEquals("schema_validation_failed", activeError.code)
        assertEquals("invalid_disc_loadout", nullError.code)
        assertEquals(HttpStatus.CONFLICT, staleError.status)
        assertEquals("operator_revision_conflict", staleError.code)
    }

    @Test
    fun `rarity limits and stable oddity keys are enforced`() {
        val limits = mapOf(3 to Triple(300, 1560, 9), 4 to Triple(305, 1820, 11), 5 to Triple(500, 2600, 15))
        limits.forEach { (testedRarity, values) ->
            rarity = testedRarity
            stored = stored.copy(entries = mapOf("op1" to baseEntry()))
            val valid = patch(
                """"combat_stats":{"oddities":{"attack":{"current":${values.first}},"hp":{"current":${values.second}},"special":{"current":${values.third}}}}""",
            )
            service.patchCurrent("u1", "acc1", "代号鸢", "op1", valid)
            assertEquals(values.third, stored.entries.getValue("op1").combatStats?.oddities?.get("special")?.current)
        }
        rarity = 4
        stored = stored.copy(entries = mapOf("op1" to baseEntry()))
        val overflow = patch(""""combat_stats":{"oddities":{"attack":{"current":306}}}""")
        val unknown = patch(""""combat_stats":{"oddities":{"damage":{"current":1}}}""")

        assertEquals(
            "invalid_combat_stats",
            assertThrows(OperatorApiException::class.java) {
                service.patchCurrent("u1", "acc1", "代号鸢", "op1", overflow)
            }.code,
        )
        assertEquals(
            "invalid_combat_stats",
            assertThrows(OperatorApiException::class.java) {
                service.patchCurrent("u1", "acc1", "代号鸢", "op1", unknown)
            }.code,
        )
    }

    @Test
    fun `SP star level is direct 0 through 5 and independent`() {
        spOf = "base"
        val invalid = patch(""""star_level":6""")

        val error = assertThrows(OperatorApiException::class.java) {
            service.patchCurrent("u1", "acc1", "代号鸢", "op1", invalid)
        }

        assertEquals("invalid_star_level", error.code)
        assertEquals("star_level", error.fieldPath)
        assertEquals(3, stored.entries.getValue("op1").starLevel)
    }

    @Test
    fun `v2 listed updates first loadout preserves second and marks observation stale`() {
        every { accountRepository.findAllByUserIdAndAccountIdIn("u1", setOf("acc1")) } returns
            listOf(SubAccount(userId = "u1", accountId = "acc1", name = "账号", game = "代号鸢"))
        every { recordRepository.findByUserIdAndAccountIdAndRecordId(any(), any(), any()) } returns null
        every { recordRepository.save(any()) } answers { firstArg<OperatorRecord>() }
        val request = OperatorImportRequest(
            format = "myshare-operator-exchange",
            version = 2,
            exportedAt = "2026-08-21T00:00:00Z",
            producer = ProducerDto("test", "1"),
            accounts = listOf(OperatorExchangeAccountDto("acc1")),
            records = listOf(
                OperatorRecordRequest(
                    accountId = "acc1",
                    recordId = "rec-v2",
                    recordType = "operator_snapshot",
                    game = "代号鸢",
                    effectiveAt = "2026-08-21T00:00:00Z",
                    snapshotScope = "listed",
                    entries = listOf(
                        OperatorEntryRequest(
                            id = "op1",
                            elite = 2,
                            starLevel = 4,
                            level = 20,
                            discs = listOf(OperatorDiscRequest("盘C")),
                        ),
                    ),
                ),
            ),
        )

        service.import("u1", request)

        val entry = stored.entries.getValue("op1")
        assertEquals(listOf("盘C"), entry.discLoadouts[0].discs.map { it.otName })
        assertEquals("命盘二", entry.discLoadouts[1].name)
        assertEquals("stale", entry.combatStats?.observedStatus)
        assertEquals(8, entry.revision)
    }

    @Test
    fun `v2 full with empty discs clears only first loadout and preserves combat data`() {
        every { accountRepository.findAllByUserIdAndAccountIdIn("u1", setOf("acc1")) } returns
            listOf(SubAccount(userId = "u1", accountId = "acc1", name = "账号", game = "代号鸢"))
        every { recordRepository.findByUserIdAndAccountIdAndRecordId(any(), any(), any()) } returns null
        every { recordRepository.save(any()) } answers { firstArg<OperatorRecord>() }
        val request = OperatorImportRequest(
            format = "myshare-operator-exchange",
            version = 2,
            exportedAt = "2026-08-21T00:00:00Z",
            producer = ProducerDto("test", "1"),
            accounts = listOf(OperatorExchangeAccountDto("acc1")),
            records = listOf(
                OperatorRecordRequest(
                    accountId = "acc1",
                    recordId = "rec-full-v2",
                    recordType = "operator_snapshot",
                    game = "代号鸢",
                    effectiveAt = "2026-08-21T00:00:00Z",
                    snapshotScope = "full",
                    entries = listOf(OperatorEntryRequest(id = "op1", elite = 1, starLevel = 3, level = 10)),
                ),
            ),
        )

        service.import("u1", request)

        val entry = stored.entries.getValue("op1")
        assertEquals(emptyList<OperatorDisc>(), entry.discLoadouts[0].discs)
        assertEquals("命盘二", entry.discLoadouts[1].name)
        assertEquals(1000, entry.combatStats?.observedAttack)
        assertEquals("stale", entry.combatStats?.observedStatus)
    }

    @Test
    fun `delete replay reapplies independent correction records after remaining v2 records`() {
        val target = history("remove", "2026-08-21T00:00:00Z", "2026-08-21T00:00:01Z")
        val remaining = history("keep", "2026-08-21T00:01:00Z", "2026-08-21T00:01:01Z")
        val correction = OperatorCorrectionRecord(
            id = "correction-1",
            userId = "u1",
            accountId = "acc1",
            game = "代号鸢",
            operatorId = "op1",
            reason = "manual_correction",
            fields = setOf("disc_loadouts", "combat_stats"),
            discLoadouts = baseEntry().discLoadouts,
            combatStats = baseEntry().combatStats,
            createdAt = Instant.parse("2026-08-21T00:02:00Z"),
        )
        var replayed: OperatorCurrent? = stored
        every { recordRepository.findByUserIdAndAccountIdAndRecordId("u1", "acc1", "remove") } returns target
        every { recordRepository.delete(target) } just runs
        every { currentRepository.deleteByUserIdAndAccountIdAndGame("u1", "acc1", "代号鸢") } answers { replayed = null }
        every { currentRepository.findByUserIdAndAccountIdAndGame("u1", "acc1", "代号鸢") } answers { replayed }
        every { currentRepository.save(any()) } answers { firstArg<OperatorCurrent>().also { replayed = it } }
        every {
            recordRepository.findByUserIdAndAccountIdAndGameOrderByEffectiveAtAsc("u1", "acc1", "代号鸢")
        } returns listOf(remaining)
        every {
            correctionRepository.findByUserIdAndAccountIdAndGameOrderByCreatedAtAsc("u1", "acc1", "代号鸢")
        } returns listOf(correction)
        every { recordRepository.save(any()) } answers { firstArg<OperatorRecord>() }

        service.deleteRecord("u1", "acc1", "remove")

        val entry = replayed!!.entries.getValue("op1")
        assertEquals("命盘二", entry.discLoadouts[1].name)
        assertEquals(1000, entry.combatStats?.observedAttack)
        assertEquals(1, entry.revision)
    }

    private fun patch(fields: String, includeRevision: Boolean = true): com.fasterxml.jackson.databind.node.ObjectNode {
        val revision = if (includeRevision) ",\"expected_revision\":7" else ""
        return mapper.readTree("{$fields$revision,\"reason\":\"manual_correction\"}") as com.fasterxml.jackson.databind.node.ObjectNode
    }

    private fun baseEntry() = OperatorEntry(
        elite = 1,
        starLevel = 3,
        level = 10,
        discs = listOf(OperatorDisc("盘A")),
        starStones = listOf(OperatorStarStone("石", "main1", 1)),
        discLoadouts = listOf(
            OperatorDiscLoadout("one", "命盘一", listOf(OperatorDisc("盘A"))),
            OperatorDiscLoadout("two", "命盘二", listOf(OperatorDisc("盘B"))),
        ),
        combatStats = OperatorCombatStats(
            observedAttack = 1000,
            observedHp = 5000,
            manualAttack = 900,
            source = "scan",
            observedStatus = "valid",
            combatInputSignature = "scan-input-v1",
            oddities = mapOf("attack" to OperatorOddityValue(100)),
        ),
        revision = 7,
    )

    private fun catalog(id: String, rarity: Int, spOf: String?) = OperatorCatalogEntity(
        operatorId = id,
        name = id,
        rarity = rarity,
        prof = emptyList(),
        subProf = emptyList(),
        games = listOf("代号鸢"),
        discs = listOf("盘A", "盘B", "盘C").map { OperatorDiscCatalog(it) },
        starStones = emptyList(),
        spOf = spOf,
        catalogVersion = "test",
    )

    private fun history(recordId: String, effectiveAt: String, receivedAt: String) = OperatorRecord(
        recordId = recordId,
        userId = "u1",
        accountId = "acc1",
        recordType = "operator_snapshot",
        game = "代号鸢",
        snapshotScope = "full",
        effectiveAt = Instant.parse(effectiveAt),
        receivedAt = Instant.parse(receivedAt),
        producer = ProducerInfo("test", "1"),
        entries = listOf(OperatorRecordEntry("op1", elite = 1, starLevel = 3, level = 10)),
    )
}
