package com.lhs.share.hub.controller.star.response

import com.lhs.share.hub.repository.entity.StarInventoryCurrent
import com.lhs.share.hub.repository.entity.StarInventoryEntry
import java.time.Instant

/** YuanStar 当前背包快照响应；不暴露 userId 或 contentHash。 */
data class StarInventorySnapshotResponse(
    val accountId: String,
    val effectiveAt: Instant?,
    val entries: List<StarInventoryEntryResponse>,
    val revision: Long?,
    val updatedAt: Instant?,
) {
    companion object {
        fun empty(accountId: String) = StarInventorySnapshotResponse(
            accountId = accountId,
            effectiveAt = null,
            entries = emptyList(),
            revision = null,
            updatedAt = null,
        )

        fun of(current: StarInventoryCurrent) = StarInventorySnapshotResponse(
            accountId = current.accountId,
            effectiveAt = current.effectiveAt,
            entries = current.entries.map(StarInventoryEntryResponse::of),
            revision = current.revision,
            updatedAt = current.updatedAt,
        )
    }
}

data class StarInventoryEntryResponse(
    val instanceId: String,
    val kind: String,
    val name: String,
    val quality: String,
    val level: Int,
) {
    companion object {
        fun of(entry: StarInventoryEntry) = StarInventoryEntryResponse(
            instanceId = entry.instanceId,
            kind = entry.kind,
            name = entry.name,
            quality = entry.quality,
            level = entry.level,
        )
    }
}
