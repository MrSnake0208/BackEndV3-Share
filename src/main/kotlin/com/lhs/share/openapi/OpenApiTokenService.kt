package com.lhs.share.openapi

import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.hub.repository.OpenApiTokenRepository
import com.lhs.share.hub.repository.entity.OpenApiToken
import com.lhs.share.hub.service.inventory.InventoryApiException
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
    fun generate(userId: String, scopes: List<String>, remark: String?): OpenApiTokenCreatedResponse {
        if (tokenRepository.countByUserId(userId) >= MAX_TOKENS_PER_USER) {
            throw ApiResultException(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "第三方API Token生成数量已达上限（最多${MAX_TOKENS_PER_USER}个）",
            )
        }

        val permissions = scopes.map { scope ->
            OpenApiPermission.byKey(scope)
                ?: throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "未知 scope: $scope")
        }
        if (permissions.distinct().size != permissions.size) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "scopes 不得重复")
        }
        val scopeCodes = permissions.map { it.code }
        val publicScopes = permissions.map { it.key }
        val tokenId = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString().replace("-", "")
        val now = Instant.now()

        // 写 Redis(不设过期,token 永不过期)
        redisCache.setCache(redisKey(token), TokenCacheData(userId = userId, scope = scopeCodes, createTime = now.toEpochMilli()), 0)

        // 落库
        tokenRepository.save(
            OpenApiToken(
                id = tokenId,
                userId = userId,
                token = token,
                scope = scopeCodes,
                remark = remark,
                createTime = now,
            ),
        )

        return OpenApiTokenCreatedResponse(
            tokenId = tokenId,
            token = token,
            remark = remark,
            scopes = publicScopes,
            createdAt = now,
        )
    }

    /**
     * 校验 token 并校验权限,返回归属 userId。
     * token 空 → 401;Redis 无则回退 Mongo,仍无 → 401;scope 不含 requiredCode → 403。
     */
    fun validateAuthorization(authorization: String?, permission: OpenApiPermission): String {
        val token = authorization
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            ?.trim()
        return validate(token, permission.code)
    }

    private fun validate(token: String?, requiredCode: Int): String {
        if (token.isNullOrBlank()) {
            throw InventoryApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "API token is missing")
        }
        val cached = redisCache.getCache(redisKey(token), TokenCacheData::class.java)
        if (cached != null) {
            if (!cached.scope.contains(requiredCode)) {
                throw InventoryApiException(HttpStatus.FORBIDDEN, "forbidden", "API token lacks the required scope")
            }
            return cached.userId
        }

        // 回退 Mongo(防 Redis 丢失)
        val entity = tokenRepository.findByToken(token)
            ?: throw InventoryApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "API token is invalid")
        if (!entity.scope.contains(requiredCode)) {
            throw InventoryApiException(HttpStatus.FORBIDDEN, "forbidden", "API token lacks the required scope")
        }
        return entity.userId
    }

    /**
     * 删除 token(校验归属,越权抛 403)
     */
    fun delete(userId: String, tokenId: String) {
        val entity = tokenRepository.findByIdAndUserId(tokenId, userId)
            ?: throw ApiResultException(HttpStatus.NOT_FOUND.value(), "token 不存在")
        redisCache.delete(redisKey(entity.token))
        tokenRepository.deleteById(tokenId)
    }

    /**
     * 列出当前用户的 token(按创建时间倒序)
     */
    fun list(userId: String): List<OpenApiTokenListItemDto> = tokenRepository.findByUserIdOrderByCreateTimeDesc(userId).map {
        OpenApiTokenListItemDto(
            tokenId = checkNotNull(it.id) { "Token document has no id" },
            remark = it.remark,
            scopes = it.scope.mapNotNull { code -> OpenApiPermission.entries.firstOrNull { permission -> permission.code == code } }
                .map { permission -> permission.key },
            createdAt = it.createTime,
        )
    }

    private fun redisKey(token: String): String = REDIS_KEY_PREFIX + token

    companion object {
        private const val REDIS_KEY_PREFIX = "open-api-token:"
        private const val BEARER_PREFIX = "Bearer "
        private const val MAX_TOKENS_PER_USER = 5
    }
}

data class OpenApiTokenCreatedResponse(
    val tokenId: String,
    val token: String,
    val remark: String?,
    val scopes: List<String>,
    val createdAt: Instant,
)

data class OpenApiTokenListItemDto(
    val tokenId: String,
    val remark: String?,
    val scopes: List<String>,
    val createdAt: Instant,
)

/**
 * Redis 中缓存的 token 数据
 */
data class TokenCacheData(
    val userId: String,
    val scope: List<Int>,
    val createTime: Long,
)
