package com.lhs.share.hub.repository

import com.lhs.share.hub.repository.entity.StarInventoryCurrent
import com.lhs.share.hub.repository.entity.StarInventoryEntry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.repository.MongoRepository
import java.time.Instant

/**
 * YuanStar 当前快照仓储。必须位于 hub.repository 顶层，由 HubMongoConfig 路由到 HubBackend。
 */
interface StarInventoryCurrentRepository : MongoRepository<StarInventoryCurrent, String>, StarInventoryCurrentRepositoryCustom {
    fun findByUserIdAndAccountId(userId: String, accountId: String): StarInventoryCurrent?

    fun deleteByUserIdAndAccountId(userId: String, accountId: String): Long
}

interface StarInventoryCurrentRepositoryCustom {
    /**
     * Takes the account-scoped reference-write barrier and returns the inventory
     * snapshot that must be used for validation in the same Mongo transaction.
     */
    fun touchReferenceBarrier(userId: String, accountId: String): StarInventoryCurrent?

    /**
     * Replacement-import CAS. Unlike ordinary snapshot PUT, this intentionally does not compare
     * effectiveAt: an exchange replacement is explicitly authorized by expectedRevision.
     */
    fun replace(
        userId: String,
        accountId: String,
        effectiveAt: Instant,
        entries: List<StarInventoryEntry>,
        contentHash: String,
        expectedRevision: Long,
        updatedAt: Instant,
        receivedAt: Instant,
    ): StarInventoryCurrent?

    /**
     * 只有当前版本仍是 expectedRevision 且旧快照时间早于新时间时才替换。
     * 无记录时借助 upsert 创建 revision=1，竞争插入由服务层重读并重试。
     */
    fun replaceIfEffectiveAtAfterCurrent(
        userId: String,
        accountId: String,
        effectiveAt: Instant,
        entries: List<StarInventoryEntry>,
        contentHash: String,
        expectedRevision: Long,
        updatedAt: Instant,
        receivedAt: Instant,
    ): StarInventoryCurrent?
}

class StarInventoryCurrentRepositoryImpl(
    @param:Qualifier("hubMongoTemplate") private val mongoTemplate: MongoTemplate,
) : StarInventoryCurrentRepositoryCustom {
    override fun touchReferenceBarrier(userId: String, accountId: String): StarInventoryCurrent? = mongoTemplate.findAndModify(
        Query.query(
            Criteria.where("_id").`is`("$userId:$accountId").and("userId").`is`(userId)
                .and("accountId").`is`(accountId),
        ),
        Update().inc("referenceEpoch", 1),
        FindAndModifyOptions.options().returnNew(true),
        StarInventoryCurrent::class.java,
    )

    override fun replace(
        userId: String,
        accountId: String,
        effectiveAt: Instant,
        entries: List<StarInventoryEntry>,
        contentHash: String,
        expectedRevision: Long,
        updatedAt: Instant,
        receivedAt: Instant,
    ): StarInventoryCurrent? = mongoTemplate.findAndModify(
        Query.query(
            Criteria.where("_id").`is`("$userId:$accountId").and("userId").`is`(userId)
                .and("accountId").`is`(accountId).and("revision").`is`(expectedRevision),
        ),
        Update().set("userId", userId).set("accountId", accountId).set("effectiveAt", effectiveAt)
            .set("entries", entries).set("contentHash", contentHash).set("revision", expectedRevision + 1)
            .set("updatedAt", updatedAt).set("receivedAt", receivedAt),
        FindAndModifyOptions.options().upsert(expectedRevision == 0L).returnNew(true),
        StarInventoryCurrent::class.java,
    )

    override fun replaceIfEffectiveAtAfterCurrent(
        userId: String,
        accountId: String,
        effectiveAt: Instant,
        entries: List<StarInventoryEntry>,
        contentHash: String,
        expectedRevision: Long,
        updatedAt: Instant,
        receivedAt: Instant,
    ): StarInventoryCurrent? {
        val id = "$userId:$accountId"
        val query = Query.query(
            Criteria().andOperator(
                Criteria.where("_id").`is`(id),
                Criteria.where("userId").`is`(userId),
                Criteria.where("accountId").`is`(accountId),
                Criteria.where("effectiveAt").lt(effectiveAt),
                Criteria.where("revision").`is`(expectedRevision),
            ),
        )
        val update = Update()
            .set("userId", userId)
            .set("accountId", accountId)
            .set("effectiveAt", effectiveAt)
            .set("entries", entries)
            .set("revision", expectedRevision + 1)
            .set("contentHash", contentHash)
            .set("updatedAt", updatedAt)
            .set("receivedAt", receivedAt)
        return mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().upsert(true).returnNew(true),
            StarInventoryCurrent::class.java,
        )
    }
}
