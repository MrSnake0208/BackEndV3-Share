package com.lhs.share.hub.repository

import com.lhs.share.hub.repository.entity.StarPlanTarget
import com.lhs.share.hub.repository.entity.StarWorkspaceBag
import com.lhs.share.hub.repository.entity.StarWorkspaceCurrent
import com.lhs.share.hub.repository.entity.StarWorkspaceExperience
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.repository.MongoRepository
import java.time.Instant

interface StarWorkspaceCurrentRepository : MongoRepository<StarWorkspaceCurrent, String>, StarWorkspaceCurrentRepositoryCustom {
    fun findByUserIdAndAccountId(userId: String, accountId: String): StarWorkspaceCurrent?
    fun deleteAllByUserIdAndAccountId(userId: String, accountId: String)
}

interface StarWorkspaceCurrentRepositoryCustom {
    fun replace(
        userId: String,
        accountId: String,
        expectedRevision: Long,
        planTargets: List<StarPlanTarget>,
        bag: StarWorkspaceBag,
        experience: StarWorkspaceExperience,
        now: Instant,
    ): StarWorkspaceCurrent?
}

class StarWorkspaceCurrentRepositoryImpl(
    @param:Qualifier("hubMongoTemplate") private val template: MongoTemplate,
) : StarWorkspaceCurrentRepositoryCustom {
    override fun replace(
        userId: String,
        accountId: String,
        expectedRevision: Long,
        planTargets: List<StarPlanTarget>,
        bag: StarWorkspaceBag,
        experience: StarWorkspaceExperience,
        now: Instant,
    ): StarWorkspaceCurrent? = template.findAndModify(
        Query.query(
            Criteria.where("_id").`is`("$userId:$accountId").and("userId").`is`(userId)
                .and("accountId").`is`(accountId).and("revision").`is`(expectedRevision),
        ),
        Update().set("userId", userId).set("accountId", accountId).set("planTargets", planTargets)
            .set("bag", bag).set("experience", experience).set("revision", expectedRevision + 1).set("updatedAt", now),
        FindAndModifyOptions.options().upsert(expectedRevision == 0L).returnNew(true),
        StarWorkspaceCurrent::class.java,
    )
}
