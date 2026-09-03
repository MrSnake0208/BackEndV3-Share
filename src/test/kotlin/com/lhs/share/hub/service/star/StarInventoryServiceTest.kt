package com.lhs.share.hub.service.star

import com.lhs.share.hub.controller.star.request.StarInventoryEntryRequest
import com.lhs.share.hub.controller.star.request.StarInventorySnapshotRequest
import com.lhs.share.hub.repository.StarInventoryCurrentRepository
import com.lhs.share.hub.repository.entity.StarInventoryCurrent
import com.lhs.share.hub.repository.entity.StarInventoryEntry
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
import org.springframework.http.HttpStatus
import java.time.Instant

class StarInventoryServiceTest {
    private val repository = mockk<StarInventoryCurrentRepository>()
    private val accountService = mockk<SubAccountService>()
    private val stored = mutableMapOf<Pair<String, String>, StarInventoryCurrent>()
    private val service = StarInventoryService(repository, accountService)

    @BeforeEach
    fun setUp() {
        stored.clear()
        every { accountService.requireAccount(any(), any()) } answers {
            SubAccount(userId = firstArg(), accountId = secondArg(), name = "账号")
        }
        every { repository.findByUserIdAndAccountId(any(), any()) } answers {
            stored[firstArg<String>() to secondArg<String>()]
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
    fun `same normalized snapshot is idempotent across order and equivalent offsets`() {
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
                effectiveAt = "2026-08-31T18:00:00+08:00",
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

    private fun request(
        effectiveAt: String = "2026-08-31T10:00:00Z",
        entries: List<StarInventoryEntryRequest> = listOf(entry("main-1", "天府", "main", "orange", 60)),
    ) = StarInventorySnapshotRequest(effectiveAt, entries)

    private fun entry(instanceId: String, name: String, kind: String, quality: String, level: Int) =
        StarInventoryEntryRequest(instanceId, kind, name, quality, level)
}
