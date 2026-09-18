package com.lhs.share.hub.service.star

import com.lhs.share.hub.controller.star.request.StarInventoryEntryRequest
import com.lhs.share.hub.controller.star.request.StarInventorySnapshotRequest
import com.lhs.share.hub.repository.StarInventoryCurrentRepository
import com.lhs.share.hub.repository.StarLoadoutCurrentRepository
import com.lhs.share.hub.repository.StarWorkspaceCurrentRepository
import com.lhs.share.hub.repository.entity.StarInventoryCurrent
import com.lhs.share.hub.repository.entity.StarInventoryEntry
import com.lhs.share.hub.repository.entity.StarLoadoutCurrent
import com.lhs.share.hub.repository.entity.StarLoadoutSlots
import com.lhs.share.hub.repository.entity.StarOperatorLoadout
import com.lhs.share.hub.repository.entity.StarPlanTarget
import com.lhs.share.hub.repository.entity.StarWorkspaceBag
import com.lhs.share.hub.repository.entity.StarWorkspaceCurrent
import com.lhs.share.hub.repository.entity.StarWorkspaceExperience
import com.lhs.share.hub.repository.entity.SubAccount
import com.lhs.share.hub.service.account.SubAccountService
import com.lhs.share.hub.service.inventory.InventoryApiException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

class StarInventoryServiceTest {
    private val repository = mockk<StarInventoryCurrentRepository>()
    private val workspaceRepository = mockk<StarWorkspaceCurrentRepository>()
    private val loadoutRepository = mockk<StarLoadoutCurrentRepository>()
    private val accountService = mockk<SubAccountService>()
    private val transactions = mockk<TransactionTemplate>()
    private val stored = mutableMapOf<Pair<String, String>, StarInventoryCurrent>()
    private val workspaces = mutableMapOf<Pair<String, String>, StarWorkspaceCurrent>()
    private val loadouts = mutableMapOf<Pair<String, String>, StarLoadoutCurrent>()
    private val service = StarInventoryService(repository, workspaceRepository, loadoutRepository, accountService, transactions)

