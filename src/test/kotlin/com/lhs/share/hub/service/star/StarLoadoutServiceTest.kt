package com.lhs.share.hub.service.star

import com.lhs.share.hub.controller.star.request.StarLoadoutCurrentRequest
import com.lhs.share.hub.repository.StarInventoryCurrentRepository
import com.lhs.share.hub.repository.StarLoadoutCurrentRepository
import com.lhs.share.hub.repository.entity.StarInventoryCurrent
import com.lhs.share.hub.repository.entity.StarInventoryEntry
import com.lhs.share.hub.repository.entity.StarLoadoutCurrent
import com.lhs.share.hub.service.account.SubAccountService
import com.lhs.share.hub.service.inventory.InventoryApiException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import org.springframework.transaction.support.TransactionOperations
import java.time.Instant

class StarLoadoutServiceTest {
    private val repository = mockk<StarLoadoutCurrentRepository>()
    private val inventoryRepository = mockk<StarInventoryCurrentRepository>()
    private val accountService = mockk<SubAccountService>()
    private val stored = mutableMapOf<Pair<String, String>, StarLoadoutCurrent>()
    private var inventory: StarInventoryCurrent? = inventory()
    private val service = StarLoadoutService(
        repository,
        inventoryRepository,
        accountService,
        TransactionOperations.withoutTransaction(),
    )

    @BeforeEach
    fun setUp() {
        stored.clear()
        inventory = inventory()
        every { accountService.requireAccount(any(), any()) } returns mockk()
        every { inventoryRepository.findByUserIdAndAccountId(any(), any()) } answers { inventory }
        every { inventoryRepository.touchReferenceBarrier(any(), any()) } answers { inventory }
        every { repository.findByUserIdAndAccountId(any(), any()) } answers {
            stored[firstArg<String>() to secondArg<String>()]
        }
        every { repository.replace(any(), any(), any(), any(), any()) } answers {
            val userId = firstArg<String>()
            val accountId = secondArg<String>()
            val expected = args[2] as Long
            val current = stored[userId to accountId]
            if ((current?.revision ?: 0) != expected) {
                null
            } else {
                StarLoadoutCurrent(
                    id = "$userId:$accountId",
                    userId = userId,
                    accountId = accountId,
                    loadouts = args[3] as List<com.lhs.share.hub.repository.entity.StarOperatorLoadout>,
                    revision = expected + 1,
                    updatedAt = args[4] as Instant,
                ).also { stored[userId to accountId] = it }
            }
        }
    }

    @Test
    fun `first empty loadout normal six-slot save and move are valid`() {
        val empty = service.putCurrent("u1", "acc_a", request(0, emptyMap()))
        val first = service.putCurrent(
            "u1",
            "acc_a",
            request(1, mapOf("operator_a" to slots(main1 = "main_1", support1 = "support_1"))),
        )
        val moved = service.putCurrent(
            "u1",
            "acc_a",
            request(2, mapOf("operator_b" to slots(main1 = "main_1"))),
        )

        assertEquals(1, empty.revision)
        assertEquals(2, first.revision)
        assertEquals(3, moved.revision)
        assertEquals("main_1", moved.loadouts["operator_b"]?.get("main1"))
    }

    @Test
    fun `kind dangling and global uniqueness constraints are enforced`() {
        val candidates = listOf(
            request(0, mapOf("operator_a" to slots(main1 = "support_1"))) to "star_loadout_slot_kind_mismatch",
            request(0, mapOf("operator_a" to slots(support1 = "main_1"))) to "star_loadout_slot_kind_mismatch",
            request(0, mapOf("operator_a" to slots(main1 = "gone"))) to "star_loadout_invalid_reference",
            request(0, mapOf("operator_a" to slots(main1 = "main_1", main2 = "main_1"))) to "star_loadout_instance_occupied",
            request(
                0,
                mapOf("operator_a" to slots(main1 = "main_1"), "operator_b" to slots(main2 = "main_1")),
            ) to "star_loadout_instance_occupied",
        )

        candidates.forEach { (candidate, code) ->
            val error = assertThrows(InventoryApiException::class.java) {
                service.putCurrent("u1", "acc_a", candidate)
            }
            assertEquals(code, error.code)
        }
    }

