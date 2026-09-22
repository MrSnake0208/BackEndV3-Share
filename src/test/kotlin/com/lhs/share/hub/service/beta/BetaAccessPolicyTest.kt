package com.lhs.share.hub.service.beta

import com.lhs.share.config.security.BetaAccessPolicy
import com.lhs.share.hub.repository.entity.BetaCampaign
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class BetaAccessPolicyTest {
    @Test
    fun `all supported cloud families protected and explicit public examples remain public`() {
        listOf(
            "/v1/accounts",
            "/v1/accounts/{id}/events",
            "/v1/inventory/current",
            "/v1/operator/training-workspace",
            "/v1/star-state/current",
            "/v1/star-loadout",
            "/v1/star-loadout-presets",
            "/v1/star/captures/{id}",
            "/hub/ledger/plan",
        ).forEach {
            assertTrue(BetaAccessPolicy.requiresBeta("GET", it), it)
        }
        listOf(
            "/v1/inventory/catalog",
            "/v1/operator/catalog",
            "/v1/operator/share/view/{token}",
            "/v1/works",
            "/v1/reports",
            "/v1/notifications",
            "/v1/admin/beta",
            "/v1/beta/me",
            "/user/open-api/tokens",
            "/user/register",
        ).forEach {
            assertFalse(BetaAccessPolicy.requiresBeta("GET", it), it)
        }
        assertTrue(BetaAccessPolicy.requiresBeta("POST", "/user/open-api/token"))
        assertTrue(BetaAccessPolicy.requiresBeta("PATCH", "/user/open-api/tokens/{id}/scopes"))
        assertFalse(BetaAccessPolicy.requiresBeta("DELETE", "/user/open-api/tokens/{id}"))
        assertTrue(BetaAccessPolicy.requiresBeta("POST", "/v1/operator/catalog"))
        assertFalse(BetaAccessPolicy.requiresBeta("GET", "/v1/accounts-other"))
    }

    @Test
    fun `all reachable quota counts remain consistent when unused reservation expires`() {
        val start = Instant.parse("2026-09-24T12:00:00Z")
        for (capacity in listOf(100, 150, 200)) {
            for (shareGranted in 0..25) {
                for (publicGranted in 0..(capacity - 25)) {
                    val c = BetaCampaign(
                        id = "test",
                        startsAt = start,
                        capacity = capacity,
                        grantedCount = shareGranted + publicGranted,
                        reservedGrantedCount = shareGranted,
                        reservedRemaining = 25 - shareGranted,
                    )
                    c.checkQuota()
                    val previous = c.publicRemaining(start)
                    c.releaseExpired(c.reservedUntil)
                    c.checkQuota()
                    assertEquals(previous + 25 - shareGranted, c.publicRemaining(c.reservedUntil))
                    assertEquals(capacity, c.capacity)
                    c.releaseExpired(c.reservedUntil.plusSeconds(1))
                    assertEquals(25 - shareGranted, c.releasedCount)
                }
            }
        }
    }

    @Test
    fun `invalid quotas rejected instead of overselling`() {
        assertThrows(IllegalStateException::class.java) { BetaCampaign("x", grantedCount = 76).checkQuota() }
        assertThrows(IllegalStateException::class.java) { BetaCampaign("x", capacity = 201).checkQuota() }
        assertThrows(IllegalStateException::class.java) { BetaCampaign("x", reservedRemaining = -1).checkQuota() }
    }
}
