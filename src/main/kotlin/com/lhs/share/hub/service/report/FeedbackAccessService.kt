package com.lhs.share.hub.service.report

import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.hub.controller.report.request.FeedbackAccessUpdateRequest
import com.lhs.share.hub.controller.report.response.CurrentFeedbackAccessResponse
import com.lhs.share.hub.controller.report.response.FeedbackAccessGrantResponse
import com.lhs.share.hub.controller.report.response.FeedbackAccessUserCandidateResponse
import com.lhs.share.hub.controller.report.response.FeedbackAssigneeResponse
import com.lhs.share.hub.controller.report.response.FeedbackAreaOptionResponse
import com.lhs.share.hub.repository.FeedbackAccessGrantRepository
import com.lhs.share.hub.repository.FeedbackTicketRepository
import com.lhs.share.hub.repository.FeedbackTicketQueryRepository
import com.lhs.share.hub.repository.FeedbackWorkflowEventRepository
import com.lhs.share.hub.repository.entity.AdminAuditAction
import com.lhs.share.hub.repository.entity.AdminAuditLog
import com.lhs.share.hub.repository.entity.AdminAuditSnapshot
import com.lhs.share.hub.repository.entity.AdminRole
import com.lhs.share.hub.repository.entity.FeedbackAccessGrant
import com.lhs.share.hub.repository.entity.FeedbackCategory
import com.lhs.share.hub.repository.entity.FeedbackTicket
import com.lhs.share.hub.repository.entity.FeedbackWorkflowEvent
import com.lhs.share.hub.service.admin.AdminAuditService
import com.lhs.share.hub.service.admin.AdminAuthorizationService
import com.lhs.share.hub.service.admin.AdminPermission
import com.lhs.share.hub.service.notification.NotificationService
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
    private val ticketRepository: FeedbackTicketRepository,
    private val ticketQueryRepository: FeedbackTicketQueryRepository,
    private val workflowEvents: FeedbackWorkflowEventRepository,
    private val notificationService: NotificationService,
    private val categories: FeedbackCategoryService,
) {
    fun current(userId: String): CurrentFeedbackAccessResponse {
        val superAdmin = authorizationService.hasRole(userId, AdminRole.SUPER_ADMIN)
        val grant = repository.findById(userId).orElse(null)
        val availableAreas = categories.list().map { FeedbackAreaOptionResponse(it.key, it.label) }
        return CurrentFeedbackAccessResponse(
            superAdmin = superAdmin,
            receiveAreas = grant?.receiveAreas.orEmpty(),
            manageAreas = authorizationService.manageableAreasFor(userId),
            availableAreas = availableAreas,
            feedbackRoles = authorizationService.feedbackRolesFor(userId),
            operatorAreas = authorizationService.operatorAreasFor(userId),
            developerAreas = authorizationService.developerAreasFor(userId),
            availableWorkAreas = availableAreas,
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

    fun operatorAreas(userId: String): Set<String> = authorizationService.operatorAreasFor(userId)

    fun developerAreas(userId: String): Set<String> = authorizationService.developerAreasFor(userId)

    fun canViewTicket(userId: String, ticket: FeedbackTicket): Boolean {
        val area = FeedbackWorkflow.area(ticket)
        return area in operatorAreas(userId) ||
            ((FeedbackWorkflow.stage(ticket) == FeedbackWorkflow.DEV_HANDOFF || ticket.developerReturnedAt != null) && area in developerAreas(userId))
    }

    fun canControlTicket(userId: String, ticket: FeedbackTicket): Boolean =
        authorizationService.hasRole(userId, AdminRole.SUPER_ADMIN) ||
            (ticket.operatorAssigneeUserId == userId && FeedbackWorkflow.area(ticket) in operatorAreas(userId))

    fun canClaimTicket(userId: String, ticket: FeedbackTicket): Boolean =
        FeedbackWorkflow.area(ticket) in operatorAreas(userId)

    fun developerUserIds(area: String): Set<String> = repository.findByDeveloperAreasContaining(area)
        .filter { "DEVELOPER" in it.feedbackRoles && userService.get(it.userId)?.activated == true }
        .mapTo(linkedSetOf()) { it.userId }

    fun operatorUserIds(area: String): Set<String> = repository.findByOperatorAreasContaining(area)
        .filter { "OPERATOR" in it.feedbackRoles && userService.get(it.userId)?.activated == true }
        .mapTo(linkedSetOf()) { it.userId }

    fun assignees(actor: String, ticketId: String): List<FeedbackAssigneeResponse> {
        val ticket = ticketRepository.findById(ticketId).orElseThrow {
            ApiResultException(HttpStatus.NOT_FOUND.value(), "工单不存在: $ticketId")
        }
        if (!canControlTicket(actor, ticket)) throw ApiResultException(HttpStatus.FORBIDDEN.value(), "无权转交该工单")
        if (!FeedbackWorkflow.isActive(ticket) || FeedbackWorkflow.stage(ticket) != FeedbackWorkflow.PROCESSING) {
            throw ApiResultException(HttpStatus.CONFLICT.value(), "工单当前不可转交")
        }
        return (operatorUserIds(FeedbackWorkflow.area(ticket)) + authorizationService.superAdminUserIds())
            .filter { it != ticket.operatorAssigneeUserId }
            .mapNotNull { userId -> userService.get(userId)?.let { FeedbackAssigneeResponse(userId, it.userName) } }
            .sortedWith(compareBy({ it.userName }, { it.id }))
    }

    fun listGrants(adminUserId: String): List<FeedbackAccessGrantResponse> {
        authorizationService.requirePermission(adminUserId, AdminPermission.ADMIN_FEEDBACK_ACCESS_MANAGE)
        return repository.findAll()
            .sortedBy { userService.get(it.userId)?.userName ?: it.userId }
            .map(::toResponse)
    }

    @Transactional(transactionManager = "hubTransactionManager")
    fun createCategory(adminUserId: String, label: String): FeedbackAreaOptionResponse {
        authorizationService.requirePermission(adminUserId, AdminPermission.ADMIN_FEEDBACK_ACCESS_MANAGE)
        val created = categories.create(label)
        auditCategory(adminUserId, AdminAuditAction.FEEDBACK_CATEGORY_CREATED, created, null)
        return FeedbackAreaOptionResponse(created.key, created.label)
    }

    @Transactional(transactionManager = "hubTransactionManager")
    fun renameCategory(adminUserId: String, key: String, label: String): FeedbackAreaOptionResponse {
        authorizationService.requirePermission(adminUserId, AdminPermission.ADMIN_FEEDBACK_ACCESS_MANAGE)
        val (before, renamed) = categories.rename(key, label)
        if (renamed != before) auditCategory(adminUserId, AdminAuditAction.FEEDBACK_CATEGORY_RENAMED, renamed, before)
        return FeedbackAreaOptionResponse(renamed.key, renamed.label)
    }

    private fun auditCategory(actor: String, action: AdminAuditAction, category: FeedbackCategory, before: FeedbackCategory?) {
        auditService.record(AdminAuditLog(
            actorUserId = actor,
            action = action,
            targetResource = "feedback_categories/${category.key}",
            before = before?.let { AdminAuditSnapshot(feedbackCategoryLabel = it.label) },
            after = AdminAuditSnapshot(feedbackCategoryLabel = category.label),
        ))
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
        val before = repository.findById(userId).orElse(null)
        val receiveAreas = validateAreas(request.receiveCategories ?: request.receiveAreas ?: before?.receiveAreas.orEmpty())
        val manageAreas = validateAreas(request.manageCategories ?: request.manageAreas ?: before?.manageAreas.orEmpty())
        val roles = request.feedbackRoles ?: before?.feedbackRoles.orEmpty()
        if (roles.any { it !in setOf("OPERATOR", "DEVELOPER") }) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "无效的反馈岗位")
        }
        val operatorAreas = request.operatorAreas?.let(::validateWorkAreas) ?: before?.operatorAreas.orEmpty()
        val developerAreas = request.developerAreas?.let(::validateWorkAreas) ?: before?.developerAreas.orEmpty()
        if ((operatorAreas.isNotEmpty() && "OPERATOR" !in roles) || (developerAreas.isNotEmpty() && "DEVELOPER" !in roles)) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "板块授权需要对应岗位")
        }
        val now = Instant.now()
        val saved = repository.save(
            FeedbackAccessGrant(
                userId = userId,
                receiveAreas = receiveAreas,
                manageAreas = manageAreas,
                feedbackRoles = roles,
                operatorAreas = operatorAreas,
                developerAreas = developerAreas,
                updatedBy = adminUserId,
                updatedAt = now,
            ),
        )
        requeueUnqualified(userId, operatorAreas, adminUserId)
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
        requeueUnqualified(userId, emptySet(), adminUserId)
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
            categories.requireValid(area)
        } catch (e: IllegalArgumentException) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), e.message)
        }
    }.toSet()

    private fun requeueUnqualified(userId: String, retainedAreas: Set<String>, actorUserId: String) {
        ticketRepository.findByOperatorAssigneeUserIdAndStatusAndMergedIntoIdIsNull(userId, "OPEN")
            .filter { FeedbackWorkflow.area(it) !in retainedAreas }
            .forEach { ticket ->
                ticketQueryRepository.saveIfUnchanged(ticket, ticket.copy(
                    workflowStage = FeedbackWorkflow.UNASSIGNED,
                    operatorAssigneeUserId = null,
                    operatorAssignedAt = null,
                    developerReturnedAt = null,
                    updatedAt = Instant.now(),
                )) ?: throw ApiResultException(HttpStatus.CONFLICT.value(), "负责人工单已变化，请刷新授权后重试")
                notificationService.clearFeedbackTasks(checkNotNull(ticket.id))
                workflowEvents.save(FeedbackWorkflowEvent(
                    ticketId = checkNotNull(ticket.id), action = "REQUEUE", actorUserId = actorUserId, note = "运营权限已撤销，返回待接单池",
                ))
            }
    }

    private fun validateWorkAreas(areas: Set<String>): Set<String> = areas.map { it.trim().uppercase() }
        .also { normalized ->
            if (normalized.any { it !in categories.keys() }) {
                throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "无效的反馈板块")
            }
        }.toSet()

    private fun toResponse(grant: FeedbackAccessGrant): FeedbackAccessGrantResponse {
        return FeedbackAccessGrantResponse(
            userId = grant.userId,
            userName = userService.get(grant.userId)?.userName ?: "未知用户",
            receiveAreas = grant.receiveAreas,
            manageAreas = grant.manageAreas,
            feedbackRoles = grant.feedbackRoles,
            operatorAreas = grant.operatorAreas,
            developerAreas = grant.developerAreas,
            updatedBy = grant.updatedBy,
            updatedAt = grant.updatedAt,
        )
    }

    private fun auditSnapshot(grant: FeedbackAccessGrant) = AdminAuditSnapshot(
        receiveAreas = grant.receiveAreas,
        manageAreas = grant.manageAreas,
        feedbackRoles = grant.feedbackRoles,
        operatorAreas = grant.operatorAreas,
        developerAreas = grant.developerAreas,
    )
}
