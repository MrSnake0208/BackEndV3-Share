package com.lhs.share.openapi

import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.hub.repository.OpenApiTokenRepository
import com.lhs.share.hub.repository.entity.OpenApiToken
import com.lhs.share.repository.RedisCache
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * 第三方 API Token 服务单元测试(MockK,不连 Mongo/Redis)
 *
 * 覆盖:生成(含上限)、校验(Redis 命中/回退 Mongo/权限/空 token)、
 * 删除(归属校验)、列举(字段映射)。
 */
class OpenApiTokenServiceTest {
    private val tokenRepository = mockk<OpenApiTokenRepository>()
    private val redisCache = mockk<RedisCache>(relaxed = true)

    private val service = OpenApiTokenService(tokenRepository, redisCache)

    private fun entity(userId: String = "u1", token: String = "tok123", scope: List<Int> = listOf(10001), remark: String? = "note") =
        OpenApiToken(
            userId = userId,
            token = token,
            scope = scope,
            remark = remark,
            createTime = Instant.ofEpochMilli(1700000000000L),
        )

    @Test
    fun generate_returns_dashless_uuid_and_persists() {
        every { tokenRepository.countByUserId("u1") } returns 0
        val saved = slot<OpenApiToken>()
        every { tokenRepository.save(capture(saved)) } answers { saved.captured }

        val resp = service.generate("u1", listOf(10001, 10002), "my-script")

        assertNotNull(resp.token)
        assertEquals(32, resp.token.length)
        assertTrue(resp.token.none { it == '-' })
        assertEquals(listOf(10001, 10002), resp.scope)
        assertEquals("u1", saved.captured.userId)
        assertEquals(resp.token, saved.captured.token)
        assertEquals(listOf(10001, 10002), saved.captured.scope)
        assertEquals("my-script", saved.captured.remark)
        verify { redisCache.setCache("open-api-token:" + resp.token, any<TokenCacheData>(), 0) }
    }

    @Test
    fun generate_over_limit_throws_429() {
        every { tokenRepository.countByUserId("u1") } returns 5
        val ex = assertThrows(ApiResultException::class.java) {
            service.generate("u1", listOf(10001), null)
        }
        assertEquals(429, ex.statusCode)
    }

    @Test
    fun validate_redis_hit_with_scope_returns_userId() {
        every { redisCache.getCache("open-api-token:tok123", TokenCacheData::class.java) } returns
            TokenCacheData(userId = "u1", scope = listOf(10001), createTime = 0L)
        assertEquals("u1", service.validate("tok123", 10001))
    }

    @Test
    fun validate_redis_hit_insufficient_scope_throws_403() {
        every { redisCache.getCache("open-api-token:tok123", TokenCacheData::class.java) } returns
            TokenCacheData(userId = "u1", scope = listOf(10001), createTime = 0L)
        val ex = assertThrows(ApiResultException::class.java) {
            service.validate("tok123", 10002)
        }
        assertEquals(403, ex.statusCode)
    }

    @Test
    fun validate_null_or_blank_token_throws_401() {
        assertEquals(
            401,
            assertThrows(ApiResultException::class.java) {
                service.validate(null, 10001)
            }.statusCode,
        )
        assertEquals(
            401,
            assertThrows(ApiResultException::class.java) {
                service.validate("  ", 10001)
            }.statusCode,
        )
    }

    @Test
    fun validate_redis_miss_falls_back_to_mongo_hit() {
        every { redisCache.getCache("open-api-token:tok123", TokenCacheData::class.java) } returns null
        every { tokenRepository.findByToken("tok123") } returns entity(userId = "u2", scope = listOf(10001))
        assertEquals("u2", service.validate("tok123", 10001))
    }

    @Test
    fun validate_redis_miss_and_mongo_miss_throws_401() {
        every { redisCache.getCache("open-api-token:tok123", TokenCacheData::class.java) } returns null
        every { tokenRepository.findByToken("tok123") } returns null
        val ex = assertThrows(ApiResultException::class.java) {
            service.validate("tok123", 10001)
        }
        assertEquals(401, ex.statusCode)
    }

    @Test
    fun validate_mongo_fallback_insufficient_scope_throws_403() {
        every { redisCache.getCache("open-api-token:tok123", TokenCacheData::class.java) } returns null
        every { tokenRepository.findByToken("tok123") } returns entity(userId = "u1", scope = listOf(10001))
        val ex = assertThrows(ApiResultException::class.java) {
            service.validate("tok123", 10002)
        }
        assertEquals(403, ex.statusCode)
    }

    @Test
    fun delete_own_token_removes_redis_and_db() {
        every { tokenRepository.findByToken("tok123") } returns entity(userId = "u1")
        every { tokenRepository.deleteByToken("tok123") } just runs
        service.delete("u1", "tok123")
        verify { redisCache.delete("open-api-token:tok123") }
        verify { tokenRepository.deleteByToken("tok123") }
    }

    @Test
    fun delete_others_token_throws_403_without_db_delete() {
        every { tokenRepository.findByToken("tok123") } returns entity(userId = "other")
        val ex = assertThrows(ApiResultException::class.java) {
            service.delete("u1", "tok123")
        }
        assertEquals(403, ex.statusCode)
        verify(exactly = 0) { tokenRepository.deleteByToken(any()) }
    }

    @Test
    fun delete_missing_token_throws_403() {
        every { tokenRepository.findByToken("tok123") } returns null
        val ex = assertThrows(ApiResultException::class.java) {
            service.delete("u1", "tok123")
        }
        assertEquals(403, ex.statusCode)
    }

    @Test
    fun list_maps_create_time_to_epoch_millis() {
        val e1 = entity(userId = "u1", token = "tok1", scope = listOf(10001), remark = "read")
        val e2 = entity(userId = "u1", token = "tok2", scope = listOf(10001, 10002), remark = null)
        every { tokenRepository.findByUserIdOrderByCreateTimeDesc("u1") } returns listOf(e1, e2)

        val list = service.list("u1")

        assertEquals(2, list.size)
        assertEquals("tok1", list[0]["token"])
        assertEquals(listOf(10001), list[0]["scope"])
        assertEquals("read", list[0]["remark"])
        assertEquals(1700000000000L, list[0]["create_time"])
        assertEquals("tok2", list[1]["token"])
        assertEquals(listOf(10001, 10002), list[1]["scope"])
        assertEquals(null, list[1]["remark"])
    }

    @Test
    fun list_empty_when_no_tokens() {
        every { tokenRepository.findByUserIdOrderByCreateTimeDesc("u1") } returns emptyList()
        assertTrue(service.list("u1").isEmpty())
    }
}
