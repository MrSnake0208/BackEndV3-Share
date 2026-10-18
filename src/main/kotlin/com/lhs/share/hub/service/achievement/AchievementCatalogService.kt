package com.lhs.share.hub.service.achievement

import com.fasterxml.jackson.databind.ObjectMapper
import com.lhs.share.hub.controller.achievement.request.AchievementRequestDecoder.invalid
import com.lhs.share.hub.controller.achievement.response.AchievementCatalogResponse
import com.lhs.share.hub.service.account.SubAccountService
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.net.URI
import java.time.LocalDate

@Service
class AchievementCatalogService(mapper: ObjectMapper) {
    private val catalogs: List<AchievementCatalogResponse> = ClassPathResource("achievement/catalog.json").inputStream.use { input ->
        mapper.readTree(input).map { mapper.treeToValue(it, AchievementCatalogResponse::class.java) }
    }.also { values ->
        check(values.map { it.game }.toSet() == SubAccountService.SUPPORTED_GAMES && values.size == 2)
        values.forEach { value ->
            check(value.catalogVersion.isNotBlank() && value.coverage.isNotBlank())
            check(value.dataStatus in setOf("empty", "partial", "complete"))
            check((value.dataStatus == "empty") == value.entries.isEmpty())
            check(value.categories.map { it.categoryId }.distinct().size == value.categories.size)
            check(value.entries.map { it.achievementId }.distinct().size == value.entries.size)
            value.entries.forEach { entry ->
                check(entry.achievementId.matches(Regex("yh_[a-z0-9_]{1,96}")) && entry.game == value.game)
                check(entry.categoryId in value.categories.map { it.categoryId } && entry.name.isNotBlank() && entry.condition.isNotBlank())
                check(entry.dataStatus == "verified" && entry.sources.isNotEmpty())
                check(LocalDate.parse(entry.verifiedAt).year in 1..9999)
                entry.sources.forEach { check(URI(it.url).scheme == "https" && !URI(it.url).host.isNullOrBlank() && it.usage.isNotBlank()) }
                if (entry.progressKind == null) {
                    check(entry.target == null && entry.unit == null)
                } else {
                    check(
                        entry.progressKind in setOf("integer", "number") && entry.target?.let { it.isFinite() && it > 0 } == true &&
                            !entry.unit.isNullOrBlank(),
                    )
                }
                if (entry.progressKind == "integer") check(entry.target!! % 1.0 == 0.0)
            }
        }
        check(values.flatMap { it.entries }.map { it.achievementId }.distinct().size == values.sumOf { it.entries.size })
    }

    fun catalog(game: String): AchievementCatalogResponse = catalogs.firstOrNull { it.game == game } ?: invalid("game 只允许代号鸢或如鸢")
}
