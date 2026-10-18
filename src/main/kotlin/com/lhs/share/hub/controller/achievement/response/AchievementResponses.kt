package com.lhs.share.hub.controller.achievement.response

import com.lhs.share.hub.repository.entity.AchievementRecord
import java.time.Instant

data class AchievementSource(val title: String, val url: String, val usage: String)
data class AchievementCategory(val categoryId: String, val name: String)
data class AchievementOperator(val id: String, val name: String)
data class AchievementEntry(
    val achievementId: String,
    val game: String,
    val categoryId: String,
    val name: String,
    val condition: String,
    val sort: Int,
    val isHidden: Boolean? = null,
    val aliases: List<String> = emptyList(),
    val operators: List<AchievementOperator> = emptyList(),
    val chapter: String? = null,
    val reward: String? = null,
    val method: String? = null,
    val spoiler: String? = null,
    val sources: List<AchievementSource> = emptyList(),
    val verifiedAt: String,
    val dataStatus: String,
    val progressKind: String? = null,
    val target: Double? = null,
    val unit: String? = null,
)
data class AchievementCatalogResponse(
    val catalogVersion: String,
    val game: String,
    val dataStatus: String,
    val coverage: String,
    val categories: List<AchievementCategory>,
    val entries: List<AchievementEntry>,
)
data class AchievementArchiveResponse(val accountId: String, val game: String, val revision: Long, val records: List<AchievementRecord>)
data class AchievementSourceAccount(val name: String)
data class AchievementBackupResponse(
    val format: String = "yuanhub-achievement",
    val schemaVersion: Int = 1,
    val game: String,
    val catalogVersion: String,
    val exportedAt: Instant,
    val sourceAccount: AchievementSourceAccount,
    val records: List<AchievementRecord>,
)
data class AchievementImportItem(val index: Int, val achievementId: String?, val outcome: String, val message: String? = null)
data class AchievementPreviewResponse(
    val accountId: String,
    val game: String,
    val expectedRevision: Long,
    val catalogVersion: String,
    val strategy: String,
    val stats: Map<String, Int>,
    val items: List<AchievementImportItem>,
    val affected: Int,
    val previewToken: String? = null,
)
