package com.lhs.share.hub.service.report

import com.lhs.share.config.external.ShareProperties
import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.controller.response.user.MaaUserInfo
import com.lhs.share.hub.controller.report.request.FeedbackMessageAppendRequest
import com.lhs.share.hub.controller.report.request.FeedbackStatusUpdateRequest
import com.lhs.share.hub.repository.FeedbackTicketQueryRepository
import com.lhs.share.hub.repository.FeedbackTicketRepository
import com.lhs.share.hub.repository.entity.FeedbackMessage
import com.lhs.share.hub.repository.entity.FeedbackTicket
import com.lhs.share.hub.service.HubUserInfoService
import com.lhs.share.hub.service.notification.NotificationService
import com.lhs.share.testinfra.TestMongo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bson.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import java.time.Instant
import java.util.Optional
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("integration")
class FeedbackPersistenceMongoTest {
    private val database = TestMongo.database("feedback")
    private val client = TestMongo.client()
    private val mongo = MongoTemplate(client, database)
    private val queries = FeedbackTicketQueryRepository(mongo)
    private val tickets = mockk<FeedbackTicketRepository>()
    private val access = mockk<FeedbackAccessService>(relaxed = true)
    private val notifications = mockk<NotificationService>(relaxed = true)
    private val users = mockk<HubUserInfoService>()
    private val service = FeedbackReportService(tickets, queries, access, mockk(), notifications, users, ShareProperties())

    init {
        // Production legacy repository.save is MongoTemplate.save. Reads can be
        // held at the same snapshot to deterministically reproduce overlap.
        every { tickets.save(any()) } answers { mongo.save(firstArg<FeedbackTicket>()) }
        every { tickets.findById(any()) } answers {
            Optional.ofNullable(mongo.findById(firstArg<String>(), FeedbackTicket::class.java))
        }
        every { access.canManage("admin", any()) } returns true
        every { users.get(any()) } answers { MaaUserInfo(firstArg(), "Synthetic feedback user") }
    }

    @AfterEach
    fun cleanup() {
        TestMongo.dropDatabase(client, database)
        client.close()
    }

    @Test
    fun `OTHER grant does not expose category-only tickets from another board`() {
        mongo.insert(ticket("operator").copy(area = null))
        mongo.insert(ticket("other").copy(type = "FEEDBACK", category = "BUG", area = null))
        assertEquals(setOf("other"), ids(setOf("OTHER")))
        assertEquals(setOf("operator"), ids(setOf("OPERATOR")))
    }

    @Test
    fun `list board precedence matches detail authorization for conflicting legacy fields`() {
        mongo.insert(ticket("conflict").copy(category = "OTHER", area = "OPERATOR"))
        assertTrue(ids(setOf("OTHER")).isEmpty())
        assertEquals(setOf("conflict"), ids(setOf("OPERATOR")))
        val denied = assertThrows(ApiResultException::class.java) { service.getById("outsider", "conflict") }
        assertEquals(403, denied.statusCode)
    }

    @Test
    fun `null and absent legacy fields fall back to OTHER without leaking known categories`() {
        val collection = mongo.getCollection("feedback_tickets")
        for (id in listOf("missing", "null")) {
            val document = Document()
            mongo.converter.write(ticket(id).copy(type = "FEEDBACK", category = "BUG", area = null), document)
            if (id == "null") document["area"] = null else document.remove("area")
            collection.insertOne(document)
        }
        assertEquals(setOf("missing", "null"), ids(setOf("OTHER")))
        assertTrue(ids(emptySet()).isEmpty())
    }

    @Test
    fun `legacy FEEDBACK filter does not return tickets normalized to a concrete type`() {
        mongo.insert(ticket("bug").copy(type = "FEEDBACK", category = "BUG"))
        mongo.insert(ticket("legacy").copy(type = "FEEDBACK", category = null))
        assertEquals(setOf("legacy"), ids(setOf("OPERATOR"), "FEEDBACK"))
        assertEquals(setOf("bug"), ids(setOf("OPERATOR"), "BUG"))
    }

