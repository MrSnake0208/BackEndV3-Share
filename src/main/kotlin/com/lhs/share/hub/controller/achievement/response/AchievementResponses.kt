package com.lhs.share.hub.controller.achievement.response

import com.lhs.share.hub.repository.entity.AchievementRecord
import java.time.Instant

data class AchievementCategory(val categoryId: String, val name: String)
data class AchievementReward(val itemKey: String, val name: String, val quantity: Long)
data class AchievementRewardItem(val itemKey: String, val name: String, val games: List<String>)
data class AchievementEntry(
    val achievementId: String,
    val game: String,
    val categoryId: String,
    val name: String,
    val condition: String,
    val sort: Int,
    val isHidden: Boolean? = null,
    val rewards: List<AchievementReward>? = null,
    val target: Long? = null,
    val linkedMetric: String? = null,
)
data class AchievementCatalogResponse(
    val catalogVersion: String,
    val game: String,
    val dataStatus: String,
    val coverage: String,
    val categories: List<AchievementCategory>,
    val entries: List<AchievementEntry>,
    val legacyRewards: Map<String, String> = emptyMap(),
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
