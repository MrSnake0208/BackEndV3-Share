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
import com.lhs.share.hub.repository.entity.OperatorCombatDisplayMode
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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
    private var specialOddityName: String? = "增伤值"
    private var lastCorrection: OperatorCorrectionRecord? = null
    private var lastCasGame: String? = null

    @BeforeEach
    fun setUp() {
        lastCorrection = null
        lastCasGame = null
        specialOddityName = "增伤值"
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
        every { currentRepository.findByUserIdAndAccountIdAndGame("u1", "acc1", "universal") } returns null
        every { currentRepository.findByUserIdAndAccountIdAndGame("u1", "acc1", "*") } returns null
        every { currentRepository.compareAndSetEntries(any(), any(), any(), any(), any(), any(), any()) } answers {
            lastCasGame = arg(2)
            val updates = arg<Map<String, OperatorEntry>>(5)
            stored = stored.copy(entries = stored.entries + updates, updatedAt = arg(6))
            stored
        }
        every { currentRepository.save(any()) } answers { firstArg<OperatorCurrent>().also { stored = it } }
        every { correctionRepository.save(any()) } answers {
            firstArg<OperatorCorrectionRecord>().also { lastCorrection = it }
        }
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
    fun `patch replaces star stones, preserves omission, clears explicitly, and marks observation stale`() {
        val replace = patch(
            """
            "star_stones":[
              {"type":"main1","name":"攻击力","level":60},
              {"type":"main2","name":"攻击力2","level":60},
              {"type":"main3","name":"攻击力3","level":60},
              {"type":"assist1","name":"生命值","level":50},
              {"type":"assist2","name":"生命值2","level":50},
              {"type":"assist3","name":"生命值3","level":50}
            ]
            """.trimIndent(),
        )
        val replaced = service.patchCurrent("u1", "acc1", "代号鸢", "op1", replace)

        assertEquals(8, replaced.revision)
        assertEquals(
            listOf("main1", "main2", "main3", "assist1", "assist2", "assist3"),
            replaced.starStones.map { it.type },
        )
        assertEquals(listOf("攻击力", "攻击力2", "攻击力3", "生命值", "生命值2", "生命值3"), replaced.starStones.map { it.name })
        assertEquals("stale", replaced.combatStats?.observedStatus)

        val omission = patch(""""level":91,"expected_revision":8""", includeRevision = false)
        service.patchCurrent("u1", "acc1", "代号鸢", "op1", omission)
        assertEquals(6, stored.entries.getValue("op1").starStones.size)

        val clear = patch(""""star_stones":[],"expected_revision":9""", includeRevision = false)
        val cleared = service.patchCurrent("u1", "acc1", "代号鸢", "op1", clear)
        assertEquals(emptyList<OperatorStarStone>(), cleared.starStones)
    }

    @Test
    fun `new scan observation without signature is valid for final growth inputs`() {
        stored = stored.copy(entries = emptyMap())
        val result = service.patchCurrent(
            "u1",
            "acc1",
            "代号鸢",
            "op1",
            patch(
                """
                "level":93,
                "elite":14,
                "star_level":3,
                "star_stones":[],
                "combat_stats":{
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
                revision = 0,
            ),
        )

        assertEquals("valid", result.combatStats?.observedStatus)
        assertEquals(4001, result.combatStats?.observedAttack)
        assertEquals(20245, result.combatStats?.observedHp)
        assertEquals(4001, result.combatStats?.manualAttack)
        assertEquals(20245, result.combatStats?.manualHp)
        assertEquals("manual", result.combatStats?.displayMode?.attack)
        assertEquals("manual", result.combatStats?.displayMode?.hp)
        assertNotNull(result.combatStats?.combatInputSignature)
        assertEquals(93, result.combatStats?.observedInputs?.level)
        assertEquals(14, result.combatStats?.observedInputs?.elite)
        assertEquals(3, result.combatStats?.observedInputs?.starLevel)
        assertNotNull(result.combatStats?.observedInputs?.odditiesSignature)
        assertNotNull(result.combatStats?.observedInputs?.equippedStarStonesSignature)
    }

    @Test
    fun `scan can change growth inputs and observations in the same patch`() {
        val result = service.patchCurrent(
            "u1",
            "acc1",
            "代号鸢",
            "op1",
            patch(
                """
                "level":93,
                "elite":14,
                "star_level":4,
                "star_stones":[{"type":"assist1","name":"生命值","level":60}],
                "combat_stats":{
                  "observed_attack":4001,
                  "observed_hp":20245,
                  "source":"scan",
                  "oddities":{"attack":{"current":0},"hp":{"current":0},"special":{"current":15}}
                }
                """.trimIndent(),
            ),
        )

        assertEquals("valid", result.combatStats?.observedStatus)
        assertEquals(4001, result.combatStats?.manualAttack)
        assertEquals(20245, result.combatStats?.manualHp)
        assertEquals("manual", result.combatStats?.displayMode?.attack)
        assertEquals("manual", result.combatStats?.displayMode?.hp)
        assertNotEquals("scan-input-v1", result.combatStats?.combatInputSignature)
        assertEquals(93, result.combatStats?.observedInputs?.level)
        assertEquals(14, result.combatStats?.observedInputs?.elite)
        assertEquals(4, result.combatStats?.observedInputs?.starLevel)
    }

    @Test
    fun `scan can refresh only observed values without a signature`() {
        val result = service.patchCurrent(
            "u1",
            "acc1",
            "代号鸢",
            "op1",
            patch(""""combat_stats":{"observed_attack":4001,"observed_hp":20245,"source":"scan"}"""),
        )

        assertEquals("valid", result.combatStats?.observedStatus)
        assertEquals(4001, result.combatStats?.observedAttack)
        assertEquals(20245, result.combatStats?.observedHp)
        assertEquals(4001, result.combatStats?.manualAttack)
        assertEquals(20245, result.combatStats?.manualHp)
        assertEquals("manual", result.combatStats?.displayMode?.attack)
        assertEquals("manual", result.combatStats?.displayMode?.hp)
        assertNotEquals("scan-input-v1", result.combatStats?.combatInputSignature)
    }

    @Test
    fun `scan keeps an explicitly submitted combat signature`() {
        stored = stored.copy(
            entries = mapOf(
                "op1" to baseEntry().copy(
                    combatStats = checkNotNull(baseEntry().combatStats).copy(
                        manualHp = 7777,
                        displayMode = OperatorCombatDisplayMode("auto", "auto"),
                    ),
                ),
            ),
        )
        val result = service.patchCurrent(
            "u1",
            "acc1",
            "代号鸢",
            "op1",
            patch(
                """"combat_stats":{"observed_attack":4001,"source":"scan","combat_input_signature":"collector-v2"}""",
            ),
        )

        assertEquals("valid", result.combatStats?.observedStatus)
        assertEquals("collector-v2", result.combatStats?.combatInputSignature)
        assertEquals(4001, result.combatStats?.manualAttack)
        assertEquals("manual", result.combatStats?.displayMode?.attack)
        assertEquals(7777, result.combatStats?.manualHp)
        assertEquals("auto", result.combatStats?.displayMode?.hp)
    }

    @Test
    fun `existing observation becomes stale when only a growth input changes`() {
        val result = service.patchCurrent("u1", "acc1", "代号鸢", "op1", patch(""""level":93"""))

        assertEquals("stale", result.combatStats?.observedStatus)
    }

    @Test
    fun `canonical combat signature is stable and covers every combat input`() {
        fun signature(
            level: Int = 10,
            elite: Int = 1,
            starLevel: Int = 3,
            attackOddity: Int = 100,
            firstType: String = "main1",
            firstName: String = "攻击力",
            firstLevel: Int = 10,
            reverseStones: Boolean = false,
            includeOddityMax: Boolean = false,
        ): String {
            val stones = listOf(
                "{\"type\":\"$firstType\",\"name\":\"$firstName\",\"level\":$firstLevel}",
                "{\"type\":\"assist1\",\"name\":\"生命值\",\"level\":20}",
            ).let { if (reverseStones) it.reversed() else it }
            val max = if (includeOddityMax) ",\"max\":500" else ""
            val request = patch(
                """
                "level":$level,
                "elite":$elite,
                "star_level":$starLevel,
                "star_stones":[${stones.joinToString()}],
                "combat_stats":{
                  "observed_attack":4001,
                  "source":"scan",
                  "oddities":{
                    "attack":{"current":$attackOddity$max},
                    "hp":{"current":0},
                    "special":{"current":15}
                  }
                }
                """.trimIndent(),
            )
            return checkNotNull(
                service.previewCurrentPatch("u1", "acc1", "代号鸢", "op1", request)
                    .after.combatStats?.combatInputSignature,
            )
        }

        val baseline = signature()
        assertEquals(baseline, signature(reverseStones = true, includeOddityMax = true))
        specialOddityName = "增伤值（新展示名）"
        assertEquals(baseline, signature())
        assertNotEquals(baseline, signature(level = 11))
        assertNotEquals(baseline, signature(elite = 2))
        assertNotEquals(baseline, signature(starLevel = 4))
        assertNotEquals(baseline, signature(attackOddity = 101))
        assertNotEquals(baseline, signature(firstType = "main2"))
        assertNotEquals(baseline, signature(firstName = "攻击力 II"))
        assertNotEquals(baseline, signature(firstLevel = 11))
    }

    @Test
    fun `patch rejects invalid star stone slots names and levels`() {
        val duplicate = patch(
            """"star_stones":[{"type":"main1","name":"攻击力","level":1},{"type":"main1","name":"生命值","level":1}]""",
        )
        val invalidSlot = patch(""""star_stones":[{"type":"main","name":"攻击力","level":1}]""")
        val invalidName = patch(""""star_stones":[{"type":"main1","name":" ","level":1}]""")
        val invalidLevel = patch(""""star_stones":[{"type":"main1","name":"攻击力","level":-1}]""")

        listOf(duplicate, invalidSlot, invalidName, invalidLevel).forEach { request ->
            val error = assertThrows(OperatorApiException::class.java) {
                service.patchCurrent("u1", "acc1", "代号鸢", "op1", request)
            }
            assertEquals("invalid_equipped_star_stones", error.code)
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.status)
        }
    }

    @Test
    fun `patch star stones uses revision and correction replay`() {
        val request = patch(
            """"star_stones":[{"type":"main1","name":"攻击力","level":60}]""",
        )
        val result = service.patchCurrent("u1", "acc1", "代号鸢", "op1", request)

        assertEquals(8, result.revision)
        val correction = checkNotNull(lastCorrection)
        assertEquals(setOf("star_stones"), correction.fields)
        assertEquals("main1", correction.starStones?.single()?.type)

        val stale = patch(""""star_stones":[],"expected_revision":7""", includeRevision = false)
        val error = assertThrows(OperatorApiException::class.java) {
            service.patchCurrent("u1", "acc1", "代号鸢", "op1", stale)
        }
        assertEquals("operator_revision_conflict", error.code)
    }

    @Test
    fun `patch materializes a generic entry into the requested game document`() {
        val generic = OperatorCurrent(
            userId = "u1",
            accountId = "acc1",
            game = "*",
            entries = mapOf("op1" to baseEntry()),
        )
        stored = stored.copy(entries = emptyMap())
        every { currentRepository.findByUserIdAndAccountIdAndGame("u1", "acc1", "*") } returns generic

        val result = service.patchCurrent(
            "u1",
            "acc1",
            "代号鸢",
            "op1",
            patch(""""star_stones":[{"type":"main1","name":"攻击力","level":60}]"""),
        )

        assertEquals("代号鸢", lastCasGame)
        assertEquals(8, result.revision)
        assertEquals("main1", stored.entries.getValue("op1").starStones.single().type)
        assertEquals("命盘二", result.discLoadouts[1].name)
        assertEquals(1000, result.combatStats?.observedAttack)
        assertEquals(7, generic.entries.getValue("op1").revision)
    }

    @Test
    fun `patch creates the requested game document when only generic current exists`() {
        val generic = OperatorCurrent(
            userId = "u1",
            accountId = "acc1",
            game = "universal",
            entries = mapOf("op1" to baseEntry()),
        )
        stored = stored.copy(entries = emptyMap())
        every { currentRepository.findByUserIdAndAccountIdAndGame("u1", "acc1", "代号鸢") } returns null
        every { currentRepository.findByUserIdAndAccountIdAndGame("u1", "acc1", "universal") } returns generic

        val result = service.patchCurrent(
            "u1",
            "acc1",
            "代号鸢",
            "op1",
            patch(""""star_stones":[{"type":"main1","name":"攻击力","level":60}]"""),
        )

        assertEquals("代号鸢", stored.game)
        assertEquals(8, result.revision)
        assertEquals("main1", stored.entries.getValue("op1").starStones.single().type)
        assertEquals("命盘二", result.discLoadouts[1].name)
        assertEquals(1000, result.combatStats?.observedAttack)
    }

    @Test
    fun `first patch creates a default entry when account has no current document`() {
        stored = stored.copy(entries = emptyMap())
        every { currentRepository.findByUserIdAndAccountIdAndGame("u1", "acc1", "代号鸢") } returns null
        every { currentRepository.findByUserIdAndAccountIdAndGame("u1", "acc1", "*") } returns null

        val result = service.patchCurrent(
            "u1",
            "acc1",
            "代号鸢",
            "op1",
            patch(
                """
                "level":0,
                "elite":0,
                "star_level":0,
                "disc_loadouts":[],
                "star_stones":[],
                "combat_stats":{
                  "manual_attack":null,
                  "manual_hp":null,
                  "display_mode":{"attack":"auto","hp":"manual"},
                  "oddities":{
                    "attack":{"current":0},
                    "hp":{"current":0},
                    "special":{"current":0}
                  }
                }
                """.trimIndent(),
                revision = 0,
            ),
        )

        assertEquals("代号鸢", stored.game)
        assertEquals(1, result.revision)
        assertEquals(0, result.level)
        assertEquals(0, result.elite)
        assertEquals(0, result.starLevel)
        assertEquals(emptyList<OperatorDiscLoadout>(), result.discLoadouts)
        assertEquals(emptyList<OperatorStarStone>(), result.starStones)
        assertEquals("auto", result.combatStats?.displayMode?.attack)
        assertEquals("manual", result.combatStats?.displayMode?.hp)
        assertEquals(0, result.combatStats?.oddities?.get("special")?.current)
    }

    @Test
    fun `full v3 completion removes outside entries and preserves retained supplemental fields`() {
        val retained = baseEntry()
        stored = stored.copy(
            entries = mapOf(
                "op1" to retained,
                "outside" to OperatorEntry(elite = 1, starLevel = 2, level = 30),
            ),
        )
        val effectiveAt = Instant.parse("2026-08-21T02:30:00Z")

        service.completeFullImport("u1", "acc1", "代号鸢", setOf("op1"), effectiveAt)

        assertEquals(setOf("op1"), stored.entries.keys)
        assertEquals(retained.discLoadouts, stored.entries.getValue("op1").discLoadouts)
        assertEquals(retained.combatStats, stored.entries.getValue("op1").combatStats)
        assertEquals(effectiveAt, stored.fullBaselineAt)
    }

    @Test
    fun `missing entry in existing game document is created only at revision zero`() {
        stored = stored.copy(entries = mapOf("other" to baseEntry()))
        every { catalogService.getOperator("op2") } returns catalog("op2", rarity, null)
        every { catalogService.getOperator("op3") } returns catalog("op3", rarity, null)

        val result = service.patchCurrent(
            "u1",
            "acc1",
            "代号鸢",
            "op2",
            patch(""""star_stones":[]""", revision = 0),
        )

        assertEquals(1, result.revision)
        assertEquals(2, stored.entries.size)
        assertEquals(7, stored.entries.getValue("other").revision)

        val conflict = assertThrows(OperatorApiException::class.java) {
            service.patchCurrent("u1", "acc1", "代号鸢", "op3", patch(""""star_stones":[]""", revision = 7))
        }
        assertEquals(HttpStatus.CONFLICT, conflict.status)
        assertEquals("operator_revision_conflict", conflict.code)
        assertNull(stored.entries["op3"])
    }

    @Test
    fun `display mode is locally merged, persisted, and does not stale observations`() {
        val first = service.patchCurrent(
            "u1",
            "acc1",
            "代号鸢",
            "op1",
            patch(""""combat_stats":{"display_mode":{"attack":"auto","hp":"manual"}}"""),
        )
        assertEquals("auto", first.combatStats?.displayMode?.attack)
        assertEquals("manual", first.combatStats?.displayMode?.hp)
        assertEquals(900, first.combatStats?.manualAttack)
        assertEquals("valid", first.combatStats?.observedStatus)

        val second = service.patchCurrent(
            "u1",
            "acc1",
            "代号鸢",
            "op1",
            patch(""""combat_stats":{"display_mode":{"attack":null}},"expected_revision":8""", includeRevision = false),
        )
        assertNull(second.combatStats?.displayMode?.attack)
        assertEquals("manual", second.combatStats?.displayMode?.hp)
        assertEquals(900, second.combatStats?.manualAttack)
        assertEquals("valid", second.combatStats?.observedStatus)
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
        assertEquals("auto", entry.combatStats?.displayMode?.attack)
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
            fields = setOf("disc_loadouts", "star_stones", "combat_stats"),
            discLoadouts = baseEntry().discLoadouts,
            starStones = baseEntry().starStones,
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
        assertEquals("main1", entry.starStones.single().type)
        assertEquals(1000, entry.combatStats?.observedAttack)
        assertEquals(1, entry.revision)
    }

    private fun patch(
        fields: String,
        includeRevision: Boolean = true,
        revision: Long = 7,
    ): com.fasterxml.jackson.databind.node.ObjectNode {
        val revisionJson = if (includeRevision) ",\"expected_revision\":$revision" else ""
        return mapper.readTree("{$fields$revisionJson,\"reason\":\"manual_correction\"}") as com.fasterxml.jackson.databind.node.ObjectNode
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
            displayMode = OperatorCombatDisplayMode("auto", "manual"),
            combatInputSignature = "scan-input-v1",
            oddities = mapOf("attack" to OperatorOddityValue(100)),
        ),
        revision = 7,
    )

    private fun catalog(id: String, rarity: Int, spOf: String?) = OperatorCatalogEntity(
        operatorId = id,
        name = id,
        rarity = rarity,
        specialOddityName = specialOddityName,
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
