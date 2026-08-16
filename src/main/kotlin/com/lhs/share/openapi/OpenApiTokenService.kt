package com.lhs.share.openapi

import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.hub.repository.OpenApiTokenRepository
import com.lhs.share.hub.repository.entity.OpenApiToken
import com.lhs.share.repository.RedisCache
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * 第三方 API Token 服务
 *
 * token 热数据存 Redis(不设过期,token 永不过期),持久化落库
 * HubBackend.open_api_token。Redis 丢失时回退 Mongo 校验,保证可用性。
 */
@Service
class OpenApiTokenService(
    private val tokenRepository: OpenApiTokenRepository,
    private val redisCache: RedisCache,
) {
    /**
     * 生成第三方 API Token(每用户上限 [MAX_TOKENS_PER_USER] 个)
     */
    fun generate(userId: String, scopeCodes: List<Int>, remark: String?): TokenResponse {
        if (tokenRepository.countByUserId(userId) >= MAX_TOKENS_PER_USER) {
            throw ApiResultException(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "第三方API Token生成数量已达上限（最多${MAX_TOKENS_PER_USER}个）",
            )
        }

        val token = UUID.randomUUID().toString().replace("-", "")
        val now = Instant.now()

        // 写 Redis(不设过期,token 永不过期)
        redisCache.setCache(redisKey(token), TokenCacheData(userId = userId, scope = scopeCodes, createTime = now.toEpochMilli()), 0)

        // 落库
        tokenRepository.save(
            OpenApiToken(
                userId = userId,
                token = token,
                scope = scopeCodes,
                remark = remark,
                createTime = now,
            ),
        )

        return TokenResponse(token = token, scope = scopeCodes)
    }

    /**
     * 校验 token 并校验权限,返回归属 userId。
     * token 空 → 401;Redis 无则回退 Mongo,仍无 → 401;scope 不含 requiredCode → 403。
     */
    fun validate(token: String?, requiredCode: Int): String {
        if (token.isNullOrBlank()) {
            throw ApiResultException(HttpStatus.UNAUTHORIZED.value(), "token不能为空")
        }
        val cached = redisCache.getCache(redisKey(token), TokenCacheData::class.java)
        if (cached != null) {
            if (!cached.scope.contains(requiredCode)) {
                throw ApiResultException(HttpStatus.FORBIDDEN.value(), "权限不足")
            }
            return cached.userId
        }

        // 回退 Mongo(防 Redis 丢失)
        val entity = tokenRepository.findByToken(token)
            ?: throw ApiResultException(HttpStatus.UNAUTHORIZED.value(), "token无效")
        if (!entity.scope.contains(requiredCode)) {
            throw ApiResultException(HttpStatus.FORBIDDEN.value(), "权限不足")
        }
        return entity.userId
    }

    /**
     * 删除 token(校验归属,越权抛 403)
     */
    fun delete(userId: String, token: String) {
        val entity = tokenRepository.findByToken(token)
            ?: throw ApiResultException(HttpStatus.FORBIDDEN.value(), "token无效或无权删除")
        if (entity.userId != userId) {
            throw ApiResultException(HttpStatus.FORBIDDEN.value(), "token无效或无权删除")
        }
        redisCache.delete(redisKey(token))
        tokenRepository.deleteByToken(token)
    }

    /**
     * 列出当前用户的 token(按创建时间倒序)
     */
    fun list(userId: String): List<Map<String, Any?>> = tokenRepository.findByUserIdOrderByCreateTimeDesc(userId).map {
        mapOf(
            "token" to it.token,
            "scope" to it.scope,
            "remark" to it.remark,
            "create_time" to it.createTime.toEpochMilli(),
        )
    }

    private fun redisKey(token: String): String = REDIS_KEY_PREFIX + token

    companion object {
        private const val REDIS_KEY_PREFIX = "open-api-token:"
        private const val MAX_TOKENS_PER_USER = 5
    }
}

/**
 * 生成 token 的响应
 */
data class TokenResponse(
    val token: String,
    val scope: List<Int>,
)

/**
 * Redis 中缓存的 token 数据
 */
data class TokenCacheData(
    val userId: String,
    val scope: List<Int>,
    val createTime: Long,
)
