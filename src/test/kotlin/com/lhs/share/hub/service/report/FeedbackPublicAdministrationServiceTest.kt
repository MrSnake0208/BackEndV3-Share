package com.lhs.share.hub.service.report

import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.hub.controller.report.request.FeedbackMergeRequest
import com.lhs.share.hub.controller.report.request.FeedbackPublicStatusRequest
import com.lhs.share.hub.controller.report.request.FeedbackPublishRequest
import com.lhs.share.hub.repository.FeedbackSupportRepository
import com.lhs.share.hub.repository.FeedbackTicketQueryRepository
import com.lhs.share.hub.repository.FeedbackTicketRepository
import com.lhs.share.hub.repository.entity.FeedbackTicket
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class FeedbackPublicAdministrationServiceTest {
    private val ticketRepository = mockk<FeedbackTicketRepository>()
    private val queryRepository = mockk<FeedbackTicketQueryRepository>()
    private val supportRepository = mockk<FeedbackSupportRepository>()
    private val accessService = mockk<FeedbackAccessService>()
    private val service = FeedbackPublicAdministrationService(
        ticketRepository,
        queryRepository,
        supportRepository,
        accessService,
    )

    private fun ticket(
        id: String,
        visibility: String? = FeedbackVisibility.PRIVATE,
        mergedIntoId: String? = null,
        reporterUserId: String = "reporter",
        publishedAt: Instant? = null,
    ) = FeedbackTicket(
        id = id,
        type = FeedbackType.BUG,
        category = FeedbackArea.OPERATOR,
        area = FeedbackArea.OPERATOR,
        status = "OPEN",
        reporterUserId = reporterUserId,
        content = "正文",
        visibility = visibility,
        publicTitle = "已填公开标题",
        publicStatus = PublicFeedbackStatus.COLLECTING,
        mergedIntoId = mergedIntoId,
        publishedAt = publishedAt,
    )

    private fun allowManage() {
        every { accessService.canManage(any(), any()) } returns true
    }

    @Test
    fun `非管理员发布返回 403`() {
        every { ticketRepository.findById("rpt_1") } returns Optional.of(ticket("rpt_1"))
        every { accessService.canManage(any(), any()) } returns false

        val error = assertThrows(ApiResultException::class.java) {
            service.publish("user", "rpt_1", FeedbackPublishRequest(publicTitle = "公开标题"))
        }

        assertEquals(403, error.statusCode)
    }

    @Test
    fun `发布要求公开标题非空`() {
        allowManage()
        every { ticketRepository.findById("rpt_1") } returns Optional.of(ticket("rpt_1"))

        val error = assertThrows(ApiResultException::class.java) {
            service.publish("admin", "rpt_1", FeedbackPublishRequest(publicTitle = "   "))
        }

        assertEquals(400, error.statusCode)
    }

    @Test
    fun `发布写入公开字段并保留首次发布时间`() {
        allowManage()
        val published = Instant.parse("2026-09-01T00:00:00Z")
        every { ticketRepository.findById("rpt_1") } returns Optional.of(ticket("rpt_1", publishedAt = published))
        every { queryRepository.setPublicInfo(any(), any(), any(), any(), any(), any(), any()) } returns ticket(
            "rpt_1",
            visibility = FeedbackVisibility.PUBLIC,
            publishedAt = published,
        )

        service.publish("admin", "rpt_1", FeedbackPublishRequest(publicTitle = "公开标题", publicStatus = "planned"))

        verify(exactly = 1) {
            queryRepository.setPublicInfo(
                ticketId = "rpt_1",
                visibility = FeedbackVisibility.PUBLIC,
                publicTitle = "公开标题",
                publicSummary = null,
                publicStatus = PublicFeedbackStatus.PLANNED,
                publishedAt = published,
                publicUpdatedAt = any(),
            )
        }
    }

    @Test
    fun `取消公开不删除支持记录`() {
        allowManage()
        every { ticketRepository.findById("rpt_1") } returns Optional.of(
            ticket("rpt_1", visibility = FeedbackVisibility.PUBLIC),
        )
        every { queryRepository.setPublicInfo(any(), any(), any(), any(), any(), any(), any()) } returns ticket(
            "rpt_1",
            visibility = FeedbackVisibility.PRIVATE,
        )

        service.unpublish("admin", "rpt_1")

        verify(exactly = 1) {
            queryRepository.setPublicInfo(
                ticketId = "rpt_1",
                visibility = FeedbackVisibility.PRIVATE,
                publicTitle = any(),
                publicSummary = any(),
                publicStatus = any(),
                publishedAt = any(),
                publicUpdatedAt = any(),
            )
        }
        verify(exactly = 0) { supportRepository.deleteByFeedbackIdAndUserId(any(), any()) }
    }

    @Test
    fun `修改公开状态需要先公开`() {
        allowManage()
        every { ticketRepository.findById("rpt_1") } returns Optional.of(ticket("rpt_1"))

        val error = assertThrows(ApiResultException::class.java) {
            service.updatePublicStatus("admin", "rpt_1", FeedbackPublicStatusRequest(publicStatus = "PLANNED"))
        }

        assertEquals(400, error.statusCode)
    }

    @Test
    fun `不能把反馈合并到自身`() {
        allowManage()
        every { ticketRepository.findById("rpt_1") } returns Optional.of(ticket("rpt_1"))

        val error = assertThrows(ApiResultException::class.java) {
            service.merge("admin", "rpt_1", FeedbackMergeRequest(targetFeedbackId = "rpt_1"))
        }

        assertEquals(400, error.statusCode)
    }

    @Test
    fun `合并成环被拒绝`() {
        allowManage()
        val source = ticket("rpt_a")
        val target = ticket("rpt_b", mergedIntoId = "rpt_a")
        every { ticketRepository.findById("rpt_a") } returns Optional.of(source)
        every { ticketRepository.findById("rpt_b") } returns Optional.of(target)

        val error = assertThrows(ApiResultException::class.java) {
            service.merge("admin", "rpt_a", FeedbackMergeRequest(targetFeedbackId = "rpt_b"))
        }

        assertEquals(400, error.statusCode)
    }

    @Test
    fun `合并解析到最终主反馈`() {
        allowManage()
        val sourceC = ticket("rpt_c")
        val targetB = ticket("rpt_b", mergedIntoId = "rpt_a")
        val rootA = ticket("rpt_a")
        every { ticketRepository.findById("rpt_c") } returns Optional.of(sourceC)
        every { ticketRepository.findById("rpt_b") } returns Optional.of(targetB)
        every { ticketRepository.findById("rpt_a") } returns Optional.of(rootA)
        every { queryRepository.setMergedInto("rpt_c", "rpt_a") } returns sourceC.copy(mergedIntoId = "rpt_a")
        every { queryRepository.incrementMergedCount("rpt_a") } returns rootA.copy(mergedCount = 1)
        every { supportRepository.existsByFeedbackIdAndUserId("rpt_a", any()) } returns true

        service.merge("admin", "rpt_c", FeedbackMergeRequest(targetFeedbackId = "rpt_b"))

        verify(exactly = 1) { queryRepository.setMergedInto("rpt_c", "rpt_a") }
        verify(exactly = 1) { queryRepository.incrementMergedCount("rpt_a") }
    }

    @Test
    fun `公开反馈不能合并到未公开反馈`() {
        allowManage()
        val source = ticket("rpt_public", visibility = FeedbackVisibility.PUBLIC)
        val target = ticket("rpt_private")
        every { ticketRepository.findById("rpt_public") } returns Optional.of(source)
        every { ticketRepository.findById("rpt_private") } returns Optional.of(target)

        val error = assertThrows(ApiResultException::class.java) {
            service.merge("admin", "rpt_public", FeedbackMergeRequest(targetFeedbackId = "rpt_private"))
        }

        assertEquals(400, error.statusCode)
    }

    @Test
    fun `合并后源提交人自动支持主反馈一次`() {
        allowManage()
        val source = ticket("rpt_source", reporterUserId = "reporter")
        val target = ticket("rpt_main")
        every { ticketRepository.findById("rpt_source") } returns Optional.of(source)
        every { ticketRepository.findById("rpt_main") } returns Optional.of(target)
        every { queryRepository.setMergedInto("rpt_source", "rpt_main") } returns source.copy(mergedIntoId = "rpt_main")
        every { queryRepository.incrementMergedCount("rpt_main") } returns target.copy(mergedCount = 1)
        every { supportRepository.existsByFeedbackIdAndUserId("rpt_main", "reporter") } returns false
        every { supportRepository.save(any()) } answers { firstArg() }
        every { queryRepository.incrementSupportCount("rpt_main") } returns target.copy(supportCount = 1)

        service.merge("admin", "rpt_source", FeedbackMergeRequest(targetFeedbackId = "rpt_main"))

        verify(exactly = 1) { supportRepository.save(match { it.feedbackId == "rpt_main" && it.userId == "reporter" }) }
        verify(exactly = 1) { queryRepository.incrementSupportCount("rpt_main") }
    }

    @Test
    fun `合并后源提交人已支持时不重复计票`() {
        allowManage()
        val source = ticket("rpt_source", reporterUserId = "reporter")
        val target = ticket("rpt_main")
        every { ticketRepository.findById("rpt_source") } returns Optional.of(source)
        every { ticketRepository.findById("rpt_main") } returns Optional.of(target)
        every { queryRepository.setMergedInto("rpt_source", "rpt_main") } returns source.copy(mergedIntoId = "rpt_main")
        every { queryRepository.incrementMergedCount("rpt_main") } returns target.copy(mergedCount = 1)
        every { supportRepository.existsByFeedbackIdAndUserId("rpt_main", "reporter") } returns true

        service.merge("admin", "rpt_source", FeedbackMergeRequest(targetFeedbackId = "rpt_main"))

        verify(exactly = 0) { supportRepository.save(any()) }
        verify(exactly = 0) { queryRepository.incrementSupportCount(any()) }
    }
}
