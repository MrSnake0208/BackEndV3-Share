package com.lhs.share.hub.controller.star.response

import com.lhs.share.hub.repository.entity.StarWorkspaceCurrent
import java.time.Instant

data class StarWorkspaceCurrentResponse(
    val accountId: String,
    val revision: Long,
    val planTargets: Map<String, Int>,
    val bag: StarWorkspaceBagResponse,
    val experience: StarWorkspaceExperienceResponse,
    val updatedAt: Instant?,
) {
    companion object {
        fun empty(accountId: String) = StarWorkspaceCurrentResponse(
            accountId = accountId,
            revision = 0,
            planTargets = emptyMap(),
            bag = StarWorkspaceBagResponse(null, null),
            experience = StarWorkspaceExperienceResponse(null, null, null),
            updatedAt = null,
        )

        fun of(current: StarWorkspaceCurrent) = StarWorkspaceCurrentResponse(
            accountId = current.accountId,
            revision = current.revision,
            planTargets = current.planTargets.associate { it.instanceId to it.targetLevel },
            bag = StarWorkspaceBagResponse(current.bag.currentCount, current.bag.capacity),
            experience = StarWorkspaceExperienceResponse(
                current.experience.orange,
                current.experience.purple,
                current.experience.white,
            ),
            updatedAt = current.updatedAt,
        )
    }
}

data class StarWorkspaceBagResponse(val currentCount: Int?, val capacity: Int?)

data class StarWorkspaceExperienceResponse(val orange: Int?, val purple: Int?, val white: Int?)
