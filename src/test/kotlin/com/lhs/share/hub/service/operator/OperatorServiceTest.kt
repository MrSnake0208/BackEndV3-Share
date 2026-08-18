package com.lhs.share.hub.service.operator

import com.lhs.share.hub.controller.inventory.request.ProducerDto
import com.lhs.share.hub.controller.operator.request.OperatorEntryRequest
import com.lhs.share.hub.controller.operator.request.OperatorExchangeAccountDto
import com.lhs.share.hub.controller.operator.request.OperatorImportRequest
import com.lhs.share.hub.controller.operator.request.OperatorRecordRequest
import com.lhs.share.hub.controller.operator.request.OperatorStarStoneRequest
import com.lhs.share.hub.repository.OperatorAccountRepository
import com.lhs.share.hub.repository.OperatorCatalogRepository
import com.lhs.share.hub.repository.OperatorCurrentRepository
import com.lhs.share.hub.repository.OperatorRecordRepository
import com.lhs.share.hub.repository.entity.OperatorAccount
import com.lhs.share.hub.repository.entity.OperatorCatalogEntity
import com.lhs.share.hub.repository.entity.OperatorCurrent
import com.lhs.share.hub.repository.entity.OperatorRecord
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate

class OperatorServiceTest {
    private val accountRepository = mockk<OperatorAccountRepository>()
    private val currentRepository = mockk<OperatorCurrentRepository>()
    private val recordRepository = mockk<OperatorRecordRepository>()
    private val catalogRepository = mockk<OperatorCatalogRepository>()
    private val catalogService = mockk<OperatorCatalogService>()
    private val transactionTemplate = TransactionTemplate(object : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
        override fun commit(status: TransactionStatus) = Unit
        override fun rollback(status: TransactionStatus) = Unit
    })
    private val service = OperatorService(
        accountRepository,
        currentRepository,
        recordRepository,
        catalogRepository,
        catalogService,
        transactionTemplate,
    )

    private fun catalog(spOf: String? = null) = OperatorCatalogEntity(
        operatorId = "op1",
        name = "密探",
        rarity = 5,
        prof = emptyList(),
        subProf = emptyList(),
        games = listOf("如鸢", "代号鸢"),
        discs = emptyList(),
        starStones = emptyList(),
        spOf = spOf,
        catalogVersion = "2026-08-16",
    )

    private fun importRequest(starStones: List<OperatorStarStoneRequest>): OperatorImportRequest = OperatorImportRequest(
        format = "myshare-operator-exchange",
        version = 2,
        exportedAt = "2026-08-16T10:00:00+08:00",
        producer = ProducerDto(platform = "test-client", version = "1.0.0"),
        accounts = listOf(OperatorExchangeAccountDto(id = "acc1")),
        records = listOf(
            OperatorRecordRequest(
                accountId = "acc1",
                recordId = "rec1",
                recordType = "operator_snapshot",
                game = null,
                effectiveAt = "2026-08-16T10:00:00+08:00",
                snapshotScope = "full",
                entries = listOf(
                    OperatorEntryRequest(
                        id = "op1",
                        elite = 0,
                        starLevel = 0,
                        level = 1,
                        starStones = starStones,
                    ),
                ),
            ),
        ),
    )

    private fun setUpHappyPath() {
        every { accountRepository.findAllByUserIdAndAccountIdIn(any(), any()) } returns
            listOf(OperatorAccount(userId = "u1", accountId = "acc1", name = "账号"))
        every { catalogService.getOperator("op1") } returns catalog()
        every { recordRepository.findByUserIdAndAccountIdAndRecordId(any(), any(), any()) } returns null
        every { currentRepository.findByUserIdAndAccountIdAndGame(any(), any(), any()) } returns null
        every { recordRepository.save(any()) } answers { firstArg<OperatorRecord>() }
        every { currentRepository.save(any()) } answers { firstArg<OperatorCurrent>() }
    }

    @Test
    fun `import accepts three main and three assist star stone slots`() {
        setUpHappyPath()
        val starStones = listOf(
            OperatorStarStoneRequest("定远", "main1", 2),
            OperatorStarStoneRequest("昭华", "main2", 1),
            OperatorStarStoneRequest("玄圭", "main3", 1),
            OperatorStarStoneRequest("白珩", "assist1", 2),
            OperatorStarStoneRequest("苍珮", "assist2", 1),
            OperatorStarStoneRequest("青圭", "assist3", 1),
        )
        val result = service.import("u1", importRequest(starStones))
        assertEquals(1, result.accepted)
        assertEquals(0, result.duplicates)
    }

    @Test
    fun `legacy main and assist star stone types remain valid`() {
        setUpHappyPath()
        val starStones = listOf(
            OperatorStarStoneRequest("定远", "main", 2),
            OperatorStarStoneRequest("白珩", "assist", 1),
        )
        val result = service.import("u1", importRequest(starStones))
        assertEquals(1, result.accepted)
    }

    @Test
    fun `duplicate star stone type within a record is still rejected`() {
        setUpHappyPath()
        val starStones = listOf(
            OperatorStarStoneRequest("定远", "main1", 2),
            OperatorStarStoneRequest("昭华", "main1", 1),
        )
        val e = assertThrows(OperatorApiException::class.java) { service.import("u1", importRequest(starStones)) }
        assertEquals("invalid_star_stone", e.code)
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.status)
    }

    // ---------- SP 形态校验（本体必须同快照 + 等级/修为一致） ----------

    private fun entry(id: String, level: Int, elite: Int = 0, starLevel: Int = 0) =
        OperatorEntryRequest(id = id, elite = elite, starLevel = starLevel, level = level)

    private fun record(entries: List<OperatorEntryRequest>, recordId: String = "rec1", game: String? = null) = OperatorRecordRequest(
        accountId = "acc1",
        recordId = recordId,
        recordType = "operator_snapshot",
        game = game,
        effectiveAt = "2026-08-16T10:00:00+08:00",
        snapshotScope = "full",
        entries = entries,
    )

    private fun importRequestWithRecords(records: List<OperatorRecordRequest>): OperatorImportRequest = OperatorImportRequest(
        format = "myshare-operator-exchange",
        version = 2,
        exportedAt = "2026-08-16T10:00:00+08:00",
        producer = ProducerDto(platform = "test-client", version = "1.0.0"),
        accounts = listOf(OperatorExchangeAccountDto(id = "acc1")),
        records = records,
    )

    private fun catalog(id: String, spOf: String?) = OperatorCatalogEntity(
        operatorId = id,
        name = id,
        rarity = 5,
        prof = emptyList(),
        subProf = emptyList(),
        games = listOf("如鸢", "代号鸢"),
        discs = emptyList(),
        starStones = emptyList(),
        spOf = spOf,
        catalogVersion = "2026-08-16",
    )

    /** 本体 + SP 都在目录里存在的常规 SP mock */
    private fun setUpSpRelation(baseId: String, spId: String) {
        every { accountRepository.findAllByUserIdAndAccountIdIn(any(), any()) } returns
            listOf(OperatorAccount(userId = "u1", accountId = "acc1", name = "账号"))
        every { recordRepository.findByUserIdAndAccountIdAndRecordId(any(), any(), any()) } returns null
        every { currentRepository.findByUserIdAndAccountIdAndGame(any(), any(), any()) } returns null
        every { recordRepository.save(any()) } answers { firstArg<OperatorRecord>() }
        every { currentRepository.save(any()) } answers { firstArg<OperatorCurrent>() }
        every { catalogService.getOperator(baseId) } returns catalog(baseId, null)
        every { catalogService.getOperator(spId) } returns catalog(spId, baseId)
    }

    @Test
    fun `SP without its base in the same snapshot is rejected`() {
        setUpSpRelation(baseId = "op2", spId = "op1")
        val e = assertThrows(OperatorApiException::class.java) {
            service.import("u1", importRequestWithRecords(listOf(record(listOf(entry("op1", 10, 2))))))
        }
        assertEquals("sp_missing_base", e.code)
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.status)
    }

    @Test
    fun `SP with base but mismatched level is rejected`() {
        setUpSpRelation(baseId = "op2", spId = "op1")
        val e = assertThrows(OperatorApiException::class.java) {
            service.import("u1", importRequestWithRecords(listOf(record(listOf(entry("op2", 20, 1), entry("op1", 10, 1))))))
        }
        assertEquals("sp_build_mismatch", e.code)
    }

    @Test
    fun `SP with base but mismatched elite cultivation is rejected`() {
        setUpSpRelation(baseId = "op2", spId = "op1")
        // 等级一致、修为(化极)不一致 => 拒绝；星级不同不影响
        val e = assertThrows(OperatorApiException::class.java) {
            service.import(
                "u1",
                importRequestWithRecords(listOf(record(listOf(entry("op2", 10, 1, starLevel = 5), entry("op1", 10, 2, starLevel = 1))))),
            )
        }
        assertEquals("sp_build_mismatch", e.code)
    }

    @Test
    fun `SP matching base level and elite is accepted regardless of star level`() {
        setUpSpRelation(baseId = "op2", spId = "op1")
        val result = service.import(
            "u1",
            importRequestWithRecords(listOf(record(listOf(entry("op2", 10, 2, starLevel = 5), entry("op1", 10, 2, starLevel = 0))))),
        )
        assertEquals(1, result.accepted)
        assertEquals(0, result.duplicates)
    }

    @Test
    fun `SP and base split across records of the same snapshot are accepted`() {
        setUpSpRelation(baseId = "op2", spId = "op1")
        val result = service.import(
            "u1",
            importRequestWithRecords(
                listOf(
                    record(listOf(entry("op2", 10, 2)), recordId = "rec1"),
                    record(listOf(entry("op1", 10, 2)), recordId = "rec2"),
                ),
            ),
        )
        assertEquals(2, result.accepted)
    }

    @Test
    fun `base in a different game does not satisfy SP in this game`() {
        setUpSpRelation(baseId = "op2", spId = "op1")
        val e = assertThrows(OperatorApiException::class.java) {
            service.import(
                "u1",
                importRequestWithRecords(
                    listOf(
                        record(listOf(entry("op2", 10, 2)), game = "如鸢", recordId = "rec1"),
                        record(listOf(entry("op1", 10, 2)), game = "代号鸢", recordId = "rec2"),
                    ),
                ),
            )
        }
        assertEquals("sp_missing_base", e.code)
    }
}
