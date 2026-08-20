package com.lhs.share.hub.service.operator

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lhs.share.hub.controller.inventory.request.ProducerDto
import com.lhs.share.hub.controller.operator.request.OperatorDiscRequest
import com.lhs.share.hub.controller.operator.request.OperatorEntryRequest
import com.lhs.share.hub.controller.operator.request.OperatorImportRequest
import com.lhs.share.hub.controller.operator.request.OperatorRecordRequest
import com.lhs.share.hub.controller.operator.request.OperatorStarStoneRequest
import com.lhs.share.hub.controller.operator.response.OperatorCurrentEntryDto
import com.lhs.share.hub.controller.operator.response.OperatorCurrentResponse
import com.lhs.share.hub.controller.operator.response.OperatorExportAccountDto
import com.lhs.share.hub.controller.operator.response.OperatorExportRecordDto
import com.lhs.share.hub.controller.operator.response.OperatorExportResponse
import com.lhs.share.hub.controller.operator.response.OperatorImportResult
import com.lhs.share.hub.controller.operator.response.OperatorOddityRules
import com.lhs.share.hub.controller.operator.response.OperatorRecordListItemDto
import com.lhs.share.hub.controller.operator.response.OperatorRecordPageResponse
import com.lhs.share.hub.repository.OperatorCatalogRepository
import com.lhs.share.hub.repository.OperatorCorrectionRecordRepository
import com.lhs.share.hub.repository.OperatorCurrentRepository
import com.lhs.share.hub.repository.OperatorRecordRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.OperatorCatalogEntity
import com.lhs.share.hub.repository.entity.OperatorCombatStats
import com.lhs.share.hub.repository.entity.OperatorCorrectionRecord
import com.lhs.share.hub.repository.entity.OperatorCurrent
import com.lhs.share.hub.repository.entity.OperatorDisc
import com.lhs.share.hub.repository.entity.OperatorDiscLoadout
import com.lhs.share.hub.repository.entity.OperatorEntry
import com.lhs.share.hub.repository.entity.OperatorObservedInputs
import com.lhs.share.hub.repository.entity.OperatorOddityValue
import com.lhs.share.hub.repository.entity.OperatorRecord
import com.lhs.share.hub.repository.entity.OperatorRecordEntry
import com.lhs.share.hub.repository.entity.OperatorStarStone
import com.lhs.share.hub.repository.entity.ProducerInfo
import com.lhs.share.hub.repository.entity.normalized
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

