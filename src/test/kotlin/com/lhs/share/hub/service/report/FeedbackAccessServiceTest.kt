package com.lhs.share.hub.service.report

import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.controller.response.user.MaaUserInfo
import com.lhs.share.hub.controller.report.request.FeedbackAccessUpdateRequest
import com.lhs.share.hub.repository.FeedbackAccessGrantRepository
import com.lhs.share.hub.repository.entity.AdminAuditAction
import com.lhs.share.hub.repository.entity.AdminAuditLog
import com.lhs.share.hub.repository.entity.FeedbackAccessGrant
import com.lhs.share.hub.service.admin.AdminAuditService
import com.lhs.share.hub.service.admin.AdminAuthorizationService
import com.lhs.share.hub.service.admin.AdminPermission
import com.lhs.share.repository.entity.MaaUser
import com.lhs.share.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import java.util.Optional

class FeedbackAccessServiceTest {
    private val repository = mockk<FeedbackAccessGrantRepository>()
    private val userService = mockk<UserService>()
    private val authorizationService = mockk<AdminAuthorizationService>()
    private val auditService = mockk<AdminAuditService>(relaxed = true)
    private val service = FeedbackAccessService(repository, userService, authorizationService, auditService)

    @Test
    fun `反馈模块管理员只能查看和管理 manageAreas`() {
        every { authorizationService.canReadFeedback("manager", FeedbackArea.INVENTORY) } returns false
        every { authorizationService.canReadFeedback("manager", FeedbackArea.OPERATOR) } returns true
        every { authorizationService.canManageFeedback("manager", FeedbackArea.OPERATOR) } returns true
        every { authorizationService.canManageFeedback("manager", FeedbackArea.INVENTORY) } returns false
        every { authorizationService.manageableAreasFor("manager") } returns setOf(FeedbackArea.OPERATOR)

        assertFalse(service.canView("manager", FeedbackArea.INVENTORY))
        assertTrue(service.canView("manager", FeedbackArea.OPERATOR))
        assertTrue(service.canManage("manager", FeedbackArea.OPERATOR))
        assertFalse(service.canManage("manager", FeedbackArea.INVENTORY))
        assertEquals(setOf(FeedbackArea.OPERATOR), service.manageableAreas("manager"))
    }

    @Test
    fun `超级管理员可以管理全部模块`() {
        every { authorizationService.canManageFeedback("root", FeedbackArea.LEDGER) } returns true
        every { authorizationService.manageableAreasFor("root") } returns FeedbackArea.all

        assertTrue(service.canManage("root", FeedbackArea.LEDGER))
        assertEquals(FeedbackArea.all, service.manageableAreas("root"))
    }

    @Test
    fun `用户反馈通知接收者使用 manageAreas 管理者集合`() {
        every { authorizationService.managerUserIdsFor(FeedbackArea.OPERATOR) } returns setOf("manager", "root")

        assertEquals(
            setOf("manager", "root"),
            service.managerUserIds(FeedbackArea.OPERATOR),
        )
        verify { authorizationService.managerUserIdsFor(FeedbackArea.OPERATOR) }
    }

    @Test
    fun `新反馈通知排除未激活和不存在用户且保留独立接收授权`() {
        every { repository.findByReceiveAreasContaining(FeedbackArea.OPERATOR) } returns listOf(
            FeedbackAccessGrant(userId = "receiver", receiveAreas = setOf(FeedbackArea.OPERATOR), updatedBy = "root"),
            FeedbackAccessGrant(userId = "disabled", receiveAreas = setOf(FeedbackArea.OPERATOR), updatedBy = "root"),
            FeedbackAccessGrant(userId = "missing", receiveAreas = setOf(FeedbackArea.OPERATOR), updatedBy = "root"),
        )
        every { userService.get("receiver") } returns MaaUserInfo("receiver", "接收者", activated = true)
        every { userService.get("disabled") } returns MaaUserInfo("disabled", "未激活", activated = false)
        every { userService.get("missing") } returns null

        assertEquals(setOf("receiver"), service.receiverUserIds(FeedbackArea.OPERATOR))
        verify(exactly = 0) { authorizationService.canManageFeedback(any(), any()) }
    }

    @Test
    fun `更新授权分别保存接收与管理模块`() {
        every { authorizationService.requirePermission("root", AdminPermission.ADMIN_FEEDBACK_ACCESS_MANAGE) } returns Unit
        every { userService.getRequired("manager") } returns MaaUserInfo("manager", "处理人", activated = true)
        every { userService.get("manager") } returns MaaUserInfo("manager", "处理人", activated = true)
        every { repository.findById("manager") } returns Optional.empty()
        every { repository.save(any()) } answers { firstArg() }

        val response = service.updateGrant(
            "root",
            "manager",
            FeedbackAccessUpdateRequest(
                receiveAreas = setOf(FeedbackArea.INVENTORY),
                manageAreas = setOf(FeedbackArea.OPERATOR),
            ),
        )

        assertEquals(setOf(FeedbackArea.INVENTORY), response.receiveAreas)
        assertEquals(setOf(FeedbackArea.OPERATOR), response.manageAreas)
        verify {
            auditService.record(
                match<AdminAuditLog> {
                    it.action == AdminAuditAction.FEEDBACK_ACCESS_UPDATED &&
                        it.targetUserId == "manager" &&
                        it.before == null &&
                        it.after?.manageAreas == setOf(FeedbackArea.OPERATOR)
                },
            )
        }
    }

