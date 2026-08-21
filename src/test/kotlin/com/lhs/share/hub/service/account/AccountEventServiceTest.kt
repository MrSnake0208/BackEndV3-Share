package com.lhs.share.hub.service.account

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

class AccountEventServiceTest {
    private val service = AccountEventService()

    @Test
    fun `events are delivered only to the matching user and account`() {
        val matching = emitter()
        val otherAccount = emitter()
        val otherUser = emitter()
        service.register("u1", "acc1", matching)
        service.register("u1", "acc2", otherAccount)
        service.register("u2", "acc1", otherUser)
        clearMocks(matching, otherAccount, otherUser, answers = false)

        service.publish("u1", "acc1", "inventory_import", "event-1", mapOf("id" to "item1"))

        verify(exactly = 1) { matching.send(any<SseEmitter.SseEventBuilder>()) }
        verify(exactly = 0) { otherAccount.send(any<SseEmitter.SseEventBuilder>()) }
        verify(exactly = 0) { otherUser.send(any<SseEmitter.SseEventBuilder>()) }
    }

    private fun emitter() = mockk<SseEmitter>(relaxed = true).also {
        every { it.onCompletion(any()) } just runs
        every { it.onTimeout(any()) } just runs
        every { it.onError(any()) } just runs
    }
}
