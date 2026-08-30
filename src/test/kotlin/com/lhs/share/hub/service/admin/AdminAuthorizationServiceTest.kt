package com.lhs.share.hub.service.admin

import com.lhs.share.controller.response.user.MaaUserInfo
import com.lhs.share.hub.repository.AdminRoleBindingRepository
import com.lhs.share.hub.repository.FeedbackAccessGrantRepository
import com.lhs.share.hub.repository.entity.AdminRole
import com.lhs.share.hub.repository.entity.AdminRoleBinding
import com.lhs.share.hub.repository.entity.FeedbackAccessGrant
import com.lhs.share.hub.service.report.FeedbackArea
import com.lhs.share.service.UserService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class AdminAuthorizationServiceTest {
    private val roleRepository = mockk<AdminRoleBindingRepository>()
    private val feedbackRepository = mockk<FeedbackAccessGrantRepository>()
    private val userService = mockk<UserService>()
    private val service = AdminAuthorizationService(roleRepository, feedbackRepository, userService)

    @Test
    fun `平台管理员只能获得公共图鉴权限`() {
        activeUser("platform")
        every { roleRepository.findById("platform") } returns Optional.of(binding("platform", AdminRole.PLATFORM_ADMIN))

        assertTrue(service.hasPermission("platform", AdminPermission.OPERATOR_CATALOG_WRITE))
        assertFalse(service.hasPermission("platform", AdminPermission.ADMIN_ROLE_MANAGE))
        assertFalse(service.hasPermission("platform", AdminPermission.ADMIN_FEEDBACK_ACCESS_MANAGE))
    }

    @Test
    fun `超级管理员继承平台和全部反馈权限`() {
        activeUser("root")
        every { roleRepository.findById("root") } returns Optional.of(binding("root", AdminRole.SUPER_ADMIN))

        assertTrue(service.hasPermission("root", AdminPermission.OPERATOR_CATALOG_WRITE))
        assertTrue(service.hasPermission("root", AdminPermission.ADMIN_ROLE_MANAGE))
        assertEquals(FeedbackArea.all, service.manageableAreasFor("root"))
    }

    @Test
    fun `receiveAreas 只接收通知不能读取工单`() {
        activeUser("receiver")
        every { roleRepository.findById("receiver") } returns Optional.empty()
        every { feedbackRepository.findById("receiver") } returns Optional.of(
            FeedbackAccessGrant(
                userId = "receiver",
                receiveAreas = setOf(FeedbackArea.INVENTORY),
                updatedBy = "root",
            ),
        )

        assertFalse(service.canReadFeedback("receiver", FeedbackArea.INVENTORY))
    }

    @Test
    fun `禁用账号的角色绑定立即失效`() {
        every { userService.get("disabled") } returns MaaUserInfo("disabled", "禁用用户", activated = false)

        assertFalse(service.hasPermission("disabled", AdminPermission.OPERATOR_CATALOG_WRITE))
        assertEquals(emptySet<String>(), service.manageableAreasFor("disabled"))
    }

    private fun activeUser(userId: String) {
        every { userService.get(userId) } returns MaaUserInfo(userId, userId, activated = true)
    }

    private fun binding(userId: String, role: AdminRole) = AdminRoleBinding(
        userId = userId,
        roles = setOf(role),
        grantedBy = "seed",
        grantedAt = Instant.EPOCH,
        updatedBy = "seed",
        updatedAt = Instant.EPOCH,
    )
}