@Service
class OperatorService(
    private val accountRepository: SubAccountRepository,
    private val currentRepository: OperatorCurrentRepository,
    private val recordRepository: OperatorRecordRepository,
    @Suppress("unused") private val catalogRepository: OperatorCatalogRepository,
    private val catalogService: OperatorCatalogService,
    private val transactionTemplate: TransactionTemplate,
    private val correctionRepository: OperatorCorrectionRecordRepository,
) {
    fun import(userId: String, request: OperatorImportRequest): OperatorImportResult = importInternal(userId, null, request)

    fun import(userId: String, accountId: String, request: OperatorImportRequest): OperatorImportResult =
        importInternal(userId, accountId, request)

    private fun importInternal(userId: String, restrictedAccountId: String?, request: OperatorImportRequest): OperatorImportResult {
        if (request.format != FORMAT) {
            throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "format must be $FORMAT")
        }
        if (request.version != VERSION) {
            throw apiError(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "unsupported_version",
                "Unsupported operator exchange version: ${request.version}",
            )
        }
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
            val existing = recordRepository.findByUserIdAndAccountIdAndRecordId(
                userId,
                item.record.accountId,
                item.record.recordId,
            )
            if (existing != null) {
                if (!sameBody(existing, item.record)) throw conflict(item.record.recordId)
                null
            } else {
                item
            }
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

    private fun validateAndSort(
        userId: String,
        restricted: String?,
        request: OperatorImportRequest,
        warnings: MutableList<String>,
    ): List<Validated> {
        val ids = request.accounts.orEmpty().map { it.id }
        if (ids.any { !ACCOUNT_ID.matches(it) } || ids.toSet().size != ids.size) {
            throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "accounts are invalid")
        }
        val referenced = request.records.map { it.accountId }.toSet()
        if (referenced.any { !ACCOUNT_ID.matches(it) }) {
            throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "record account_id is invalid")
        }
        if (restricted != null && referenced != setOf(restricted)) {
            throw apiError(
                HttpStatus.FORBIDDEN,
                "account_scope_mismatch",
                "Document contains records outside the API token account",
            )
        }
        val owned = accountRepository.findAllByUserIdAndAccountIdIn(userId, referenced).associateBy { it.accountId }
        referenced.firstOrNull { it !in owned }?.let {
            throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "unknown_account_id", "Unknown account_id: $it")
        }
        return request.records.map { record ->
            validateRecord(record, checkNotNull(owned[record.accountId]).game, warnings)
        }.sortedWith(compareBy<Validated> { it.record.accountId }.thenBy { it.effectiveAt })
    }

    private fun validateRecord(record: OperatorRecordRequest, accountGame: String, warnings: MutableList<String>): Validated {
        if (record.recordId.isBlank() || record.recordId.length > 128) {
            throw apiError(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "schema_validation_failed",
                "record_id length must be 1..128",
                record.recordId,
            )
        }
        if (record.recordType != RECORD_TYPE || record.snapshotScope !in SCOPES) {
            throw apiError(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "schema_validation_failed",
                "Invalid operator record enum",
                record.recordId,
            )
        }
        if (record.snapshotScope == LISTED && record.entries.isEmpty()) {
            throw apiError(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "schema_validation_failed",
                "listed snapshots require at least one entry",
                record.recordId,
            )
        }
        if (record.game != null && record.game !in GAMES) {
            throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_game", "Unsupported game", record.recordId)
        }
        if (record.game != accountGame) {
            throw apiError(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "account_game_mismatch",
                "Record game must match the account game",
                record.recordId,
            )
        }
        val effective = parseTime(record.effectiveAt, "effective_at")
        val entryIds = mutableSetOf<String>()
        record.entries.forEach { entry -> validateV2Entry(record, entry, entryIds, warnings) }
        return Validated(record, effective)
    }

    private fun validateV2Entry(
        record: OperatorRecordRequest,
        entry: OperatorEntryRequest,
        entryIds: MutableSet<String>,
        warnings: MutableList<String>,
    ) {
        if (!entryIds.add(entry.id)) {
            throw apiError(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "schema_validation_failed",
                "Duplicate entry id",
                record.recordId,
                entry.id,
            )
        }
        val catalog = catalogService.getOperator(entry.id)
            ?: throw apiError(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "unknown_operator_id",
                "Unknown operator id: ${entry.id}",
                record.recordId,
                entry.id,
            )
        if (listOf(entry.elite, entry.starLevel, entry.level).any { it < 0 }) {
            throw apiError(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "schema_validation_failed",
                "Operator levels must be non-negative",
                record.recordId,
                entry.id,
            )
        }
        validateStarLevel(entry.starLevel, catalog, record.recordId, entry.id)
        if (record.game !in catalog.games) {
            throw apiError(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "invalid_game",
                "Operator is not available in game",
                record.recordId,
                entry.id,
            )
        }
        if (entry.rarity != null && entry.rarity != catalog.rarity) warnings.add("${entry.id}: rarity conflicts with catalog")
        if (entry.prof != null && entry.prof != catalog.prof) warnings.add("${entry.id}: prof conflicts with catalog")
        if (entry.subProf != null && entry.subProf != catalog.subProf) warnings.add("${entry.id}: subProf conflicts with catalog")
        val discNames = entry.discs.map { it.otName }
        if (discNames.toSet().size != discNames.size || discNames.any { name -> catalog.discs.none { it.otName == name } }) {
            throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_disc", "Invalid disc", record.recordId, entry.id)
        }
        val stoneTypes = entry.starStones.map { normalizeStoneType(it.type) }
        if (
            entry.starStones.any { it.type !in STONE_TYPES } ||
            stoneTypes.toSet().size != stoneTypes.size ||
            entry.starStones.any { it.level < 0 }
        ) {
            throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_star_stone", "Invalid star stone", record.recordId, entry.id)
        }
    }

    private fun validateStarLevel(
        starLevel: Int,
        catalog: OperatorCatalogEntity,
        recordId: String? = null,
        operatorId: String = catalog.operatorId,
    ) {
        val max = if (catalog.spOf == null) MAX_STAR_LEVEL else MAX_SP_STAR_LEVEL
        if (starLevel !in 0..max) {
            throw apiError(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "invalid_star_level",
                if (catalog.spOf == null) "star_level must be 0..31" else "SP star_level must be 0..5",
                recordId,
                operatorId,
                operatorId,
                "star_level",
            )
        }
    }

    private fun applyRecord(userId: String, item: Validated): String {
        val record = item.record
        val game = record.game ?: GENERIC_GAME
        val current = currentRepository.findByUserIdAndAccountIdAndGame(userId, record.accountId, game)
        if (record.snapshotScope == FULL && current?.fullBaselineAt?.isAfter(item.effectiveAt) == true) return SUPERSEDED
        if (
            record.snapshotScope == LISTED &&
            record.entries.all { entry -> current?.entries?.get(entry.id)?.listedBaselineAt?.isAfter(item.effectiveAt) == true }
        ) {
            return SUPERSEDED
        }
        val now = Instant.now()
        val next: MutableMap<String, OperatorEntry> = if (record.snapshotScope == FULL) {
            val mapped = record.entries.associate { request ->
                request.id to mergeV2Entry(current?.entries?.get(request.id), request, null, now)
            }.toMutableMap()
            current?.entries?.forEach { (id, value) ->
                if (value.listedBaselineAt?.isAfter(item.effectiveAt) == true) mapped[id] = value
            }
            mapped
        } else {
            val mapped = (current?.entries ?: emptyMap()).toMutableMap()
            record.entries.forEach { request ->
                mapped[request.id] = mergeV2Entry(mapped[request.id], request, item.effectiveAt, now)
            }
            mapped
        }
        normalizeSpRelations(next, record.entries.map { it.id }.toSet(), now)
        val fullBaselineAt = if (record.snapshotScope == FULL) item.effectiveAt else current?.fullBaselineAt
        currentRepository.save(
            OperatorCurrent(
                current?.id ?: key(userId, record.accountId, game),
                userId,
                record.accountId,
                game,
                fullBaselineAt,
                next,
                now,
            ),
        )
        return APPLIED
    }

    private fun mergeV2Entry(existingRaw: OperatorEntry?, request: OperatorEntryRequest, listedAt: Instant?, now: Instant): OperatorEntry {
        val existing = existingRaw?.normalized()
        val discs = request.discs.map { OperatorDisc(it.otName, it.abbreviation, it.color, it.desp) }
        val stones = request.starStones.map { OperatorStarStone(it.name, normalizeStoneType(it.type), it.level) }
        val loadouts = replaceFirstLoadout(existing?.discLoadouts.orEmpty(), discs)
        var next = OperatorEntry(
            elite = request.elite,
            starLevel = request.starLevel,
            level = request.level,
            discs = discs,
            starStones = stones,
            discLoadouts = loadouts,
            combatStats = existing?.combatStats,
            revision = (existing?.revision ?: -1L) + 1L,
            listedBaselineAt = listedAt,
            updatedAt = now,
        )
        if (existing != null && combatInputsChanged(existing, next)) next = next.markObservationStale()
        return next
    }

    private fun replaceFirstLoadout(existing: List<OperatorDiscLoadout>, discs: List<OperatorDisc>): List<OperatorDiscLoadout> {
        if (existing.isEmpty()) {
            return if (discs.isEmpty()) emptyList() else listOf(OperatorDiscLoadout("disc_1", "命盘一", discs))
        }
        return existing.toMutableList().also { it[0] = it[0].copy(discs = discs) }
    }

    private fun normalizeSpRelations(next: MutableMap<String, OperatorEntry>, writtenIds: Set<String>, now: Instant) {
        next.keys.toList().forEach { id ->
            val baseId = catalogService.getOperator(id)?.spOf
            if (baseId == null) {
                catalogService.spFormsOf(id).forEach { spId ->
                    if (spId !in next) {
                        val base = next.getValue(id)
                        next[spId] = OperatorEntry(base.elite, 0, base.level, updatedAt = now)
                    }
                }
            } else if (baseId !in next) {
                val sp = next.getValue(id)
                next[baseId] = OperatorEntry(sp.elite, 0, sp.level, updatedAt = now)
            }
        }
        next.keys.toList().forEach { id ->
            val baseId = catalogService.getOperator(id)?.spOf ?: return@forEach
            if (baseId !in next) return@forEach
            val base = next.getValue(baseId)
            val sp = next.getValue(id)
            when {
                id in writtenIds && baseId !in writtenIds -> next[baseId] = syncLevelElite(base, sp.level, sp.elite, now)
                baseId in writtenIds && id !in writtenIds -> next[id] = syncLevelElite(sp, base.level, base.elite, now)
            }
        }
    }

    private fun syncLevelElite(entry: OperatorEntry, level: Int, elite: Int, now: Instant): OperatorEntry {
        if (entry.level == level && entry.elite == elite) return entry
        return entry.copy(
            level = level,
            elite = elite,
            revision = entry.revision + 1,
            updatedAt = now,
        ).markObservationStale()
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

    fun patchCurrent(userId: String, accountId: String, game: String, operatorId: String, request: ObjectNode): OperatorCurrentEntryDto {
        rejectUnknown(
            request,
            setOf("level", "elite", "star_level", "disc_loadouts", "combat_stats", "expected_revision", "reason"),
            "schema_validation_failed",
            operatorId,
            "",
        )
        val expectedRevision = requiredLong(request, "expected_revision", operatorId)
        if (expectedRevision < 0) invalid("expected_revision must be non-negative", operatorId, "expected_revision")
        val reason = request.get("reason")?.takeIf { it.isTextual }?.asText()
            ?: invalid("reason is required", operatorId, "reason")
        if (reason !in CORRECTION_REASONS) invalid("Invalid correction reason", operatorId, "reason")
        val account = accountRepository.findByUserIdAndAccountId(userId, accountId)
            ?: throw apiError(HttpStatus.NOT_FOUND, "account_not_found", "Account not found")
        if (game !in GAMES) throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_game", "Unsupported game")
        if (account.game != game) {
            throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "account_game_mismatch", "game must match the account game")
        }
        val catalog = catalogService.getOperator(operatorId)
            ?: throw apiError(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "unknown_operator_id",
                "Unknown operator id: $operatorId",
                operator = operatorId,
            )
        if (game !in catalog.games) {
            throw apiError(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "invalid_game",
                "Operator is not available in game",
                operator = operatorId,
            )
        }
        val sourceCurrent = currentRepository.findByUserIdAndAccountIdAndGame(userId, accountId, game)
            ?: currentRepository.findByUserIdAndAccountIdAndGame(userId, accountId, GENERIC_GAME)
            ?: throw apiError(HttpStatus.NOT_FOUND, "operator_not_found", "Operator current entry not found")
        val existing = sourceCurrent.entries[operatorId]?.normalized()
            ?: throw apiError(HttpStatus.NOT_FOUND, "operator_not_found", "Operator current entry not found")
        if (existing.revision != expectedRevision) revisionConflict(operatorId)

        val now = Instant.now()
        val fields = mutableSetOf<String>()
        var merged = existing
        if (request.has("level")) {
            fields += "level"
            val level = requiredInt(request, "level", operatorId)
            if (level !in 0..100) invalid("level must be 0..100", operatorId, "level")
            merged = merged.copy(level = level)
        }
        if (request.has("elite")) {
            fields += "elite"
            val elite = requiredInt(request, "elite", operatorId)
            if (elite !in 0..17) invalid("elite must be 0..17", operatorId, "elite")
            merged = merged.copy(elite = elite)
        }
        if (request.has("star_level")) {
            fields += "star_level"
            val starLevel = requiredInt(request, "star_level", operatorId)
            validateStarLevel(starLevel, catalog)
            merged = merged.copy(starLevel = starLevel)
        }
        if (request.has("disc_loadouts")) {
            fields += "disc_loadouts"
            merged = merged.copy(discLoadouts = parseDiscLoadouts(request.get("disc_loadouts"), catalog), discs = emptyList())
            merged = merged.copy(discs = merged.discLoadouts.firstOrNull()?.discs.orEmpty())
        }
        val hasCombatPatch = request.has("combat_stats")
        if (hasCombatPatch) {
            fields += "combat_stats"
            val node = request.get("combat_stats")
            merged = if (node == null || node.isNull) {
                merged.copy(combatStats = null)
            } else {
                merged.copy(combatStats = mergeCombatStats(merged.combatStats, node, catalog, operatorId))
            }
        }
        if (fields.isEmpty()) invalid("At least one patch field is required", operatorId, "")
        if (combatInputsChanged(existing, merged) && !hasFreshObservation(request.get("combat_stats"))) {
            merged = merged.markObservationStale()
        }
        if (hasFreshObservation(request.get("combat_stats")) && merged.combatStats != null) {
            val observed = merged.combatStats.observedInputs
            merged = merged.copy(
                combatStats = merged.combatStats.copy(
                    observedInputs = (observed ?: OperatorObservedInputs()).copy(
                        level = observed?.level ?: merged.level,
                        elite = observed?.elite ?: merged.elite,
                        starLevel = observed?.starLevel ?: merged.starLevel,
                    ),
                ),
            )
        }
        merged = merged.copy(revision = expectedRevision + 1, updatedAt = now).normalized()

        val updates = mutableMapOf(operatorId to merged)
        if ("level" in fields || "elite" in fields) {
            val relatedIds = if (catalog.spOf != null) listOf(catalog.spOf) else catalogService.spFormsOf(operatorId)
            relatedIds.filterNotNull().forEach { relatedId ->
                sourceCurrent.entries[relatedId]?.normalized()?.let { related ->
                    val synced = syncLevelElite(related, merged.level, merged.elite, now)
                    if (synced != related) updates[relatedId] = synced
                }
            }
        }

        var saved: OperatorCurrent? = null
        transactionTemplate.executeWithoutResult {
            saved = currentRepository.compareAndSetEntries(
                userId,
                accountId,
                sourceCurrent.game,
                operatorId,
                expectedRevision,
                updates,
                now,
            ) ?: revisionConflict(operatorId)
            correctionRepository.save(
                OperatorCorrectionRecord(
                    userId = userId,
                    accountId = accountId,
                    game = sourceCurrent.game,
                    operatorId = operatorId,
                    reason = reason,
                    fields = fields,
                    level = merged.level.takeIf { "level" in fields },
                    elite = merged.elite.takeIf { "elite" in fields },
                    starLevel = merged.starLevel.takeIf { "star_level" in fields },
                    discLoadouts = merged.discLoadouts.takeIf { "disc_loadouts" in fields },
                    combatStats = merged.combatStats.takeIf { "combat_stats" in fields },
                    createdAt = now,
                ),
            )
        }
        return OperatorCurrentEntryDto.of(checkNotNull(saved).entries.getValue(operatorId))
    }

    private fun parseDiscLoadouts(node: JsonNode?, catalog: OperatorCatalogEntity): List<OperatorDiscLoadout> {
        if (node == null || node.isNull || !node.isArray) {
            invalid("disc_loadouts must be an array", catalog.operatorId, "disc_loadouts", "invalid_disc_loadout")
        }
        if (node.size() > 2) {
            invalid("disc_loadouts supports at most two loadouts", catalog.operatorId, "disc_loadouts", "invalid_disc_loadout")
        }
        val ids = mutableSetOf<String>()
        return node.mapIndexed { index, raw ->
            if (!raw.isObject) {
                invalid("loadout must be an object", catalog.operatorId, "disc_loadouts[$index]", "invalid_disc_loadout")
            }
            val item = raw as ObjectNode
            rejectUnknown(
                item,
                setOf("id", "name", "discs"),
                "invalid_disc_loadout",
                catalog.operatorId,
                "disc_loadouts[$index]",
            )
            val id = item.get("id")?.takeIf { it.isTextual }?.asText()?.trim().orEmpty()
            if (id.length !in 1..64 || !ids.add(id)) {
                invalid(
                    "loadout id must be unique with length 1..64",
                    catalog.operatorId,
                    "disc_loadouts[$index].id",
                    "invalid_disc_loadout",
                )
            }
            val submittedName = item.get("name")
            val name = if (submittedName == null || submittedName.isNull || submittedName.asText().isBlank()) {
                if (index == 0) "命盘一" else "命盘二"
            } else if (submittedName.isTextual) {
                submittedName.asText().trim()
            } else {
                invalid(
                    "loadout name must be a string",
                    catalog.operatorId,
                    "disc_loadouts[$index].name",
                    "invalid_disc_loadout",
                )
            }
            if (name.length !in 1..64) {
                invalid(
                    "loadout name length must be 1..64",
                    catalog.operatorId,
                    "disc_loadouts[$index].name",
                    "invalid_disc_loadout",
                )
            }
            val discsNode = item.get("discs")
            if (discsNode == null || discsNode.isNull || !discsNode.isArray || discsNode.size() > 3) {
                invalid(
                    "loadout discs must be an array with at most three items",
                    catalog.operatorId,
                    "disc_loadouts[$index].discs",
                    "invalid_disc_loadout",
                )
            }
            val names = mutableSetOf<String>()
            val discs = discsNode.mapIndexed { discIndex, rawDisc ->
                if (!rawDisc.isObject) {
                    invalid(
                        "disc must be an object",
                        catalog.operatorId,
                        "disc_loadouts[$index].discs[$discIndex]",
                        "invalid_disc_loadout",
                    )
                }
                val disc = rawDisc as ObjectNode
                rejectUnknown(
                    disc,
                    setOf("ot_name"),
                    "invalid_disc_loadout",
                    catalog.operatorId,
                    "disc_loadouts[$index].discs[$discIndex]",
                )
                val otName = disc.get("ot_name")?.takeIf { it.isTextual }?.asText()
                    ?: invalid(
                        "ot_name is required",
                        catalog.operatorId,
                        "disc_loadouts[$index].discs[$discIndex].ot_name",
                        "invalid_disc_loadout",
                    )
                val catalogDisc = catalog.discs.firstOrNull { it.otName == otName }
                    ?: invalid(
                        "disc is not in the operator catalog",
                        catalog.operatorId,
                        "disc_loadouts[$index].discs[$discIndex].ot_name",
                        "invalid_disc_loadout",
                    )
                if (!names.add(otName)) {
                    invalid(
                        "disc ot_name must be unique within a loadout",
                        catalog.operatorId,
                        "disc_loadouts[$index].discs",
                        "invalid_disc_loadout",
                    )
                }
                OperatorDisc(catalogDisc.otName, catalogDisc.abbreviation, catalogDisc.color, catalogDisc.desp)
            }
            OperatorDiscLoadout(id, name, discs)
        }
    }

    private fun mergeCombatStats(
        existing: OperatorCombatStats?,
        raw: JsonNode,
        catalog: OperatorCatalogEntity,
        operatorId: String,
    ): OperatorCombatStats {
        if (!raw.isObject) {
            invalid("combat_stats must be an object or null", operatorId, "combat_stats", "invalid_combat_stats")
        }
        val node = raw as ObjectNode
        rejectUnknown(
            node,
            setOf(
                "observed_attack",
                "observed_hp",
                "manual_attack",
                "manual_hp",
                "source",
                "observed_at",
                "observed_status",
                "combat_input_signature",
                "observed_inputs",
                "oddities",
            ),
            "invalid_combat_stats",
            operatorId,
            "combat_stats",
        )
        var result = existing ?: OperatorCombatStats()
        if (node.has("observed_attack")) {
            result = result.copy(observedAttack = nullableNonNegativeLong(node, "observed_attack", operatorId))
        }
        if (node.has("observed_hp")) {
            result = result.copy(observedHp = nullableNonNegativeLong(node, "observed_hp", operatorId))
        }
        if (node.has("manual_attack")) {
            result = result.copy(manualAttack = nullableNonNegativeLong(node, "manual_attack", operatorId))
        }
        if (node.has("manual_hp")) {
            result = result.copy(manualHp = nullableNonNegativeLong(node, "manual_hp", operatorId))
        }
        if (node.has("source")) {
            val source = requiredText(node, "source", operatorId)
            if (source !in COMBAT_SOURCES) {
                invalid("Invalid combat source", operatorId, "combat_stats.source", "invalid_combat_stats")
            }
            result = result.copy(source = source)
        }
        if (node.has("observed_status")) {
            val status = requiredText(node, "observed_status", operatorId)
            if (status !in OBSERVED_STATUSES) {
                invalid("Invalid observed status", operatorId, "combat_stats.observed_status", "invalid_combat_stats")
            }
            result = result.copy(observedStatus = status)
        }
        if (node.has("observed_at")) {
            val atNode = node.get("observed_at")
            result = result.copy(observedAt = if (atNode.isNull) null else parseInstantNode(atNode, operatorId, "combat_stats.observed_at"))
        }
        if (node.has("combat_input_signature")) {
            val signatureNode = node.get("combat_input_signature")
            val signature = if (signatureNode.isNull) null else requiredText(node, "combat_input_signature", operatorId)
            if (signature != null && signature.length !in 1..256) {
                invalid(
                    "combat_input_signature length must be 1..256",
                    operatorId,
                    "combat_stats.combat_input_signature",
                    "invalid_combat_stats",
                )
            }
            result = result.copy(combatInputSignature = signature)
        }
        if (node.has("observed_inputs")) {
            val inputs = node.get("observed_inputs")
            result = result.copy(observedInputs = if (inputs.isNull) null else parseObservedInputs(inputs, operatorId))
        }
        if (node.has("oddities")) result = result.copy(oddities = mergeOddities(result.oddities, node.get("oddities"), catalog, operatorId))
        return result
    }

    private fun mergeOddities(
        existing: Map<String, OperatorOddityValue>,
        raw: JsonNode,
        catalog: OperatorCatalogEntity,
        operatorId: String,
    ): Map<String, OperatorOddityValue> {
        if (!raw.isObject) invalid("oddities must be an object", operatorId, "combat_stats.oddities", "invalid_combat_stats")
        val oddities = raw as ObjectNode
        rejectUnknown(oddities, ODDITY_KEYS, "invalid_combat_stats", operatorId, "combat_stats.oddities")
        val limits = oddityLimits(catalog.rarity)
        val merged = existing.toMutableMap()
        oddities.fieldNames().asSequence().forEach { key ->
            val value = oddities.get(key)
            if (!value.isObject) {
                invalid("oddity must be an object", operatorId, "combat_stats.oddities.$key", "invalid_combat_stats")
            }
            val oddity = value as ObjectNode
            rejectUnknown(
                oddity,
                setOf("current", "max"),
                "invalid_combat_stats",
                operatorId,
                "combat_stats.oddities.$key",
            )
            val current = requiredInt(
                oddity,
                "current",
                operatorId,
                "combat_stats.oddities.$key.current",
                "invalid_combat_stats",
            )
            if (current !in 0..limits.getValue(key)) {
                invalid(
                    "oddity current exceeds the catalog limit",
                    operatorId,
                    "combat_stats.oddities.$key.current",
                    "invalid_combat_stats",
                )
            }
            if (oddity.has("max")) {
                val diagnosticMax = requiredInt(
                    oddity,
                    "max",
                    operatorId,
                    "combat_stats.oddities.$key.max",
                    "invalid_combat_stats",
                )
                if (diagnosticMax < 0) {
                    invalid(
                        "oddity max must be non-negative",
                        operatorId,
                        "combat_stats.oddities.$key.max",
                        "invalid_combat_stats",
                    )
                }
            }
            merged[key] = OperatorOddityValue(current)
        }
        return merged
    }

    private fun parseObservedInputs(node: JsonNode, operatorId: String): OperatorObservedInputs {
        if (!node.isObject) {
            invalid("observed_inputs must be an object", operatorId, "combat_stats.observed_inputs", "invalid_combat_stats")
        }
        val item = node as ObjectNode
        rejectUnknown(
            item,
            setOf("level", "elite", "star_level", "oddities_signature", "equipped_star_stones_signature"),
            "invalid_combat_stats",
            operatorId,
            "combat_stats.observed_inputs",
        )
        val level = optionalInt(item, "level", operatorId, "combat_stats.observed_inputs.level")
        val elite = optionalInt(item, "elite", operatorId, "combat_stats.observed_inputs.elite")
        val starLevel = optionalInt(item, "star_level", operatorId, "combat_stats.observed_inputs.star_level")
        if (level != null && level !in 0..100) {
            invalid("observed level must be 0..100", operatorId, "combat_stats.observed_inputs.level", "invalid_combat_stats")
        }
        if (elite != null && elite !in 0..17) {
            invalid("observed elite must be 0..17", operatorId, "combat_stats.observed_inputs.elite", "invalid_combat_stats")
        }
        if (starLevel != null && starLevel !in 0..31) {
            invalid(
                "observed star_level must be 0..31",
                operatorId,
                "combat_stats.observed_inputs.star_level",
                "invalid_combat_stats",
            )
        }
        return OperatorObservedInputs(
            level,
            elite,
            starLevel,
            optionalText(item, "oddities_signature", operatorId),
            optionalText(item, "equipped_star_stones_signature", operatorId),
        )
    }

    private fun hasFreshObservation(raw: JsonNode?): Boolean = raw?.isObject == true &&
        raw.hasNonNull("combat_input_signature") &&
        (raw.hasNonNull("observed_attack") || raw.hasNonNull("observed_hp"))

    fun listRecords(
        userId: String,
        accountId: String,
        game: String?,
        from: Instant?,
        to: Instant?,
        cursor: String?,
        limit: Int,
    ): OperatorRecordPageResponse {
        if (limit !in 1..100) {
            throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "limit must be 1..100")
        }
        var records = recordRepository.findByUserIdAndAccountIdOrderByEffectiveAtDesc(userId, accountId).filter {
            (game == null || it.game == game) &&
                (from == null || !it.effectiveAt.isBefore(from)) &&
                (to == null || it.effectiveAt.isBefore(to))
        }
        if (cursor != null) {
            val split = String(Base64.getUrlDecoder().decode(cursor)).split("|", limit = 2)
            val at = Instant.parse(split[0])
            records = records.dropWhile {
                !it.effectiveAt.isBefore(at) || (it.effectiveAt.equals(at) && it.recordId >= split[1])
            }
        }
        val page = records.take(limit)
        val next = if (records.size > limit) {
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                (page.last().effectiveAt.toString() + "|" + page.last().recordId).toByteArray(),
            )
        } else {
            null
        }
        return OperatorRecordPageResponse(
            page.map {
                OperatorRecordListItemDto(
                    it.accountId,
                    it.recordId,
                    it.recordType,
                    it.game,
                    it.snapshotScope,
                    it.effectiveAt,
                    it.receivedAt,
                    it.snapshotEffect,
                    it.entries,
                )
            },
            next,
        )
    }

    fun deleteRecord(userId: String, accountId: String, recordId: String) {
        val target = recordRepository.findByUserIdAndAccountIdAndRecordId(userId, accountId, recordId)
            ?: throw apiError(HttpStatus.NOT_FOUND, "record_not_found", "Record not found")
        transactionTemplate.executeWithoutResult {
            recordRepository.delete(target)
            val game = target.game ?: GENERIC_GAME
            currentRepository.deleteByUserIdAndAccountIdAndGame(userId, accountId, game)
            val records = recordRepository.findByUserIdAndAccountIdAndGameOrderByEffectiveAtAsc(
                userId,
                accountId,
                target.game,
            )
            val corrections = correctionRepository.findByUserIdAndAccountIdAndGameOrderByCreatedAtAsc(
                userId,
                accountId,
                game,
            )
            val events = records.map { ReplayEvent(it.receivedAt, "record:${it.recordId}", it, null) } +
                corrections.map { ReplayEvent(it.createdAt, "correction:${it.id.orEmpty()}", null, it) }
            events.sortedWith(compareBy<ReplayEvent> { it.at }.thenBy { it.key }).forEach { event ->
                event.record?.let { record ->
                    val effect = applyRecord(userId, Validated(record.toRequest(), record.effectiveAt))
                    if (record.snapshotEffect != effect) recordRepository.save(record.copy(snapshotEffect = effect))
                }
                event.correction?.let(::applyCorrectionReplay)
            }
        }
    }

    private fun applyCorrectionReplay(correction: OperatorCorrectionRecord) {
        val current = currentRepository.findByUserIdAndAccountIdAndGame(
            correction.userId,
            correction.accountId,
            correction.game,
        ) ?: return
        val existing = current.entries[correction.operatorId]?.normalized() ?: return
        var merged = existing
        if ("level" in correction.fields) merged = merged.copy(level = checkNotNull(correction.level))
        if ("elite" in correction.fields) merged = merged.copy(elite = checkNotNull(correction.elite))
        if ("star_level" in correction.fields) merged = merged.copy(starLevel = checkNotNull(correction.starLevel))
        if ("disc_loadouts" in correction.fields) {
            merged = merged.copy(discLoadouts = correction.discLoadouts.orEmpty())
            merged = merged.copy(discs = merged.discLoadouts.firstOrNull()?.discs.orEmpty())
        }
        if ("combat_stats" in correction.fields) merged = merged.copy(combatStats = correction.combatStats)
        if (combatInputsChanged(existing, merged) && "combat_stats" !in correction.fields) merged = merged.markObservationStale()
        merged = merged.copy(revision = existing.revision + 1, updatedAt = correction.createdAt).normalized()
        val entries = current.entries.toMutableMap().also { it[correction.operatorId] = merged }
        normalizeSpRelations(entries, setOf(correction.operatorId), correction.createdAt)
        currentRepository.save(current.copy(entries = entries, updatedAt = correction.createdAt))
    }

    fun export(userId: String, accountId: String?, scope: String?): OperatorExportResponse {
        if ((accountId == null && scope != "all") || (accountId != null && scope != null)) {
            throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "Specify account_id or scope=all")
        }
        val accounts = if (accountId != null) {
            listOf(
                accountRepository.findByUserIdAndAccountId(userId, accountId)
                    ?: throw apiError(HttpStatus.NOT_FOUND, "account_not_found", "Account not found"),
            )
        } else {
            accountRepository.findAllByUserIdOrderByCreatedAtAsc(userId)
        }
        val exportId = UUID.randomUUID().toString().replace("-", "")
        val records = accounts.flatMap { account ->
            currentRepository.findByUserIdAndAccountIdOrderByUpdatedAtDesc(userId, account.accountId).map { current ->
                OperatorExportRecordDto(
                    account.accountId,
                    "myshare:export:$exportId:${account.accountId}:" +
                        if (current.game == GENERIC_GAME) "generic" else current.game,
                    game = current.game.takeUnless { it == GENERIC_GAME },
                    effectiveAt = current.fullBaselineAt ?: current.updatedAt,
                    entries = current.entries.map { (id, raw) ->
                        val entry = raw.normalized()
                        OperatorRecordEntry(
                            id = id,
                            elite = entry.elite,
                            starLevel = entry.starLevel,
                            level = entry.level,
                            discs = entry.discs,
                            starStones = entry.starStones,
                        )
                    },
                )
            }
        }
        return OperatorExportResponse(
            exportedAt = Instant.now(),
            catalogVersion = catalogService.currentCatalogVersion(),
            producer = ProducerDto("myshare", "5"),
            accounts = accounts.map { OperatorExportAccountDto(it.accountId, it.name) },
            records = records,
        )
    }

    private fun combatInputsChanged(beforeRaw: OperatorEntry, afterRaw: OperatorEntry): Boolean {
        val before = beforeRaw.normalized()
        val after = afterRaw.normalized()
        return before.level != after.level ||
            before.elite != after.elite ||
            before.starLevel != after.starLevel ||
            before.starStones != after.starStones ||
            before.combatStats?.oddities.orEmpty() != after.combatStats?.oddities.orEmpty()
    }

    private fun OperatorEntry.markObservationStale(): OperatorEntry {
        val stats = combatStats ?: return this
        if (stats.observedAttack == null && stats.observedHp == null) return this
        if (stats.observedStatus == "unavailable") return this
        return copy(combatStats = stats.copy(observedStatus = "stale"))
    }

    private fun oddityLimits(rarity: Int): Map<String, Int> {
        val schema = OperatorOddityRules.schema(rarity, null)
        return mapOf("attack" to schema.attack.max, "hp" to schema.hp.max, "special" to schema.special.max)
    }

    private fun sameBody(existing: OperatorRecord, request: OperatorRecordRequest): Boolean = existing.recordType == request.recordType &&
        existing.game == request.game &&
        existing.snapshotScope == request.snapshotScope &&
        existing.effectiveAt.equals(parseTime(request.effectiveAt, "effective_at")) &&
        existing.entries == request.entries.map { it.toEntity() }

    private fun Validated.toEntity(userId: String, producer: ProducerDto, effect: String) = OperatorRecord(
        recordId = record.recordId,
        userId = userId,
        accountId = record.accountId,
        recordType = record.recordType,
        game = record.game,
        snapshotScope = record.snapshotScope,
        effectiveAt = effectiveAt,
        producer = ProducerInfo(producer.platform, producer.version),
        entries = record.entries.map { it.toEntity() },
        snapshotEffect = effect,
    )

    private fun OperatorEntryRequest.toEntity() = OperatorRecordEntry(
        id,
        name,
        alias,
        rarity,
        prof,
        subProf,
        games,
        elite,
        starLevel,
        level,
        discs.map { OperatorDisc(it.otName, it.abbreviation, it.color, it.desp) },
        starStones.map { OperatorStarStone(it.name, it.type, it.level) },
    )

    private fun OperatorRecord.toRequest() = OperatorRecordRequest(
        accountId,
        recordId,
        recordType,
        game,
        effectiveAt.toString(),
        snapshotScope,
        entries.map {
            OperatorEntryRequest(
                it.id,
                it.name,
                it.alias,
                it.rarity,
                it.prof,
                it.subProf,
                it.games,
                it.elite,
                it.starLevel,
                it.level,
                it.discs.map { disc -> OperatorDiscRequest(disc.otName, disc.abbreviation, disc.color, disc.desp) },
                it.starStones.map { stone -> OperatorStarStoneRequest(stone.name, stone.type, stone.level) },
            )
        },
    )

    private fun rejectUnknown(node: ObjectNode, allowed: Set<String>, code: String, operatorId: String, path: String) {
        node.fieldNames().asSequence().firstOrNull { it !in allowed }?.let { field ->
            invalid(
                "Unsupported field: $field",
                operatorId,
                listOf(path, field).filter { it.isNotEmpty() }.joinToString("."),
                code,
            )
        }
    }

    private fun requiredInt(
        node: ObjectNode,
        field: String,
        operatorId: String,
        path: String = field,
        code: String = "schema_validation_failed",
    ): Int {
        val value = node.get(field)
        if (value == null || value.isNull || !value.isIntegralNumber || !value.canConvertToInt()) {
            invalid("$field must be an integer", operatorId, path, code)
        }
        return value.intValue()
    }

    private fun optionalInt(node: ObjectNode, field: String, operatorId: String, path: String): Int? =
        if (!node.has(field)) null else requiredInt(node, field, operatorId, path, "invalid_combat_stats")

    private fun requiredLong(node: ObjectNode, field: String, operatorId: String): Long {
        val value = node.get(field)
        if (value == null || value.isNull || !value.isIntegralNumber || !value.canConvertToLong()) {
            invalid("$field must be an integer", operatorId, field)
        }
        return value.longValue()
    }

    private fun nullableNonNegativeLong(node: ObjectNode, field: String, operatorId: String): Long? {
        if (!node.has(field)) return null
        val value = node.get(field)
        if (value.isNull) return null
        if (!value.isIntegralNumber || !value.canConvertToLong() || value.longValue() < 0) {
            invalid(
                "$field must be a non-negative integer or null",
                operatorId,
                "combat_stats.$field",
                "invalid_combat_stats",
            )
        }
        return value.longValue()
    }

    private fun requiredText(node: ObjectNode, field: String, operatorId: String): String {
        val value = node.get(field)
        if (value == null || !value.isTextual) {
            invalid("$field must be a string", operatorId, "combat_stats.$field", "invalid_combat_stats")
        }
        return value.asText()
    }

    private fun optionalText(node: ObjectNode, field: String, operatorId: String): String? {
        if (!node.has(field)) return null
        val value = node.get(field)
        if (value.isNull) return null
        val text = requiredText(node, field, operatorId)
        if (text.length !in 1..256) {
            invalid(
                "$field length must be 1..256",
                operatorId,
                "combat_stats.observed_inputs.$field",
                "invalid_combat_stats",
            )
        }
        return text
    }

    private fun parseInstantNode(node: JsonNode, operatorId: String, path: String): Instant {
        if (!node.isTextual) invalid("$path must be a timestamp", operatorId, path, "invalid_combat_stats")
        return try {
            OffsetDateTime.parse(node.asText()).toInstant()
        } catch (_: Exception) {
            invalid("Invalid timestamp", operatorId, path, "invalid_combat_stats")
        }
    }

    private fun invalid(message: String, operatorId: String, fieldPath: String, code: String = "schema_validation_failed"): Nothing {
        throw apiError(
            HttpStatus.UNPROCESSABLE_ENTITY,
            code,
            message,
            operator = operatorId,
            field = fieldPath,
        )
    }

    private fun revisionConflict(operatorId: String): Nothing = throw apiError(
        HttpStatus.CONFLICT,
        "operator_revision_conflict",
        "Operator revision has changed",
        operator = operatorId,
        field = "expected_revision",
    )

    private fun parseTime(value: String, field: String) = try {
        OffsetDateTime.parse(value).toInstant()
    } catch (_: Exception) {
        throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "Invalid $field")
    }

    private fun validateProducer(producer: ProducerDto) {
        if (!producer.platform.matches(Regex("^[a-z0-9][a-z0-9._-]{0,63}$")) || producer.version == "") {
            throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "Invalid producer")
        }
    }

    private fun normalizeStoneType(type: String): String = when (type) {
        "main" -> "main1"
        "assist" -> "assist1"
        else -> type
    }

    private fun key(user: String, account: String, game: String) = "$user:$account:$game"

    private fun apiError(
        status: HttpStatus,
        code: String,
        message: String,
        record: String? = null,
        entry: String? = null,
        operator: String? = null,
        field: String? = null,
    ) = OperatorApiException(status, code, message, record, entry, operator, field)

    private fun conflict(record: String) = apiError(
        HttpStatus.CONFLICT,
        "record_conflict",
        "Record conflicts with existing record",
        record,
    )

    private data class Validated(val record: OperatorRecordRequest, val effectiveAt: Instant)

    private data class ReplayEvent(
        val at: Instant,
        val key: String,
        val record: OperatorRecord?,
        val correction: OperatorCorrectionRecord?,
    )

    companion object {
        const val FORMAT = "myshare-operator-exchange"
        const val VERSION = 2
        const val RECORD_TYPE = "operator_snapshot"
        const val FULL = "full"
        const val LISTED = "listed"
        const val APPLIED = "applied"
        const val SUPERSEDED = "superseded"
        const val GENERIC_GAME = "*"
        const val MAX_STAR_LEVEL = 31
        const val MAX_SP_STAR_LEVEL = 5
        val SCOPES = setOf(FULL, LISTED)
        val GAMES = setOf("如鸢", "代号鸢")
        val STONE_TYPES = setOf("main", "assist", "main1", "main2", "main3", "assist1", "assist2", "assist3")
        val ACCOUNT_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        val CORRECTION_REASONS = setOf("manual_correction", "local_migration")
        val COMBAT_SOURCES = setOf("scan", "manual", "imported")
        val OBSERVED_STATUSES = setOf("valid", "stale", "unverified", "unavailable")
        val ODDITY_KEYS = setOf("attack", "hp", "special")
    }
}
