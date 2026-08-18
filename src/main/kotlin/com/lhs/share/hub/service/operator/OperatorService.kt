package com.lhs.share.hub.service.operator

import com.lhs.share.hub.controller.inventory.request.ProducerDto
import com.lhs.share.hub.controller.operator.request.OperatorEntryRequest
import com.lhs.share.hub.controller.operator.request.OperatorImportRequest
import com.lhs.share.hub.controller.operator.request.OperatorRecordRequest
import com.lhs.share.hub.controller.operator.response.*
import com.lhs.share.hub.repository.OperatorAccountRepository
import com.lhs.share.hub.repository.OperatorCatalogRepository
import com.lhs.share.hub.repository.OperatorCurrentRepository
import com.lhs.share.hub.repository.OperatorRecordRepository
import com.lhs.share.hub.repository.entity.*
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

@Service
class OperatorService(
    private val accountRepository: OperatorAccountRepository,
    private val currentRepository: OperatorCurrentRepository,
    private val recordRepository: OperatorRecordRepository,
    private val catalogRepository: OperatorCatalogRepository,
    private val catalogService: OperatorCatalogService,
    private val transactionTemplate: TransactionTemplate,
) {
    fun import(userId: String, request: OperatorImportRequest): OperatorImportResult = importInternal(userId, null, request)
    fun import(userId: String, accountId: String, request: OperatorImportRequest): OperatorImportResult = importInternal(userId, accountId, request)

    private fun importInternal(userId: String, restrictedAccountId: String?, request: OperatorImportRequest): OperatorImportResult {
        if (request.format != FORMAT) throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "format must be " + FORMAT)
        if (request.version != VERSION) throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "unsupported_version", "Unsupported operator exchange version: " + request.version)
        parseTime(request.exportedAt, "exported_at")
        validateProducer(request.producer)
        val warnings = mutableListOf<String>()
        val ordered = validateAndSort(userId, restrictedAccountId, request, warnings)
        val seen = mutableMapOf<Pair<String, String>, OperatorRecordRequest>()
        val prepared = ordered.map { item ->
            val key = item.record.accountId to item.record.recordId
            val prior = seen.putIfAbsent(key, item.record)
            if (prior != null) {
                if (prior != item.record) throw conflict(item.record.recordId)
                return@map null
            }
            val existing = recordRepository.findByUserIdAndAccountIdAndRecordId(userId, item.record.accountId, item.record.recordId)
            if (existing != null) {
                if (!sameBody(existing, item.record)) throw conflict(item.record.recordId)
                null
            } else item
        }.filterNotNull()
        val duplicates = ordered.size - prepared.size
        var accepted = 0
        var superseded = 0
        transactionTemplate.executeWithoutResult {
            prepared.forEach { item ->
                val effect = applyRecord(userId, item)
                recordRepository.save(item.toEntity(userId, request.producer, effect))
                accepted++
                if (effect == SUPERSEDED) superseded++
            }
        }
        return OperatorImportResult(accepted, duplicates, superseded, warnings)
    }

    private fun validateAndSort(userId: String, restricted: String?, request: OperatorImportRequest, warnings: MutableList<String>): List<Validated> {
        val ids = request.accounts.orEmpty().map { it.id }
        if (ids.any { !ACCOUNT_ID.matches(it) } || ids.toSet().size != ids.size) throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "accounts are invalid")
        val referenced = request.records.map { it.accountId }.toSet()
        if (referenced.any { !ACCOUNT_ID.matches(it) }) throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "record account_id is invalid")
        if (restricted != null && referenced != setOf(restricted)) throw apiError(HttpStatus.FORBIDDEN, "account_scope_mismatch", "Document contains records outside the API token account")
        val owned = accountRepository.findAllByUserIdAndAccountIdIn(userId, referenced).map { it.accountId }.toSet()
        referenced.firstOrNull { it !in owned }?.let { throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "unknown_account_id", "Unknown account_id: " + it) }
        return request.records.map { record ->
            if (record.recordId.isBlank() || record.recordId.length > 128) throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "record_id length must be 1..128", record.recordId)
            if (record.recordType != RECORD_TYPE || record.snapshotScope !in SCOPES) throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "Invalid operator record enum", record.recordId)
            if (record.snapshotScope == LISTED && record.entries.isEmpty()) throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "listed snapshots require at least one entry", record.recordId)
            if (record.game != null && record.game !in GAMES) throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_game", "Unsupported game", record.recordId)
            val effective = parseTime(record.effectiveAt, "effective_at")
            val entryIds = mutableSetOf<String>()
            record.entries.forEach { entry ->
                if (!entryIds.add(entry.id)) throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "Duplicate entry id", record.recordId, entry.id)
                val catalog = catalogService.getOperator(entry.id) ?: throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "unknown_operator_id", "Unknown operator id: " + entry.id, record.recordId, entry.id)
                if (listOf(entry.elite, entry.starLevel, entry.level).any { it < 0 }) throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "Operator levels must be non-negative", record.recordId, entry.id)
                if (record.game != null && record.game !in catalog.games) throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_game", "Operator is not available in game", record.recordId, entry.id)
                if (entry.rarity != null && entry.rarity != catalog.rarity) warnings.add(entry.id + ": rarity conflicts with catalog")
                if (entry.prof != null && entry.prof != catalog.prof) warnings.add(entry.id + ": prof conflicts with catalog")
                if (entry.subProf != null && entry.subProf != catalog.subProf) warnings.add(entry.id + ": subProf conflicts with catalog")
                val discNames = entry.discs.map { it.otName }
                if (discNames.toSet().size != discNames.size || discNames.any { name -> catalog.discs.none { it.otName == name } }) throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_disc", "Invalid disc", record.recordId, entry.id)
                val stoneTypes = entry.starStones.map { it.type }
                if (stoneTypes.any { it !in STONE_TYPES } || stoneTypes.toSet().size != stoneTypes.size || entry.starStones.any { it.level < 0 }) throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_star_stone", "Invalid star stone", record.recordId, entry.id)
            }
            Validated(record, effective)
        }.sortedWith(compareBy<Validated> { it.record.accountId }.thenBy { it.effectiveAt })
    }

    private fun applyRecord(userId: String, item: Validated): String {
        val record = item.record
        val game = record.game ?: GENERIC_GAME
        val current = currentRepository.findByUserIdAndAccountIdAndGame(userId, record.accountId, game)
        if (record.snapshotScope == FULL && current?.fullBaselineAt?.isAfter(item.effectiveAt) == true) return SUPERSEDED
        if (record.snapshotScope == LISTED && record.entries.all { entry -> current?.entries?.get(entry.id)?.listedBaselineAt?.isAfter(item.effectiveAt) == true }) return SUPERSEDED
        val now = Instant.now()
        if (record.snapshotScope == FULL) {
            val next = record.entries.associate { it.id to it.toCurrent(null) }.toMutableMap()
            current?.entries?.forEach { (id, value) -> if (value.listedBaselineAt?.isAfter(item.effectiveAt) == true) next[id] = value }
            currentRepository.save(OperatorCurrent(current?.id ?: key(userId, record.accountId, game), userId, record.accountId, game, item.effectiveAt, next, now))
        } else {
            val next = (current?.entries ?: emptyMap()).toMutableMap()
            record.entries.forEach { next[it.id] = it.toCurrent(item.effectiveAt) }
            currentRepository.save(OperatorCurrent(current?.id ?: key(userId, record.accountId, game), userId, record.accountId, game, current?.fullBaselineAt, next, now))
        }
        return APPLIED
    }

    fun current(userId: String, accountId: String, game: String?): List<OperatorCurrentResponse> {
        val all = currentRepository.findByUserIdAndAccountIdOrderByUpdatedAtDesc(userId, accountId)
        if (game == null) return all.map(OperatorCurrentResponse::of)
        val specific = all.firstOrNull { it.game == game }
        val generic = all.firstOrNull { it.game == GENERIC_GAME }
        if (specific == null) return listOfNotNull(generic).map(OperatorCurrentResponse::of)
        if (generic == null) return listOf(OperatorCurrentResponse.of(specific))
        return listOf(OperatorCurrentResponse.of(specific.copy(entries = generic.entries + specific.entries)))
    }

    fun listRecords(userId: String, accountId: String, game: String?, from: Instant?, to: Instant?, cursor: String?, limit: Int): OperatorRecordPageResponse {
        if (limit !in 1..100) throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "limit must be 1..100")
        var records = recordRepository.findByUserIdAndAccountIdOrderByEffectiveAtDesc(userId, accountId).filter { (game == null || it.game == game) && (from == null || !it.effectiveAt.isBefore(from)) && (to == null || it.effectiveAt.isBefore(to)) }
        if (cursor != null) {
            val split = String(Base64.getUrlDecoder().decode(cursor)).split("|", limit = 2)
            val at = Instant.parse(split[0])
            records = records.dropWhile { !it.effectiveAt.isBefore(at) || (it.effectiveAt == at && it.recordId >= split[1]) }
        }
        val page = records.take(limit)
        val next = if (records.size > limit) Base64.getUrlEncoder().withoutPadding().encodeToString((page.last().effectiveAt.toString() + "|" + page.last().recordId).toByteArray()) else null
        return OperatorRecordPageResponse(page.map { OperatorRecordListItemDto(it.accountId, it.recordId, it.recordType, it.game, it.snapshotScope, it.effectiveAt, it.receivedAt, it.snapshotEffect, it.entries) }, next)
    }

    fun deleteRecord(userId: String, accountId: String, recordId: String) {
        val target = recordRepository.findByUserIdAndAccountIdAndRecordId(userId, accountId, recordId) ?: throw apiError(HttpStatus.NOT_FOUND, "record_not_found", "Record not found")
        transactionTemplate.executeWithoutResult {
            recordRepository.delete(target)
            val game = target.game ?: GENERIC_GAME
            currentRepository.deleteByUserIdAndAccountIdAndGame(userId, accountId, game)
            recordRepository.findByUserIdAndAccountIdAndGameOrderByEffectiveAtAsc(userId, accountId, target.game).forEach { record ->
                val request = record.toRequest()
                val validated = Validated(request, record.effectiveAt)
                val effect = applyRecord(userId, validated)
                if (record.snapshotEffect != effect) recordRepository.save(record.copy(snapshotEffect = effect))
            }
        }
    }

    fun export(userId: String, accountId: String?, scope: String?): OperatorExportResponse {
        if ((accountId == null && scope != "all") || (accountId != null && scope != null)) throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "Specify account_id or scope=all")
        val accounts = if (accountId != null) listOf(accountRepository.findByUserIdAndAccountId(userId, accountId) ?: throw apiError(HttpStatus.NOT_FOUND, "account_not_found", "Account not found")) else accountRepository.findAllByUserIdOrderByCreatedAtAsc(userId)
        val exportId = UUID.randomUUID().toString().replace("-", "")
        val records = accounts.flatMap { account -> currentRepository.findByUserIdAndAccountIdOrderByUpdatedAtDesc(userId, account.accountId).map { current -> OperatorExportRecordDto(account.accountId, "myshare:export:" + exportId + ":" + account.accountId + ":" + if (current.game == GENERIC_GAME) "generic" else current.game, game = current.game.takeUnless { it == GENERIC_GAME }, effectiveAt = current.fullBaselineAt ?: current.updatedAt, entries = current.entries.map { (id, e) -> OperatorRecordEntry(id = id, elite = e.elite, starLevel = e.starLevel, level = e.level, discs = e.discs, starStones = e.starStones) }) } }
        return OperatorExportResponse(exportedAt = Instant.now(), catalogVersion = catalogService.currentCatalogVersion(), producer = ProducerDto("myshare", "5"), accounts = accounts.map { OperatorExportAccountDto(it.accountId, it.name) }, records = records)
    }

    private fun sameBody(existing: OperatorRecord, request: OperatorRecordRequest): Boolean = existing.recordType == request.recordType && existing.game == request.game && existing.snapshotScope == request.snapshotScope && existing.effectiveAt == parseTime(request.effectiveAt, "effective_at") && existing.entries == request.entries.map { it.toEntity() }
    private fun Validated.toEntity(userId: String, producer: ProducerDto, effect: String) = OperatorRecord(recordId = record.recordId, userId = userId, accountId = record.accountId, recordType = record.recordType, game = record.game, snapshotScope = record.snapshotScope, effectiveAt = effectiveAt, producer = ProducerInfo(producer.platform, producer.version), entries = record.entries.map { it.toEntity() }, snapshotEffect = effect)
    private fun OperatorEntryRequest.toEntity() = OperatorRecordEntry(id, name, alias, rarity, prof, subProf, games, elite, starLevel, level, discs.map { OperatorDisc(it.otName, it.abbreviation, it.color, it.desp) }, starStones.map { OperatorStarStone(it.name, it.type, it.level) })
    private fun OperatorEntryRequest.toCurrent(listedAt: Instant?) = OperatorEntry(elite, starLevel, level, discs.map { OperatorDisc(it.otName, it.abbreviation, it.color, it.desp) }, starStones.map { OperatorStarStone(it.name, it.type, it.level) }, listedAt)
    private fun OperatorRecord.toRequest() = OperatorRecordRequest(accountId, recordId, recordType, game, effectiveAt.toString(), snapshotScope, entries.map { OperatorEntryRequest(it.id, it.name, it.alias, it.rarity, it.prof, it.subProf, it.games, it.elite, it.starLevel, it.level, it.discs.map { d -> com.lhs.share.hub.controller.operator.request.OperatorDiscRequest(d.otName, d.abbreviation, d.color, d.desp) }, it.starStones.map { s -> com.lhs.share.hub.controller.operator.request.OperatorStarStoneRequest(s.name, s.type, s.level) }) })
    private fun parseTime(value: String, field: String) = try { OffsetDateTime.parse(value).toInstant() } catch (_: Exception) { throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "Invalid " + field) }
    private fun validateProducer(p: ProducerDto) { if (!p.platform.matches(Regex("^[a-z0-9][a-z0-9._-]{0,63}$")) || p.version == "") throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "Invalid producer") }
    private fun key(user: String, account: String, game: String) = user + ":" + account + ":" + game
    private fun apiError(status: HttpStatus, code: String, message: String, record: String? = null, entry: String? = null) = OperatorApiException(status, code, message, record, entry)
    private fun conflict(record: String) = apiError(HttpStatus.CONFLICT, "record_conflict", "Record conflicts with existing record", record)
    private data class Validated(val record: OperatorRecordRequest, val effectiveAt: Instant)

    companion object {
        const val FORMAT = "myshare-operator-exchange"
        const val VERSION = 2
        const val RECORD_TYPE = "operator_snapshot"
        const val FULL = "full"
        const val LISTED = "listed"
        const val APPLIED = "applied"
        const val SUPERSEDED = "superseded"
        const val GENERIC_GAME = "*"
        val SCOPES = setOf(FULL, LISTED)
        val GAMES = setOf("如鸢", "代号鸢")
        /**
         * 星石槽位类型：
         * - main / assist：旧协议兼容用（等价 main1 / assist1）
         * - main1..3：3 个主星石槽位
         * - assist1..3：3 个辅星石槽位
         */
        val STONE_TYPES = setOf(
            "main",
            "assist",
            "main1",
            "main2",
            "main3",
            "assist1",
            "assist2",
            "assist3",
        )
        val ACCOUNT_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    }
}