    @Test
    fun `simultaneous reporter supplements do not silently overwrite each other or exceed quota`() {
        val initial = ticket("parallel").let { value ->
            value.copy(messages = value.messages + (1..2).map { message("pending_$it") })
        }
        mongo.insert(initial)
        val barrier = CyclicBarrier(2)
        every { tickets.findById("parallel") } answers {
            val snapshot = mongo.findById("parallel", FeedbackTicket::class.java)!!
            barrier.await(10, TimeUnit.SECONDS)
            Optional.of(snapshot)
        }
        val pool = Executors.newFixedThreadPool(2)
        val results = try {
            (1..2).map { index ->
                pool.submit(
                    Callable {
                        runCatching {
                            service.appendMessage(
                                "reporter",
                                "parallel",
                                FeedbackMessageAppendRequest("append $index", actorMode = "REPORTER"),
                            )
                        }
                    },
                )
            }.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, results.count { it.isSuccess }, "One stale writer must receive a conflict, not false success")
        assertEquals(409, (results.single { it.isFailure }.exceptionOrNull() as ApiResultException).statusCode)
        val saved = mongo.findById("parallel", FeedbackTicket::class.java)!!
        assertEquals(4, saved.messages.size)
        every { tickets.findById("parallel") } returns Optional.of(saved)
        val quota = assertThrows(ApiResultException::class.java) {
            service.appendMessage("reporter", "parallel", FeedbackMessageAppendRequest("fourth", actorMode = "REPORTER"))
        }
        assertEquals(400, quota.statusCode)
    }

    @Test
    fun `stale status write cannot erase a newly saved administrator reply`() {
        val initial = mongo.insert(ticket("status"))
        every { tickets.findById("status") } returns Optional.of(initial)
        service.appendMessage("admin", "status", FeedbackMessageAppendRequest("response", actorMode = "ADMIN"))
        val error = assertThrows(ApiResultException::class.java) {
            service.updateStatus("admin", "status", FeedbackStatusUpdateRequest("RESOLVED", "ADMIN"))
        }
        assertEquals(409, error.statusCode)
        val saved = mongo.findById("status", FeedbackTicket::class.java)!!
        assertEquals(2, saved.messages.size)
        assertEquals("OPEN", saved.status)
        verify(exactly = 1) { notifications.create(any(), any(), any(), any(), any(), any()) }
        every { tickets.findById("status") } returns Optional.of(saved)
        assertEquals("RESOLVED", service.updateStatus("admin", "status", FeedbackStatusUpdateRequest("RESOLVED", "ADMIN")).status)
        assertEquals(2, mongo.findById("status", FeedbackTicket::class.java)!!.messages.size)
    }

    @Test
    fun `stale reply cannot reopen a closed ticket or send a false reply notification`() {
        val initial = mongo.insert(ticket("closed"))
        every { tickets.findById("closed") } returns Optional.of(initial)
        service.updateStatus("admin", "closed", FeedbackStatusUpdateRequest("RESOLVED", "ADMIN"))
        val error = assertThrows(ApiResultException::class.java) {
            service.appendMessage("admin", "closed", FeedbackMessageAppendRequest("late reply", actorMode = "ADMIN"))
        }
        assertEquals(409, error.statusCode)
        val saved = mongo.findById("closed", FeedbackTicket::class.java)!!
        assertEquals("RESOLVED", saved.status)
        assertEquals(1, saved.messages.size)
        verify(exactly = 1) { notifications.create(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `legacy ticket without messages or update timestamp remains writable`() {
        val document = Document()
        mongo.converter.write(ticket("old"), document)
        document.remove("messages")
        document.remove("updatedAt")
        mongo.getCollection("feedback_tickets").insertOne(document)
        val response = service.appendMessage("admin", "old", FeedbackMessageAppendRequest("legacy reply", actorMode = "ADMIN"))
        assertEquals("legacy reply", response.messages.single().content)
        assertTrue(response.hasAdminReply)
        assertFalse(response.quota.canAppend)
    }

    private fun ids(areas: Set<String>, type: String? = null): Set<String> = queries.search(
        null,
        areas,
        null,
        type,
        null,
        null,
        PageRequest.of(0, 100, Sort.Direction.DESC, "createdAt"),
    ).content.map { it.id!! }.toSet()

    private fun message(id: String) = FeedbackMessage(id, "REPORTER", "reporter", "Synthetic message $id")

    private fun ticket(id: String) = FeedbackTicket(
        id = id,
        type = "BUG",
        category = "OPERATOR",
        area = "OPERATOR",
        reporterUserId = "reporter",
        content = "Synthetic feedback",
        messages = listOf(message("initial")),
        updatedAt = Instant.parse("2026-09-01T00:00:00Z"),
    )
}
