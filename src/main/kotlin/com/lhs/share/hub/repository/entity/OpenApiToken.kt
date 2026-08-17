package com.lhs.share.hub.repository.entity

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.io.Serializable
import java.time.Instant

/**
 * 第三方 API Token(HubBackend.open_api_token)
 *
 * userId 引用 MaaBackend.maa_user.userId,跨库无法 join。token 为
 * 无连字符 UUID,唯一索引保证不重复;scope 存 OpenApiPermission.code 列表。
 * token 热数据同时存 Redis(见 OpenApiTokenService),本集合为持久化权威来源。
 */
@Document("open_api_token")
data class OpenApiToken(
    @Id
    val id: String? = null,
    /**
     * 归属用户 id(引用 MaaBackend.maa_user.userId),来自 JWT,绝不由前端传入
     */
    @Indexed
    val userId: String,
    /**
     * Token 绑定的库存子账号 id
     */
    @Indexed
    val accountId: String,
    /**
     * token 字符串(无连字符 UUID),唯一
     */
    @Indexed(unique = true)
    val token: String,
    /**
     * 授权范围,存 OpenApiPermission.code
     */
    val scope: List<Int>,
    /**
     * 备注,可空
     */
    val remark: String?,
    /**
     * 创建时间
     */
    val createTime: Instant = Instant.now(),
    /**
     * 最近使用时间,可空
     */
    val lastUsedAt: Instant? = null,
) : Serializable
