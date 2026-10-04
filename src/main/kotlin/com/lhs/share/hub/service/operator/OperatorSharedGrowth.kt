package com.lhs.share.hub.service.operator

import com.lhs.share.hub.repository.entity.OperatorEntry
import java.time.Instant

/** Shared growth stays mirrored in entries; ownership and combat-form data stay local. */
object OperatorSharedGrowth {
    fun baseId(id: String, catalog: OperatorCatalogService): String = catalog.getOperator(id)?.spOf ?: id

    fun members(id: String, catalog: OperatorCatalogService): List<String> {
        val base = baseId(id, catalog)
        return (listOf(base) + catalog.spFormsOf(base)).distinct()
    }

    fun synchronize(entries: MutableMap<String, OperatorEntry>, sourceId: String, catalog: OperatorCatalogService, now: Instant) {
        val source = entries.getValue(sourceId)
        members(sourceId, catalog).filter { it != sourceId }.forEach { id ->
            val existing = entries[id]
            entries[id] = if (existing == null) {
                OperatorEntry(elite = source.elite, starLevel = 0, level = source.level, revision = 1, updatedAt = now)
            } else if (existing.level != source.level || existing.elite != source.elite) {
                existing.copy(
                    level = source.level,
                    elite = source.elite,
                    revision = existing.revision + 1,
                    updatedAt = now,
                    combatStats = existing.combatStats?.let { stats ->
                        if (stats.observedStatus != "unavailable" && (stats.observedAttack != null || stats.observedHp != null)) {
                            stats.copy(observedStatus = "stale")
                        } else {
                            stats
                        }
                    },
                )
            } else {
                existing
            }
        }
    }
}
