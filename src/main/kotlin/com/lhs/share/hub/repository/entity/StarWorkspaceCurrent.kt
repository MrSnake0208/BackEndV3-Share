package com.lhs.share.hub.repository.entity

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/** Account-scoped YuanStar planning state; OCR evidence remains client-local. */
@Document("star_workspace_current")
@CompoundIndexes(
    CompoundIndex(
        name = "idx_star_workspace_user_account_unique",
        def = "{'userId': 1, 'accountId': 1}",
        unique = true,
    ),
    CompoundIndex(
        name = "idx_star_workspace_user_updated_at",
        def = "{'userId': 1, 'updatedAt': -1}",
    ),
)
data class StarWorkspaceCurrent(
    @Id val id: String? = null,
    val userId: String,
    val accountId: String,
    /** Stored as values rather than BSON map fields so valid IDs containing '.' round-trip unchanged. */
    val planTargets: List<StarPlanTarget> = emptyList(),
    val bag: StarWorkspaceBag = StarWorkspaceBag(),
    val experience: StarWorkspaceExperience = StarWorkspaceExperience(),
    val revision: Long = 1,
    val updatedAt: Instant = Instant.now(),
)

data class StarWorkspaceBag(
    val currentCount: Int? = null,
    val capacity: Int? = null,
)

data class StarWorkspaceExperience(
    val orange: Int? = null,
    val purple: Int? = null,
    val white: Int? = null,
)

data class StarPlanTarget(val instanceId: String, val targetLevel: Int)
