package com.lhs.share.hub.service.star

import com.lhs.share.hub.controller.star.request.StarExchangeReplaceInventoryRequest
import com.lhs.share.hub.controller.star.request.StarExchangeReplaceRequest
import com.lhs.share.hub.controller.star.request.StarInventoryEntryRequest
import com.lhs.share.hub.controller.star.request.StarWorkspaceBagRequest
import com.lhs.share.hub.controller.star.request.StarWorkspaceCurrentRequest
import com.lhs.share.hub.controller.star.request.StarWorkspaceExperienceRequest
import com.lhs.share.hub.controller.star.response.StarLoadoutCurrentResponse
import com.lhs.share.hub.repository.StarInventoryCurrentRepository
import com.lhs.share.hub.repository.StarLoadoutCurrentRepository
import com.lhs.share.hub.repository.StarWorkspaceCurrentRepository
import com.lhs.share.hub.repository.entity.StarInventoryCurrent
import com.lhs.share.hub.repository.entity.StarInventoryEntry
import com.lhs.share.hub.repository.entity.StarWorkspaceBag
import com.lhs.share.hub.repository.entity.StarWorkspaceCurrent
import com.lhs.share.hub.repository.entity.StarWorkspaceExperience
import com.lhs.share.hub.repository.entity.StarPlanTarget
import com.lhs.share.hub.service.account.SubAccountService
import com.lhs.share.hub.service.inventory.InventoryApiException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DuplicateKeyException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

class StarExchangeReplaceServiceTest {
    private val accounts = mockk<SubAccountService>()
    private val inventoryRepository = mockk<StarInventoryCurrentRepository>()
    private val workspaceRepository = mockk<StarWorkspaceCurrentRepository>()
    private val loadoutRepository = mockk<StarLoadoutCurrentRepository>()
    private val loadoutService = mockk<StarLoadoutService>()
    private val transactions = mockk<TransactionTemplate>()
    private val inventoryService = StarInventoryService(inventoryRepository, workspaceRepository, loadoutRepository, accounts, transactions)
    private val workspaceService = StarWorkspaceService(workspaceRepository, inventoryRepository, accounts)
    private val service = StarExchangeReplaceService(
        accounts,
        inventoryService,
        workspaceService,
        loadoutService,
        inventoryRepository,
        workspaceRepository,
        transactions,
    )

