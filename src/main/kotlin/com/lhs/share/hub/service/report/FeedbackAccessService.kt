package com.lhs.share.hub.service.report

import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.hub.controller.report.request.FeedbackAccessUpdateRequest
import com.lhs.share.hub.controller.report.response.CurrentFeedbackAccessResponse
import com.lhs.share.hub.controller.report.response.FeedbackAccessGrantResponse
import com.lhs.share.hub.controller.report.response.FeedbackAreaOptionResponse
import com.lhs.share.hub.repository.FeedbackAccessGrantRepository
import com.lhs.share.hub.repository.entity.FeedbackAccessGrant
import com.lhs.share.service.UserService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class FeedbackAccessService(
    private val repository: FeedbackAccessGrantRepository,
    private val userService: UserService,
) {
    fun current(userId: String): CurrentFeedbackAccessResponse {
        val superAdmin = userService.hasAdminPrivileges(userId)
        val grant = repository.findById(userId).orElse(null)
        return CurrentFeedbackAccessResponse(
            superAdmin = superAdmin,
            receiveAreas = grant?.receiveAreas.orEmpty(),
            manageAreas = if (superAdmin) FeedbackArea.all else grant?.manageAreas.orEmpty(),
            availableAreas = FeedbackArea.labels.map { (key, label) -> FeedbackAreaOptionResponse(key, label) },
        )
    }

    fun canView(userId: String, area: String): Boolean {
        if (userService.hasAdminPrivileges(userId)) return true
        val grant = repository.findById(userId).orElse(null) ?: return false
        return area in grant.receiveAreas || area in grant.manageAreas
    }

    fun canManage(userId: String, area: String): Boolean {
        if (userService.hasAdminPrivileges(userId)) return true
        return repository.findById(userId).orElse(null)?.manageAreas?.contains(area) == true
    }

    fun manageableAreas(userId: String): Set<String> {
        if (userService.hasAdminPrivileges(userId)) return FeedbackArea.all
        return repository.findById(userId).orElse(null)?.manageAreas.orEmpty()
    }

    fun receiverUserIds(area: String): Set<String> = repository.findByReceiveAreasContaining(area)
        .map { it.userId }
        .toSet()

    fun listGrants(adminUserId: String): List<FeedbackAccessGrantResponse> {
        requireSuperAdmin(adminUserId)
        return repository.findAll()
            .sortedBy { userService.get(it.userId)?.userName ?: it.userId }
            .map(::toResponse)
    }

    fun updateGrant(adminUserId: String, userId: String, request: FeedbackAccessUpdateRequest): FeedbackAccessGrantResponse {
        requireSuperAdmin(adminUserId)
        userService.getRequired(userId)
        val receiveAreas = validateAreas(request.receiveCategories ?: request.receiveAreas)
        val manageAreas = validateAreas(request.manageCategories ?: request.manageAreas)
        val saved = repository.save(
            FeedbackAccessGrant(
                userId = userId,
                receiveAreas = receiveAreas,
                manageAreas = manageAreas,
                updatedBy = adminUserId,
                updatedAt = Instant.now(),
            ),
        )
        return toResponse(saved)
    }

    fun deleteGrant(adminUserId: String, userId: String) {
        requireSuperAdmin(adminUserId)
        repository.deleteById(userId)
    }

    private fun validateAreas(areas: Set<String>): Set<String> = areas.map { area ->
        try {
            FeedbackArea.requireValid(area)
        } catch (e: IllegalArgumentException) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), e.message)
        }
    }.toSet()

    private fun requireSuperAdmin(userId: String) {
        if (!userService.hasAdminPrivileges(userId)) {
            throw ApiResultException(HttpStatus.FORBIDDEN.value(), "仅超级管理员可配置反馈权限")
        }
    }

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
}
