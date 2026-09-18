package com.lhs.share.hub.work.service

import com.lhs.share.hub.repository.entity.level.LevelCatalogEntity
import com.lhs.share.hub.repository.level.LevelCatalogRepository
import com.lhs.share.hub.work.adapter.MaaYuanWorkAdapter
import com.lhs.share.hub.work.adapter.YuanAssistWorkAdapter
import com.lhs.share.hub.work.model.CompatibilityIssue
import com.lhs.share.hub.work.model.CompatibilityStatus
import com.lhs.share.hub.work.model.MaaYuanExtension
import com.lhs.share.hub.work.model.WorkCompatibilityResponse
import com.lhs.share.hub.work.model.WorkConversion
import com.lhs.share.hub.work.model.WorkDetailResponse
import com.lhs.share.hub.work.model.WorkDoc
import com.lhs.share.hub.work.model.WorkDocument
import com.lhs.share.hub.work.model.WorkExec
import com.lhs.share.hub.work.model.WorkExtensions
import com.lhs.share.hub.work.model.WorkLevelSummary
import com.lhs.share.hub.work.model.WorkListItem
import com.lhs.share.hub.work.model.WorkMetadata
import com.lhs.share.hub.work.model.WorkPageResponse
import com.lhs.share.hub.work.model.WorkSource
import com.lhs.share.hub.work.model.WorkTarget
import com.lhs.share.hub.work.model.statusOf
import com.lhs.share.hub.work.parser.LegacyParseResult
import com.lhs.share.hub.work.parser.LegacyWorkDraft
import com.lhs.share.hub.work.parser.MaaYuanLegacyParser
import com.lhs.share.hub.work.repository.MaaCopilotWorkSource
import com.lhs.share.hub.work.repository.MaaCopilotWorkSourceRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

class WorkApiException(val status: HttpStatus, override val message: String) : RuntimeException(message)