    @Test
    fun `更新授权拒绝未知模块`() {
        every { authorizationService.requirePermission("root", AdminPermission.ADMIN_FEEDBACK_ACCESS_MANAGE) } returns Unit
        every { userService.getRequired("manager") } returns MaaUserInfo("manager", "处理人", activated = true)

        assertThrows(ApiResultException::class.java) {
            service.updateGrant(
                "root",
                "manager",
                FeedbackAccessUpdateRequest(receiveAreas = setOf("UNKNOWN")),
            )
        }
    }

    @Test
    fun `超级管理员可按用户名或邮箱搜索已激活候选`() {
        every { authorizationService.requirePermission("root", AdminPermission.ADMIN_FEEDBACK_ACCESS_MANAGE) } returns Unit
        every { userService.findFeedbackAccessUserByEmail("alice+@example.com") } returns null
        every { userService.searchFeedbackAccessUsers(any(), any()) } returns PageImpl(
            listOf(
                MaaUser(
                    userId = "user-1",
                    userName = "alice",
                    email = "alice@example.com",
                    password = "unused",
                    status = 1,
                ),
            ),
        )

        val candidates = service.searchUserCandidates("root", "alice+@example.com", 1, 10)

        assertEquals(1, candidates.size)
        assertEquals("alice@example.com", candidates.single().email)
        assertTrue(candidates.single().activated)
    }

    @Test
    fun `完整邮箱优先精确匹配并忽略大小写且支持管理员账号`() {
        every { authorizationService.requirePermission("root", AdminPermission.ADMIN_FEEDBACK_ACCESS_MANAGE) } returns Unit
        every { userService.findFeedbackAccessUserByEmail("YANGPEIDE0208@GMAIL.COM") } returns MaaUser(
            userId = "user-1",
            userName = "yangpeide",
            email = "yangpeide0208@gmail.com",
            password = "unused",
            status = 2,
        )

        val candidates = service.searchUserCandidates("root", " YANGPEIDE0208@GMAIL.COM ", 1, 10)

        assertEquals("yangpeide", candidates.single().userName)
        assertTrue(candidates.single().activated)
        verify(exactly = 0) { userService.searchFeedbackAccessUsers(any(), any()) }
    }

    @Test
    fun `候选搜索拒绝空关键词和非法分页`() {
        every { authorizationService.requirePermission("root", AdminPermission.ADMIN_FEEDBACK_ACCESS_MANAGE) } returns Unit

        assertThrows(ApiResultException::class.java) {
            service.searchUserCandidates("root", "   ", 1, 10)
        }
        assertThrows(ApiResultException::class.java) {
            service.searchUserCandidates("root", "alice", 0, 10)
        }
        assertThrows(ApiResultException::class.java) {
            service.searchUserCandidates("root", "alice", 1, 11)
        }
    }

    @Test
    fun `更新授权拒绝未激活用户`() {
        every { authorizationService.requirePermission("root", AdminPermission.ADMIN_FEEDBACK_ACCESS_MANAGE) } returns Unit
        every { userService.getRequired("disabled") } returns MaaUserInfo("disabled", "已禁用")

        assertThrows(ApiResultException::class.java) {
            service.updateGrant(
                "root",
                "disabled",
                FeedbackAccessUpdateRequest(receiveAreas = setOf(FeedbackArea.OPERATOR)),
            )
        }
    }

    @Test
    fun `删除反馈授权记录删除前快照审计`() {
        val grant = FeedbackAccessGrant(
            userId = "manager",
            receiveAreas = setOf(FeedbackArea.INVENTORY),
            manageAreas = setOf(FeedbackArea.OPERATOR),
            updatedBy = "root",
        )
        every { authorizationService.requirePermission("root", AdminPermission.ADMIN_FEEDBACK_ACCESS_MANAGE) } returns Unit
        every { repository.findById("manager") } returns Optional.of(grant)
        every { repository.deleteById("manager") } returns Unit

        service.deleteGrant("root", "manager")

        verify { repository.deleteById("manager") }
        verify {
            auditService.record(
                match<AdminAuditLog> {
                    it.action == AdminAuditAction.FEEDBACK_ACCESS_DELETED &&
                        it.before?.receiveAreas == setOf(FeedbackArea.INVENTORY) &&
                        it.after == null
                },
            )
        }
    }
}
