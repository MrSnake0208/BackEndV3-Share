package com.lhs.share.hub.repository

import com.lhs.share.hub.repository.entity.RecruitmentCatalogPool
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository

@Repository
class RecruitmentCatalogRepository(@param:Qualifier("hubMongoTemplate") private val template: MongoTemplate) {
    fun all(): List<RecruitmentCatalogPool> = template.findAll(RecruitmentCatalogPool::class.java)
    fun find(poolId: String): RecruitmentCatalogPool? = template.findById(poolId, RecruitmentCatalogPool::class.java)

    /** revision=0 is the read-only seed or a new id; insert's unique _id arbitrates concurrent first writes. */
    fun save(next: RecruitmentCatalogPool): Boolean {
        if (next.revision == 1L) {
            template.insert(next)
            return true
        }
        return template.updateFirst(
            Query.query(Criteria.where("_id").`is`(next.poolId).and("revision").`is`(next.revision - 1)),
            Update().set("name", next.name).set("startDate", next.startDate).set("endDate", next.endDate)
                .set("poolType", next.poolType).set("enabled", next.enabled).set("revision", next.revision)
                .set("upAgents", next.upAgents).set("upStatus", next.upStatus)
                .set("updatedBy", next.updatedBy).set("updatedAt", next.updatedAt),
            RecruitmentCatalogPool::class.java,
        ).matchedCount == 1L
    }
}
