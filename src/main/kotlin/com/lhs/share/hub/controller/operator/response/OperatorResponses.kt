package com.lhs.share.hub.controller.operator.response

import com.lhs.share.hub.controller.inventory.request.ProducerDto
import com.lhs.share.hub.repository.entity.OperatorAccount
import com.lhs.share.hub.repository.entity.OperatorCatalogEntity
import com.lhs.share.hub.repository.entity.OperatorCurrent
import com.lhs.share.hub.repository.entity.OperatorDisc
import com.lhs.share.hub.repository.entity.OperatorDiscCatalog
import com.lhs.share.hub.repository.entity.OperatorRecordEntry
import com.lhs.share.hub.repository.entity.OperatorStarStone
import java.time.Instant

data class OperatorAccountResponse(val id: String, val name: String, val createdAt: Instant, val updatedAt: Instant) {
    companion object { fun of(a: OperatorAccount) = OperatorAccountResponse(a.accountId, a.name, a.createdAt, a.updatedAt) }
}

data class OperatorCurrentResponse(
    val userId: String,
    val accountId: String,
    val game: String,
    val fullBaselineAt: Instant?,
    val entries: Map<String, OperatorCurrentEntryDto>,
    val updatedAt: Instant,
) {
    companion object { fun of(c: OperatorCurrent) = OperatorCurrentResponse(c.userId, c.accountId, c.game, c.fullBaselineAt, c.entries.mapValues { (_, e) -> OperatorCurrentEntryDto(e.elite, e.starLevel, e.level, e.discs, e.starStones, e.listedBaselineAt) }, c.updatedAt) }
}

data class OperatorCurrentEntryDto(val elite: Int, val starLevel: Int, val level: Int, val discs: List<OperatorDisc>, val starStones: List<OperatorStarStone>, val listedBaselineAt: Instant?)
data class OperatorImportResult(val accepted: Int, val duplicates: Int, val superseded: Int, val warnings: List<String> = emptyList())
data class OperatorExportResponse(
    val format: String = "myshare-operator-exchange",
    val version: Int = 2,
    val exportedAt: Instant,
    val catalogVersion: String,
    val producer: ProducerDto,
    val accounts: List<OperatorExportAccountDto>,
    val records: List<OperatorExportRecordDto>,
)
data class OperatorExportAccountDto(val id: String, val name: String)
data class OperatorExportRecordDto(val accountId: String, val recordId: String, val recordType: String = "operator_snapshot", val game: String?, val effectiveAt: Instant, val snapshotScope: String = "full", val entries: List<OperatorRecordEntry>)
data class OperatorRecordPageResponse(val items: List<OperatorRecordListItemDto>, val nextCursor: String?)
data class OperatorRecordListItemDto(val accountId: String, val recordId: String, val recordType: String, val game: String?, val snapshotScope: String, val effectiveAt: Instant, val receivedAt: Instant, val snapshotEffect: String, val entries: List<OperatorRecordEntry>)
/**
 * 公共图鉴（GET /v1/operator/catalog）的单条密探：只回答"有哪些密探、长什么样"。
 * 不含 starStones —— 星石槽位是用户养成档案字段（见 current/records），
 * 且目录里每个密探都是同一份 "主星石/辅星石" 模板，放进公共目录只会误导前端当真实养成数据展示。
 */
data class OperatorCatalogEntryResponse(
    val id: String,
    val name: String,
    val alias: String?,
    val rarity: Int,
    val prof: List<String>,
    val subProf: List<String>,
    val games: List<String>,
    val discs: List<OperatorDiscCatalog>,
) {
    companion object { fun of(e: OperatorCatalogEntity) = OperatorCatalogEntryResponse(e.operatorId, e.name, e.alias, e.rarity, e.prof, e.subProf, e.games, e.discs) }
}
data class OperatorCatalogResponse(val format: String = "myshare-operator-catalog", val version: Int = 1, val catalogVersion: String, val operators: List<OperatorCatalogEntryResponse>)
data class OperatorErrorResponse(val error: OperatorError)
data class OperatorError(val code: String, val message: String, val recordId: String? = null, val entryId: String? = null)
