package com.lhs.share.hub.service.report

import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.hub.repository.FeedbackTicketQueryRepository
import com.lhs.share.hub.repository.FeedbackTicketRepository
import com.lhs.share.hub.repository.FeedbackWorkflowEventRepository
import com.lhs.share.hub.repository.entity.FeedbackMessage
import com.lhs.share.hub.repository.entity.FeedbackTicket
import com.lhs.share.hub.service.notification.NotificationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

class FeedbackWorkflowServiceTest {
    private val tickets = mockk<FeedbackTicketRepository>()
    private val query = mockk<FeedbackTicketQueryRepository>()
    private val events = mockk<FeedbackWorkflowEventRepository>(relaxed = true)
    private val access = mockk<FeedbackAccessService>(relaxed = true)
    private val notifications = mockk<NotificationService>(relaxed = true)
    private val service = FeedbackWorkflowService(tickets, query, events, access, notifications)

    private fun ticket(id: String = "rpt_1") = FeedbackTicket(
        id = id,
        type = "BUG",
        category = "OPERATOR",
        area = "OPERATOR",
        status = "OPEN",
        reporterUserId = "reporter",
        content = "反馈",
        messages = listOf(FeedbackMessage("rpm_1", "REPORTER", "reporter", "反馈")),
    )

    @Test
    fun `旧开放工单未接单且历史消息不制造团队未读`() {
        val old = ticket()
        assertEquals(FeedbackWorkflow.UNASSIGNED, FeedbackWorkflow.stage(old))
        assertEquals(FeedbackArea.OPERATOR, FeedbackWorkflow.area(old))
        assertTrue(FeedbackWorkflow.needsReply(old))
        assertFalse(FeedbackWorkflow.needsReply(old.copy(messages = old.messages + FeedbackMessage("rpm_2", "ADMIN", "admin", "已回复"))))
    }

    @Test
    fun `接单使用快照比较且冲突返回 409`() {
        val old = ticket()
        every { tickets.findById("rpt_1") } returns Optional.of(old)
        every { access.canClaimTicket("alice", old) } returns true
        every { query.saveIfUnchanged(old, any()) } returns null

        val error = assertThrows(ApiResultException::class.java) { service.claim("alice", "rpt_1") }

        assertEquals(409, error.statusCode)
        verify(exactly = 0) { events.save(any()) }
    }

    @Test
    fun `接单保存处理中阶段并阻止重复接单`() {
        var stored = ticket()
        every { tickets.findById("rpt_1") } answers { Optional.of(stored) }
        every { access.canClaimTicket("alice", any()) } returns true
        every { query.saveIfUnchanged(any(), any()) } answers { secondArg<FeedbackTicket>().also { stored = it } }

        val claimed = service.claim("alice", "rpt_1")

        assertEquals(FeedbackWorkflow.PROCESSING, claimed.workflowStage)
        assertEquals("alice", claimed.operatorAssigneeUserId)
        assertEquals(409, assertThrows(ApiResultException::class.java) { service.claim("alice", "rpt_1") }.statusCode)
        verify(exactly = 1) { query.saveIfUnchanged(any(), any()) }
    }

    @Test
    fun `无程序板块权限不能读取内部处理记录`() {
        val old = ticket().copy(workflowStage = FeedbackWorkflow.DEV_HANDOFF, workArea = "STAR")
        every { tickets.findById("rpt_1") } returns Optional.of(old)
        every { access.canViewTicket("outsider", old) } returns false

        assertEquals(403, assertThrows(ApiResultException::class.java) { service.events("outsider", "rpt_1") }.statusCode)
        verify(exactly = 0) { events.findByTicketIdOrderByCreatedAtAsc(any()) }
    }

    @Test
    fun `阅读仅推进请求对应的用户消息边界`() {
        val old = ticket().copy(messages = listOf(
            FeedbackMessage("rpm_old", "REPORTER", "reporter", "旧消息"),
            FeedbackMessage("rpm_new", "REPORTER", "reporter", "新消息"),
        ))
        every { tickets.findById("rpt_1") } returns Optional.of(old)
        every { access.canViewTicket("alice", old) } returns true
        every { query.advanceTeamRead("rpt_1", 0) } returns old.copy(teamReadReporterIndex = 0)

        service.markRead("alice", "rpt_1", "rpm_old")

        verify(exactly = 1) { query.advanceTeamRead("rpt_1", 0) }
        verify(exactly = 1) { notifications.clearFeedbackReminders("rpt_1", 0) }
    }

    @Test
    fun `无效阅读边界不推进团队游标`() {
        val old = ticket()
        every { tickets.findById("rpt_1") } returns Optional.of(old)
        every { access.canViewTicket("alice", old) } returns true

        assertEquals(400, assertThrows(ApiResultException::class.java) {
            service.markRead("alice", "rpt_1", "rpm_missing")
        }.statusCode)
        verify(exactly = 0) { query.advanceTeamRead(any(), any()) }
    }
}
