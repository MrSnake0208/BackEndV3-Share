package com.lhs.share.hub.service.calendar

import com.lhs.share.hub.controller.calendar.request.ActivityCalendarWriteRequest
import com.lhs.share.hub.controller.calendar.request.CalendarProgressRequest
import com.lhs.share.hub.controller.calendar.request.CalendarSubscribeRequest
import com.lhs.share.hub.repository.ActivityCalendarRepository
import com.lhs.share.hub.repository.ActivityCalendarSubscriptionRepository
import com.lhs.share.hub.repository.RecruitmentCatalogRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.SubAccountRepositoryImpl
import com.lhs.share.hub.repository.entity.ActivityCalendarCategory
import com.lhs.share.hub.repository.entity.ActivityCalendarEvent
import com.lhs.share.hub.repository.entity.ActivityCalendarSubscription
import com.lhs.share.hub.repository.entity.SubAccount
import com.lhs.share.hub.service.account.SubAccountService
import com.lhs.share.openapi.OpenApiTokenService
import com.lhs.share.testinfra.TestMongo
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("integration")
class ActivityCalendarSubscriptionMongoTest {
    private val database = TestMongo.database("cal_subs")
    private val client = TestMongo.client()
    private val factory = SimpleMongoClientDatabaseFactory(client, database)
    private val mongo = MongoTemplate(factory)
    private val transaction = TransactionTemplate(MongoTransactionManager(factory))
    private val repository = ActivityCalendarSubscriptionRepository(mongo)
    private val events = ActivityCalendarRepository(mongo)
    private val calendar = ActivityCalendarService(events, RecruitmentCatalogRepository(mongo))
    private val accounts = mockk<SubAccountRepository>()
    private val accountWrites = SubAccountRepositoryImpl(mongo)
    private val tokens = mockk<OpenApiTokenService>(relaxed = true)
    private val now = Instant.parse("2026-10-05T04:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val service = ActivityCalendarSubscriptionService(repository, accounts, calendar, transaction, clock)
    private val lifecycle = SubAccountService(
        accountRepository = accounts, inventoryCurrentRepository = mockk(relaxed = true), inventoryRecordRepository = mockk(relaxed = true),
        inventoryDeletedRecordRepository = mockk(relaxed = true), favoriteRepository = mockk(relaxed = true),
        operatorCurrentRepository = mockk(relaxed = true), operatorRecordRepository = mockk(relaxed = true),
        operatorCorrectionRecordRepository = mockk(relaxed = true), operatorV3ImportRecordRepository = mockk(relaxed = true),
        operatorScanReviewRepository = mockk(relaxed = true), tokenService = tokens, transactionTemplate = transaction,
        calendarSubscriptions = repository,
    )
    private lateinit var eventId: String

    @BeforeEach
    fun setup() {
        listOf(SubAccount::class.java, ActivityCalendarEvent::class.java, ActivityCalendarSubscription::class.java).forEach { type ->
            mongo.createCollection(type)
            MongoPersistentEntityIndexResolver(mongo.converter.mappingContext).resolveIndexFor(type).forEach {
                mongo.indexOps(type).createIndex(it)
            }
        }
        mongo.insert(SubAccount(id = "account-doc", userId = "owner", accountId = "account", name = "大号", game = "如鸢"))
        mongo.insert(SubAccount(id = "other-doc", userId = "other", accountId = "other-account", name = "小号", game = "如鸢"))
        every { accounts.findByUserIdAndAccountId(any(), any()) } answers {
            mongo.findOne(
                Query(Criteria.where("userId").`is`(firstArg<String>()).and("accountId").`is`(secondArg<String>())),
                SubAccount::class.java,
            )
        }
        every { accounts.fenceRecruitmentWrite(any(), any(), any()) } answers
            { accountWrites.fenceRecruitmentWrite(firstArg(), secondArg(), thirdArg()) }
        every { accounts.deleteById(any()) } answers
            {
                mongo.remove(Query(Criteria.where("_id").`is`(firstArg<String>())), SubAccount::class.java)
                Unit
            }
        val day = LocalDate.parse("2026-10-05")
        eventId =
            calendar.create(
                "editor",
                ActivityCalendarWriteRequest("如鸢", "本期活动", ActivityCalendarCategory.ACTIVITY, day, day.plusDays(3)),
            ).item.id
    }

    @AfterEach
    fun cleanup() {
        TestMongo.dropDatabase(client, database)
        client.close()
    }

    private fun concurrent(actions: List<() -> Any>): List<Result<Any>> {
        val executor = Executors.newFixedThreadPool(actions.size)
        val ready = CountDownLatch(actions.size)
        val start = CountDownLatch(1)
        try {
            val results = actions.map { action ->
                executor.submit(
                    Callable {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        runCatching(action)
                    },
                )
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            return results.map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `unique index and concurrent subscription yield one record and owner isolation`() {
        val results = concurrent(List(2) { { service.subscribe("owner", eventId, CalendarSubscribeRequest("account", null, true)) } })
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1L, mongo.count(Query(), ActivityCalendarSubscription::class.java))
        val saved = repository.find("owner", "account", eventId)!!
        assertThrows(DuplicateKeyException::class.java) { repository.save(saved.copy(id = "duplicate", version = null)) }
        assertNull(repository.find("other", "account", eventId))
        assertThrows(ActivityCalendarApiException::class.java) { service.get("other", "account", eventId) }
        assertEquals(0, service.list("other", "other-account", null, null, null).subscribedCount)
    }

    @Test
    fun `concurrent progress CAS cannot lose saved update and stale retry fails`() {
        service.subscribe("owner", eventId, CalendarSubscribeRequest("account", null, true))
        val results =
            concurrent(List(2) { { service.progress("owner", eventId, CalendarProgressRequest("account", 0, true, emptyList())) } })
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1L, repository.find("owner", "account", eventId)!!.version)
        assertTrue(repository.find("owner", "account", eventId)!!.isCompleted())
        assertThrows(ActivityCalendarApiException::class.java) {
            service.progress("owner", eventId, CalendarProgressRequest("account", 0, false, emptyList()))
        }
    }

    @Test
    fun `failed save rolls back both subscription and account fence`() {
        val broken = spyk(repository)
        every { broken.save(any()) } answers {
            repository.save(firstArg())
            error("injected after write")
        }
        val failing = ActivityCalendarSubscriptionService(broken, accounts, calendar, transaction, clock)
        assertThrows(IllegalStateException::class.java) {
            failing.subscribe("owner", eventId, CalendarSubscribeRequest("account", null, true))
        }
        assertNull(repository.find("owner", "account", eventId))
        assertEquals(0L, mongo.findById("account-doc", SubAccount::class.java)!!.recruitmentFence)
    }

    @Test
    fun `real account deletion cascades cancelled history and rolls back on later failure`() {
        service.subscribe("owner", eventId, CalendarSubscribeRequest("account", null, true))
        service.subscribe("owner", eventId, CalendarSubscribeRequest("account", 0, false))
        every { tokens.revokeByAccount("owner", "account") } throws IllegalStateException("injected deletion failure")
        assertThrows(IllegalStateException::class.java) { lifecycle.delete("owner", "account") }
        assertNotNull(mongo.findById("account-doc", SubAccount::class.java))
        assertNotNull(repository.find("owner", "account", eventId))
        every { tokens.revokeByAccount("owner", "account") } returns Unit
        lifecycle.delete("owner", "account")
        assertNull(mongo.findById("account-doc", SubAccount::class.java))
        assertNull(repository.find("owner", "account", eventId))
    }

    @Test
    fun `delete and subscribe race never leave an orphan including after explicit delete retry`() {
        val results =
            concurrent(
                listOf({ service.subscribe("owner", eventId, CalendarSubscribeRequest("account", null, true)) }, {
                    lifecycle.delete("owner", "account")
                    true
                }),
            )
        assertTrue(results.any { it.isSuccess })
        if (mongo.findById("account-doc", SubAccount::class.java) == null) {
            assertFalse(repository.exists("owner", "account"))
        } else {
            lifecycle.delete("owner", "account")
        }
        assertNull(mongo.findById("account-doc", SubAccount::class.java))
        assertFalse(repository.exists("owner", "account"))
        assertNotNull(mongo.findById("other-doc", SubAccount::class.java))
    }
}
