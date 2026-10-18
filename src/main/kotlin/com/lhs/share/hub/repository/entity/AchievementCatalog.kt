package com.lhs.share.hub.repository.entity

import com.lhs.share.hub.controller.achievement.response.AchievementCatalogResponse
import com.lhs.share.hub.controller.achievement.response.AchievementCategory
import com.lhs.share.hub.controller.achievement.response.AchievementEntry
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

data class CatalogCategory(val categoryId: String, val name: String, val sort: Int = 0, val enabled: Boolean = true)

data class CatalogContent(
    val game: String,
    val dataStatus: String = "empty",
    val coverage: String = "",
    val categories: List<CatalogCategory> = emptyList(),
    val entries: List<AchievementEntry> = emptyList(),
    val retiredIds: Set<String> = emptySet(),
    val catalogVersion: String = "",
) {
    fun publicCatalog(version: String) = AchievementCatalogResponse(
        version,
        game,
        dataStatus,
        coverage,
        categories.filter { it.enabled }.sortedBy { it.sort }.map { AchievementCategory(it.categoryId, it.name) },
        entries.filter { it.achievementId !in retiredIds }.sortedBy { it.sort },
    )
}

data class CatalogPublication(
    val version: String,
    val content: CatalogContent,
    val publishedAt: Instant,
    val publishedBy: String,
    val restoredFrom: String? = null,
)

@Document("achievement_catalogs")
data class AchievementCatalog(
    @Id val game: String,
    val revision: Long = 0,
    val published: CatalogPublication,
    val draft: CatalogContent,
    val history: List<CatalogPublication> = emptyList(),
    val identities: Map<String, AchievementEntry> = emptyMap(),
    val issuedIds: Set<String> = emptySet(),
    val categoryIds: Set<String> = emptySet(),
)
