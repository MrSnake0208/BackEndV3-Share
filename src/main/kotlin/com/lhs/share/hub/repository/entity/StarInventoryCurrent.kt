package com.lhs.share.hub.repository.entity

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.io.Serializable
import java.time.Instant

/**
 * YuanStar 当前星石背包快照(HubBackend.star_inventory_current)。
 *
 * 每个用户、子账号最多一份；快照是完整替换状态，不与普通 inventory_current 混用。
 */
@Document("star_inventory_current")
@CompoundIndexes(
    CompoundIndex(
        name = "idx_star_inventory_user_account_unique",
        def = "{'userId': 1, 'accountId': 1}",
        unique = true,
    ),
    CompoundIndex(
        name = "idx_star_inventory_user_updated_at",
        def = "{'userId': 1, 'updatedAt': -1}",
    ),
)
data class StarInventoryCurrent(
    @Id
    val id: String? = null,
    /** 用户身份来自 JWT，不接受客户端提供。 */
    val userId: String,
    val accountId: String,
    val effectiveAt: Instant,
    val entries: List<StarInventoryEntry> = emptyList(),
    val revision: Long = 1,
    /** 规范化快照的 SHA-256，仅用于重复 PUT 幂等判断。 */
    val contentHash: String,
    val updatedAt: Instant = Instant.now(),
    val receivedAt: Instant = updatedAt,
) : Serializable

data class StarInventoryEntry(
    val instanceId: String,
    val kind: String,
    val name: String,
    val quality: String,
    val level: Int,
) : Serializable
