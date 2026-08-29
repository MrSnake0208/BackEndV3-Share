package com.lhs.share.hub.service.report

import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.controller.response.user.MaaUserInfo
import com.lhs.share.repository.entity.MaaUser
import com.lhs.share.hub.controller.report.request.FeedbackAccessUpdateRequest
import com.lhs.share.hub.repository.FeedbackAccessGrantRepository
import com.lhs.share.hub.repository.entity.FeedbackAccessGrant
import com.lhs.share.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional
import org.springframework.data.domain.PageImpl

class FeedbackAccessServiceTest {
    private val repository = mockk<FeedbackAccessGrantRepository>()
    private val userService = mockk<UserService>()
    private val service = FeedbackAccessService(repository, userService)

    @Test
    fun `委派管理员只能管理获授权模块`() {
        every { userService.hasAdminPrivileges("manager") } returns false
        every { repository.findById("manager") } returns Optional.of(
            FeedbackAccessGrant(
                userId = "manager",
                receiveAreas = setOf(FeedbackArea.INVENTORY),
                manageAreas = setOf(FeedbackArea.OPERATOR),
                updatedBy = "root",
            ),
        )

        assertTrue(service.canView("manager", FeedbackArea.INVENTORY))
        assertTrue(service.canView("manager", FeedbackArea.OPERATOR))
        assertTrue(service.canManage("manager", FeedbackArea.OPERATOR))
        assertFalse(service.canManage("manager", FeedbackArea.INVENTORY))
        assertEquals(setOf(FeedbackArea.OPERATOR), service.manageableAreas("manager"))
    }

    @Test
    fun `超级管理员可以管理全部模块`() {
        every { userService.hasAdminPrivileges("root") } returns true

        assertTrue(service.canManage("root", FeedbackArea.LEDGER))
        assertEquals(FeedbackArea.all, service.manageableAreas("root"))
    }

    @Test
    fun `更新授权分别保存接收与管理模块`() {
        every { userService.hasAdminPrivileges("root") } returns true
        every { userService.getRequired("manager") } returns MaaUserInfo("manager", "处理人", activated = true)
        every { userService.get("manager") } returns MaaUserInfo("manager", "处理人", activated = true)
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
    }

    @Test
    fun `更新授权拒绝未知模块`() {
        every { userService.hasAdminPrivileges("root") } returns true
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
        every { userService.hasAdminPrivileges("root") } returns true
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
        every { userService.hasAdminPrivileges("root") } returns true
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
        every { userService.hasAdminPrivileges("root") } returns true

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
        every { userService.hasAdminPrivileges("root") } returns true
        every { userService.getRequired("disabled") } returns MaaUserInfo("disabled", "已禁用")

        assertThrows(ApiResultException::class.java) {
            service.updateGrant(
                "root",
                "disabled",
                FeedbackAccessUpdateRequest(receiveAreas = setOf(FeedbackArea.OPERATOR)),
            )
        }
    }
}
