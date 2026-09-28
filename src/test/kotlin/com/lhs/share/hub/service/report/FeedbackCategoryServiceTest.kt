package com.lhs.share.hub.service.report

import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.hub.repository.FeedbackCategoryCatalogRepository
import com.lhs.share.hub.repository.entity.FeedbackCategoryCatalog
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.OptimisticLockingFailureException
import java.util.Optional

class FeedbackCategoryServiceTest {
    private val repository = mockk<FeedbackCategoryCatalogRepository>()
    private val service = FeedbackCategoryService(repository)

    @Test
    fun `新增板块后所有读取使用持久化目录且改名保持标识`() {
        var stored: FeedbackCategoryCatalog? = null
        every { repository.findById("feedback") } answers { Optional.ofNullable(stored) }
        every { repository.save(any()) } answers { firstArg<FeedbackCategoryCatalog>().also { stored = it } }

        assertTrue(service.keys().containsAll(setOf(FeedbackArea.STAR, FeedbackArea.MAAYUAN)))
        val created = service.create("  新板块  ")
        assertTrue(created.key.startsWith("CUSTOM_"))
        assertEquals("新板块", service.label(created.key))
        assertEquals(created.key, service.rename(created.key, "新名称").second.key)
        assertEquals("新名称", service.label(created.key))
        assertTrue(service.keys().contains(FeedbackArea.STAR))
    }

    @Test
    fun `空名和重复名不会写入目录`() {
        every { repository.findById("feedback") } returns Optional.empty()
        assertEquals(400, assertThrows(ApiResultException::class.java) { service.create("  ") }.statusCode)
        assertEquals(409, assertThrows(ApiResultException::class.java) { service.create("星石") }.statusCode)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `并发修改返回冲突而非覆盖`() {
        every { repository.findById("feedback") } returns Optional.empty()
        every { repository.save(any()) } throws OptimisticLockingFailureException("stale")
        assertEquals(409, assertThrows(ApiResultException::class.java) { service.create("新板块") }.statusCode)
    }
}
