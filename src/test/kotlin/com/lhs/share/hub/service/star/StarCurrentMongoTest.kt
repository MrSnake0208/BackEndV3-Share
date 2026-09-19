package com.lhs.share.hub.service.star

import com.lhs.share.hub.controller.star.request.StarLoadoutCurrentRequest
import com.lhs.share.hub.controller.star.request.StarLoadoutPresetCurrentRequest
import com.lhs.share.hub.controller.star.request.StarLoadoutPresetRequest
import com.lhs.share.hub.controller.star.request.StarWorkspaceBagRequest
import com.lhs.share.hub.controller.star.request.StarWorkspaceCurrentRequest
import com.lhs.share.hub.controller.star.request.StarWorkspaceExperienceRequest
import com.lhs.share.hub.repository.StarInventoryCurrentRepository
import com.lhs.share.hub.repository.StarLoadoutCurrentRepository
import com.lhs.share.hub.repository.StarLoadoutCurrentRepositoryImpl
import com.lhs.share.hub.repository.StarLoadoutPresetCurrentRepository
import com.lhs.share.hub.repository.StarLoadoutPresetCurrentRepositoryImpl
import com.lhs.share.hub.repository.StarWorkspaceCurrentRepository
import com.lhs.share.hub.repository.StarWorkspaceCurrentRepositoryImpl
import com.lhs.share.hub.repository.entity.StarInventoryCurrent
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments
import org.springframework.transaction.support.TransactionOperations
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/** Uses an isolated disposable database and is skipped unless PLANNER_TEST_MONGO_URI is configured. */
@EnabledIfEnvironmentVariable(named = "PLANNER_TEST_MONGO_URI", matches = ".+")
class StarCurrentMongoTest {
    private val database = "star_current_test_${UUID.randomUUID().toString().replace("-", "")}"
    private val client = MongoClients.create(System.getenv("PLANNER_TEST_MONGO_URI"))
    private val template = MongoTemplate(SimpleMongoClientDatabaseFactory(client, database))
    private val factory = MongoRepositoryFactory(template)
    private val workspaceRepository = factory.getRepository(
        StarWorkspaceCurrentRepository::class.java,
        RepositoryFragments.just(StarWorkspaceCurrentRepositoryImpl(template)),
    )
    private val loadoutRepository = factory.getRepository(
        StarLoadoutCurrentRepository::class.java,
        RepositoryFragments.just(StarLoadoutCurrentRepositoryImpl(template)),
    )
    private val presetRepository = factory.getRepository(
        StarLoadoutPresetCurrentRepository::class.java,
        RepositoryFragments.just(StarLoadoutPresetCurrentRepositoryImpl(template)),
    )
    private val accounts = mockk<SubAccountService>()
    private val inventoryRepository = mockk<StarInventoryCurrentRepository>()
    private val workspaceService = StarWorkspaceService(
        workspaceRepository,
        inventoryRepository,
        accounts,
        TransactionOperations.withoutTransaction(),
    )
    private val loadoutService = StarLoadoutService(
        loadoutRepository,
        inventoryRepository,
        accounts,
        TransactionOperations.withoutTransaction(),
    )
    private val presetService = StarLoadoutPresetService(presetRepository)

    @BeforeEach
    fun setUp() {
        every { accounts.requireAccount(any(), any()) } returns mockk()
        val inventory = StarInventoryCurrent(
            id = "owner:account",
            userId = "owner",
            accountId = "account",
            effectiveAt = Instant.now(),
            entries = listOf(StarInventoryEntry("star.001", "main", "天府", "orange", 60)),
            contentHash = "test",
        )
        every { inventoryRepository.findByUserIdAndAccountId("owner", any()) } returns inventory
        every { inventoryRepository.touchReferenceBarrier("owner", any()) } returns inventory
    }

    @AfterEach
    fun cleanUp() {
        client.getDatabase(database).drop()
        client.close()
    }

    @Test
    fun `typed persistence round trips dotted IDs without changing them`() {
        val workspace = workspaceRepository.replace(
            "owner",
            "account",
            0,
            listOf(StarPlanTarget("star.001", 60)),
            StarWorkspaceBag(1, 2),
            StarWorkspaceExperience(1, 2, 3),
            Instant.now(),
        )
        val loadout = loadoutRepository.replace(
            "owner",
            "account",
            0,
            listOf(StarOperatorLoadout("operator.001", StarLoadoutSlots(main1 = "star.001"))),
            Instant.now(),
        )

        assertEquals("star.001", workspace!!.planTargets.single().instanceId)
        assertEquals("operator.001", loadout!!.loadouts.single().operatorId)
        assertEquals("star.001", loadout.loadouts.single().slots.main1)
    }

    @Test
    fun `concurrent first workspace and loadout creates have one winner and one stable conflict`() {
        assertConcurrentFirstCreate(
            action = {
                workspaceService.putCurrent(
                    "owner",
                    "workspace-account",
                    StarWorkspaceCurrentRequest(
                        0,
                        mapOf("star.001" to 60),
                        StarWorkspaceBagRequest(1, 2),
                        StarWorkspaceExperienceRequest(1, 2, 3),
                    ),
                )
            },
            expectedCode = "star_workspace_revision_conflict",
            count = { template.getCollection("star_workspace_current").countDocuments() },
        )
        assertConcurrentFirstCreate(
            action = {
                loadoutService.putCurrent(
                    "owner",
                    "account",
                    StarLoadoutCurrentRequest(
                        0,
                        mapOf(
                            "operator.001" to mapOf(
                                "main1" to "star.001",
                                "main2" to null,
                                "main3" to null,
                                "support1" to null,
                                "support2" to null,
                                "support3" to null,
                            ),
                        ),
                    ),
                )
            },
            expectedCode = "star_loadout_revision_conflict",
            count = { template.getCollection("star_loadout_current").countDocuments() },
        )
        assertConcurrentFirstCreate(
            action = {
                presetService.putCurrent(
                    "owner",
                    StarLoadoutPresetCurrentRequest(
                        0,
                        listOf(StarLoadoutPresetRequest("main-1", "预设1", listOf("天府"))),
                        emptyList(),
                    ),
                )
            },
            expectedCode = "star_loadout_preset_revision_conflict",
            count = { template.getCollection("star_loadout_preset_current").countDocuments() },
        )
    }

    private fun assertConcurrentFirstCreate(action: () -> Any, expectedCode: String, count: () -> Long) {
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = executor.invokeAll(
                (1..2).map {
                    Callable {
                        barrier.await()
                        runCatching(action)
                    }
                },
            ).map { it.get() }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(expectedCode, (results.single { it.isFailure }.exceptionOrNull() as InventoryApiException).code)
            assertEquals(1, count())
        } finally {
            executor.shutdownNow()
        }
    }
}
