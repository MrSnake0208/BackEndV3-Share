package com.lhs.share.hub.service.calendar

import com.lhs.share.hub.controller.calendar.request.ActivityCalendarWriteRequest
import com.lhs.share.hub.controller.calendar.response.ActivityCalendarSourceType
import com.lhs.share.hub.repository.ActivityCalendarRepository
import com.lhs.share.hub.repository.RecruitmentCatalogRepository
import com.lhs.share.hub.repository.entity.ActivityCalendarCategory
import com.lhs.share.hub.repository.entity.ActivityCalendarEvent
import com.lhs.share.hub.repository.entity.RecruitmentCatalogPool
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.OptimisticLockingFailureException
import java.time.Instant
import java.time.LocalDate

class ActivityCalendarServiceTest {
    private val repository = mockk<ActivityCalendarRepository>()
    private val recruitment = mockk<RecruitmentCatalogRepository>()
    private val service = ActivityCalendarService(repository, recruitment)
    private val date = LocalDate.parse("2026-10-03")
    private val request = ActivityCalendarWriteRequest("如鸢", " 活动 ", ActivityCalendarCategory.ACTIVITY, date, date.plusDays(7))
    private val event = ActivityCalendarEvent(
        "evt_test", "如鸢", "活动", ActivityCalendarCategory.ACTIVITY, date, date.plusDays(7),
        createdBy = "creator", createdAt = Instant.EPOCH, updatedBy = "creator", updatedAt = Instant.EPOCH, version = 0,
    )

    @Test
    fun `公开读取合并排序且不派生停用缺日期或倒置卡池并保持只读`() {
        every { repository.list(null, null, null, emptySet(), true) } returns listOf(
            event.copy(id = "evt_z", startTime = "10:00", endTime = "11:00"),
            event.copy(id = "evt_a"),
        )
        every { recruitment.all() } returns listOf(
            pool("later", date.plusDays(1), date.plusDays(3)),
            pool("earlier", date.minusDays(2), date),
            pool("disabled", date, date).copy(enabled = false),
            pool("missing-start", null, date),
            pool("missing-end", date, null),
            pool("no-dates", null, null),
            pool("inverted", date.plusDays(1), date),
        )
        val items = service.publicItems(null, null, null, null).items
        assertEquals(listOf("recruitment:earlier", "evt_a", "evt_z", "recruitment:later"), items.map { it.id })
        assertEquals(ActivityCalendarSourceType.MANUAL, items[1].sourceType)
        assertNull(items[1].sourceRef)
        assertNull(items[1].startTime)
        assertNull(items[1].endTime)
        assertEquals("earlier", items[0].sourceRef)
        assertEquals(ActivityCalendarSourceType.RECRUITMENT_POOL, items[0].sourceType)
        assertEquals(ActivityCalendarCategory.RECRUITMENT, items[0].category)
        assertEquals("https://example.com/pool", items[0].sourceUrl)
        assertEquals(items, service.publicItems(null, null, null, null).items)
        verify(exactly = 0) {
            repository.save(any())
            recruitment.save(any())
        }
    }

    @Test
    fun `游戏类别和闭区间overlap应用于两个来源且支持重复和逗号类别`() {
        val categories = setOf(ActivityCalendarCategory.ACTIVITY, ActivityCalendarCategory.RECRUITMENT)
        every { repository.list("如鸢", date, date.plusDays(1), categories, true) } returns listOf(event)
        every { recruitment.all() } returns listOf(
            pool("touch-start", date.minusDays(10), date),
            pool("touch-end", date.plusDays(1), date.plusDays(10)),
            pool("cover", date.minusDays(10), date.plusDays(10)),
            pool("before", date.minusDays(10), date.minusDays(1)),
            pool("after", date.plusDays(2), date.plusDays(10)),
            pool("other-game", date, date).copy(game = "代号鸢"),
        )
        val items = service.publicItems("如鸢", date, date.plusDays(1), listOf("ACTIVITY,RECRUITMENT", "ACTIVITY")).items
        assertEquals(
            setOf("evt_test", "recruitment:touch-start", "recruitment:touch-end", "recruitment:cover"),
            items.map {
                it.id
            }.toSet(),
        )
        verify { repository.list("如鸢", date, date.plusDays(1), categories, true) }
        every { repository.list(null, null, null, setOf(ActivityCalendarCategory.SHOP), true) } returns emptyList()
        assertTrue(service.publicItems(null, null, null, listOf("SHOP")).items.isEmpty())
        every { repository.list(null, date, null, emptySet(), true) } returns emptyList()
        assertFalse(service.publicItems(null, date, null, null).items.any { it.id == "recruitment:before" })
        every { repository.list(null, null, date, emptySet(), true) } returns emptyList()
        assertFalse(service.publicItems(null, null, date, null).items.any { it.id == "recruitment:after" })
    }

