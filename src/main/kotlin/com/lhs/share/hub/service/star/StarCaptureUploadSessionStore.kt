package com.lhs.share.hub.service.star

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Instant

/** Temporary uploads are deliberately separate from the formal capture/pending store. */
interface StarCaptureUploadSessionStore {
    fun find(key: StarCaptureKey): StarCaptureUploadSession?
    fun create(session: StarCaptureUploadSession, ttlSeconds: Long): Boolean
    fun image(key: StarCaptureKey, sourceImageId: String): StoredStarCaptureImage?

    /** 1 = inserted, 0 = already uploaded, -1 = expired session. */
    fun putImage(key: StarCaptureKey, image: StoredStarCaptureImage): Long
    fun dueCleanup(now: Instant): List<StarCaptureUploadCleanup>
    fun completeCleanup(entry: StarCaptureUploadCleanup)
}

data class StarCaptureUploadSession(
    val key: StarCaptureKey,
    val rawManifest: String,
    val manifest: StarCaptureManifest,
    val directory: String,
    val expiresAt: Instant,
)

data class StarCaptureUploadCleanup(
    val key: StarCaptureKey,
    val directory: String,
    val sourceImageIds: List<String>,
)

@Component
class RedisStarCaptureUploadSessionStore(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) : StarCaptureUploadSessionStore {
    override fun find(key: StarCaptureKey): StarCaptureUploadSession? =
        redis.opsForValue()[sessionKey(key)]?.let { objectMapper.readValue(it, StarCaptureUploadSession::class.java) }

    override fun create(session: StarCaptureUploadSession, ttlSeconds: Long): Boolean {
        val cleanup = StarCaptureUploadCleanup(session.key, session.directory, session.manifest.images.map { it.sourceImageId })
        return redis.execute(
            CREATE,
            listOf(sessionKey(session.key), EXPIRY_INDEX),
            objectMapper.writeValueAsString(session),
            ttlSeconds.coerceAtLeast(1).toString(),
            objectMapper.writeValueAsString(cleanup),
            session.expiresAt.toEpochMilli().toString(),
        ) == 1L
    }

    override fun image(key: StarCaptureKey, sourceImageId: String): StoredStarCaptureImage? =
        redis.opsForValue()[imageKey(key, sourceImageId)]?.let { objectMapper.readValue(it, StoredStarCaptureImage::class.java) }

    override fun putImage(key: StarCaptureKey, image: StoredStarCaptureImage): Long = redis.execute(
        PUT_IMAGE,
        listOf(sessionKey(key), imageKey(key, image.sourceImageId)),
        objectMapper.writeValueAsString(image),
    ) ?: -1L

    override fun dueCleanup(now: Instant): List<StarCaptureUploadCleanup> =
        redis.opsForZSet().rangeByScore(EXPIRY_INDEX, 0.0, now.toEpochMilli().toDouble(), 0, 200).orEmpty()
            .map { objectMapper.readValue(it, StarCaptureUploadCleanup::class.java) }

    override fun completeCleanup(entry: StarCaptureUploadCleanup) {
        redis.delete(listOf(sessionKey(entry.key)) + entry.sourceImageIds.map { imageKey(entry.key, it) })
        redis.opsForZSet().remove(EXPIRY_INDEX, objectMapper.writeValueAsString(entry))
    }

    private fun sessionKey(key: StarCaptureKey) = "star-capture:upload:v1:${key.userId}:${key.accountId}:${key.captureId}"
    private fun imageKey(key: StarCaptureKey, sourceImageId: String) = "${sessionKey(key)}:image:$sourceImageId"

    private companion object {
        const val EXPIRY_INDEX = "star-capture:upload-expiry:v1"
        val CREATE = DefaultRedisScript(
            """
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', ARGV[2]) then
                redis.call('ZADD', KEYS[2], ARGV[4], ARGV[3])
                return 1
            end
            return 0
            """.trimIndent(),
            Long::class.javaObjectType,
        )
        val PUT_IMAGE = DefaultRedisScript(
            """
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl <= 0 then return -1 end
            if redis.call('SET', KEYS[2], ARGV[1], 'NX', 'PX', ttl) then return 1 end
            return 0
            """.trimIndent(),
            Long::class.javaObjectType,
        )
    }
}
