package com.lhs.share.hub.service.achievement

import com.fasterxml.jackson.databind.ObjectMapper
import com.lhs.share.hub.controller.achievement.request.AchievementRequestDecoder.invalid
import com.lhs.share.hub.controller.achievement.response.AchievementCatalogResponse
import com.lhs.share.hub.repository.AchievementCatalogRepository
import com.lhs.share.hub.repository.entity.AchievementCatalog
import com.lhs.share.hub.repository.entity.CatalogCategory
import com.lhs.share.hub.repository.entity.CatalogContent
import com.lhs.share.hub.repository.entity.CatalogPublication
import com.lhs.share.hub.service.account.SubAccountService
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class AchievementCatalogService(private val mapper: ObjectMapper, private val repository: AchievementCatalogRepository) {
    fun catalog(game: String): AchievementCatalogResponse {
        requireGame(game)
        val published = repository.published(game) ?: load(game).published
        return published.content.publicCatalog(published.version)
    }

    private fun requireGame(game: String) {
        if (game !in SubAccountService.SUPPORTED_GAMES) invalid("game 只允许代号鸢或如鸢")
    }

    fun load(game: String): AchievementCatalog {
        requireGame(game)
        repository.find(game)?.let { return it }
        // Only an absent document may be seeded; database errors must propagate.
        val seed = ClassPathResource("achievement/catalog.json").inputStream.use { input ->
            mapper.readTree(input).map { mapper.treeToValue(it, AchievementCatalogResponse::class.java) }
        }
        check(seed.size == 2 && seed.map { it.game }.toSet() == SubAccountService.SUPPORTED_GAMES)
        check(seed.flatMap { it.entries }.map { it.achievementId }.distinct().size == seed.sumOf { it.entries.size })
        val contents = seed.map { value ->
            check(value.catalogVersion.isNotBlank())
            CatalogContent(
                value.game,
                value.dataStatus,
                value.coverage,
                value.categories.mapIndexed { i, c -> CatalogCategory(c.categoryId, c.name, i) },
                value.entries,
            ).also { check(AchievementCatalogRules.errors(it, true).isEmpty()) { "非法成就种子目录" } }
        }
        val content = contents.single { it.game == game }
        val initial = AchievementCatalog(
            game = game,
            published = CatalogPublication(
                seed.single { it.game == game }.catalogVersion,
                content,
                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                "seed",
            ),
            draft = content,
            identities = content.entries.associateBy { it.achievementId },
            issuedIds = content.entries.map { it.achievementId }.toSet(),
            categoryIds = content.categories.map { it.categoryId }.toSet(),
        )
        return try {
            repository.insert(initial)
        } catch (_: DuplicateKeyException) {
            checkNotNull(repository.find(game)) { "目录初始化冲突后无法读取" }
        }
    }
}