@Service
class WorkService(
    private val sourceRepository: MaaCopilotWorkSourceRepository,
    private val levelRepository: LevelCatalogRepository,
    private val parser: MaaYuanLegacyParser,
    private val maaYuanAdapter: MaaYuanWorkAdapter,
    private val yuanAssistAdapter: YuanAssistWorkAdapter,
) {
    fun list(page: Int, limit: Int): WorkPageResponse {
        if (page < 1) throw WorkApiException(HttpStatus.BAD_REQUEST, "page 必须大于等于 1")
        if (limit !in 1..100) throw WorkApiException(HttpStatus.BAD_REQUEST, "limit 必须在 1..100")
        val sourcePage = sourceRepository.findPublic(page, limit)
        val levels = levelRepository.findAllByOrderBySortOrderAscLevelKeyAsc()
        val items = sourcePage.items.mapNotNull { source ->
            val id = source.copilotId ?: return@mapNotNull null
            val resolved = resolve(source, levels)
            WorkListItem(
                id = id,
                title = resolved.parse.draft?.title ?: source.name ?: source.stageName ?: "作业 $id",
                stageName = resolved.work?.stageName ?: resolved.parse.draft?.stageName,
                game = resolved.work?.game ?: gameFrom(source, resolved.parse.draft),
                level = resolved.level?.summary(),
                uploaderId = source.uploaderId,
                uploadTime = source.uploadTime ?: source.firstUploadTime,
                views = source.views,
                hotScore = source.hotScore,
                conversionStatus = statusOf(resolved.issues),
                issueCount = resolved.issues.size,
            )
        }
        return WorkPageResponse(page, limit, sourcePage.total, page.toLong() * limit < sourcePage.total, items)
    }

    fun get(id: Long): WorkDetailResponse {
        val source = sourceRepository.findPublicById(id) ?: throw WorkApiException(HttpStatus.NOT_FOUND, "作业不存在")
        return detail(source, levelRepository.findAllByOrderBySortOrderAscLevelKeyAsc())
    }

    fun compatibility(id: Long, target: String): WorkCompatibilityResponse {
        val parsedTarget = try {
            WorkTarget.valueOf(target.uppercase())
        } catch (_: IllegalArgumentException) {
            throw WorkApiException(HttpStatus.BAD_REQUEST, "to 必须是 MAAYUAN 或 YUANASSIST")
        }
        val detail = get(id)
        return when (parsedTarget) {
            WorkTarget.MAAYUAN -> maaYuanAdapter.check(detail.work, detail.conversion.issues)
            WorkTarget.YUANASSIST -> yuanAssistAdapter.check(detail.work, detail.level, detail.conversion.issues)
        }
    }

    private fun detail(source: MaaCopilotWorkSource, levels: List<LevelCatalogEntity>): WorkDetailResponse {
        val id = source.copilotId ?: throw WorkApiException(HttpStatus.NOT_FOUND, "作业不存在")
        val resolved = resolve(source, levels)
        return WorkDetailResponse(
            metadata = WorkMetadata(
                id = id,
                title = resolved.parse.draft?.title ?: source.name ?: source.stageName ?: "作业 $id",
                uploaderId = source.uploaderId,
                uploadTime = source.uploadTime ?: source.firstUploadTime,
                views = source.views,
                hotScore = source.hotScore,
                likeCount = source.likeCount,
            ),
            level = resolved.level?.summary(),
            conversion = WorkConversion(statusOf(resolved.issues), resolved.issues),
            work = resolved.work,
            source = WorkSource(id = id, rawContent = source.content.orEmpty()),
        )
    }

    private fun resolve(source: MaaCopilotWorkSource, levels: List<LevelCatalogEntity>): ResolvedWork {
        val parse = parser.parse(source.content.orEmpty())
        val draft = parse.draft
        val knownGame = gameFrom(source, draft)
        val level = draft?.let { findLevel(it, source, knownGame, levels) }
        val game = knownGame ?: level?.game?.takeIf(::isProtocolGame)
        val issues = parse.issues.toMutableList()
        if (draft != null && game == null) {
            val taggedGames = source.tags.filter(::isProtocolGame).distinct()
            issues += CompatibilityIssue(
                if (taggedGames.size > 1) "ambiguous_game" else "missing_game",
                "$.game",
                "game",
                if (taggedGames.size > 1) "公开标签同时包含多个游戏，无法可靠确定游戏" else "无法从 content、公开标签或唯一 Level Catalog 关联确定游戏",
                CompatibilityStatus.UNSUPPORTED,
            )
        }
        val work = draft?.takeIf {
            game != null &&
                it.stageName != null &&
                it.title != null &&
                it.details.length <= 20_000 &&
                it.rounds.isNotEmpty()
        }?.let {
            WorkDocument(
                game = game!!,
                levelId = level?.levelKey,
                stageName = it.stageName!!,
                doc = WorkDoc(it.title!!, it.details),
                operators = it.operators,
                exec = enrichExec(it.exec, level),
                rounds = it.rounds,
            )
        }
        return ResolvedWork(parse, level, work, issues)
    }

    private fun gameFrom(source: MaaCopilotWorkSource, draft: LegacyWorkDraft?): String? =
        draft?.game ?: source.tags.filter(::isProtocolGame).distinct().singleOrNull()

    private fun isProtocolGame(value: String) = value == "代号鸢" || value == "如鸢"

    private fun findLevel(
        draft: LegacyWorkDraft,
        source: MaaCopilotWorkSource,
        game: String?,
        levels: List<LevelCatalogEntity>,
    ): LevelCatalogEntity? {
        val scoped = levels.filter { game == null || it.game == game }
        val strongHints = listOfNotNull(
            draft.levelIdHint,
            source.levelMeta?.levelId,
            draft.stageIdHint,
            source.levelMeta?.stageId,
            source.stageId,
        ).map(String::trim).filter(String::isNotEmpty).distinct()
        strongHints.forEach { hint ->
            val matches = scoped.filter { it.levelKey == hint || it.levelId == hint || it.stageId == hint }
            if (matches.size == 1) return matches.single()
            if (matches.size > 1) return null
        }
        val names = listOfNotNull(draft.levelNameHint, source.levelMeta?.name, source.name, source.stageName)
            .map(String::trim).filter(String::isNotEmpty).distinct()
        names.forEach { name ->
            val matches = scoped.filter { it.name == name || it.stageId == name || it.levelId == name }
            if (matches.size == 1) return matches.single()
            if (matches.size > 1) return null
        }
        return null
    }

    private fun enrichExec(exec: WorkExec?, level: LevelCatalogEntity?): WorkExec? {
        if (level == null) return exec
        val existing = exec?.extensions?.maayuan
        val catalogType = level.catOne.takeIf { it in MAA_LEVEL_TYPES }
        val enriched = MaaYuanExtension(
            levelType = existing?.levelType ?: catalogType,
            recognitionName = existing?.recognitionName ?: when (catalogType) {
                "兰台" -> level.catThree.takeIf(String::isNotBlank)
                "活动", "活动有分级", "其他" -> level.catTwo.takeIf(String::isNotBlank) ?: level.name
                else -> null
            },
            recTargetOffset = existing?.recTargetOffset,
            difficulty = existing?.difficulty,
            caveType = existing?.caveType ?: level.catThree.takeIf { catalogType == "洞窟" && it in setOf("左", "右") },
            lantaiNav = existing?.lantaiNav,
        ).takeUnless { it == MaaYuanExtension() }
        val extensions = WorkExtensions(enriched, exec?.extensions?.yuanassist)
        return WorkExec(exec?.delaysMs, extensions)
    }

    private fun LevelCatalogEntity.summary() = WorkLevelSummary(levelKey, game, name, levelId, stageId)

    private data class ResolvedWork(
        val parse: LegacyParseResult,
        val level: LevelCatalogEntity?,
        val work: WorkDocument?,
        val issues: List<CompatibilityIssue>,
    )

    private companion object {
        val MAA_LEVEL_TYPES = setOf("主线", "洞窟", "活动", "活动有分级", "白鹄", "兰台", "其他")
    }
}
