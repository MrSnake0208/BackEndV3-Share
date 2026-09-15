package com.lhs.share.hub.service.star

import com.lhs.share.hub.controller.star.request.StarInventoryEntryRequest
import com.lhs.share.hub.controller.star.request.StarInventorySnapshotRequest
import com.lhs.share.hub.controller.star.response.StarInventorySnapshotResponse
import com.lhs.share.hub.repository.StarInventoryCurrentRepository
import com.lhs.share.hub.repository.entity.StarInventoryCurrent
import com.lhs.share.hub.repository.entity.StarInventoryEntry
import com.lhs.share.hub.service.account.SubAccountService
import com.lhs.share.hub.service.inventory.InventoryApiException
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Service
class StarInventoryService(
    private val repository: StarInventoryCurrentRepository,
    private val accountService: SubAccountService,
) {
    fun current(userId: String, accountId: String): StarInventorySnapshotResponse {
        validateAccount(userId, accountId)
        return repository.findByUserIdAndAccountId(userId, accountId)
            ?.let(StarInventorySnapshotResponse::of)
            ?: StarInventorySnapshotResponse.empty(accountId)
    }

    fun putCurrent(
        userId: String,
        accountId: String,
        request: StarInventorySnapshotRequest,
    ): StarInventorySnapshotResponse {
        validateAccount(userId, accountId)
        val normalized = normalize(request)
        val contentHash = hashEntries(normalized.entries)
        var current = repository.findByUserIdAndAccountId(userId, accountId)

        repeat(MAX_WRITE_ATTEMPTS) {
            current?.let {
                if (hashEntries(it.entries) == contentHash) return StarInventorySnapshotResponse.of(it)
                rejectIfNotNewer(it, normalized.effectiveAt)
            }

            try {
                val saved = repository.replaceIfEffectiveAtAfterCurrent(
                    userId = userId,
                    accountId = accountId,
                    effectiveAt = normalized.effectiveAt,
                    entries = normalized.entries,
                    contentHash = contentHash,
                    expectedRevision = current?.revision ?: 0,
                    updatedAt = Instant.now(),
                    receivedAt = Instant.now(),
                )
                if (saved != null) return StarInventorySnapshotResponse.of(saved)
            } catch (_: DuplicateKeyException) {
                // A concurrent first write won the owner unique index; classify it below.
            }

            current = repository.findByUserIdAndAccountId(userId, accountId)
        }

        val latest = current ?: repository.findByUserIdAndAccountId(userId, accountId)
        if (latest != null) {
            if (hashEntries(latest.entries) == contentHash) return StarInventorySnapshotResponse.of(latest)
            rejectIfNotNewer(latest, normalized.effectiveAt)
        }
        throw conflict("Concurrent star inventory update could not be applied")
    }

    private fun validateAccount(userId: String, accountId: String) {
        if (!ACCOUNT_ID.matches(accountId)) {
            throw InventoryApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "schema_validation_failed",
                "account_id 格式无效",
            )
        }
        accountService.requireAccount(userId, accountId)
    }

    private fun normalize(request: StarInventorySnapshotRequest): NormalizedSnapshot {
        val effectiveAt = try {
            OffsetDateTime.parse(request.effectiveAt, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (_: RuntimeException) {
            throw invalid("effective_at 必须是带时区的 ISO-8601 时间")
        }
        if (request.entries.size > MAX_ENTRIES) throw invalid("entries 数量不能超过 $MAX_ENTRIES")

        val seen = HashSet<String>(request.entries.size)
        val entries = request.entries.map { normalizeEntry(it, seen) }.sortedBy { it.instanceId }
        return NormalizedSnapshot(effectiveAt, entries)
    }

    private fun normalizeEntry(
        entry: StarInventoryEntryRequest,
        seen: MutableSet<String>,
    ): StarInventoryEntry {
        if (!INSTANCE_ID.matches(entry.instanceId) || !seen.add(entry.instanceId)) {
            throw invalid("instance_id 必须唯一且格式有效")
        }
        if (entry.kind !in KINDS) throw invalid("kind 仅支持 main 或 support")
        val name = entry.name.trim()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) throw invalid("name 去除首尾空白后不能为空且不能超过 256")
        if (entry.quality !in QUALITIES) throw invalid("quality 仅支持 orange、purple、blue、green、white")
        val level = entry.level ?: throw invalid("level 不能为空")
        if (level !in MIN_LEVEL..MAX_LEVEL) throw invalid("level 必须在 $MIN_LEVEL..$MAX_LEVEL")
        return StarInventoryEntry(entry.instanceId, entry.kind, name, entry.quality, level)
    }

    private fun hashEntries(entries: List<StarInventoryEntry>): String {
        val values = buildList {
            add("yuanstar-star-inventory-v1")
            entries.sortedBy { it.instanceId }.forEach { entry ->
                add(entry.instanceId)
                add(entry.kind)
                add(entry.name)
                add(entry.quality)
                add(entry.level.toString())
            }
        }
        val canonical = values.joinToString(separator = "") { value ->
            val length = value.toByteArray(StandardCharsets.UTF_8).size
            "$length:$value;"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun rejectIfNotNewer(current: StarInventoryCurrent, incomingEffectiveAt: Instant) {
        when {
            incomingEffectiveAt.isBefore(current.effectiveAt) -> throw InventoryApiException(
                HttpStatus.CONFLICT,
                "star_inventory_stale_snapshot",
                "The submitted snapshot is older than the current snapshot",
            )
            incomingEffectiveAt.equals(current.effectiveAt) -> throw conflict("A different snapshot already exists at this time")
        }
    }

    private fun invalid(message: String) = InventoryApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "star_inventory_invalid_snapshot",
        message,
    )

    private fun conflict(message: String) = InventoryApiException(
        HttpStatus.CONFLICT,
        "star_inventory_revision_conflict",
        message,
    )

    private data class NormalizedSnapshot(
        val effectiveAt: Instant,
        val entries: List<StarInventoryEntry>,
    )

    companion object {
        const val MAX_ENTRIES = 1000
        const val MIN_LEVEL = 1
        const val MAX_LEVEL = 60
        private const val MAX_NAME_LENGTH = 256
        private const val MAX_WRITE_ATTEMPTS = 3
        private val ACCOUNT_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private val INSTANCE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
        private val KINDS = setOf("main", "support")
        private val QUALITIES = setOf("orange", "purple", "blue", "green", "white")
    }
}
