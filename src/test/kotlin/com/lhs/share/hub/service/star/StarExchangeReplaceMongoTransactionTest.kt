package com.lhs.share.hub.service.star

import com.lhs.share.hub.controller.star.request.StarExchangeReplaceInventoryRequest
import com.lhs.share.hub.controller.star.request.StarExchangeReplaceRequest
import com.lhs.share.hub.controller.star.request.StarInventoryEntryRequest
import com.lhs.share.hub.controller.star.request.StarInventorySnapshotRequest
import com.lhs.share.hub.controller.star.request.StarWorkspaceBagRequest
import com.lhs.share.hub.controller.star.request.StarWorkspaceCurrentRequest
import com.lhs.share.hub.controller.star.request.StarWorkspaceExperienceRequest
import com.lhs.share.hub.repository.StarInventoryCurrentRepository
import com.lhs.share.hub.repository.StarInventoryCurrentRepositoryImpl
import com.lhs.share.hub.repository.StarLoadoutCurrentRepository
import com.lhs.share.hub.repository.StarLoadoutCurrentRepositoryImpl
import com.lhs.share.hub.repository.StarWorkspaceCurrentRepository
import com.lhs.share.hub.repository.StarWorkspaceCurrentRepositoryImpl
import com.lhs.share.hub.repository.entity.StarInventoryEntry
import com.lhs.share.hub.repository.entity.StarLoadoutSlots
import com.lhs.share.hub.repository.entity.StarOperatorLoadout
import com.lhs.share.hub.repository.entity.StarPlanTarget
import com.lhs.share.hub.repository.entity.StarWorkspaceBag
import com.lhs.share.hub.repository.entity.StarWorkspaceExperience
import com.lhs.share.hub.service.account.SubAccountService
import com.lhs.share.hub.service.inventory.InventoryApiException
import com.mongodb.client.MongoClients
import io.mockk.every
import io.mockk.mockk
import org.bson.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/** Requires a replica set or sharded MongoDB because replacement writes use a real transaction. */
@EnabledIfEnvironmentVariable(named = "PLANNER_TEST_MONGO_URI", matches = ".+")
class StarExchangeReplaceMongoTransactionTest {
    private val database = "star_exchange_replace_test_${UUID.randomUUID().toString().replace("-", "")}"
    private val client = MongoClients.create(System.getenv("PLANNER_TEST_MONGO_URI"))
    private val databaseFactory = SimpleMongoClientDatabaseFactory(client, database)
    private val template = MongoTemplate(databaseFactory)
    private val repositoryFactory = MongoRepositoryFactory(template)
    private val inventoryRepository = repositoryFactory.getRepository(
        StarInventoryCurrentRepository::class.java,
        RepositoryFragments.just(StarInventoryCurrentRepositoryImpl(template)),
    )
    private val workspaceRepository = repositoryFactory.getRepository(
        StarWorkspaceCurrentRepository::class.java,
        RepositoryFragments.just(StarWorkspaceCurrentRepositoryImpl(template)),
    )
    private val loadoutRepository = repositoryFactory.getRepository(
        StarLoadoutCurrentRepository::class.java,
        RepositoryFragments.just(StarLoadoutCurrentRepositoryImpl(template)),
    )
    private val accounts = mockk<SubAccountService>()
    private val transactions = TransactionTemplate(org.springframework.data.mongodb.MongoTransactionManager(databaseFactory))
    private val inventoryService = StarInventoryService(inventoryRepository, workspaceRepository, loadoutRepository, accounts, transactions)
    private val workspaceService = StarWorkspaceService(workspaceRepository, inventoryRepository, accounts)
    private val loadoutService = StarLoadoutService(loadoutRepository, inventoryRepository, accounts)
    private val service = replacementService(loadoutService)

    @BeforeEach
    fun setUp() {
        every { accounts.requireAccount(any(), any()) } returns mockk()
    }

    @AfterEach
    fun cleanUp() {
        client.getDatabase(database).drop()
        client.close()
    }

    @Test
    fun `replacement is account scoped clears loadout retains preset and accepts duplicate names by instance id`() {
        seed("target")
        seed("other")
        template.getCollection("star_loadout_preset_current")
            .insertOne(Document("_id", "owner").append("marker", "must-remain"))

        val result = service.replace("owner", replacementRequest("target"))

        assertEquals(listOf("new-main-1", "new-main-2"), result.inventory.entries.map { it.instanceId })
        assertEquals(listOf("天府", "天府"), result.inventory.entries.map { it.name })
        assertEquals(mapOf("new-main-2" to 60), result.workspace.planTargets)
        assertEquals(emptyMap<String, Map<String, String?>>(), result.loadout.loadouts)
        assertEquals(2, result.loadout.revision)
        assertEquals(listOf("old-main"), inventoryRepository.findByUserIdAndAccountId("owner", "other")!!.entries.map { it.instanceId })
        assertEquals(mapOf("old-main" to 60), workspaceRepository.findByUserIdAndAccountId("owner", "other")!!
            .planTargets.associate { it.instanceId to it.targetLevel })
        assertEquals("old-main", loadoutRepository.findByUserIdAndAccountId("owner", "other")!!
            .loadouts.single().slots.main1)
        assertEquals("must-remain", template.getCollection("star_loadout_preset_current")
            .find(Document("_id", "owner")).first()!!["marker"])
    }

