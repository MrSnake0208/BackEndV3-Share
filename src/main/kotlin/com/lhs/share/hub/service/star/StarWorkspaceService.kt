package com.lhs.share.hub.service.star

import com.lhs.share.hub.controller.star.request.StarWorkspaceCurrentRequest
import com.lhs.share.hub.controller.star.response.StarWorkspaceCurrentResponse
import com.lhs.share.hub.repository.StarWorkspaceCurrentRepository
import com.lhs.share.hub.repository.entity.StarPlanTarget
import com.lhs.share.hub.repository.entity.StarWorkspaceBag
import com.lhs.share.hub.repository.entity.StarWorkspaceExperience
import com.lhs.share.hub.service.account.SubAccountService
import com.lhs.share.hub.service.inventory.InventoryApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class StarWorkspaceService(
    private val repository: StarWorkspaceCurrentRepository,
    private val accountService: SubAccountService,
) {
    fun current(userId: String, accountId: String): StarWorkspaceCurrentResponse {
        accountService.requireAccount(userId, accountId)
        return repository.findByUserIdAndAccountId(userId, accountId)
            ?.let(StarWorkspaceCurrentResponse::of)
            ?: StarWorkspaceCurrentResponse.empty(accountId)
    }

    fun putCurrent(userId: String, accountId: String, request: StarWorkspaceCurrentRequest): StarWorkspaceCurrentResponse {
        accountService.requireAccount(userId, accountId)
        val normalized = normalize(request)
        val saved = starCasConflictBoundary({ throw conflict() }) {
            repository.replace(
                userId,
                accountId,
                normalized.expectedRevision,
                normalized.planTargets,
                normalized.bag,
                normalized.experience,
                Instant.now(),
            ) ?: throw conflict()
        }
        return StarWorkspaceCurrentResponse.of(saved)
    }

    private fun normalize(request: StarWorkspaceCurrentRequest): NormalizedWorkspace {
        val expected = request.expectedRevision ?: throw invalid("expected_revision 不能为空")
        if (expected < 0) throw invalid("expected_revision 不能小于 0")
        val targets = request.planTargets ?: throw invalid("plan_targets 不能为空")
        if (targets.size > MAX_PLAN_TARGETS) throw invalid("plan_targets 数量不能超过 $MAX_PLAN_TARGETS")
        val normalizedTargets = targets.entries.sortedBy { it.key }.map { (id, level) ->
            if (!INSTANCE_ID.matches(id)) throw invalid("plan_targets 的 instance_id 格式无效")
            if (level !in MIN_LEVEL..MAX_LEVEL) throw invalid("plan_targets 的 target_level 必须在 $MIN_LEVEL..$MAX_LEVEL")
            StarPlanTarget(id, level)
        }
        val requestBag = request.bag ?: throw invalid("bag 不能为空")
        val currentCount = requestBag.currentCount
        val capacity = requestBag.capacity
        if (currentCount != null && currentCount < 0) throw invalid("bag.current_count 不能小于 0")
        if (capacity != null && capacity < 1) throw invalid("bag.capacity 最小为 1")
        if (currentCount != null && capacity != null && currentCount > capacity) {
            throw invalid("bag.current_count 不能大于 bag.capacity")
        }
        val requestExperience = request.experience ?: throw invalid("experience 不能为空")
        val experience = listOf(
            "orange" to requestExperience.orange,
            "purple" to requestExperience.purple,
            "white" to requestExperience.white,
        )
        if (experience.any { (_, value) -> value != null && value < 0 }) {
            throw invalid("experience 数量不能小于 0")
        }
        return NormalizedWorkspace(
            expected,
            normalizedTargets,
            StarWorkspaceBag(currentCount, capacity),
            StarWorkspaceExperience(requestExperience.orange, requestExperience.purple, requestExperience.white),
        )
    }

    private fun invalid(message: String) = InventoryApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "star_workspace_invalid_snapshot",
        message,
    )

    private fun conflict() = InventoryApiException(
        HttpStatus.CONFLICT,
        "star_workspace_revision_conflict",
        "Star workspace changed; reload before saving",
    )

    private data class NormalizedWorkspace(
        val expectedRevision: Long,
        val planTargets: List<StarPlanTarget>,
        val bag: StarWorkspaceBag,
        val experience: StarWorkspaceExperience,
    )

    companion object {
        private const val MAX_PLAN_TARGETS = 1000
        private const val MIN_LEVEL = 1
        private const val MAX_LEVEL = 60
        private val INSTANCE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    }
}