    @Test
    fun `missing inventory rejects nonempty loadout but permits clear and CAS conflict`() {
        inventory = null
        val missing = assertThrows(InventoryApiException::class.java) {
            service.putCurrent(
                "u1",
                "acc_a",
                request(0, mapOf("operator_a" to slots(main1 = "main_1"))),
            )
        }
        val empty = service.putCurrent("u1", "acc_a", request(0, emptyMap()))
        val conflict = assertThrows(InventoryApiException::class.java) {
            service.putCurrent("u1", "acc_a", request(0, emptyMap()))
        }

        assertEquals("star_loadout_inventory_required", missing.code)
        assertEquals(1, empty.revision)
        assertEquals("star_loadout_revision_conflict", conflict.code)
    }

    @Test
    fun `loadout shape rejects missing slot and foreign account`() {
        val malformed = StarLoadoutCurrentRequest(0, mapOf("operator_a" to mapOf("main1" to "main_1")))
        val error = assertThrows(InventoryApiException::class.java) {
            service.putCurrent("u1", "acc_a", malformed)
        }
        every { accountService.requireAccount("u1", "foreign") } throws InventoryApiException(
            org.springframework.http.HttpStatus.NOT_FOUND,
            "account_not_found",
            "Account not found",
        )
        val foreign = assertThrows(InventoryApiException::class.java) {
            service.current("u1", "foreign")
        }

        assertEquals("star_loadout_invalid_snapshot", error.code)
        assertEquals("account_not_found", foreign.code)
    }

    @Test
    fun `duplicate key on first create is a stable revision conflict`() {
        every { repository.replace(any(), any(), any(), any(), any()) } throws DuplicateKeyException("race")

        val error = assertThrows(InventoryApiException::class.java) {
            service.putCurrent("u1", "acc_a", request(0, emptyMap()))
        }

        assertEquals("star_loadout_revision_conflict", error.code)
    }

    @Test
    fun `dotted operator and instance IDs remain valid through the persistence-shaped service model`() {
        val baseInventory = inventory()
        inventory = baseInventory.copy(entries = baseInventory.entries + StarInventoryEntry("main.001", "main", "天府", "orange", 60))
        val response = service.putCurrent(
            "u1",
            "acc_a",
            request(0, mapOf("operator.001" to slots(main1 = "main.001"))),
        )

        assertEquals("main.001", response.loadouts["operator.001"]?.get("main1"))
        assertEquals("operator.001", stored["u1" to "acc_a"]!!.loadouts.single().operatorId)
    }

    @Test
    fun `reference validation uses the serialized inventory snapshot`() {
        every { inventoryRepository.touchReferenceBarrier("u1", "acc_a") } returns inventory!!.copy(entries = emptyList())

        val error = assertThrows(InventoryApiException::class.java) {
            service.putCurrent("u1", "acc_a", request(0, mapOf("operator_a" to slots(main1 = "main_1"))))
        }

        assertEquals("star_loadout_invalid_reference", error.code)
        verify(exactly = 1) { inventoryRepository.touchReferenceBarrier("u1", "acc_a") }
        verify(exactly = 0) { inventoryRepository.findByUserIdAndAccountId(any(), any()) }
    }

    private fun request(expected: Long, loadouts: Map<String, Map<String, String?>>): StarLoadoutCurrentRequest {
        return StarLoadoutCurrentRequest(expected, loadouts)
    }

    private fun slots(
        main1: String? = null,
        main2: String? = null,
        main3: String? = null,
        support1: String? = null,
        support2: String? = null,
        support3: String? = null,
    ) = linkedMapOf(
        "main1" to main1,
        "main2" to main2,
        "main3" to main3,
        "support1" to support1,
        "support2" to support2,
        "support3" to support3,
    )

    private fun inventory() = StarInventoryCurrent(
        id = "u1:acc_a",
        userId = "u1",
        accountId = "acc_a",
        effectiveAt = Instant.parse("2026-09-01T00:00:00Z"),
        entries = listOf(
            StarInventoryEntry("main_1", "main", "天府", "orange", 60),
            StarInventoryEntry("main_2", "main", "武曲", "purple", 1),
            StarInventoryEntry("support_1", "support", "文曲", "white", 1),
        ),
        contentHash = "hash",
    )
}
