package com.lhs.share.hub.service.star

import com.lhs.share.hub.controller.star.request.StarWorkspaceBagRequest
import com.lhs.share.hub.controller.star.request.StarWorkspaceCurrentRequest
import com.lhs.share.hub.controller.star.request.StarWorkspaceExperienceRequest
import com.lhs.share.hub.repository.StarWorkspaceCurrentRepository
import com.lhs.share.hub.repository.entity.StarWorkspaceCurrent
import com.lhs.share.hub.service.account.SubAccountService
import com.lhs.share.hub.service.inventory.InventoryApiException
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DuplicateKeyException
import java.time.Instant

class StarWorkspaceServiceTest {
    private val repository = mockk<StarWorkspaceCurrentRepository>()
    private val accountService = mockk<SubAccountService>()
    private val stored = mutableMapOf<Pair<String, String>, StarWorkspaceCurrent>()
    private val service = StarWorkspaceService(repository, accountService)

    @BeforeEach
    fun setUp() {
        stored.clear()
        every { accountService.requireAccount(any(), any()) } returns mockk()
        every { repository.findByUserIdAndAccountId(any(), any()) } answers {
            stored[firstArg<String>() to secondArg<String>()]
        }
        every { repository.replace(any(), any(), any(), any(), any(), any(), any()) } answers {
            val userId = firstArg<String>()
            val accountId = secondArg<String>()
            val expected = args[2] as Long
            val current = stored[userId to accountId]
            if ((current?.revision ?: 0) != expected) {
                null
            } else {
                StarWorkspaceCurrent(
                    id = "$userId:$accountId",
                    userId = userId,
                    accountId = accountId,
                    planTargets = args[3] as List<com.lhs.share.hub.repository.entity.StarPlanTarget>,
                    bag = args[4] as com.lhs.share.hub.repository.entity.StarWorkspaceBag,
                    experience = args[5] as com.lhs.share.hub.repository.entity.StarWorkspaceExperience,
                    revision = expected + 1,
                    updatedAt = args[6] as Instant,
                ).also { stored[userId to accountId] = it }
            }
        }
    }

    @Test
    fun `first create normal update and empty GET use account scoped CAS`() {
        assertEquals(0, service.current("u1", "acc_a").revision)
        val first = service.putCurrent("u1", "acc_a", request(0, mapOf("star_1" to 60)))
        val updated = service.putCurrent("u1", "acc_a", request(1, mapOf("star_1" to 50)))

        assertEquals(1, first.revision)
        assertEquals(2, updated.revision)
        assertEquals(50, updated.planTargets["star_1"])
    }

    @Test
    fun `stale expected revision and foreign account are rejected`() {
        service.putCurrent("u1", "acc_a", request(0))
        val stale = assertThrows(InventoryApiException::class.java) {
            service.putCurrent("u1", "acc_a", request(0))
        }
        every { accountService.requireAccount("u1", "foreign") } throws InventoryApiException(
            org.springframework.http.HttpStatus.NOT_FOUND,
            "account_not_found",
            "Account not found",
        )
        val foreign = assertThrows(InventoryApiException::class.java) {
            service.current("u1", "foreign")
        }

        assertEquals("star_workspace_revision_conflict", stale.code)
        assertEquals("account_not_found", foreign.code)
    }

    @Test
    fun `bag experience and target validation reject invalid snapshots`() {
        val invalidRequests = listOf(
            request(0, mapOf("star_1" to 0)),
            request(0, bag = StarWorkspaceBagRequest(-1, null)),
            request(0, bag = StarWorkspaceBagRequest(2, 1)),
            request(0, experience = StarWorkspaceExperienceRequest(0, -1, null)),
        )

        invalidRequests.forEach { candidate ->
            val error = assertThrows(InventoryApiException::class.java) {
                service.putCurrent("u1", "acc_a", candidate)
            }
            assertEquals("star_workspace_invalid_snapshot", error.code)
        }
    }

    @Test
    fun `target levels one and sixty are accepted while zero and sixty one are rejected`() {
        val one = service.putCurrent("u1", "acc_a", request(0, mapOf("star_1" to 1)))
        val sixty = service.putCurrent("u1", "acc_a", request(1, mapOf("star_1" to 60)))
        val zero = assertThrows(InventoryApiException::class.java) {
            service.putCurrent("u1", "acc_a", request(2, mapOf("star_1" to 0)))
        }
        val sixtyOne = assertThrows(InventoryApiException::class.java) {
            service.putCurrent("u1", "acc_a", request(2, mapOf("star_1" to 61)))
        }

        assertEquals(1, one.planTargets["star_1"])
        assertEquals(60, sixty.planTargets["star_1"])
        assertEquals("star_workspace_invalid_snapshot", zero.code)
        assertEquals("star_workspace_invalid_snapshot", sixtyOne.code)
    }

    @Test
    fun `duplicate key on first create is a stable revision conflict`() {
        every { repository.replace(any(), any(), any(), any(), any(), any(), any()) } throws DuplicateKeyException("race")

        val error = assertThrows(InventoryApiException::class.java) {
            service.putCurrent("u1", "acc_a", request(0))
        }

        assertEquals("star_workspace_revision_conflict", error.code)
    }

    @Test
    fun `ordinary database failure is not misclassified as a revision conflict`() {
        val databaseFailure = DataAccessResourceFailureException("database unavailable")
        every { repository.replace(any(), any(), any(), any(), any(), any(), any()) } throws databaseFailure

        val error = assertThrows(DataAccessResourceFailureException::class.java) {
            service.putCurrent("u1", "acc_a", request(0))
        }

        assertEquals(databaseFailure, error)
    }

    @Test
    fun `dotted instance ID remains valid through the persistence-shaped service model`() {
        val response = service.putCurrent("u1", "acc_a", request(0, mapOf("star.001" to 60)))

        assertEquals(60, response.planTargets["star.001"])
        assertEquals("star.001", stored["u1" to "acc_a"]!!.planTargets.single().instanceId)
    }

    private fun request(
        expected: Long,
        targets: Map<String, Int> = emptyMap(),
        bag: StarWorkspaceBagRequest = StarWorkspaceBagRequest(1, 2),
        experience: StarWorkspaceExperienceRequest = StarWorkspaceExperienceRequest(1, 2, 3),
    ) = StarWorkspaceCurrentRequest(expected, targets, bag, experience)
}