    @BeforeEach
    fun setUp() {
        stored.clear()
        workspaces.clear()
        loadouts.clear()
        every { accountService.requireAccount(any(), any()) } answers {
            SubAccount(userId = firstArg(), accountId = secondArg(), name = "账号")
        }
        every { transactions.execute<Any>(any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (firstArg<TransactionCallback<Any>>()).doInTransaction(SimpleTransactionStatus())
        }
        every { repository.findByUserIdAndAccountId(any(), any()) } answers {
            stored[firstArg<String>() to secondArg<String>()]
        }
        every { workspaceRepository.findByUserIdAndAccountId(any(), any()) } answers {
            workspaces[firstArg<String>() to secondArg<String>()]
        }
        every { loadoutRepository.findByUserIdAndAccountId(any(), any()) } answers {
            loadouts[firstArg<String>() to secondArg<String>()]
        }
        every {
            repository.replaceIfEffectiveAtAfterCurrent(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } answers {
            val userId = firstArg<String>()
            val accountId = secondArg<String>()
            val effectiveAt = thirdArg<Instant>()
            val current = stored[userId to accountId]
            val expectedRevision = args[5] as Long
            if (current != null && (current.revision != expectedRevision || !current.effectiveAt.isBefore(effectiveAt))) {
                null
            } else {
                val saved = StarInventoryCurrent(
                    id = "$userId:$accountId",
                    userId = userId,
                    accountId = accountId,
                    effectiveAt = effectiveAt,
                    entries = args[3] as List<StarInventoryEntry>,
                    revision = expectedRevision + 1,
                    contentHash = args[4] as String,
                    updatedAt = args[6] as Instant,
                    receivedAt = args[7] as Instant,
                )
                stored[userId to accountId] = saved
                saved
            }
        }
        every { workspaceRepository.replace(any(), any(), any(), any(), any(), any(), any()) } answers {
            val userId = firstArg<String>()
            val accountId = secondArg<String>()
            val expected = args[2] as Long
            val current = workspaces[userId to accountId]
            if (current == null || current.revision != expected) null else StarWorkspaceCurrent(
                id = current.id,
                userId = userId,
                accountId = accountId,
                planTargets = args[3] as List<StarPlanTarget>,
                bag = args[4] as StarWorkspaceBag,
                experience = args[5] as StarWorkspaceExperience,
                revision = expected + 1,
                updatedAt = args[6] as Instant,
            ).also { workspaces[userId to accountId] = it }
        }
        every { loadoutRepository.replace(any(), any(), any(), any(), any()) } answers {
            val userId = firstArg<String>()
            val accountId = secondArg<String>()
            val expected = args[2] as Long
            val current = loadouts[userId to accountId]
            if (current == null || current.revision != expected) null else StarLoadoutCurrent(
                id = current.id,
                userId = userId,
                accountId = accountId,
                loadouts = args[3] as List<StarOperatorLoadout>,
                revision = expected + 1,
                updatedAt = args[4] as Instant,
            ).also { loadouts[userId to accountId] = it }
        }
    }

    @Test
    fun `first PUT normalizes entries and GET reads the current snapshot`() {
        val response = service.putCurrent(
            "u1",
            "acc_a",
            request(
                entries = listOf(
                    entry("support-1", " 文曲 ", "support", "white", 1),
                    entry("main-1", " 天府 ", "main", "orange", 60),
                ),
            ),
        )

        assertEquals(1L, response.revision)
        assertEquals(listOf("main-1", "support-1"), response.entries.map { it.instanceId })
        assertEquals("天府", response.entries[0].name)
        assertEquals(response, service.current("u1", "acc_a"))
    }

    @Test
    fun `same entries are idempotent even when effective time changes`() {
        val first = service.putCurrent(
            "u1",
            "acc_a",
            request(
                effectiveAt = "2026-08-31T10:00:00Z",
                entries = listOf(entry("main-1", " 天府 ", "main", "orange", 60)),
            ),
        )
        val second = service.putCurrent(
            "u1",
            "acc_a",
            request(
                effectiveAt = "2026-08-31T11:00:00Z",
                entries = listOf(entry("main-1", "天府", "main", "orange", 60)),
            ),
        )

        assertEquals(1L, first.revision)
        assertEquals(first, second)
        verify(exactly = 1) {
            repository.replaceIfEffectiveAtAfterCurrent(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `same entries with earlier effective time are still idempotent`() {
        val first = service.putCurrent("u1", "acc_a", request())
        val repeated = service.putCurrent(
            "u1",
            "acc_a",
            request(
                effectiveAt = "2026-08-31T09:00:00Z",
                entries = listOf(entry("main-1", "天府", "main", "orange", 60)),
            ),
        )

        assertEquals(first, repeated)
        verify(exactly = 1) {
            repository.replaceIfEffectiveAtAfterCurrent(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `legacy content hash with same entries at an earlier time is idempotent without replacing`() {
        storeLegacyCurrent()

        val response = service.putCurrent(
            "u1",
            "acc_a",
            request(effectiveAt = "2026-08-31T09:00:00Z"),
        )

        assertEquals(7L, response.revision)
        verify(exactly = 0) { repository.replaceIfEffectiveAtAfterCurrent(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `legacy content hash with same entries at the same time is idempotent without replacing`() {
        storeLegacyCurrent()

        val response = service.putCurrent(
            "u1",
            "acc_a",
            request(effectiveAt = "2026-08-31T10:00:00Z"),
        )

        assertEquals(7L, response.revision)
        verify(exactly = 0) { repository.replaceIfEffectiveAtAfterCurrent(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `legacy content hash with same entries at a later time is idempotent without replacing`() {
        storeLegacyCurrent()

        val response = service.putCurrent(
            "u1",
            "acc_a",
            request(effectiveAt = "2026-08-31T11:00:00Z"),
        )

        assertEquals(7L, response.revision)
        verify(exactly = 0) { repository.replaceIfEffectiveAtAfterCurrent(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `legacy content hash with different earlier entries remains stale`() {
        storeLegacyCurrent()

        val error = assertThrows(InventoryApiException::class.java) {
            service.putCurrent(
                "u1",
                "acc_a",
                request(
                    effectiveAt = "2026-08-31T09:00:00Z",
                    entries = listOf(entry("main-2", "武曲", "main", "purple", 20)),
                ),
            )
        }

        assertEquals("star_inventory_stale_snapshot", error.code)
        verify(exactly = 0) { repository.replaceIfEffectiveAtAfterCurrent(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `newer PUT completely replaces the current entries`() {
        service.putCurrent("u1", "acc_a", request())

        val replaced = service.putCurrent(
            "u1",
            "acc_a",
            request(
                effectiveAt = "2026-08-31T11:00:00Z",
                entries = listOf(entry("support-1", "文曲", "support", "white", 1)),
            ),
        )

        assertEquals(2L, replaced.revision)
        assertEquals(listOf("support-1"), replaced.entries.map { it.instanceId })
        assertEquals(replaced, service.current("u1", "acc_a"))
    }

    @Test
    fun `removing a planned instance prunes only that workspace target`() {
        service.putCurrent(
            "u1",
            "acc_a",
            request(entries = listOf(entry("removed", "天府", "main", "orange", 40), entry("kept", "文曲", "support", "white", 1))),
        )
        workspaces["u1" to "acc_a"] = workspace(
            revision = 7,
            targets = listOf(StarPlanTarget("removed", 60), StarPlanTarget("kept", 50)),
            bag = StarWorkspaceBag(12, 100),
            experience = StarWorkspaceExperience(3, 4, 5),
        )
        loadouts["u1" to "acc_a"] = StarLoadoutCurrent(
            id = "u1:acc_a",
            userId = "u1",
            accountId = "acc_a",
            loadouts = listOf(
                StarOperatorLoadout("operator-a", StarLoadoutSlots(main1 = "removed", support1 = "kept")),
                StarOperatorLoadout("operator-b", StarLoadoutSlots(main2 = "kept")),
            ),
            revision = 4,
        )

        service.putCurrent(
            "u1",
            "acc_a",
            request("2026-08-31T11:00:00Z", listOf(entry("kept", "文曲", "support", "white", 1))),
        )

        val workspace = workspaces["u1" to "acc_a"]!!
        assertEquals(8, workspace.revision)
        assertEquals(listOf(StarPlanTarget("kept", 50)), workspace.planTargets)
        assertEquals(StarWorkspaceBag(12, 100), workspace.bag)
        assertEquals(StarWorkspaceExperience(3, 4, 5), workspace.experience)
        val loadout = loadouts["u1" to "acc_a"]!!
        assertEquals(5, loadout.revision)
        assertEquals(null, loadout.loadouts[0].slots.main1)
        assertEquals("kept", loadout.loadouts[0].slots.support1)
        assertEquals("kept", loadout.loadouts[1].slots.main2)
    }

    @Test
    fun `inventory deletion leaves already-pruned workspace untouched`() {
        service.putCurrent(
            "u1",
            "acc_a",
            request(entries = listOf(entry("removed", "天府", "main", "orange", 40), entry("kept", "文曲", "support", "white", 1))),
        )
        val before = workspace(revision = 7, targets = listOf(StarPlanTarget("kept", 50)))
        workspaces["u1" to "acc_a"] = before

        service.putCurrent(
            "u1",
            "acc_a",
            request("2026-08-31T11:00:00Z", listOf(entry("kept", "文曲", "support", "white", 1))),
        )

        assertEquals(before, workspaces["u1" to "acc_a"])
        verify(exactly = 0) { workspaceRepository.replace("u1", "acc_a", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `removing an equipped instance clears only its slots and preserves operators`() {
        service.putCurrent(
            "u1",
            "acc_a",
            request(entries = listOf(entry("removed", "天府", "main", "orange", 40), entry("kept", "文曲", "support", "white", 1))),
        )
        loadouts["u1" to "acc_a"] = StarLoadoutCurrent(
            id = "u1:acc_a",
            userId = "u1",
            accountId = "acc_a",
            loadouts = listOf(
                StarOperatorLoadout("operator-a", StarLoadoutSlots(main1 = "removed", support1 = "kept")),
                StarOperatorLoadout("operator-b", StarLoadoutSlots(main2 = "kept")),
            ),
            revision = 4,
        )

        service.putCurrent(
            "u1",
            "acc_a",
            request("2026-08-31T11:00:00Z", listOf(entry("kept", "文曲", "support", "white", 1))),
        )

        val loadout = loadouts["u1" to "acc_a"]!!
        assertEquals(5, loadout.revision)
        assertEquals(null, loadout.loadouts[0].slots.main1)
        assertEquals("kept", loadout.loadouts[0].slots.support1)
        assertEquals("kept", loadout.loadouts[1].slots.main2)
    }

    @Test
    fun `same instance ID level update preserves workspace and loadout references`() {
        service.putCurrent("u1", "acc_a", request(entries = listOf(entry("main-1", "天府", "main", "orange", 40))))
        val workspace = workspace(revision = 7, targets = listOf(StarPlanTarget("main-1", 60)))
        val loadout = StarLoadoutCurrent(
            id = "u1:acc_a", userId = "u1", accountId = "acc_a",
            loadouts = listOf(StarOperatorLoadout("operator-a", StarLoadoutSlots(main1 = "main-1"))), revision = 4,
        )
        workspaces["u1" to "acc_a"] = workspace
        loadouts["u1" to "acc_a"] = loadout

        service.putCurrent("u1", "acc_a", request("2026-08-31T11:00:00Z", listOf(entry("main-1", "天府", "main", "orange", 60))))

        assertEquals(workspace, workspaces["u1" to "acc_a"])
        assertEquals(loadout, loadouts["u1" to "acc_a"])
    }

    @Test
    fun `loadout prune failure rolls inventory workspace and loadout back together`() {
        service.putCurrent("u1", "acc_a", request(entries = listOf(entry("removed", "天府", "main", "orange", 40), entry("kept", "文曲", "support", "white", 1))))
        workspaces["u1" to "acc_a"] = workspace(revision = 7, targets = listOf(StarPlanTarget("removed", 60)))
        loadouts["u1" to "acc_a"] = StarLoadoutCurrent(
            id = "u1:acc_a", userId = "u1", accountId = "acc_a",
            loadouts = listOf(StarOperatorLoadout("operator-a", StarLoadoutSlots(main1 = "removed"))), revision = 4,
        )
        val beforeInventory = stored["u1" to "acc_a"]!!
        val beforeWorkspace = workspaces["u1" to "acc_a"]!!
        val beforeLoadout = loadouts["u1" to "acc_a"]!!
        every { transactions.execute<Any>(any()) } answers {
            val inventoryBefore = stored.toMap()
            val workspaceBefore = workspaces.toMap()
            val loadoutBefore = loadouts.toMap()
            try {
                @Suppress("UNCHECKED_CAST")
                (firstArg<TransactionCallback<Any>>()).doInTransaction(SimpleTransactionStatus())
            } catch (error: Throwable) {
                stored.clear(); stored.putAll(inventoryBefore)
                workspaces.clear(); workspaces.putAll(workspaceBefore)
                loadouts.clear(); loadouts.putAll(loadoutBefore)
                throw error
            }
        }
        every { loadoutRepository.replace("u1", "acc_a", 4, any(), any()) } throws DataAccessResourceFailureException("loadout unavailable")

        assertThrows(DataAccessResourceFailureException::class.java) {
            service.putCurrent("u1", "acc_a", request("2026-08-31T11:00:00Z", listOf(entry("kept", "文曲", "support", "white", 1))))
        }

        assertEquals(beforeInventory, stored["u1" to "acc_a"])
        assertEquals(beforeWorkspace, workspaces["u1" to "acc_a"])
        assertEquals(beforeLoadout, loadouts["u1" to "acc_a"])
    }

    @Test
    fun `workspace prune failure rolls inventory workspace and loadout back together`() {
        service.putCurrent("u1", "acc_a", request(entries = listOf(entry("removed", "天府", "main", "orange", 40), entry("kept", "文曲", "support", "white", 1))))
        workspaces["u1" to "acc_a"] = workspace(revision = 7, targets = listOf(StarPlanTarget("removed", 60)))
        loadouts["u1" to "acc_a"] = StarLoadoutCurrent(
            id = "u1:acc_a", userId = "u1", accountId = "acc_a",
            loadouts = listOf(StarOperatorLoadout("operator-a", StarLoadoutSlots(support1 = "kept"))), revision = 4,
        )
        val beforeInventory = stored["u1" to "acc_a"]!!
        val beforeWorkspace = workspaces["u1" to "acc_a"]!!
        val beforeLoadout = loadouts["u1" to "acc_a"]!!
        every { transactions.execute<Any>(any()) } answers {
            val inventoryBefore = stored.toMap()
            val workspaceBefore = workspaces.toMap()
            val loadoutBefore = loadouts.toMap()
            try {
                @Suppress("UNCHECKED_CAST")
                (firstArg<TransactionCallback<Any>>()).doInTransaction(SimpleTransactionStatus())
            } catch (error: Throwable) {
                stored.clear(); stored.putAll(inventoryBefore)
                workspaces.clear(); workspaces.putAll(workspaceBefore)
                loadouts.clear(); loadouts.putAll(loadoutBefore)
                throw error
            }
        }
        every { workspaceRepository.replace("u1", "acc_a", 7, any(), any(), any(), any()) } answers {
            val current = workspaces["u1" to "acc_a"]!!
            workspaces["u1" to "acc_a"] = current.copy(
                planTargets = args[3] as List<StarPlanTarget>,
                revision = 8,
                updatedAt = args[6] as Instant,
            )
            throw DataAccessResourceFailureException("workspace unavailable")
        }

        assertThrows(DataAccessResourceFailureException::class.java) {
            service.putCurrent("u1", "acc_a", request("2026-08-31T11:00:00Z", listOf(entry("kept", "文曲", "support", "white", 1))))
        }

        assertEquals(beforeInventory, stored["u1" to "acc_a"])
        assertEquals(beforeWorkspace, workspaces["u1" to "acc_a"])
        assertEquals(beforeLoadout, loadouts["u1" to "acc_a"])
    }

    @Test
    fun `older and same-time different snapshots are rejected without replacing data`() {
        val first = service.putCurrent("u1", "acc_a", request())

        val stale = assertThrows(InventoryApiException::class.java) {
            service.putCurrent(
                "u1",
                "acc_a",
                request("2026-08-30T10:00:00Z", listOf(entry("main-2", "武曲", "main", "purple", 20))),
            )
        }
        val sameTime = assertThrows(InventoryApiException::class.java) {
            service.putCurrent(
                "u1",
                "acc_a",
                request("2026-08-31T10:00:00Z", listOf(entry("main-2", "武曲", "main", "purple", 20))),
            )
        }

        assertEquals("star_inventory_stale_snapshot", stale.code)
        assertEquals("star_inventory_revision_conflict", sameTime.code)
        assertEquals(first, service.current("u1", "acc_a"))
    }

    @Test
    fun `empty snapshot is valid and entries are isolated by owner`() {
        val empty = service.putCurrent("u1", "acc_a", request(entries = emptyList()))
        service.putCurrent("u1", "acc_b", request(entries = listOf(entry("main-1", "天府", "main", "orange", 1))))
        service.putCurrent("u2", "acc_a", request(entries = listOf(entry("main-2", "武曲", "main", "purple", 2))))

        assertTrue(empty.entries.isEmpty())
        assertTrue(service.current("u1", "acc_a").entries.isEmpty())
        assertEquals("main-1", service.current("u1", "acc_b").entries.single().instanceId)
        assertEquals("main-2", service.current("u2", "acc_a").entries.single().instanceId)
    }

    @Test
    fun `invalid and unknown accounts fail before snapshot reads`() {
        every { accountService.requireAccount("u1", "missing") } throws
            InventoryApiException(HttpStatus.NOT_FOUND, "account_not_found", "Account not found")

        val missing = assertThrows(InventoryApiException::class.java) {
            service.current("u1", "missing")
        }
        val invalid = assertThrows(InventoryApiException::class.java) {
            service.current("u1", "bad/id")
        }

        assertEquals("account_not_found", missing.code)
        assertEquals("schema_validation_failed", invalid.code)
        verify(exactly = 0) { repository.findByUserIdAndAccountId("u1", "missing") }
        verify(exactly = 0) { repository.findByUserIdAndAccountId("u1", "bad/id") }
    }

    @Test
    fun `invalid snapshot fields fail before repository writes`() {
        val invalidRequests = listOf(
            request(effectiveAt = "2026-08-31T10:00:00"),
            request(entries = listOf(entry("main-1", "  ", "main", "orange", 1))),
            request(entries = listOf(entry("main-1", "天府", "other", "orange", 1))),
            request(entries = listOf(entry("main-1", "天府", "main", "red", 1))),
            request(entries = listOf(entry("main-1", "天府", "main", "orange", 61))),
            request(entries = listOf(entry("main-1", "天府", "main", "orange", 0))),
            request(entries = listOf(entry("main-1", "天府", "main", "orange", 1), entry("main-1", "天府", "main", "orange", 1))),
        )

        invalidRequests.forEach { invalidRequest ->
            val error = assertThrows(InventoryApiException::class.java) {
                service.putCurrent("u1", "acc_a", invalidRequest)
            }
            assertEquals("star_inventory_invalid_snapshot", error.code)
        }
        verify(exactly = 0) { repository.replaceIfEffectiveAtAfterCurrent(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `level boundaries one and sixty are accepted`() {
        val saved = service.putCurrent(
            "u1",
            "acc_a",
            request(entries = listOf(entry("main-1", "天府", "main", "orange", 1))),
        )
        val replaced = service.putCurrent(
            "u1",
            "acc_a",
            request(
                effectiveAt = "2026-08-31T11:00:00Z",
                entries = listOf(entry("main-1", "天府", "main", "orange", 60)),
            ),
        )

        assertEquals(1, saved.entries.single().level)
        assertEquals(60, replaced.entries.single().level)
        assertEquals(2L, replaced.revision)
    }

    private fun request(
        effectiveAt: String = "2026-08-31T10:00:00Z",
        entries: List<StarInventoryEntryRequest> = listOf(entry("main-1", "天府", "main", "orange", 60)),
    ) = StarInventorySnapshotRequest(effectiveAt, entries)

    private fun entry(instanceId: String, name: String, kind: String, quality: String, level: Int) =
        StarInventoryEntryRequest(instanceId, kind, name, quality, level)

    private fun workspace(
        revision: Long,
        targets: List<StarPlanTarget>,
        bag: StarWorkspaceBag = StarWorkspaceBag(1, 2),
        experience: StarWorkspaceExperience = StarWorkspaceExperience(1, 2, 3),
    ) = StarWorkspaceCurrent(
        id = "u1:acc_a",
        userId = "u1",
        accountId = "acc_a",
        planTargets = targets,
        bag = bag,
        experience = experience,
        revision = revision,
    )

    private fun storeLegacyCurrent() {
        stored["u1" to "acc_a"] = StarInventoryCurrent(
            id = "u1:acc_a",
            userId = "u1",
            accountId = "acc_a",
            effectiveAt = Instant.parse("2026-08-31T10:00:00Z"),
            entries = listOf(StarInventoryEntry("main-1", "main", "天府", "orange", 60)),
            revision = 7,
            contentHash = "legacy-hash-including-effective-at",
            updatedAt = Instant.parse("2026-08-31T10:00:00Z"),
            receivedAt = Instant.parse("2026-08-31T10:00:00Z"),
        )
    }
}
