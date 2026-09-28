package com.lhs.share.hub.service.report

import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.controller.response.user.MaaUserInfo
import com.lhs.share.hub.controller.report.request.FeedbackAccessUpdateRequest
import com.lhs.share.hub.repository.FeedbackAccessGrantRepository
import com.lhs.share.hub.repository.FeedbackTicketRepository
import com.lhs.share.hub.repository.FeedbackTicketQueryRepository
import com.lhs.share.hub.repository.FeedbackWorkflowEventRepository
import com.lhs.share.hub.repository.entity.AdminAuditAction
import com.lhs.share.hub.repository.entity.AdminAuditLog
import com.lhs.share.hub.repository.entity.FeedbackAccessGrant
import com.lhs.share.hub.repository.entity.FeedbackTicket
import com.lhs.share.hub.repository.entity.AdminRole
import com.lhs.share.hub.service.admin.AdminAuditService
import com.lhs.share.hub.service.admin.AdminAuthorizationService
import com.lhs.share.hub.service.admin.AdminPermission
import com.lhs.share.hub.service.notification.NotificationService
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
    private val tickets = mockk<FeedbackTicketRepository>()
    private val ticketQueries = mockk<FeedbackTicketQueryRepository>()
    private val events = mockk<FeedbackWorkflowEventRepository>(relaxed = true)
    private val notifications = mockk<NotificationService>(relaxed = true)
    private val service = FeedbackAccessService(repository, userService, authorizationService, auditService, tickets, ticketQueries, events, notifications)

    init {
        every { tickets.findByOperatorAssigneeUserIdAndStatusAndMergedIntoIdIsNull(any(), any()) } returns emptyList()
    }

    @Test
    fun `程序岗只可查看转交至本人板块的工单且不能执行运营控制`() {
        val ticket = FeedbackTicket(id = "rpt_1", type = "BUG", category = "OPERATOR", workArea = "STAR", reporterUserId = "reporter", content = "反馈")
        every { authorizationService.operatorAreasFor("dev") } returns emptySet()
        every { authorizationService.developerAreasFor("dev") } returns setOf("STAR")
        every { authorizationService.hasRole("dev", AdminRole.SUPER_ADMIN) } returns false

        assertFalse(service.canViewTicket("dev", ticket))
        assertTrue(service.canViewTicket("dev", ticket.copy(workflowStage = FeedbackWorkflow.DEV_HANDOFF)))
        assertFalse(service.canControlTicket("dev", ticket.copy(workflowStage = FeedbackWorkflow.DEV_HANDOFF)))
    }

    @Test
    fun `旧管理授权不自动授予新岗位`() {
        every { authorizationService.operatorAreasFor("legacy") } returns emptySet()
        every { authorizationService.developerAreasFor("legacy") } returns emptySet()
        val ticket = FeedbackTicket(id = "rpt_1", type = "BUG", category = "OPERATOR", reporterUserId = "reporter", content = "反馈")

        assertFalse(service.canViewTicket("legacy", ticket))
        assertFalse(service.canClaimTicket("legacy", ticket))
    }

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
    fun `转交候选人只包含当前板块已激活的其他运营`() {
        val ticket = FeedbackTicket(id = "rpt_1", type = "BUG", category = "OPERATOR", workArea = "STAR", workflowStage = FeedbackWorkflow.PROCESSING, operatorAssigneeUserId = "owner", reporterUserId = "reporter", content = "反馈")
        every { tickets.findById("rpt_1") } returns Optional.of(ticket)
        every { authorizationService.hasRole("owner", AdminRole.SUPER_ADMIN) } returns false
        every { authorizationService.operatorAreasFor("owner") } returns setOf("STAR")
        every { authorizationService.superAdminUserIds() } returns setOf("root")
        every { repository.findByOperatorAreasContaining("STAR") } returns listOf(
            FeedbackAccessGrant(userId = "owner", feedbackRoles = setOf("OPERATOR"), operatorAreas = setOf("STAR"), updatedBy = "root"),
            FeedbackAccessGrant(userId = "active", feedbackRoles = setOf("OPERATOR"), operatorAreas = setOf("STAR"), updatedBy = "root"),
            FeedbackAccessGrant(userId = "disabled", feedbackRoles = setOf("OPERATOR"), operatorAreas = setOf("STAR"), updatedBy = "root"),
            FeedbackAccessGrant(userId = "developer", feedbackRoles = setOf("DEVELOPER"), operatorAreas = setOf("STAR"), updatedBy = "root"),
        )
        every { userService.get("owner") } returns MaaUserInfo("owner", "原负责人", activated = true)
        every { userService.get("active") } returns MaaUserInfo("active", "新负责人", activated = true)
        every { userService.get("disabled") } returns MaaUserInfo("disabled", "已停用", activated = false)
        every { userService.get("root") } returns MaaUserInfo("root", "超级管理员", activated = true)

        assertEquals(setOf("active", "root"), service.assignees("owner", "rpt_1").map { it.id }.toSet())
        verify(exactly = 0) { ticketQueries.saveIfUnchanged(any(), any()) }
    }

    @Test
    fun `转交候选人拒绝非负责人和已结束工单`() {
        val ticket = FeedbackTicket(id = "rpt_1", type = "BUG", category = "OPERATOR", workArea = "STAR", workflowStage = FeedbackWorkflow.PROCESSING, operatorAssigneeUserId = "owner", reporterUserId = "reporter", content = "反馈")
        every { tickets.findById("rpt_1") } returns Optional.of(ticket)
        every { authorizationService.hasRole("other", AdminRole.SUPER_ADMIN) } returns false

        assertEquals(403, assertThrows(ApiResultException::class.java) { service.assignees("other", "rpt_1") }.statusCode)

        every { tickets.findById("rpt_1") } returns Optional.of(ticket.copy(status = "RESOLVED"))
        every { authorizationService.hasRole("owner", AdminRole.SUPER_ADMIN) } returns true
        assertEquals(409, assertThrows(ApiResultException::class.java) { service.assignees("owner", "rpt_1") }.statusCode)
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
    fun `撤销运营板块时活动工单返回待接单池`() {
        val oldGrant = FeedbackAccessGrant(
            userId = "manager", feedbackRoles = setOf("OPERATOR"), operatorAreas = setOf("STAR"), updatedBy = "root",
        )
        val assigned = FeedbackTicket(
            id = "rpt_1", type = "BUG", category = "OPERATOR", workArea = "STAR", workflowStage = FeedbackWorkflow.PROCESSING,
            operatorAssigneeUserId = "manager", reporterUserId = "reporter", content = "反馈",
        )
        every { authorizationService.requirePermission("root", AdminPermission.ADMIN_FEEDBACK_ACCESS_MANAGE) } returns Unit
        every { userService.getRequired("manager") } returns MaaUserInfo("manager", "处理人", activated = true)
        every { userService.get("manager") } returns MaaUserInfo("manager", "处理人", activated = true)
        every { repository.findById("manager") } returns Optional.of(oldGrant)
        every { repository.save(any()) } answers { firstArg() }
        every { tickets.findByOperatorAssigneeUserIdAndStatusAndMergedIntoIdIsNull("manager", "OPEN") } returns listOf(assigned)
        every { ticketQueries.saveIfUnchanged(assigned, any()) } answers { secondArg() }

        service.updateGrant("root", "manager", FeedbackAccessUpdateRequest(feedbackRoles = emptySet(), operatorAreas = emptySet()))

        verify { ticketQueries.saveIfUnchanged(assigned, match {
            it.workflowStage == FeedbackWorkflow.UNASSIGNED && it.operatorAssigneeUserId == null
        }) }
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
