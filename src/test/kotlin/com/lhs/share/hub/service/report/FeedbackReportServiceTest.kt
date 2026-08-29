package com.lhs.share.hub.service.report

import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.controller.response.user.MaaUserInfo
import com.lhs.share.hub.controller.report.request.FeedbackReportCreateRequest
import com.lhs.share.hub.repository.FeedbackTicketQueryRepository
import com.lhs.share.hub.repository.FeedbackTicketRepository
import com.lhs.share.hub.repository.MediaAssetRepository
import com.lhs.share.hub.repository.entity.FeedbackTicket
import com.lhs.share.hub.service.HubUserInfoService
import com.lhs.share.hub.service.notification.NotificationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FeedbackReportServiceTest {
    private val ticketRepository = mockk<FeedbackTicketRepository>()
    private val queryRepository = mockk<FeedbackTicketQueryRepository>()
    private val accessService = mockk<FeedbackAccessService>()
    private val mediaRepository = mockk<MediaAssetRepository>()
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val userInfoService = mockk<HubUserInfoService>()
    private val service = FeedbackReportService(
        ticketRepository,
        queryRepository,
        accessService,
        mediaRepository,
        notificationService,
        userInfoService,
    )

    private fun prepareCreate() {
        every { mediaRepository.findAllById(emptyList()) } returns emptyList()
        every { ticketRepository.save(any()) } answers { firstArg() }
        every { accessService.receiverUserIds(any()) } returns emptySet()
        every { accessService.canManage("user", any()) } returns false
        every { userInfoService.get("user") } returns MaaUserInfo("user", "用户")
    }

    @Test
    fun `新格式使用 type 和 category 保存`() {
        prepareCreate()

        val response = service.create(
            "user",
            FeedbackReportCreateRequest(type = "bug", category = "operator", content = "密探无法保存"),
        )

        assertEquals(FeedbackType.BUG, response.type)
        assertEquals(FeedbackArea.OPERATOR, response.category)
        verify {
            ticketRepository.save(match {
                it.type == FeedbackType.BUG &&
                    it.category == FeedbackArea.OPERATOR &&
                    it.area == FeedbackArea.OPERATOR
            })
        }
    }

    @Test
    fun `旧 FEEDBACK category area 请求映射到新语义`() {
        prepareCreate()

        val response = service.create(
            "user",
            FeedbackReportCreateRequest(
                type = "FEEDBACK",
                category = "BUG",
                area = "OPERATOR",
                content = "旧客户端反馈",
            ),
        )

        assertEquals(FeedbackType.BUG, response.type)
        assertEquals(FeedbackArea.OPERATOR, response.category)
    }

    @Test
    fun `只有旧 bug 类型时使用 OTHER 板块`() {
        prepareCreate()

        val response = service.create(
            "user",
            FeedbackReportCreateRequest(type = "bug", content = "没有板块信息"),
        )

        assertEquals(FeedbackType.BUG, response.type)
        assertEquals(FeedbackArea.OTHER, response.category)
    }

    @Test
    fun `拒绝未知类型和缺少 REPORT 板块`() {
        prepareCreate()

        assertThrows(ApiResultException::class.java) {
            service.create("user", FeedbackReportCreateRequest(type = "UNKNOWN", content = "无效"))
        }
        assertThrows(ApiResultException::class.java) {
            service.create("user", FeedbackReportCreateRequest(type = "REPORT", content = "缺少板块"))
        }
    }
}
