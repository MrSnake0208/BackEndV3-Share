package com.lhs.share.hub.service.calendar

import com.lhs.share.hub.controller.calendar.request.CalendarProgressRequest
import com.lhs.share.hub.controller.calendar.request.CalendarSubscribeRequest
import com.lhs.share.hub.controller.calendar.response.ActivityCalendarItem
import com.lhs.share.hub.controller.calendar.response.ActivityCalendarResponse
import com.lhs.share.hub.controller.calendar.response.ActivityCalendarSourceType
import com.lhs.share.hub.repository.ActivityCalendarSubscriptionRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.ActivityCalendarCategory
import com.lhs.share.hub.repository.entity.ActivityCalendarSubscription
import com.lhs.share.hub.repository.entity.CalendarChecklistEntry
import com.lhs.share.hub.repository.entity.SubAccount
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ActivityCalendarSubscriptionServiceTest {
    private val repository = mockk<ActivityCalendarSubscriptionRepository>()
    private val accounts = mockk<SubAccountRepository>()
    private val calendar = mockk<ActivityCalendarService>()
    private val transaction = TransactionTemplate(mockk<PlatformTransactionManager>(relaxed = true))
    private val now = Instant.parse("2026-10-05T04:00:00Z")
    private val day = LocalDate.parse("2026-10-05")
    private val owner = SubAccount(userId = "user", accountId = "account", name = "大号", game = "如鸢")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val service = ActivityCalendarSubscriptionService(repository, accounts, calendar, transaction, clock)
    private fun item(id: String = "event", end: LocalDate = day.plusDays(2)) = ActivityCalendarItem(
        id,
        ActivityCalendarSourceType.MANUAL,
        null,
        "如鸢",
        "活动",
        ActivityCalendarCategory.ACTIVITY,
        day,
        end,
    )
    private fun record(id: String = "event") = ActivityCalendarSubscription(
        "sub-$id",
        "user",
        "account",
        id,
        "如鸢",
        createdAt = now,
        updatedAt = now,
        version = 0,
    )
    private fun failure(code: Int, action: () -> Unit) =
        assertEquals(code, assertThrows(ActivityCalendarApiException::class.java, action).status.value())

    @BeforeEach
    fun setup() {
        every { accounts.findByUserIdAndAccountId("user", "account") } returns owner
        every { accounts.fenceRecruitmentWrite("user", "account", "如鸢") } returns true
        every { calendar.publicItems("如鸢", null, null, null) } returns ActivityCalendarResponse(listOf(item()))
        every { repository.find("user", "account", any()) } returns null
        every { repository.save(any()) } answers {
            val value = firstArg<ActivityCalendarSubscription>()
            value.copy(version = value.version?.plus(1) ?: 0)
        }
    }

    @Test
    fun `create cancellation and restoration preserve progress and use account fence`() {
        val created = service.subscribe("user", "event", CalendarSubscribeRequest("account", null, true))
        assertEquals(0L, created.version)
        val completed = record().copy(completed = true)
        every { repository.find("user", "account", "event") } returns completed
        val cancelled = service.subscribe("user", "event", CalendarSubscribeRequest("account", 0, false))
        assertFalse(cancelled.subscribed)
        assertTrue(cancelled.completed)
        every { repository.find("user", "account", "event") } returns completed.copy(subscribed = false, version = 1)
        val restored = service.subscribe("user", "event", CalendarSubscribeRequest("account", 1, true))
        assertTrue(restored.subscribed && restored.completed)
        verify(exactly = 3) { accounts.fenceRecruitmentWrite("user", "account", "如鸢") }
    }

    @Test
    fun `versions duplicates and account deletion conflict without overwriting progress`() {
        every { repository.find("user", "account", "event") } returns record()
        failure(409) { service.subscribe("user", "event", CalendarSubscribeRequest("account", null, true)) }
        failure(409) { service.progress("user", "event", CalendarProgressRequest("account", 2, true, emptyList())) }
        verify(exactly = 0) { repository.save(any()) }
        every { accounts.fenceRecruitmentWrite(any(), any(), any()) } returns false
        failure(409) { service.subscribe("user", "event", CalendarSubscribeRequest("account", 0, false)) }
        every { accounts.fenceRecruitmentWrite(any(), any(), any()) } returns true
        every { repository.save(any()) } throws DuplicateKeyException("unique")
        failure(409) { service.subscribe("user", "event", CalendarSubscribeRequest("account", 0, false)) }
    }

    @Test
    fun `checklist computes completion normalizes names and preserves final deletion result`() {
        val entries = listOf(CalendarChecklistEntry("one", "第一关", true))
        every { repository.find("user", "account", "event") } returns record().copy(checklist = entries)
        assertTrue(service.progress("user", "event", CalendarProgressRequest("account", 0, false, entries)).completed)
        val added = service.progress(
            "user",
            "event",
            CalendarProgressRequest(
                "account",
                0,
                false,
                entries + CalendarChecklistEntry("two", " 第二关 ", false),
            ),
        )
        assertFalse(added.completed)
        assertEquals("第二关", added.checklist.last().title)
        assertTrue(service.progress("user", "event", CalendarProgressRequest("account", 0, true, emptyList())).completed)
        assertFalse(service.progress("user", "event", CalendarProgressRequest("account", 0, false, emptyList())).completed)
        failure(422) { service.progress("user", "event", CalendarProgressRequest("account", 0, true, entries)) }
        failure(422) { service.progress("user", "event", CalendarProgressRequest("account", 0, false, entries + entries)) }
        assertTrue(
            service.progress(
                "user",
                "event",
                CalendarProgressRequest("account", 0, false, listOf(CalendarChecklistEntry("new", "关卡", true))),
            ).completed,
        )
        failure(422) {
            service.progress(
                "user",
                "event",
                CalendarProgressRequest("account", 0, false, listOf(CalendarChecklistEntry("one", " ", false))),
            )
        }
    }

    @Test
    fun `list joins current public data and isolates unavailable sources without writes`() {
        every { repository.list("user", "account") } returns listOf(record(), record("gone"), record("wrong").copy(game = "代号鸢"))
        every { calendar.publicItems("如鸢", null, null, null) } returns
            ActivityCalendarResponse(listOf(item().copy(title = "延期更正"), item("wrong")))
        val result = service.list("user", "account", day, day.plusDays(7), null)
        assertEquals("延期更正", result.items.single().item!!.title)
        assertEquals(setOf("gone", "wrong"), result.unavailableItems.map { it.eventId }.toSet())
        assertEquals(3, result.subscribedCount)
        failure(422) { service.list("user", "account", day, day.plusDays(101), null) }
        verify(exactly = 0) {
            repository.save(any())
            accounts.fenceRecruitmentWrite(any(), any(), any())
        }
    }

    @Test
    fun `summary filters entire set before taking three and counts activities not stages`() {
        val events = (1..5).map { item("event$it", day.plusDays(it.toLong())) }
        every { calendar.publicItems("如鸢", null, null, null) } returns ActivityCalendarResponse(events)
        every { repository.list("user", "account") } returns events.map { record(it.id) } + record("gone")
        val result = service.summary("user", "account")
        assertEquals(5, result.totalPending)
        assertEquals(listOf("event1", "event2", "event3"), result.items.map { it.eventId })
        assertEquals(6, result.subscribedCount)
    }

    @Test
    fun `exact cutoff is exclusive with timezone and date-only remains active for end date`() {
        val exact = item().copy(startTime = "10:00", endTime = "12:00", endDate = day)
        every { calendar.publicItems("如鸢", null, null, null) } returns ActivityCalendarResponse(listOf(exact))
        every { repository.list("user", "account") } returns listOf(record())
        assertEquals(0, service.summary("user", "account").totalPending)
        failure(422) { service.subscribe("user", "event", CalendarSubscribeRequest("account", null, true)) }
        val before =
            ActivityCalendarSubscriptionService(
                repository,
                accounts,
                calendar,
                transaction,
                Clock.fixed(now.minusSeconds(1), ZoneOffset.UTC),
            )
        assertEquals(1, before.summary("user", "account").totalPending)
        every { calendar.publicItems("如鸢", null, null, null) } returns ActivityCalendarResponse(listOf(item(end = day)))
        assertEquals(1, service.summary("user", "account").totalPending)
        val nextDay =
            ActivityCalendarSubscriptionService(
                repository,
                accounts,
                calendar,
                transaction,
                Clock.fixed(Instant.parse("2026-10-05T16:00:00Z"), ZoneOffset.UTC),
            )
        assertEquals(0, nextDay.summary("user", "account").totalPending)
    }

    @Test
    fun `future completed wrong-game and stopped events never become reminders`() {
        every { calendar.publicItems("如鸢", null, null, null) } returns
            ActivityCalendarResponse(listOf(item("future").copy(startDate = day.plusDays(1)), item("done")))
        every { repository.list("user", "account") } returns listOf(record("future"), record("done").copy(completed = true), record("gone"))
        assertEquals(0, service.summary("user", "account").totalPending)
        every { repository.find("user", "account", "gone") } returns record("gone")
        assertFalse(service.subscribe("user", "gone", CalendarSubscribeRequest("account", 0, false)).subscribed)
        failure(422) { service.progress("user", "gone", CalendarProgressRequest("account", 0, true, emptyList())) }
    }

    @Test
    fun `other owner account is indistinguishable from missing and never queries subscriptions`() {
        every { accounts.findByUserIdAndAccountId("other", "account") } returns null
        failure(404) { service.get("other", "account", "event") }
        failure(404) { service.summary("other", "account") }
        verify(exactly = 0) {
            repository.find(any(), any(), any())
            repository.list(any(), any())
            repository.save(any())
        }
    }

    @Test
    fun `memo fields are independent of completion and omitted fields retain saved values`() {
        val entries = listOf(CalendarChecklistEntry("legacy", "旧关卡", false))
        every { repository.find("user", "account", "event") } returns record().copy(checklist = entries)
        val saved = service.progress("user", "event", CalendarProgressRequest("account", 0, false, entries, " 本期已完成 ", "兑换\n提示"))
        assertEquals("本期已完成", saved.memoTitle)
        assertEquals("兑换\n提示", saved.memoNote)
        assertFalse(saved.completed)
        assertEquals(entries, saved.checklist)
        every { repository.find("user", "account", "event") } returns record().copy(
            checklist = entries,
            memoTitle = saved.memoTitle,
            memoNote = saved.memoNote,
        )
        val legacy = service.progress("user", "event", CalendarProgressRequest("account", 0, false, entries))
        assertEquals(saved.memoTitle, legacy.memoTitle)
        assertEquals(saved.memoNote, legacy.memoNote)
        val cleared = service.progress("user", "event", CalendarProgressRequest("account", 0, false, entries, memoNote = ""))
        assertEquals(saved.memoTitle, cleared.memoTitle)
        assertEquals("", cleared.memoNote)
        val renamed = service.progress("user", "event", CalendarProgressRequest("account", 0, false, entries, memoTitle = "新标题"))
        assertEquals("新标题", renamed.memoTitle)
        assertEquals(saved.memoNote, renamed.memoNote)
        every { repository.list("user", "account") } returns listOf(record().copy(checklist = entries, memoTitle = "本期已完成"))
        assertEquals(1, service.summary("user", "account").totalPending)
    }

    @Test
    fun `memo boundaries validate before save and title alone never completes empty checklist`() {
        every { repository.find("user", "account", "event") } returns record()
        listOf(
            CalendarProgressRequest("account", 0, false, emptyList(), ""),
            CalendarProgressRequest("account", 0, false, emptyList(), "   "),
            CalendarProgressRequest("account", 0, false, emptyList(), "字".repeat(61)),
            CalendarProgressRequest("account", 0, false, emptyList(), memoNote = "字".repeat(1001)),
        ).forEach { request -> failure(422) { service.progress("user", "event", request) } }
        verify(exactly = 0) { repository.save(any()) }
        val maximum = service.progress(
            "user",
            "event",
            CalendarProgressRequest("account", 0, false, emptyList(), "字".repeat(60), "字".repeat(1000)),
        )
        assertEquals(60, maximum.memoTitle.length)
        assertEquals(1000, maximum.memoNote.length)
        assertFalse(service.progress("user", "event", CalendarProgressRequest("account", 0, false, emptyList(), "本期已完成")).completed)
    }

    @Test
    fun `memo survives cancellation restoration cutoff and unavailable source without widening permissions`() {
        val original = record().copy(memoTitle = "本期目标", memoNote = "保留历史", checklist = listOf(CalendarChecklistEntry("old", "旧待办", true)))
        every { repository.find("user", "account", "event") } returns original
        val cancelled = service.subscribe("user", "event", CalendarSubscribeRequest("account", 0, false))
        assertEquals("本期目标", cancelled.memoTitle)
        assertEquals(original.checklist, cancelled.checklist)
        every { repository.find("user", "account", "event") } returns original.copy(subscribed = false, version = 1)
        val restored = service.subscribe("user", "event", CalendarSubscribeRequest("account", 1, true))
        assertEquals("保留历史", restored.memoNote)
        every { repository.find("user", "account", "event") } returns original
        every { calendar.publicItems("如鸢", null, null, null) } returns ActivityCalendarResponse(listOf(item(end = day.minusDays(1))))
        assertEquals(
            "截止后修改",
            service.progress("user", "event", CalendarProgressRequest("account", 0, true, emptyList(), "截止后修改")).memoTitle,
        )
        failure(422) { service.subscribe("user", "event", CalendarSubscribeRequest("account", 0, true)) }
        every { calendar.publicItems("如鸢", null, null, null) } returns ActivityCalendarResponse(emptyList())
        assertEquals("保留历史", service.get("user", "account", "event").memoNote)
        failure(422) { service.progress("user", "event", CalendarProgressRequest("account", 0, false, emptyList(), "越权修改")) }
        failure(409) { service.progress("user", "event", CalendarProgressRequest("account", 2, false, emptyList(), "并发修改")) }
        assertEquals("保留历史", service.subscribe("user", "event", CalendarSubscribeRequest("account", 0, false)).memoNote)
    }
}
