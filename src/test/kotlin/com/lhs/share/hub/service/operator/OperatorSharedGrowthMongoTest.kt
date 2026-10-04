package com.lhs.share.hub.service.operator

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.hub.repository.OperatorCatalogRepository
import com.lhs.share.hub.repository.OperatorCorrectionRecordRepository
import com.lhs.share.hub.repository.OperatorCurrentRepository
import com.lhs.share.hub.repository.OperatorCurrentRepositoryImpl
import com.lhs.share.hub.repository.OperatorRecordRepository
import com.lhs.share.hub.repository.OperatorScanReviewRepository
import com.lhs.share.hub.repository.OperatorV3ImportRecordRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.OperatorCatalogEntity
import com.lhs.share.hub.repository.entity.OperatorCombatStats
import com.lhs.share.hub.repository.entity.OperatorCurrent
import com.lhs.share.hub.repository.entity.OperatorEntry
import com.lhs.share.hub.repository.entity.SubAccount
import com.lhs.share.hub.service.account.AccountEventService
import com.lhs.share.hub.service.star.StarStateService
import com.lhs.share.testinfra.TestMongo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/** Uses only owned disposable Mongo; does not load the application's configured services. */
@Tag("integration")
class OperatorSharedGrowthMongoTest {
    private val database = TestMongo.database("sp_growth")
    private val client = TestMongo.client()
    private val template = MongoTemplate(SimpleMongoClientDatabaseFactory(client, database))
    private val factory = MongoRepositoryFactory(template)
    private val current = factory.getRepository(
        OperatorCurrentRepository::class.java,
        RepositoryFragments.just(OperatorCurrentRepositoryImpl(template)),
    )
    private val corrections = factory.getRepository(OperatorCorrectionRecordRepository::class.java)
    private val records = factory.getRepository(OperatorRecordRepository::class.java)
    private val audits = factory.getRepository(OperatorV3ImportRecordRepository::class.java)
    private val accounts = mockk<SubAccountRepository>()
    private val catalog = mockk<OperatorCatalogService>()
    private val mapper = jacksonObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
    private val transactions = TransactionTemplate(MongoTransactionManager(template.mongoDatabaseFactory))
    private val service =
        OperatorService(
            accounts,
            current,
            records,
            mockk<OperatorCatalogRepository>(),
            catalog,
            transactions,
            corrections,
            mockk<StarStateService>(relaxed = true),
        )
    private val imports = OperatorV3ImportService(
        mapper, OperatorV3SchemaValidator(mapper), accounts, catalog, service, audits,
        mockk<AccountEventService>(relaxed = true), mockk<OperatorScanReviewRepository>(relaxed = true),
        transactionTemplate = transactions,
    )

    @BeforeEach
    fun setup() {
        every { accounts.findByUserIdAndAccountId("owner", "account") } returns
            SubAccount(userId = "owner", accountId = "account", name = "test", game = "如鸢")
        every { catalog.getOperator(any()) } answers {
            val id = firstArg<String>()
            OperatorCatalogEntity(
                operatorId = id, name = id, rarity = 5,
                prof = listOf("风"),
                subProf = listOf("shenji"),
                games = listOf("如鸢"),
                discs = emptyList(),
                starStones = emptyList(),
                catalogVersion = "test",
                spOf = if (id == "base") null else "base",
            )
        }
        every { catalog.spFormsOf("base") } returns listOf("movie", "sibling")
        listOf("operator_current", "operator_correction_records", "operator_records", "operator_v3_import_records").forEach {
            template.createCollection(it)
        }
        resetCurrent()
    }

    @AfterEach
    fun cleanup() {
        TestMongo.dropDatabase(client, database)
        client.close()
    }

