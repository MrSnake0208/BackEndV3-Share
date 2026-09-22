package com.lhs.share.hub.service.report

import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.hub.controller.report.request.FeedbackAccessUpdateRequest
import com.lhs.share.hub.controller.report.response.CurrentFeedbackAccessResponse
import com.lhs.share.hub.controller.report.response.FeedbackAccessGrantResponse
import com.lhs.share.hub.controller.report.response.FeedbackAccessUserCandidateResponse
import com.lhs.share.hub.controller.report.response.FeedbackAreaOptionResponse
import com.lhs.share.hub.repository.FeedbackAccessGrantRepository
import com.lhs.share.hub.repository.entity.AdminAuditAction
import com.lhs.share.hub.repository.entity.AdminAuditLog
import com.lhs.share.hub.repository.entity.AdminAuditSnapshot
import com.lhs.share.hub.repository.entity.AdminRole
import com.lhs.share.hub.repository.entity.FeedbackAccessGrant
import com.lhs.share.hub.service.admin.AdminAuditService
import com.lhs.share.hub.service.admin.AdminAuthorizationService
import com.lhs.share.hub.service.admin.AdminPermission
import com.lhs.share.service.UserService
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.regex.Pattern

@Service
class FeedbackAccessService(
    private val repository: FeedbackAccessGrantRepository,
    private val userService: UserService,
    private val authorizationService: AdminAuthorizationService,
    private val auditService: AdminAuditService,
) {
    fun current(userId: String): CurrentFeedbackAccessResponse {
        val superAdmin = authorizationService.hasRole(userId, AdminRole.SUPER_ADMIN)
        val grant = repository.findById(userId).orElse(null)
        return CurrentFeedbackAccessResponse(
            superAdmin = superAdmin,
            receiveAreas = grant?.receiveAreas.orEmpty(),
            manageAreas = authorizationService.manageableAreasFor(userId),
            availableAreas = FeedbackArea.labels.map { (key, label) -> FeedbackAreaOptionResponse(key, label) },
        )
    }

    fun canView(userId: String, area: String): Boolean = authorizationService.canReadFeedback(userId, area)

    fun canManage(userId: String, area: String): Boolean = authorizationService.canManageFeedback(userId, area)

    fun manageableAreas(userId: String): Set<String> = authorizationService.manageableAreasFor(userId)

    fun receiverUserIds(area: String): Set<String> = repository.findByReceiveAreasContaining(area)
        .map { it.userId }
        .filter { userService.get(it)?.activated == true }
        .toSet()

    fun managerUserIds(area: String): Set<String> = authorizationService.managerUserIdsFor(area)

    fun listGrants(adminUserId: String): List<FeedbackAccessGrantResponse> {
        authorizationService.requirePermission(adminUserId, AdminPermission.ADMIN_FEEDBACK_ACCESS_MANAGE)
        return repository.findAll()
            .sortedBy { userService.get(it.userId)?.userName ?: it.userId }
            .map(::toResponse)
    }

    fun searchUserCandidates(adminUserId: String, query: String, page: Int, size: Int): List<FeedbackAccessUserCandidateResponse> {
        authorizationService.requirePermission(adminUserId, AdminPermission.ADMIN_FEEDBACK_ACCESS_MANAGE)
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "搜索关键词不能为空")
        }
        if (page < 1) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "page 必须大于等于 1")
        }
        if (size !in 1..10) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "size 必须在 1..10 之间")
        }
        if (page == 1 && normalizedQuery.contains('@')) {
            userService.findFeedbackAccessUserByEmail(normalizedQuery)?.let {
                return listOf(FeedbackAccessUserCandidateResponse(it))
            }
        }
        val escapedQuery = Pattern.quote(normalizedQuery)
        return userService.searchFeedbackAccessUsers(escapedQuery, PageRequest.of(page - 1, size))
            .content
            .map(::FeedbackAccessUserCandidateResponse)
    }

    @Transactional(transactionManager = "hubTransactionManager")
    fun updateGrant(adminUserId: String, userId: String, request: FeedbackAccessUpdateRequest): FeedbackAccessGrantResponse {
        authorizationService.requirePermission(adminUserId, AdminPermission.ADMIN_FEEDBACK_ACCESS_MANAGE)
        val user = userService.getRequired(userId)
        if (!user.activated) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "只能为已激活用户配置反馈权限")
        }
        val receiveAreas = validateAreas(request.receiveCategories ?: request.receiveAreas)
        val manageAreas = validateAreas(request.manageCategories ?: request.manageAreas)
        val before = repository.findById(userId).orElse(null)
        val now = Instant.now()
        val saved = repository.save(
            FeedbackAccessGrant(
                userId = userId,
                receiveAreas = receiveAreas,
                manageAreas = manageAreas,
                updatedBy = adminUserId,
                updatedAt = now,
            ),
        )
        auditService.record(
            AdminAuditLog(
                actorUserId = adminUserId,
                action = AdminAuditAction.FEEDBACK_ACCESS_UPDATED,
                targetUserId = userId,
                targetResource = "feedback_access_grants/$userId",
                before = before?.let(::auditSnapshot),
                after = auditSnapshot(saved),
                occurredAt = now,
            ),
        )
        return toResponse(saved)
    }

    @Transactional(transactionManager = "hubTransactionManager")
    fun deleteGrant(adminUserId: String, userId: String) {
        authorizationService.requirePermission(adminUserId, AdminPermission.ADMIN_FEEDBACK_ACCESS_MANAGE)
        val before = repository.findById(userId).orElse(null) ?: return
        repository.deleteById(userId)
        auditService.record(
            AdminAuditLog(
                actorUserId = adminUserId,
                action = AdminAuditAction.FEEDBACK_ACCESS_DELETED,
                targetUserId = userId,
                targetResource = "feedback_access_grants/$userId",
                before = auditSnapshot(before),
                occurredAt = Instant.now(),
            ),
        )
    }

    private fun validateAreas(areas: Set<String>): Set<String> = areas.map { area ->
        try {
            FeedbackArea.requireValid(area)
        } catch (e: IllegalArgumentException) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), e.message)
        }
    }.toSet()

    private fun toResponse(grant: FeedbackAccessGrant): FeedbackAccessGrantResponse {
        return FeedbackAccessGrantResponse(
            userId = grant.userId,
            userName = userService.get(grant.userId)?.userName ?: "未知用户",
            receiveAreas = grant.receiveAreas,
            manageAreas = grant.manageAreas,
            updatedBy = grant.updatedBy,
            updatedAt = grant.updatedAt,
        )
    }

    private fun auditSnapshot(grant: FeedbackAccessGrant) = AdminAuditSnapshot(
        receiveAreas = grant.receiveAreas,
        manageAreas = grant.manageAreas,
    )
}
