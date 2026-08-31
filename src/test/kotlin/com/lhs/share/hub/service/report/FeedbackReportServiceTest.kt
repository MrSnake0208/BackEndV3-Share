package com.lhs.share.hub.service.report

import com.lhs.share.config.external.ShareProperties
import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.controller.response.user.MaaUserInfo
import com.lhs.share.hub.controller.report.request.FeedbackMessageAppendRequest
import com.lhs.share.hub.controller.report.request.FeedbackReportCreateRequest
import com.lhs.share.hub.controller.report.request.FeedbackStatusUpdateRequest
import com.lhs.share.hub.repository.FeedbackTicketQueryRepository
import com.lhs.share.hub.repository.FeedbackTicketRepository
import com.lhs.share.hub.repository.MediaAssetRepository
import com.lhs.share.hub.repository.entity.FeedbackMessage
import com.lhs.share.hub.repository.entity.FeedbackMessageFile
import com.lhs.share.hub.repository.entity.FeedbackMessageImage
import com.lhs.share.hub.repository.entity.FeedbackTicket
import com.lhs.share.hub.repository.entity.MediaAsset
import com.lhs.share.hub.repository.entity.MediaKind
import com.lhs.share.hub.service.HubUserInfoService
import com.lhs.share.hub.service.notification.NotificationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class FeedbackReportServiceTest {
    private val ticketRepository = mockk<FeedbackTicketRepository>()
    private val queryRepository = mockk<FeedbackTicketQueryRepository>()
    private val accessService = mockk<FeedbackAccessService>()
    private val mediaRepository = mockk<MediaAssetRepository>()
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val userInfoService = mockk<HubUserInfoService>()
    private val properties = ShareProperties().apply { info.publicBaseUrl = "https://api.example.test/" }
    private val service = FeedbackReportService(
        ticketRepository,
        queryRepository,
        accessService,
        mediaRepository,
        notificationService,
        userInfoService,
        properties,
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

    @Test
    fun `媒体引用按请求顺序保存并在响应中转换为绝对 URL`() {
        prepareCreate()
        val first = media("med_first", "/media/med_first.png")
        val second = media("med_second", "/media/med_second.webp")
        every { mediaRepository.findAllById(listOf("med_second", "med_first")) } returns listOf(first, second)

        val response = service.create(
            "user",
            FeedbackReportCreateRequest(
                type = "BUG",
                category = "OPERATOR",
                content = "带截图的问题",
                mediaIds = listOf("med_second", "med_first"),
            ),
        )

        assertEquals(
            listOf("med_second", "med_first"),
            response.messages.single().images.map { it.id },
        )
        assertEquals(
            listOf(
                "https://api.example.test/media/med_second.webp",
                "https://api.example.test/media/med_first.png",
            ),
            response.messages.single().images.map { it.url },
        )
        verify { mediaRepository.findAllById(listOf("med_second", "med_first")) }
    }

    @Test
    fun `混合附件按类别拆分并返回文件下载契约`() {
        prepareCreate()
        val image = media("med_image", "/media/med_image.png")
        val file = media(
            id = "med_log",
            path = "med_log.log",
            name = "error.log",
            mime = "text/plain",
            size = 2048,
            kind = MediaKind.FILE,
        )
        every { mediaRepository.findAllById(listOf("med_image", "med_log")) } returns listOf(file, image)

        val response = service.create(
            "user",
            FeedbackReportCreateRequest("BUG", "OPERATOR", null, "混合附件", listOf("med_image", "med_log")),
        )

        assertEquals(listOf("med_image"), response.messages.single().images.map { it.id })
        val responseFile = response.messages.single().files.single()
        assertEquals("med_log", responseFile.id)
        assertEquals("error.log", responseFile.name)
        assertEquals(2048, responseFile.size)
        assertTrue(responseFile.downloadUrl.matches(Regex("/v1/reports/rpt_[0-9a-f]{16}/attachments/med_log")))
    }

    @Test
    fun `附件下载要求工单查看权限和真实文件引用`() {
        val referencedFile = FeedbackMessageFile("med_log", "error.log", "text/plain", 10)
        val ticket = openTicket().copy(
            messages = openTicket().messages.map { it.copy(files = listOf(referencedFile)) },
        )
        val asset = media("med_log", "med_log.log", kind = MediaKind.FILE, mime = "text/plain")
        every { ticketRepository.findById("rpt_1") } returns Optional.of(ticket)
        every { mediaRepository.findById("med_log") } returns Optional.of(asset)

        assertEquals(asset, service.getAttachment("user", "rpt_1", "med_log"))

        every { accessService.canView("admin", FeedbackArea.OPERATOR) } returns true
        assertEquals(asset, service.getAttachment("admin", "rpt_1", "med_log"))

        every { accessService.canView("outsider", FeedbackArea.OPERATOR) } returns false
        val forbidden = assertThrows(ApiResultException::class.java) {
            service.getAttachment("outsider", "rpt_1", "med_log")
        }
        assertEquals(403, forbidden.statusCode)

        val missing = assertThrows(ApiResultException::class.java) {
            service.getAttachment("user", "rpt_1", "med_unbound")
        }
        assertEquals(404, missing.statusCode)
        verify(exactly = 0) { mediaRepository.findById("med_unbound") }
    }

    @Test
    fun `追加消息复用媒体归属校验并保留请求顺序`() {
        val ticket = openTicket()
        every { ticketRepository.findById("rpt_1") } returns Optional.of(ticket)
        every { ticketRepository.save(any()) } answers { firstArg() }
        every { accessService.canManage("user", any()) } returns false
        every { userInfoService.get("user") } returns MaaUserInfo("user", "用户")
        val first = media("med_first", "/media/med_first.png")
        val second = media("med_second", "/media/med_second.webp")
        every { mediaRepository.findAllById(listOf("med_second", "med_first")) } returns listOf(first, second)

        val response = service.appendMessage(
            "user",
            "rpt_1",
            FeedbackMessageAppendRequest("补充截图", listOf("med_second", "med_first")),
        )

        assertEquals(
            listOf("med_second", "med_first"),
            response.messages.last().images.map { it.id },
        )
    }

    @Test
    fun `管理员追加消息也能携带图片并保存为管理员消息`() {
        val ticket = openTicket()
        every { ticketRepository.findById("rpt_1") } returns Optional.of(ticket)
        every { ticketRepository.save(any()) } answers { firstArg() }
        every { accessService.canManage("admin", any()) } returns true
        every { userInfoService.get("user") } returns MaaUserInfo("user", "用户")
        every { userInfoService.get("admin") } returns MaaUserInfo("admin", "管理员")
        val first = media("med_first", "/media/med_first.png", owner = "admin")
        val second = media("med_second", "/media/med_second.webp", owner = "admin")
        every { mediaRepository.findAllById(listOf("med_first", "med_second")) } returns listOf(second, first)

        val response = service.appendMessage(
            "admin",
            "rpt_1",
            FeedbackMessageAppendRequest("处理结果", listOf("med_first", "med_second")),
        )

        assertEquals("ADMIN", response.messages.last().senderKind)
        assertEquals(listOf("med_first", "med_second"), response.messages.last().images.map { it.id })
    }

    @Test
    fun `双角色显式提交人追加只产生提交人副作用`() {
        val ticket = openTicket()
        prepareTicket(ticket, "user", canManage = true)

        val response = service.appendMessage(
            "user",
            "rpt_1",
            FeedbackMessageAppendRequest("个人补充", actorMode = " reporter "),
        )

        assertEquals("REPORTER", response.messages.last().senderKind)
        assertEquals(1, response.quota.pendingCount)
        assertTrue(response.quota.canAppend)
        verify {
            ticketRepository.save(match {
                it.lastMessageSender == "REPORTER" &&
                    !it.hasAdminReply &&
                    it.adminReply == null
            })
        }
        verify(exactly = 0) { notificationService.create(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `双角色显式管理员追加更新摘要但不通知自己`() {
        val ticket = openTicket()
        prepareTicket(ticket, "user", canManage = true)

        val response = service.appendMessage(
            "user",
            "rpt_1",
            FeedbackMessageAppendRequest("工作台回复", actorMode = "ADMIN"),
        )

        assertEquals("ADMIN", response.messages.last().senderKind)
        verify {
            ticketRepository.save(match {
                it.lastMessageSender == "ADMIN" &&
                    it.hasAdminReply &&
                    it.adminReply == "工作台回复"
            })
        }
        verify(exactly = 0) { notificationService.create(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `消息身份非法越权和双角色缺省均拒绝写入`() {
        val ticket = openTicket()
        every { ticketRepository.findById("rpt_1") } returns Optional.of(ticket)
        every { accessService.canManage("admin", any()) } returns true
        every { accessService.canManage("user", any()) } returns false
        every { accessService.canManage("outsider", any()) } returns false

        val reporterForbidden = assertThrows(ApiResultException::class.java) {
            service.appendMessage("admin", "rpt_1", FeedbackMessageAppendRequest("越权", actorMode = "REPORTER"))
        }
        val adminForbidden = assertThrows(ApiResultException::class.java) {
            service.appendMessage("user", "rpt_1", FeedbackMessageAppendRequest("越权", actorMode = "ADMIN"))
        }
        val invalid = assertThrows(ApiResultException::class.java) {
            service.appendMessage("outsider", "rpt_1", FeedbackMessageAppendRequest("非法", actorMode = "OWNER"))
        }
        every { accessService.canManage("user", any()) } returns true
        val ambiguous = assertThrows(ApiResultException::class.java) {
            service.appendMessage("user", "rpt_1", FeedbackMessageAppendRequest("缺省"))
        }

        assertEquals(403, reporterForbidden.statusCode)
        assertEquals(403, adminForbidden.statusCode)
        assertEquals(400, invalid.statusCode)
        assertEquals(400, ambiguous.statusCode)
        verify(exactly = 0) { ticketRepository.save(any()) }
        verify(exactly = 0) { notificationService.create(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `双角色提交人达到补充上限但管理员模式仍可回复`() {
        val reporterMessages = (1..3).map { index ->
            FeedbackMessage(
                id = "rpm_pending_$index",
                senderKind = "REPORTER",
                authorUserId = "user",
                content = "补充 $index",
            )
        }
        val ticket = openTicket().copy(messages = openTicket().messages + reporterMessages)
        prepareTicket(ticket, "user", canManage = true)

        val detail = service.getById("user", "rpt_1")
        val quotaException = assertThrows(ApiResultException::class.java) {
            service.appendMessage(
                "user",
                "rpt_1",
                FeedbackMessageAppendRequest("第四次补充", actorMode = "REPORTER"),
            )
        }
        val adminResponse = service.appendMessage(
            "user",
            "rpt_1",
            FeedbackMessageAppendRequest("管理员回复", actorMode = "ADMIN"),
        )

        assertEquals(3, detail.quota.pendingCount)
        assertFalse(detail.quota.canAppend)
        assertEquals(400, quotaException.statusCode)
        assertEquals("ADMIN", adminResponse.messages.last().senderKind)
    }

    @Test
    fun `双角色以提交人关闭工单不写处理字段也不通知`() {
        val ticket = openTicket()
        prepareTicket(ticket, "user", canManage = true)

        val response = service.updateStatus(
            "user",
            "rpt_1",
            FeedbackStatusUpdateRequest("RESOLVED", actorMode = "REPORTER"),
        )

        assertEquals("RESOLVED", response.status)
        assertNull(response.handler)
        verify {
            ticketRepository.save(match {
                it.status == "RESOLVED" && it.handlerUserId == null && it.handledAt == null
            })
        }
        verify(exactly = 0) { notificationService.create(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `双角色以管理员处理工单写处理字段但不通知自己`() {
        val ticket = openTicket()
        prepareTicket(ticket, "user", canManage = true)

        val response = service.updateStatus(
            "user",
            "rpt_1",
            FeedbackStatusUpdateRequest("DISMISSED", actorMode = "ADMIN"),
        )

        assertEquals("DISMISSED", response.status)
        assertEquals("user", response.handler?.id)
        verify {
            ticketRepository.save(match {
                it.status == "DISMISSED" && it.handlerUserId == "user" && it.handledAt != null
            })
        }
        verify(exactly = 0) { notificationService.create(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `管理员处理他人工单会通知且状态不变时不重复保存`() {
        val ticket = openTicket()
        prepareTicket(ticket, "admin", canManage = true)

        val response = service.updateStatus(
            "admin",
            "rpt_1",
            FeedbackStatusUpdateRequest("RESOLVED", actorMode = "ADMIN"),
        )

        assertEquals("admin", response.handler?.id)
        assertNotNull(response.updatedAt)
        verify {
            notificationService.create(
                userId = "user",
                kind = "FEEDBACK_STATUS_UPDATED",
                title = any(),
                body = any(),
                refType = "FEEDBACK",
                refId = "rpt_1",
            )
        }

        val resolved = ticket.copy(status = "RESOLVED")
        every { ticketRepository.findById("rpt_1") } returns Optional.of(resolved)
        service.updateStatus("admin", "rpt_1", FeedbackStatusUpdateRequest("RESOLVED", actorMode = "ADMIN"))
        verify(exactly = 1) { ticketRepository.save(any()) }
        verify(exactly = 1) { notificationService.create(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `状态身份非法越权和双角色缺省与消息规则一致`() {
        val ticket = openTicket()
        every { ticketRepository.findById("rpt_1") } returns Optional.of(ticket)
        every { accessService.canManage("admin", any()) } returns true
        every { accessService.canManage("user", any()) } returns false
        every { accessService.canManage("outsider", any()) } returns false

        val reporterForbidden = assertThrows(ApiResultException::class.java) {
            service.updateStatus("admin", "rpt_1", FeedbackStatusUpdateRequest("RESOLVED", "REPORTER"))
        }
        val adminForbidden = assertThrows(ApiResultException::class.java) {
            service.updateStatus("user", "rpt_1", FeedbackStatusUpdateRequest("RESOLVED", "ADMIN"))
        }
        val invalid = assertThrows(ApiResultException::class.java) {
            service.updateStatus("outsider", "rpt_1", FeedbackStatusUpdateRequest("RESOLVED", "OWNER"))
        }
        every { accessService.canManage("user", any()) } returns true
        val ambiguous = assertThrows(ApiResultException::class.java) {
            service.updateStatus("user", "rpt_1", FeedbackStatusUpdateRequest("RESOLVED"))
        }

        assertEquals(403, reporterForbidden.statusCode)
        assertEquals(403, adminForbidden.statusCode)
        assertEquals(400, invalid.statusCode)
        assertEquals(400, ambiguous.statusCode)
        verify(exactly = 0) { ticketRepository.save(any()) }
        verify(exactly = 0) { notificationService.create(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `重复媒体 ID 被拒绝且不会查询仓库`() {
        prepareCreate()

        val exception = assertThrows(ApiResultException::class.java) {
            service.create(
                "user",
                FeedbackReportCreateRequest(
                    type = "BUG",
                    category = "OPERATOR",
                    content = "重复图片",
                    mediaIds = listOf("med_same", "med_same"),
                ),
            )
        }

        assertEquals(400, exception.statusCode)
        verify(exactly = 0) { mediaRepository.findAllById(any()) }
    }

    @Test
    fun `其他用户媒体和已结束工单不能通过图片追加`() {
        prepareCreate()
        every { mediaRepository.findAllById(listOf("med_other")) } returns listOf(media("med_other", owner = "other"))
        val ownershipException = assertThrows(ApiResultException::class.java) {
            service.create(
                "user",
                FeedbackReportCreateRequest("BUG", "OPERATOR", null, "越权图片", listOf("med_other")),
            )
        }
        assertEquals(403, ownershipException.statusCode)

        listOf("RESOLVED", "DISMISSED").forEach { status ->
            val ticketId = "rpt_${status.lowercase()}"
            every { ticketRepository.findById(ticketId) } returns Optional.of(
                openTicket().copy(id = ticketId, status = status),
            )
            val statusException = assertThrows(ApiResultException::class.java) {
                service.appendMessage(
                    "user",
                    ticketId,
                    FeedbackMessageAppendRequest("已结束", listOf("med_other")),
                )
            }
            assertEquals(400, statusException.statusCode)
        }
    }

    @Test
    fun `媒体数量、缺失媒体和已删除媒体会被拒绝`() {
        prepareCreate()

        val tooManyException = assertThrows(ApiResultException::class.java) {
            service.create(
                "user",
                FeedbackReportCreateRequest(
                    type = "BUG",
                    category = "OPERATOR",
                    content = "图片太多",
                    mediaIds = listOf("med_1", "med_2", "med_3", "med_4"),
                ),
            )
        }
        assertEquals(400, tooManyException.statusCode)

        every { mediaRepository.findAllById(listOf("med_missing")) } returns emptyList()
        val missingException = assertThrows(ApiResultException::class.java) {
            service.create(
                "user",
                FeedbackReportCreateRequest("BUG", "OPERATOR", null, "媒体缺失", listOf("med_missing")),
            )
        }
        assertEquals(400, missingException.statusCode)

        every { mediaRepository.findAllById(listOf("med_deleted")) } returns listOf(
            media("med_deleted").copy(deletedAt = Instant.parse("2026-08-30T00:00:00Z")),
        )
        val deletedException = assertThrows(ApiResultException::class.java) {
            service.create(
                "user",
                FeedbackReportCreateRequest("BUG", "OPERATOR", null, "媒体已删除", listOf("med_deleted")),
            )
        }
        assertEquals(400, deletedException.statusCode)
    }

    @Test
    fun `历史绝对图片 URL 和空 URL 不会被重复拼接或阻断响应`() {
        val ticket = openTicket().copy(
            messages = listOf(
                FeedbackMessage(
                    id = "rpm_history",
                    senderKind = "REPORTER",
                    authorUserId = "user",
                    content = "历史数据",
                    images = listOf(
                        FeedbackMessageImage("med_abs", "https://cdn.example.test/a.webp"),
                        FeedbackMessageImage("med_empty", ""),
                    ),
                    createdAt = Instant.parse("2026-08-30T00:00:00Z"),
                ),
            ),
        )
        every { ticketRepository.findById("rpt_1") } returns Optional.of(ticket)
        every { accessService.canManage("user", any()) } returns false
        every { userInfoService.get("user") } returns MaaUserInfo("user", "用户")

        val response = service.getById("user", "rpt_1")

        assertEquals("https://cdn.example.test/a.webp", response.messages.single().images[0].url)
        assertEquals("", response.messages.single().images[1].url)
    }

    private fun media(
        id: String,
        path: String = "/media/$id.webp",
        owner: String = "user",
        name: String = "$id.webp",
        mime: String = "image/webp",
        size: Long = 12,
        kind: MediaKind? = null,
    ): MediaAsset = MediaAsset(id, owner, name, mime, size, path, kind = kind)

    private fun prepareTicket(ticket: FeedbackTicket, currentUserId: String, canManage: Boolean) {
        every { ticketRepository.findById(checkNotNull(ticket.id)) } returns Optional.of(ticket)
        every { ticketRepository.save(any()) } answers { firstArg() }
        every { accessService.canManage(currentUserId, any()) } returns canManage
        every { mediaRepository.findAllById(emptyList()) } returns emptyList()
        every { userInfoService.get(any()) } answers {
            val userId = firstArg<String>()
            MaaUserInfo(userId, userId)
        }
    }

    private fun openTicket(): FeedbackTicket = FeedbackTicket(
        id = "rpt_1",
        type = FeedbackType.BUG,
        category = FeedbackArea.OPERATOR,
        area = FeedbackArea.OPERATOR,
        reporterUserId = "user",
        content = "原始反馈",
        messages = listOf(
            FeedbackMessage(
                id = "rpm_initial",
                senderKind = "REPORTER",
                authorUserId = "user",
                content = "原始反馈",
            ),
        ),
    )
}