    @BeforeEach
    fun setUp() {
        every { accounts.requireAccount(any(), any()) } returns mockk()
        every { transactions.execute<Any>(any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (firstArg<TransactionCallback<Any>>()).doInTransaction(SimpleTransactionStatus())
        }
    }

    @Test
    fun `replacement writes the new snapshots and clears only the target account loadout`() {
        every {
            inventoryRepository.replace("owner", "target", any(), any(), any(), 4, any(), any())
        } returns inventory("target", 5, listOf(entry("new-main-1"), entry("new-main-2")))
        every {
            workspaceRepository.replace("owner", "target", 7, any(), any(), any(), any())
        } returns workspace("target", 8, listOf(StarPlanTarget("new-main-2", 60)))
        every { loadoutService.clearForReplacement("owner", "target", any()) } returns
            StarLoadoutCurrentResponse("target", 3, emptyMap(), Instant.parse("2026-09-17T00:00:00Z"))

        val result = service.replace("owner", request(inventoryRevision = 4, workspaceRevision = 7))

        assertEquals(listOf("new-main-1", "new-main-2"), result.inventory.entries.map { it.instanceId })
        assertEquals(60, result.workspace.planTargets["new-main-2"])
        assertEquals(emptyMap<String, Map<String, String?>>(), result.loadout.loadouts)
        verify(exactly = 1) { accounts.requireAccount("owner", "target") }
        verify(exactly = 1) { inventoryRepository.replace("owner", "target", any(), any(), any(), 4, any(), any()) }
        verify(exactly = 1) { workspaceRepository.replace("owner", "target", 7, any(), any(), any(), any()) }
        verify(exactly = 1) { loadoutService.clearForReplacement("owner", "target", any()) }
        verify(exactly = 0) { inventoryRepository.replace("owner", "other", any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { workspaceRepository.replace("owner", "other", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unknown workspace instance rejects before every replacement write`() {
        val invalid = request(targetId = "missing-instance")

        val error = assertThrows(InventoryApiException::class.java) { service.replace("owner", invalid) }

        assertEquals("star_exchange_invalid_workspace_reference", error.code)
        verify(exactly = 0) { inventoryRepository.replace(any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { workspaceRepository.replace(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { loadoutService.clearForReplacement(any(), any(), any()) }
    }

    @Test
    fun `stale inventory revision stops workspace and loadout writes`() {
        every {
            inventoryRepository.replace("owner", "target", any(), any(), any(), 3, any(), any())
        } returns null

        val error = assertThrows(InventoryApiException::class.java) {
            service.replace("owner", request(inventoryRevision = 3))
        }

        assertEquals("star_inventory_revision_conflict", error.code)
        verify(exactly = 0) { workspaceRepository.replace(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { loadoutService.clearForReplacement(any(), any(), any()) }
    }

    @Test
    fun `inventory duplicate key from a revision zero upsert becomes stable conflict without changes`() {
        val originalInventory = inventory("target", 5, listOf(entry("old-main")))
        val originalWorkspace = workspace("target", 8, listOf(StarPlanTarget("old-main", 60)))
        val originalLoadout = StarLoadoutCurrentResponse("target", 3, emptyMap(), Instant.parse("2026-09-17T00:00:00Z"))
        var inventoryState = originalInventory
        var workspaceState = originalWorkspace
        var loadoutState = originalLoadout
        installRollbackBoundary(
            snapshot = { Triple(inventoryState, workspaceState, loadoutState) },
            restore = { restored ->
                inventoryState = restored.first
                workspaceState = restored.second
                loadoutState = restored.third
            },
        )
        every { inventoryRepository.replace("owner", "target", any(), any(), any(), 0, any(), any()) } answers {
            inventoryState = inventory("target", 1, listOf(entry("new-main-1")))
            throw DuplicateKeyException("concurrent first create")
        }

        val error = assertThrows(InventoryApiException::class.java) {
            service.replace("owner", request(inventoryRevision = 0, workspaceRevision = 0))
        }

        assertEquals("star_inventory_revision_conflict", error.code)
        assertEquals(originalInventory, inventoryState)
        assertEquals(originalWorkspace, workspaceState)
        assertEquals(originalLoadout, loadoutState)
        verify(exactly = 0) { workspaceRepository.replace(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { loadoutService.clearForReplacement(any(), any(), any()) }
    }

    @Test
    fun `workspace duplicate key from a revision zero upsert becomes stable conflict and rolls back inventory`() {
        val originalInventory = inventory("target", 5, listOf(entry("old-main")))
        val originalWorkspace = workspace("target", 8, listOf(StarPlanTarget("old-main", 60)))
        val originalLoadout = StarLoadoutCurrentResponse("target", 3, emptyMap(), Instant.parse("2026-09-17T00:00:00Z"))
        var inventoryState = originalInventory
        var workspaceState = originalWorkspace
        var loadoutState = originalLoadout
        installRollbackBoundary(
            snapshot = { Triple(inventoryState, workspaceState, loadoutState) },
            restore = { restored ->
                inventoryState = restored.first
                workspaceState = restored.second
                loadoutState = restored.third
            },
        )
        every { inventoryRepository.replace("owner", "target", any(), any(), any(), 0, any(), any()) } answers {
            inventory("target", 1, listOf(entry("new-main-1"))).also { inventoryState = it }
        }
        every { workspaceRepository.replace("owner", "target", 0, any(), any(), any(), any()) } answers {
            workspaceState = workspace("target", 1, listOf(StarPlanTarget("new-main-2", 60)))
            throw DuplicateKeyException("concurrent first create")
        }

        val error = assertThrows(InventoryApiException::class.java) {
            service.replace("owner", request(inventoryRevision = 0, workspaceRevision = 0))
        }

        assertEquals("star_workspace_revision_conflict", error.code)
        assertEquals(originalInventory, inventoryState)
        assertEquals(originalWorkspace, workspaceState)
        assertEquals(originalLoadout, loadoutState)
        verify(exactly = 0) { loadoutService.clearForReplacement(any(), any(), any()) }
    }

    @Test
    fun `ordinary database failures are not remapped as CAS conflicts`() {
        val failure = DataAccessResourceFailureException("database unavailable")
        every { inventoryRepository.replace("owner", "target", any(), any(), any(), 0, any(), any()) } throws failure

        val error = assertThrows(DataAccessResourceFailureException::class.java) {
            service.replace("owner", request(inventoryRevision = 0, workspaceRevision = 0))
        }

        assertEquals(failure, error)
        verify(exactly = 0) { workspaceRepository.replace(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { loadoutService.clearForReplacement(any(), any(), any()) }
    }

    private fun installRollbackBoundary(
        snapshot: () -> Triple<StarInventoryCurrent, StarWorkspaceCurrent, StarLoadoutCurrentResponse>,
        restore: (Triple<StarInventoryCurrent, StarWorkspaceCurrent, StarLoadoutCurrentResponse>) -> Unit,
    ) {
        every { transactions.execute<Any>(any()) } answers {
            val before = snapshot()
            try {
                @Suppress("UNCHECKED_CAST")
                (firstArg<TransactionCallback<Any>>()).doInTransaction(SimpleTransactionStatus())
            } catch (error: Throwable) {
                restore(before)
                throw error
            }
        }
    }

    private fun request(
        inventoryRevision: Long = 4,
        workspaceRevision: Long = 7,
        targetId: String = "new-main-2",
    ) = StarExchangeReplaceRequest(
        accountId = "target",
        inventory = StarExchangeReplaceInventoryRequest(
            inventoryRevision,
            "2026-09-17T00:00:00Z",
            listOf(
                entryRequest("new-main-1", "天府"),
                entryRequest("new-main-2", "天府"),
            ),
        ),
        workspace = StarWorkspaceCurrentRequest(
            workspaceRevision,
            mapOf(targetId to 60),
            StarWorkspaceBagRequest(2, 100),
            StarWorkspaceExperienceRequest(1, 2, 3),
        ),
    )

    private fun entryRequest(instanceId: String, name: String) =
        StarInventoryEntryRequest(instanceId, "main", name, "orange", 60)

    private fun entry(instanceId: String) = StarInventoryEntry(instanceId, "main", "天府", "orange", 60)

    private fun inventory(accountId: String, revision: Long, entries: List<StarInventoryEntry>) = StarInventoryCurrent(
        id = "owner:$accountId",
        userId = "owner",
        accountId = accountId,
        effectiveAt = Instant.parse("2026-09-17T00:00:00Z"),
        entries = entries,
        revision = revision,
        contentHash = "hash",
    )

    private fun workspace(accountId: String, revision: Long, targets: List<StarPlanTarget>) = StarWorkspaceCurrent(
        id = "owner:$accountId",
        userId = "owner",
        accountId = accountId,
        planTargets = targets,
        bag = StarWorkspaceBag(2, 100),
        experience = StarWorkspaceExperience(1, 2, 3),
        revision = revision,
    )
}