    @Test
    fun `管理读取停用手工项与只读招募来源且支持状态和标题筛选`() {
        every { repository.list(null, null, null, emptySet(), null) } returns listOf(event.copy(enabled = false))
        every { recruitment.all() } returns listOf(pool("pool", date, date))
        val items = service.adminItems(null, null, null, null, null, null).items
        assertFalse(items.first { it.item.id == "evt_test" }.enabled)
        assertFalse(items.first { it.item.id == "evt_test" }.readOnly)
        val derived = items.first { it.readOnly }
        assertNull(derived.version)
        assertEquals("来源备注", derived.sourceNote)
        assertEquals(listOf("evt_test"), service.adminItems(null, null, null, null, null, " 活动 ").items.map { it.item.id })
        every { repository.list(null, null, null, emptySet(), false) } returns listOf(event.copy(enabled = false))
        assertEquals(listOf("evt_test"), service.adminItems(null, null, null, null, false, null).items.map { it.item.id })
        verify(exactly = 0) {
            repository.save(any())
            recruitment.save(any())
        }
    }

    @Test
    fun `创建生成身份保留日期精度更新保留创建审计并支持停用`() {
        every { repository.save(any()) } answers
            { firstArg<ActivityCalendarEvent>().copy(version = (firstArg<ActivityCalendarEvent>().version ?: -1) + 1) }
        val created = service.create("creator", request)
        assertTrue(created.item.id.startsWith("evt_"))
        assertEquals("活动", created.item.title)
        assertNull(created.item.startTime)
        assertNull(created.item.endTime)
        assertEquals(0L, created.version)
        assertEquals("creator", created.createdBy)
        every { repository.find(event.id) } returns event
        val updated = service.update("editor", event.id, request.copy(expectedVersion = 0, enabled = false))
        assertEquals(1L, updated.version)
        assertFalse(updated.enabled)
        assertEquals("creator", updated.createdBy)
        assertEquals(Instant.EPOCH, updated.createdAt)
        assertEquals("editor", updated.updatedBy)
    }

    @Test
    fun `显式旧版本和实际保存竞争均返回冲突且派生项不可写`() {
        every { repository.find(event.id) } returns event
        assertEquals(409, failure { service.update("editor", event.id, request.copy(expectedVersion = 1)) }.status.value())
        verify(exactly = 0) { repository.save(any()) }
        every { repository.save(any()) } throws OptimisticLockingFailureException("concurrent update")
        val conflict = failure { service.update("editor", event.id, request.copy(expectedVersion = 0)) }
        assertEquals(409, conflict.status.value())
        assertEquals("activity_calendar_version_conflict", conflict.code)
        assertEquals(422, failure { service.update("editor", "recruitment:pool", request.copy(expectedVersion = 0)) }.status.value())
        verify(exactly = 0) {
            repository.find("recruitment:pool")
            recruitment.save(any())
        }
        every { repository.find("missing") } returns null
        assertEquals(404, failure { service.update("editor", "missing", request.copy(expectedVersion = 0)) }.status.value())
    }

    @Test
    fun `写入校验长度游戏类别日期成对时间时区来源与更新版本`() {
        val invalid = listOf(
            request.copy(game = "other"), request.copy(title = " "), request.copy(title = "a".repeat(121)),
            request.copy(category = ActivityCalendarCategory.RECRUITMENT), request.copy(endDate = date.minusDays(1)),
            request.copy(startTime = "10:00"), request.copy(endTime = "10:00"),
            request.copy(startTime = "24:00", endTime = "12:00"),
            request.copy(startTime = "9:00", endTime = "12:00"),
            request.copy(endDate = date, startTime = "12:00", endTime = "11:59"),
            request.copy(timeZone = "bad-zone"), request.copy(description = "a".repeat(1001)),
            request.copy(sourceUrl = "javascript:alert(1)"), request.copy(sourceUrl = "http:/missing-host"),
            request.copy(sourceUrl = "https://bad host"), request.copy(expectedVersion = 0),
        )
        invalid.forEach { input -> assertEquals(422, failure { service.create("editor", input) }.status.value()) }
        listOf(null, -1L, Long.MAX_VALUE).forEach { version ->
            assertEquals(422, failure { service.update("editor", event.id, request.copy(expectedVersion = version)) }.status.value())
        }
        verify(exactly = 0) { repository.save(any()) }
        every { repository.save(any()) } answers { firstArg<ActivityCalendarEvent>().copy(version = 0) }
        service.create(
            "editor",
            request.copy(title = "a".repeat(120), description = "a".repeat(1000), sourceUrl = " https://example.com/path "),
        )
        service.create("editor", request.copy(endDate = date, startTime = "10:00", endTime = "10:00"))
        service.create("editor", request.copy(startTime = "23:00", endTime = "01:00"))
    }

    @Test
    fun `无效查询在读取前被拒绝`() {
        assertEquals(422, failure { service.publicItems("other", null, null, null) }.status.value())
        assertEquals(422, failure { service.publicItems(null, date, date.minusDays(1), null) }.status.value())
        assertEquals(422, failure { service.publicItems(null, null, null, listOf("UNKNOWN")) }.status.value())
        verify {
            repository wasNot io.mockk.Called
            recruitment wasNot io.mockk.Called
        }
    }

    private fun pool(id: String, start: LocalDate?, end: LocalDate?) =
        RecruitmentCatalogPool(id, "如鸢", "卡池", start, end, sourceUrl = "https://example.com/pool", sourceNote = "来源备注")

    private fun failure(action: () -> Unit) = assertThrows(ActivityCalendarApiException::class.java, action)
}
