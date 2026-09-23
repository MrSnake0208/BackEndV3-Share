package com.lhs.share.hub.service.admin

import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.hub.repository.AdminRoleBindingRepository
import com.lhs.share.hub.repository.FeedbackAccessGrantRepository
import com.lhs.share.hub.repository.entity.AdminRole
import com.lhs.share.hub.service.report.FeedbackArea
import com.lhs.share.service.UserService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

enum class AdminPermission(val value: String) {
    BETA_MANAGE("beta:manage"),
    OPERATOR_CATALOG_WRITE("operator_catalog:write"),
    LEVEL_CATALOG_WRITE("level_catalog:write"),
    CHANGELOG_WRITE("changelog:write"),
    CHANGELOG_REVIEW("changelog:review"),
    ADMIN_ROLE_MANAGE("admin:role:manage"),
    ADMIN_FEEDBACK_ACCESS_MANAGE("admin:feedback_access:manage"),
    ADMIN_AUDIT_READ("admin:audit:read"),
}

@Service
class AdminAuthorizationService(
    private val roleRepository: AdminRoleBindingRepository,
    private val feedbackAccessRepository: FeedbackAccessGrantRepository,
    private val userService: UserService,
) {
    fun rolesFor(userId: String): Set<AdminRole> {
        if (userService.get(userId)?.activated != true) return emptySet()
        return roleRepository.findById(userId).orElse(null)?.roles.orEmpty()
    }

    fun hasRole(userId: String, role: AdminRole): Boolean = role in rolesFor(userId)

    /** Matches the frontend's management-capability semantics: admin roles or feedback manage grants. */
    fun hasAnyAdminCapability(userId: String): Boolean {
        if (userService.get(userId)?.activated != true) return false
        val roles = roleRepository.findById(userId).orElse(null)?.roles.orEmpty()
        if (roles.isNotEmpty()) return true
        return feedbackAccessRepository.findById(userId).orElse(null)?.manageAreas.orEmpty().isNotEmpty()
    }

    fun hasPermission(userId: String, permission: AdminPermission): Boolean {
        val roles = rolesFor(userId)
        if (AdminRole.SUPER_ADMIN in roles) return true
        return when (permission) {
            AdminPermission.BETA_MANAGE,
            AdminPermission.OPERATOR_CATALOG_WRITE,
            AdminPermission.LEVEL_CATALOG_WRITE,
            -> AdminRole.PLATFORM_ADMIN in roles
            AdminPermission.CHANGELOG_WRITE -> AdminRole.CHANGELOG_EDITOR in roles
            AdminPermission.CHANGELOG_REVIEW -> AdminRole.CHANGELOG_REVIEWER in roles
            else -> false
        }
    }

    fun requirePermission(userId: String, permission: AdminPermission) {
        if (!hasPermission(userId, permission)) {
            throw ApiResultException(HttpStatus.FORBIDDEN.value(), "权限不足: ${permission.value}")
        }
    }

    fun receiveAreasFor(userId: String): Set<String> {
        if (userService.get(userId)?.activated != true) return emptySet()
        return feedbackAccessRepository.findById(userId).orElse(null)?.receiveAreas.orEmpty()
    }

    fun manageableAreasFor(userId: String): Set<String> {
        if (userService.get(userId)?.activated != true) return emptySet()
        val roles = roleRepository.findById(userId).orElse(null)?.roles.orEmpty()
        if (AdminRole.SUPER_ADMIN in roles) return FeedbackArea.all
        return feedbackAccessRepository.findById(userId).orElse(null)?.manageAreas.orEmpty()
    }

    fun managerUserIdsFor(area: String): Set<String> {
        val grantUserIds = feedbackAccessRepository.findByManageAreasContaining(area)
            .map { it.userId }
        val superAdminUserIds = roleRepository.findByRolesContaining(AdminRole.SUPER_ADMIN)
            .map { it.userId }
        return (grantUserIds + superAdminUserIds)
            .filter { userService.get(it)?.activated == true }
            .toSet()
    }

    fun canReadFeedback(userId: String, area: String): Boolean = area in manageableAreasFor(userId)

    fun canManageFeedback(userId: String, area: String): Boolean = area in manageableAreasFor(userId)

    fun requireFeedbackRead(userId: String, area: String) {
        if (!canReadFeedback(userId, area)) {
            throw ApiResultException(HttpStatus.FORBIDDEN.value(), "没有该反馈模块的查看权限")
        }
    }

    fun requireFeedbackManage(userId: String, area: String) {
        if (!canManageFeedback(userId, area)) {
            throw ApiResultException(HttpStatus.FORBIDDEN.value(), "没有该反馈模块的管理权限")
        }
    }

    fun permissionsFor(userId: String): Set<String> {
        val permissions = AdminPermission.entries
            .filter { hasPermission(userId, it) }
            .mapTo(linkedSetOf()) { it.value }
        manageableAreasFor(userId).forEach { area ->
            permissions += "feedback:$area:read"
            permissions += "feedback:$area:manage"
        }
        return permissions.toSortedSet()
    }
}
