package com.lhs.share.hub.service.report

import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.hub.repository.FeedbackCategoryCatalogRepository
import com.lhs.share.hub.repository.entity.FeedbackCategory
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
    fun `旧目录只补日历一次且读取不持久化不改历史板块`() {
        val historical = listOf(FeedbackCategory("CUSTOM_OLD", "旧板块"), FeedbackCategory(FeedbackArea.OTHER, "旧其他名称"))
        val stored = FeedbackCategoryCatalog(categories = historical, version = 7)
        every { repository.findById("feedback") } returns Optional.of(stored)
        repeat(2) {
            assertEquals(historical + FeedbackCategory(FeedbackArea.CALENDAR, "活动日历"), service.list())
            assertEquals(FeedbackArea.CALENDAR, service.requireValid(" calendar "))
        }
        assertEquals(historical, stored.categories)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `已有日历 key 或同名动态板块保持原标识和名称`() {
        for (calendar in listOf(FeedbackCategory("CUSTOM_CAL", "活动日历"), FeedbackCategory(FeedbackArea.CALENDAR, "自定义日历名称"))) {
            every { repository.findById("feedback") } returns Optional.of(FeedbackCategoryCatalog(categories = listOf(calendar)))
            val expected = if (calendar.key == FeedbackArea.CALENDAR) calendar else calendar.copy(builtinKey = FeedbackArea.CALENDAR)
            assertEquals(listOf(expected), service.list())
            assertEquals(calendar.key, service.requireValid(calendar.key))
        }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `同名自定义日历改名后仍复用原 key 不补第二个日历`() {
        var stored = FeedbackCategoryCatalog(categories = listOf(FeedbackCategory("CUSTOM_CAL", "活动日历")), version = 3)
        every { repository.findById("feedback") } answers { Optional.of(stored) }
        every { repository.save(any()) } answers { firstArg<FeedbackCategoryCatalog>().also { stored = it } }
        service.rename("CUSTOM_CAL", "活动与招募日程")
        assertEquals(listOf(FeedbackCategory("CUSTOM_CAL", "活动与招募日程", FeedbackArea.CALENDAR)), service.list())
        assertEquals(setOf("CUSTOM_CAL"), service.keys())
    }

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
