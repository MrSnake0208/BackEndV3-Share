package com.lhs.share.hub.service.account

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

@Service
class AccountEventService {
    private val subscribers = ConcurrentHashMap<SubscriberKey, CopyOnWriteArraySet<SseEmitter>>()

    fun subscribe(userId: String, accountId: String): SseEmitter = register(userId, accountId, SseEmitter(0L))

    internal fun register(userId: String, accountId: String, emitter: SseEmitter): SseEmitter {
        val key = SubscriberKey(userId, accountId)
        subscribers.computeIfAbsent(key) { CopyOnWriteArraySet() }.add(emitter)
        emitter.onCompletion { remove(key, emitter) }
        emitter.onTimeout { remove(key, emitter) }
        emitter.onError { remove(key, emitter) }
        try {
            emitter.send(SseEmitter.event().reconnectTime(3_000).comment("connected"))
        } catch (_: Exception) {
            remove(key, emitter)
        }
        return emitter
    }

    fun publish(userId: String, accountId: String, eventName: String, eventId: String, data: Any) {
        val key = SubscriberKey(userId, accountId)
        subscribers[key]?.forEach { emitter ->
            try {
                emitter.send(SseEmitter.event().id(eventId).name(eventName).data(data))
            } catch (_: Exception) {
                remove(key, emitter)
            }
        }
    }

    @Scheduled(fixedDelay = 15_000)
    fun keepAlive() {
        subscribers.forEach { (key, emitters) ->
            emitters.forEach { emitter ->
                try {
                    emitter.send(SseEmitter.event().comment("keepalive"))
                } catch (_: Exception) {
                    remove(key, emitter)
                }
            }
        }
    }

    private fun remove(key: SubscriberKey, emitter: SseEmitter) {
        subscribers.computeIfPresent(key) { _, emitters ->
            emitters.remove(emitter)
            emitters.takeUnless { it.isEmpty() }
        }
    }

    private data class SubscriberKey(val userId: String, val accountId: String)
}