    private fun resetCurrent() {
        current.save(
            OperatorCurrent(
                "state",
                "owner",
                "account",
                "如鸢",
                entries = mapOf(
                    "base" to
                        OperatorEntry(
                            16,
                            31,
                            90,
                            revision = 7,
                            combatStats = OperatorCombatStats(observedAttack = 100, observedStatus = "valid"),
                        ),
                    "movie" to OperatorEntry(16, 2, 90, revision = 7),
                ),
            ),
        )
    }

    private fun document(id: String, reverse: Boolean = false, conflict: Boolean = false): ObjectNode {
        val rows = listOf(
            """{"operator_id":"base","level":100,"elite":17,"star_level":31,"section_status":{"basic":"ready","huaji":"ready"}}""",
            """{"operator_id":"movie","level":${if (conflict) 95 else 100},"elite":17,"star_level":3,
              "section_status":{"basic":"ready","huaji":"ready"}}""",
        ).let { if (reverse) it.reversed() else it }
        return mapper.readTree(
            """{"account_mapping":{"local":"account"},"document":{
          "format":"myshare-operator-exchange","version":3,"exported_at":"2026-10-05T00:00:00Z",
          "producer":{"platform":"test","version":"1"},"accounts":[{"id":"local","name":"测试账号","game_scope":"如鸢"}],
          "records":[{"account_id":"local","record_id":"$id","record_type":"operator_snapshot","game":"如鸢",
          "effective_at":"2026-10-05T00:00:00Z","snapshot_scope":"listed","source_kind":"scan","entries":[${rows.joinToString(",")}]}]}}""",
        ) as ObjectNode
    }

    @Test
    fun `consistent multi-form v3 commits in either order with final revisions and independent stars`() {
        for (reverse in listOf(false, true)) {
            resetCurrent()
            val result = imports.commitBrowser("owner", document("batch-$reverse", reverse))
            val entries = current.findById("state").orElseThrow().entries
            assertEquals(setOf(100), entries.values.map { it.level }.toSet())
            assertEquals(setOf(17), entries.values.map { it.elite }.toSet())
            assertEquals(31, entries.getValue("base").starLevel)
            assertEquals(3, entries.getValue("movie").starLevel)
            assertEquals(0, entries.getValue("sibling").starLevel)
            assertEquals("stale", entries.getValue("base").combatStats?.observedStatus)
            result.items.forEach { assertEquals(entries.getValue(it.operatorId).revision, it.revision) }
        }
    }

    @Test
    fun `conflicting group rejects preview and commit without current corrections or audit writes`() {
        val before = current.findById("state").orElseThrow()
        assertEquals(2, imports.previewBrowser("owner", document("conflict", conflict = true)).rejected)
        assertEquals(2, imports.commitBrowser("owner", document("conflict", conflict = true)).rejected)
        assertEquals(before, current.findById("state").orElseThrow())
        assertEquals(0L, corrections.count())
        assertEquals(0L, audits.count())
    }

    @Test
    fun `related CAS conflict cannot overwrite another form and transaction rollback removes all group writes`() {
        val entries = current.findById("state").orElseThrow().entries
        val stale = entries.mapValues { (_, value) -> value.copy(level = 100, revision = 8) }
        current.compareAndSetEntries(
            "owner",
            "account",
            "如鸢",
            "movie",
            7,
            mapOf(
                "movie" to entries.getValue("movie").copy(starLevel = 3, revision = 8),
            ),
            Instant.now(),
        )
        assertNull(current.compareAndSetEntries("owner", "account", "如鸢", "base", 7, stale, Instant.now()))
        resetCurrent()
        assertThrows(IllegalStateException::class.java) {
            transactions.executeWithoutResult {
                service.patchCurrent(
                    "owner",
                    "account",
                    "如鸢",
                    "base",
                    mapper.readTree("""{"level":100,"expected_revision":7,"reason":"manual_correction"}""") as ObjectNode,
                )
                error("force rollback")
            }
        }
        assertEquals(entries, current.findById("state").orElseThrow().entries)
        assertEquals(0L, corrections.count())
    }
}