    @Test
    fun `invalid workspace reference and stale revision leave all three account documents unchanged`() {
        seed("target")
        val before = state("target")

        val invalid = assertThrows(InventoryApiException::class.java) {
            service.replace("owner", replacementRequest("target", targetInstanceId = "not-in-new-inventory"))
        }
        assertEquals("star_exchange_invalid_workspace_reference", invalid.code)
        assertEquals(before, state("target"))

        val stale = assertThrows(InventoryApiException::class.java) {
            service.replace("owner", replacementRequest("target", inventoryRevision = 0))
        }
        assertEquals("star_inventory_revision_conflict", stale.code)
        assertEquals(before, state("target"))
    }

    @Test
    fun `loadout write failure rolls inventory workspace and loadout back together`() {
        seed("target")
        val before = state("target")
        val failingLoadoutService = mockk<StarLoadoutService>()
        every { failingLoadoutService.clearForReplacement(any(), any(), any()) } throws
            DataAccessResourceFailureException("simulated loadout write failure")
        val failingService = replacementService(failingLoadoutService)

        assertThrows(DataAccessResourceFailureException::class.java) {
            failingService.replace("owner", replacementRequest("target"))
        }

        assertEquals(before, state("target"))
    }

    @Test
    fun `ordinary inventory replacement prunes stale workspace and loadout references in one transaction`() {
        seed("target")

        val saved = inventoryService.putCurrent(
            "owner",
            "target",
            StarInventorySnapshotRequest(
                "2026-09-17T01:00:00Z",
                listOf(StarInventoryEntryRequest("new-main", "main", "天府", "orange", 60)),
            ),
        )

        assertEquals(listOf("new-main"), saved.entries.map { it.instanceId })
        assertEquals(emptyList<StarPlanTarget>(), workspaceRepository.findByUserIdAndAccountId("owner", "target")!!.planTargets)
        assertEquals(null, loadoutRepository.findByUserIdAndAccountId("owner", "target")!!.loadouts.single().slots.main1)
    }

    @Test
    fun `ordinary inventory prune loadout failure rolls all three documents back`() {
        seed("target")
        val before = state("target")
        val failingLoadoutRepository = mockk<StarLoadoutCurrentRepository>()
        every { failingLoadoutRepository.findByUserIdAndAccountId(any(), any()) } answers {
            loadoutRepository.findByUserIdAndAccountId(firstArg(), secondArg())
        }
        every { failingLoadoutRepository.replace(any(), any(), any(), any(), any()) } answers {
            loadoutRepository.replace(
                firstArg(),
                secondArg(),
                args[2] as Long,
                args[3] as List<StarOperatorLoadout>,
                args[4] as Instant,
            )
            throw DataAccessResourceFailureException("simulated loadout prune failure")
        }
        val failingInventoryService = StarInventoryService(
            inventoryRepository,
            workspaceRepository,
            failingLoadoutRepository,
            accounts,
            transactions,
        )

        assertThrows(DataAccessResourceFailureException::class.java) {
            failingInventoryService.putCurrent(
                "owner",
                "target",
                StarInventorySnapshotRequest(
                    "2026-09-17T01:00:00Z",
                    listOf(StarInventoryEntryRequest("new-main", "main", "天府", "orange", 60)),
                ),
            )
        }

        assertEquals(before, state("target"))
    }

    private fun replacementService(loadout: StarLoadoutService) = StarExchangeReplaceService(
        accounts,
        inventoryService,
        workspaceService,
        loadout,
        inventoryRepository,
        workspaceRepository,
        transactions,
    )

    private fun seed(accountId: String) {
        inventoryRepository.replace(
            "owner",
            accountId,
            Instant.parse("2026-09-17T00:00:00Z"),
            listOf(StarInventoryEntry("old-main", "main", "旧天府", "orange", 60)),
            "old-hash",
            0,
            Instant.parse("2026-09-17T00:00:00Z"),
            Instant.parse("2026-09-17T00:00:00Z"),
        )
        workspaceRepository.replace(
            "owner",
            accountId,
            0,
            listOf(StarPlanTarget("old-main", 60)),
            StarWorkspaceBag(1, 100),
            StarWorkspaceExperience(1, 2, 3),
            Instant.parse("2026-09-17T00:00:00Z"),
        )
        loadoutRepository.replace(
            "owner",
            accountId,
            0,
            listOf(StarOperatorLoadout("operator", StarLoadoutSlots(main1 = "old-main"))),
            Instant.parse("2026-09-17T00:00:00Z"),
        )
    }

    private fun state(accountId: String) = Triple(
        inventoryRepository.findByUserIdAndAccountId("owner", accountId),
        workspaceRepository.findByUserIdAndAccountId("owner", accountId),
        loadoutRepository.findByUserIdAndAccountId("owner", accountId),
    )

    private fun replacementRequest(
        accountId: String,
        inventoryRevision: Long = 1,
        targetInstanceId: String = "new-main-2",
    ) = StarExchangeReplaceRequest(
        accountId = accountId,
        inventory = StarExchangeReplaceInventoryRequest(
            inventoryRevision,
            "2026-09-17T01:00:00Z",
            listOf(
                StarInventoryEntryRequest("new-main-1", "main", "天府", "orange", 60),
                StarInventoryEntryRequest("new-main-2", "main", "天府", "orange", 1),
            ),
        ),
        workspace = StarWorkspaceCurrentRequest(
            1,
            mapOf(targetInstanceId to 60),
            StarWorkspaceBagRequest(2, 100),
            StarWorkspaceExperienceRequest(4, 5, 6),
        ),
    )
}
