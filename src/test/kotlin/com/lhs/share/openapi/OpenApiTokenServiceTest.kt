package com.lhs.share.openapi

import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.hub.repository.OpenApiTokenRepository
import com.lhs.share.hub.repository.entity.OpenApiToken
import com.lhs.share.hub.service.inventory.InventoryApiException
import com.lhs.share.repository.RedisCache
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class OpenApiTokenServiceTest {
    private val tokenRepository = mockk<OpenApiTokenRepository>()
    private val redisCache = mockk<RedisCache>(relaxed = true)
    private val service = OpenApiTokenService(tokenRepository, redisCache)

    private fun entity(
        id: String = "token-id",
        userId: String = "u1",
        token: String = "tok123",
        scope: List<Int> = listOf(10001),
        remark: String? = "note",
    ) = OpenApiToken(
        id = id,
        userId = userId,
        token = token,
        scope = scope,
        remark = remark,
        createTime = Instant.parse("2023-11-14T22:13:20Z"),
    )

    @Test
    fun `generation maps public scopes and returns the full token once`() {
        every { tokenRepository.countByUserId("u1") } returns 0
        val saved = slot<OpenApiToken>()
        every { tokenRepository.save(capture(saved)) } answers { saved.captured }

        val response = service.generate("u1", listOf("inventory:read", "inventory:write"), "script")

        assertEquals(32, response.token.length)
        assertEquals(listOf("inventory:read", "inventory:write"), response.scopes)
        assertEquals(listOf(10001, 10002), saved.captured.scope)
        assertEquals(response.tokenId, saved.captured.id)
        verify { redisCache.setCache("open-api-token:${response.token}", any<TokenCacheData>(), 0) }
    }

    @Test
    fun `unknown public scope is rejected`() {
        every { tokenRepository.countByUserId("u1") } returns 0
        val error = assertThrows(ApiResultException::class.java) {
            service.generate("u1", listOf("inventory:admin"), null)
        }
        assertEquals(400, error.statusCode)
    }

    @Test
    fun `token limit is enforced`() {
        every { tokenRepository.countByUserId("u1") } returns 5
        val error = assertThrows(ApiResultException::class.java) {
            service.generate("u1", listOf("inventory:read"), null)
        }
        assertEquals(429, error.statusCode)
    }

    @Test
    fun `valid bearer token with required scope returns owner`() {
        every { redisCache.getCache("open-api-token:tok123", TokenCacheData::class.java) } returns
            TokenCacheData("u1", listOf(10002), 0)

        assertEquals("u1", service.validateAuthorization("Bearer tok123", OpenApiPermission.INVENTORY_WRITE))
    }

    @Test
    fun `missing malformed invalid and under-scoped bearer tokens are rejected`() {
        listOf(null, "", "tok123", "Basic tok123", "Bearer ").forEach { authorization ->
            val error = assertThrows(InventoryApiException::class.java) {
                service.validateAuthorization(authorization, OpenApiPermission.INVENTORY_WRITE)
            }
            assertEquals(401, error.status.value())
            assertEquals("unauthorized", error.code)
        }

        every { redisCache.getCache("open-api-token:invalid", TokenCacheData::class.java) } returns null
        every { tokenRepository.findByToken("invalid") } returns null
        assertEquals(
            401,
            assertThrows(InventoryApiException::class.java) {
                service.validateAuthorization("Bearer invalid", OpenApiPermission.INVENTORY_WRITE)
            }.status.value(),
        )

        every { redisCache.getCache("open-api-token:read-only", TokenCacheData::class.java) } returns
            TokenCacheData("u1", listOf(10001), 0)
        val forbidden = assertThrows(InventoryApiException::class.java) {
            service.validateAuthorization("Bearer read-only", OpenApiPermission.INVENTORY_WRITE)
        }
        assertEquals(403, forbidden.status.value())
        assertEquals("forbidden", forbidden.code)
    }

    @Test
    fun `Mongo fallback validates token`() {
        every { redisCache.getCache("open-api-token:tok123", TokenCacheData::class.java) } returns null
        every { tokenRepository.findByToken("tok123") } returns entity(userId = "u2", scope = listOf(10003))

        assertEquals("u2", service.validateAuthorization("Bearer tok123", OpenApiPermission.INVENTORY_EXPORT))
    }

    @Test
    fun `revocation uses token id and clears cached secret`() {
        every { tokenRepository.findByIdAndUserId("token-id", "u1") } returns entity()
        every { tokenRepository.deleteById("token-id") } just runs

        service.delete("u1", "token-id")

        verify { redisCache.delete("open-api-token:tok123") }
        verify { tokenRepository.deleteById("token-id") }
    }

    @Test
    fun `token list uses DTO and never exposes secret or integer scopes`() {
        every { tokenRepository.findByUserIdOrderByCreateTimeDesc("u1") } returns listOf(
            entity(scope = listOf(10001, 10002, 10003)),
        )

        val item = service.list("u1").single()

        assertEquals("token-id", item.tokenId)
        assertEquals(listOf("inventory:read", "inventory:write", "inventory:export"), item.scopes)
        assertFalse(item.toString().contains("tok123"))
        assertEquals(Instant.parse("2023-11-14T22:13:20Z"), item.createdAt)
    }
}
