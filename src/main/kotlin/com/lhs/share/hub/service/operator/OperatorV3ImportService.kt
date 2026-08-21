package com.lhs.share.hub.service.operator

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lhs.share.hub.controller.operator.response.OperatorCurrentEntryDto
import com.lhs.share.hub.controller.operator.response.OperatorScanImportEvent
import com.lhs.share.hub.controller.operator.response.OperatorV3FieldChange
import com.lhs.share.hub.controller.operator.response.OperatorV3ImportCommitResponse
import com.lhs.share.hub.controller.operator.response.OperatorV3ImportItem
import com.lhs.share.hub.controller.operator.response.OperatorV3ImportPreviewResponse
import com.lhs.share.hub.controller.operator.response.OperatorV3Issue
import com.lhs.share.hub.controller.operator.response.toCommitResponse
import com.lhs.share.hub.controller.operator.response.toPreviewResponse
import com.lhs.share.hub.repository.OperatorV3ImportRecordRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.OperatorCatalogEntity
import com.lhs.share.hub.repository.entity.OperatorV3ImportRecord
import com.lhs.share.hub.repository.entity.SubAccount
import com.lhs.share.hub.service.account.AccountEventService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

@Service
class OperatorV3ImportService(
    private val objectMapper: ObjectMapper,
    private val schemaValidator: OperatorV3SchemaValidator,
    private val accountRepository: SubAccountRepository,
    private val catalogService: OperatorCatalogService,
    private val operatorService: OperatorService,
    private val importRecordRepository: OperatorV3ImportRecordRepository,
    private val accountEventService: AccountEventService,
) {
    fun previewBrowser(userId: String, body: JsonNode): OperatorV3ImportPreviewResponse =
        prepare(userId, parseCommand(body, null)).map(PreparedItem::response).toPreviewResponse()

    fun commitBrowser(userId: String, body: JsonNode): OperatorV3ImportCommitResponse = commit(userId, parseCommand(body, null))

    fun previewScan(userId: String, accountId: String, document: JsonNode): OperatorV3ImportPreviewResponse =
        prepare(userId, parseCommand(document, accountId)).map(PreparedItem::response).toPreviewResponse()

    fun commitScan(userId: String, accountId: String, document: JsonNode): OperatorV3ImportCommitResponse =
        commit(userId, parseCommand(document, accountId))

    private fun commit(userId: String, command: ImportCommand): OperatorV3ImportCommitResponse {
        val committed = mutableListOf<OperatorV3ImportItem>()
        records(command.document).forEach { record ->
            val targetAccountId = command.mapping.getValue(text(record, "account_id"))
            val target = requireOwnedAccount(userId, targetAccountId)
            val game = targetGame(record, target)
            val priorAudit = importRecordRepository.findByUserIdAndAccountIdAndRecordId(
                userId,
                targetAccountId,
                text(record, "record_id"),
            )
            val prepared = prepareRecord(userId, command, record)
            val written = mutableListOf<OperatorV3ImportItem>()
            prepared.forEach { item ->
                val result = if (item.patch == null || item.response.status == REJECTED || item.response.status == UNCHANGED) {
                    item.response
                } else {
                    val current = operatorService.patchCurrent(
                        userId,
                        item.targetAccountId,
                        item.targetGame,
                        item.operatorId,
                        item.patch,
                    )
                    item.response.copy(
                        revision = current.revision,
                        targetRevision = current.revision,
                        observedStatus = current.combatStats?.observedStatus,
                    )
                }
                written += result
                if (command.scanAccountId != null) publishScanEvent(userId, result)
            }
            if (priorAudit == null &&
                text(record, "snapshot_scope") == "full" &&
                written.none { it.status == REJECTED || it.status == REVIEW }
            ) {
                operatorService.completeFullImport(
                    userId,
                    targetAccountId,
                    game,
                    entries(record).map { text(it, "operator_id") }.toSet(),
                    OffsetDateTime.parse(text(record, "effective_at")).toInstant(),
                )
            }
            val shouldAudit = priorAudit == null && (
                prepared.any { it.patch != null } ||
                    written.any { it.status == UNCHANGED } ||
                    (entries(record).isEmpty() && text(record, "snapshot_scope") == "full")
                )
            if (shouldAudit) {
                importRecordRepository.save(
                    OperatorV3ImportRecord(
                        userId = userId,
                        accountId = targetAccountId,
                        sourceAccountId = text(record, "account_id"),
                        recordId = text(record, "record_id"),
                        game = game,
                        sourceKind = text(record, "source_kind"),
                        snapshotScope = text(record, "snapshot_scope"),
                        payload = objectMapper.writeValueAsString(record),
                        revisions = written.mapNotNull { item ->
                            item.revision?.let { item.operatorId to it }
                        }.toMap(),
                    ),
                )
            }
            committed += written
        }
        return committed.toCommitResponse()
    }

    private fun publishScanEvent(userId: String, item: OperatorV3ImportItem) {
        val event = OperatorScanImportEvent(
            accountId = item.accountId,
            operatorId = item.operatorId,
            recordId = item.recordId,
            status = item.status,
            revision = item.revision,
            stale = item.stale,
            observedStatus = item.observedStatus,
            warnings = item.warnings,
            blockingErrors = item.blockingErrors,
        )
        accountEventService.publish(
            userId,
            item.accountId,
            SCAN_EVENT_NAME,
            event.eventId,
            event,
        )
    }

    private fun prepare(userId: String, command: ImportCommand): List<PreparedItem> =
        records(command.document).flatMap { prepareRecord(userId, command, it) }

    private fun prepareRecord(userId: String, command: ImportCommand, record: ObjectNode): List<PreparedItem> {
        val sourceAccountId = text(record, "account_id")
        val targetAccountId = command.mapping.getValue(sourceAccountId)
        val target = requireOwnedAccount(userId, targetAccountId)
        val targetGame = targetGame(record, target)
        val recordId = text(record, "record_id")
        val existingAudit = importRecordRepository.findByUserIdAndAccountIdAndRecordId(userId, targetAccountId, recordId)
        if (existingAudit != null) {
            val same = objectMapper.readTree(existingAudit.payload) == record
            return entries(record).map { entry ->
                val operatorId = entry.path("operator_id").asText("")
                PreparedItem(
                    targetAccountId,
                    targetGame,
                    operatorId,
                    null,
                    OperatorV3ImportItem(
                        accountId = targetAccountId,
                        operatorId = operatorId,
                        recordId = recordId,
                        status = if (same) UNCHANGED else REJECTED,
                        warnings = if (same) listOf(issue("duplicate_record", "record_id was already committed")) else emptyList(),
                        blockingErrors = if (same) {
                            emptyList()
                        } else {
                            listOf(issue("idempotency_conflict", "record_id was already used with different content"))
                        },
                        targetRevision = existingAudit.revisions[operatorId],
                        revision = existingAudit.revisions[operatorId],
                    ),
                    duplicate = same,
                )
            }
        }
        return entries(record).map { entry -> prepareEntry(userId, command, record, entry, target, targetGame) }
    }

    private fun prepareEntry(
        userId: String,
        command: ImportCommand,
        record: ObjectNode,
        entry: ObjectNode,
        target: SubAccount,
        targetGame: String,
    ): PreparedItem {
        val recordId = text(record, "record_id")
        val operatorId = text(entry, "operator_id")
        return try {
            val catalog = catalogService.getOperator(operatorId)
                ?: invalid("unknown_operator", "Unknown operator_id: $operatorId", recordId, operatorId, "operator_id")
            if (targetGame !in catalog.games) {
                invalid("invalid_game", "Operator is not available in the target game", recordId, operatorId, "game")
            }
            validateEntrySemantics(entry, record, catalog, command.scanAccountId != null)
            val warnings = entry.path("warnings").takeIf(JsonNode::isArray)?.map {
                issue("producer_warning", it.asText())
            }.orEmpty().toMutableList()
            entry.get("name")?.asText()?.takeIf { it != catalog.name }?.let {
                warnings += issue("catalog_name_mismatch", "name differs from the public catalog", "name")
            }
            if (entry.path("combat_stats").path("oddities").isObject && catalog.specialOddityName.isNullOrBlank()) {
                warnings += issue("catalog_oddity_name_missing", "Public catalog has no special oddity display name")
            }
            warnings += oddityMaxWarnings(entry, catalog)

            val patch = buildPatch(entry, record, command)
            val review = hasReview(entry)
            val partial = hasPartial(entry)
            val matchReview = entry.path("match").path("status").asText("ready") != "ready"
            if ((review || matchReview) && !command.confirmReview) removeReviewSections(patch, entry, matchReview)
            if (patch.size() <= 1) {
                val status = if (review || matchReview) REVIEW else UNCHANGED
                return PreparedItem(
                    target.accountId,
                    targetGame,
                    operatorId,
                    null,
                    OperatorV3ImportItem(
                        target.accountId,
                        operatorId,
                        recordId,
                        status,
                        warnings = warnings,
                        targetRevision = currentRevision(userId, target.accountId, targetGame, operatorId),
                    ),
                )
            }
            val expectedRevision = currentRevision(userId, target.accountId, targetGame, operatorId)
            patch.put("expected_revision", expectedRevision)
            val preview = operatorService.previewCurrentPatch(userId, target.accountId, targetGame, operatorId, patch)
            val changes = changes(patch, preview.before, preview.after)
            val unchanged = changes.isEmpty()
            val status = when {
                unchanged -> UNCHANGED
                (review || matchReview) && !command.confirmReview -> REVIEW
                partial -> PARTIAL
                else -> ACCEPTED
            }
            PreparedItem(
                target.accountId,
                targetGame,
                operatorId,
                patch.takeUnless { unchanged },
                OperatorV3ImportItem(
                    accountId = target.accountId,
                    operatorId = operatorId,
                    recordId = recordId,
                    status = status,
                    changes = changes,
                    warnings = warnings,
                    stale = preview.stale,
                    targetRevision = if (unchanged) expectedRevision else preview.after.revision,
                    observedStatus = preview.after.combatStats?.observedStatus,
                ),
            )
        } catch (e: OperatorApiException) {
            PreparedItem(
                target.accountId,
                targetGame,
                operatorId,
                null,
                OperatorV3ImportItem(
                    accountId = target.accountId,
                    operatorId = operatorId,
                    recordId = recordId,
                    status = REJECTED,
                    blockingErrors = listOf(issue(e.code, e.message, e.fieldPath)),
                    targetRevision = currentRevision(userId, target.accountId, targetGame, operatorId),
                ),
            )
        }
    }

    private fun parseCommand(body: JsonNode, scanAccountId: String?): ImportCommand {
        if (!body.isObject) schemaError("Request body must be an object")
        val root = body as ObjectNode
        val wrapped = root.has("document")
        if (wrapped) rejectUnknown(root, setOf("document", "account_mapping", "confirm_review"))
        val document = (if (wrapped) root.get("document") else root) as? ObjectNode
            ?: schemaError("document must be an object")
        schemaValidator.validate(document)
        validateDocumentSemantics(document, scanAccountId)
        val sourceIds = accounts(document).map { text(it, "id") }
        val mapping = if (scanAccountId != null) {
            if (sourceIds.size != 1) {
                throw OperatorApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "scan_scope_not_allowed",
                    "Scan import requires one source account",
                )
            }
            sourceIds.associateWith { scanAccountId }
        } else if (wrapped) {
            val rawMapping = root.get("account_mapping")
            if (rawMapping == null || !rawMapping.isObject) {
                throw OperatorApiException(HttpStatus.UNPROCESSABLE_ENTITY, "account_mapping_required", "account_mapping is required")
            }
            rawMapping.fields().asSequence().associate { (source, value) ->
                if (!value.isTextual || value.asText().isBlank()) schemaError("account_mapping values must be account ids")
                source to value.asText()
            }.also { mappings ->
                if (sourceIds.any { it !in mappings }) {
                    throw OperatorApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "account_mapping_required",
                        "Every source account must be mapped",
                    )
                }
            }
        } else {
            sourceIds.associateWith { it }
        }
        val confirmReview = wrapped && root.path("confirm_review").asBoolean(false)
        return ImportCommand(document, mapping, scanAccountId, confirmReview)
    }

    private fun validateDocumentSemantics(document: ObjectNode, scanAccountId: String?) {
        parseTimestamp(text(document, "exported_at"), "exported_at")
        val accountIds = accounts(document).map { text(it, "id") }
        if (accountIds.distinct().size != accountIds.size) schemaError("accounts[].id must be unique")
        val records = records(document)
        val recordIds = records.map { text(it, "record_id") }
        if (recordIds.distinct().size != recordIds.size) schemaError("record_id must be unique")
        records.forEach { record ->
            val source = text(record, "account_id")
            if (source !in accountIds) schemaError("record account_id does not reference accounts", text(record, "record_id"))
            if (text(record, "record_type") != "operator_snapshot") {
                throw OperatorApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    if (scanAccountId != null) "scan_scope_not_allowed" else "unsupported_record_type",
                    "This release only imports operator_snapshot records",
                    text(record, "record_id"),
                )
            }
            parseTimestamp(text(record, "effective_at"), "effective_at", text(record, "record_id"))
            if (scanAccountId != null &&
                (text(record, "source_kind") != "scan" || text(record, "snapshot_scope") != "listed")
            ) {
                throw OperatorApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "scan_scope_not_allowed",
                    "OpenAPI scan import requires source_kind=scan and snapshot_scope=listed",
                    text(record, "record_id"),
                )
            }
        }
        if (scanAccountId != null && records.map { text(it, "account_id") }.distinct().size != 1) {
            throw OperatorApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "scan_scope_not_allowed",
                "Scan records must use one source account",
            )
        }
    }

    private fun validateEntrySemantics(entry: ObjectNode, record: ObjectNode, catalog: OperatorCatalogEntity, scan: Boolean) {
        entry.get("observed_at")?.let { parseTimestamp(it.asText(), "observed_at", text(record, "record_id")) }
        entry.path("combat_stats").get("observed_at")?.let {
            parseTimestamp(it.asText(), "combat_stats.observed_at", text(record, "record_id"))
        }
        entry.get("star_level")?.let {
            val maximum = if (catalog.spOf == null) 31 else 5
            if (it.intValue() !in 0..maximum) {
                invalid(
                    "invalid_star_level",
                    "star_level is invalid for this catalog operator",
                    text(record, "record_id"),
                    catalog.operatorId,
                    "star_level",
                )
            }
        }
        val oddities = entry.path("combat_stats").path("oddities")
        val oddityStatus = entry.path("section_status").path("oddities").asText("")
        if (oddityStatus == "ready" && oddities.isObject && oddities.size() != 3) {
            invalid(
                "invalid_oddities",
                "ready oddities must include attack, hp, and special",
                text(record, "record_id"),
                catalog.operatorId,
                "combat_stats.oddities",
            )
        }
        validateCatalogEntryFields(entry, record, catalog)
        if (scan) {
            val combat = entry.path("combat_stats")
            if (combat.has("manual_attack") || combat.has("manual_hp") || combat.has("display_mode")) {
                invalid(
                    "scan_field_not_allowed",
                    "OpenAPI scan import cannot change manual values or display_mode",
                    text(record, "record_id"),
                    catalog.operatorId,
                    "combat_stats",
                )
            }
        }
    }

    private fun validateCatalogEntryFields(entry: ObjectNode, record: ObjectNode, catalog: OperatorCatalogEntity) {
        val recordId = text(record, "record_id")
        entry.path("disc_loadouts").takeIf(JsonNode::isArray)?.forEachIndexed { loadoutIndex, loadout ->
            loadout.path("discs").takeIf(JsonNode::isArray)?.forEachIndexed { discIndex, disc ->
                val name = disc.path("ot_name").asText()
                if (catalog.discs.none { it.otName == name }) {
                    invalid(
                        "invalid_disc_loadout",
                        "disc is not in the operator catalog",
                        recordId,
                        catalog.operatorId,
                        "disc_loadouts[$loadoutIndex].discs[$discIndex].ot_name",
                    )
                }
            }
        }
        val stoneTypes = mutableSetOf<String>()
        entry.path("equipped_star_stones").takeIf(JsonNode::isArray)?.forEachIndexed { index, stone ->
            val type = stone.path("type").asText()
            val catalogType = when {
                type.startsWith("main") -> "main"
                type.startsWith("assist") -> "assist"
                else -> type
            }
            if (!stoneTypes.add(type) ||
                (catalog.starStones.isNotEmpty() && catalog.starStones.none { it.type == type || it.type == catalogType })
            ) {
                invalid(
                    "invalid_equipped_star_stones",
                    "star stone type is not supported by the operator catalog",
                    recordId,
                    catalog.operatorId,
                    "equipped_star_stones[$index].type",
                )
            }
        }
        val limits = oddityLimits(catalog.rarity)
        entry.path("combat_stats").path("oddities").takeIf(JsonNode::isObject)?.fields()?.forEach { (key, value) ->
            val current = value.path("current")
            if (!current.isIntegralNumber || current.intValue() !in 0..limits.getValue(key)) {
                invalid(
                    "invalid_combat_stats",
                    "oddity current exceeds the catalog limit",
                    recordId,
                    catalog.operatorId,
                    "combat_stats.oddities.$key.current",
                )
            }
        }
    }

    private fun buildPatch(entry: ObjectNode, record: ObjectNode, command: ImportCommand): ObjectNode {
        val patch = objectMapper.createObjectNode().put("reason", "v3_import")
        if (includeSection(entry, "basic", command.confirmReview)) {
            if (entry.has("level")) patch.set<JsonNode>("level", entry.get("level"))
            if (entry.has("elite")) patch.set<JsonNode>("elite", entry.get("elite"))
        }
        if (includeSection(entry, "huaji", command.confirmReview) && entry.has("star_level")) {
            patch.set<JsonNode>("star_level", entry.get("star_level"))
        }
        if (includeSection(entry, "disc_loadouts", command.confirmReview) && entry.has("disc_loadouts")) {
            patch.set<JsonNode>("disc_loadouts", entry.get("disc_loadouts"))
        }
        if (includeSection(entry, "equipment", command.confirmReview) && entry.has("equipped_star_stones")) {
            val stones = objectMapper.createArrayNode()
            entry.withArray("equipped_star_stones").forEach { raw ->
                val stone = objectMapper.createObjectNode()
                    .put("type", raw.path("type").asText())
                    .put("name", raw.path("name").asText(raw.path("stone_id").asText()))
                    .put("level", raw.path("level").intValue())
                stones.add(stone)
            }
            patch.set<JsonNode>("star_stones", stones)
        }
        if (entry.has("combat_stats")) {
            val source = entry.get("combat_stats") as ObjectNode
            val combat = objectMapper.createObjectNode()
            val includeCombat = includeSection(entry, "combat_stats", command.confirmReview)
            val includeOddities = includeSection(entry, "oddities", command.confirmReview)
            source.fields().forEach { (field, value) ->
                if ((field == "oddities" && includeOddities) || (field != "oddities" && includeCombat)) {
                    combat.set<JsonNode>(field, value)
                }
            }
            if (command.scanAccountId != null || text(record, "source_kind") == "scan") {
                if (combat.size() > 0) combat.put("source", "scan")
            }
            if (combat.size() > 0) patch.set<JsonNode>("combat_stats", combat)
        }
        return patch
    }

    private fun removeReviewSections(patch: ObjectNode, entry: ObjectNode, all: Boolean) {
        if (all || sectionStatus(entry, "basic") == REVIEW) {
            patch.remove("level")
            patch.remove("elite")
        }
        if (all || sectionStatus(entry, "huaji") == REVIEW) patch.remove("star_level")
        if (all || sectionStatus(entry, "disc_loadouts") == REVIEW) patch.remove("disc_loadouts")
        if (all || sectionStatus(entry, "equipment") == REVIEW) patch.remove("star_stones")
        val combat = patch.get("combat_stats") as? ObjectNode ?: return
        if (all || sectionStatus(entry, "combat_stats") == REVIEW) {
            combat.fieldNames().asSequence().filter { it != "oddities" }.toList().forEach(combat::remove)
        }
        if (all || sectionStatus(entry, "oddities") == REVIEW) combat.remove("oddities")
        if (combat.size() == 0) patch.remove("combat_stats")
    }

    private fun changes(
        patch: ObjectNode,
        before: OperatorCurrentEntryDto?,
        after: OperatorCurrentEntryDto,
    ): Map<String, OperatorV3FieldChange> = buildMap {
        fun add(field: String, old: Any?, new: Any?) {
            if (old != new) put(field, OperatorV3FieldChange(old, new))
        }
        if (patch.has("level")) add("level", before?.level, after.level)
        if (patch.has("elite")) add("elite", before?.elite, after.elite)
        if (patch.has("star_level")) add("star_level", before?.starLevel, after.starLevel)
        if (patch.has("disc_loadouts")) add("disc_loadouts", before?.discLoadouts, after.discLoadouts)
        if (patch.has("star_stones")) add("equipped_star_stones", before?.starStones, after.starStones)
        if (patch.has("combat_stats")) add("combat_stats", before?.combatStats, after.combatStats)
    }

    private fun oddityMaxWarnings(entry: ObjectNode, catalog: OperatorCatalogEntity): List<OperatorV3Issue> {
        val limits = oddityLimits(catalog.rarity)
        val oddities = entry.path("combat_stats").path("oddities")
        if (!oddities.isObject) return emptyList()
        return limits.mapNotNull { (key, maximum) ->
            oddities.path(key).get("max")?.takeIf { it.intValue() != maximum }?.let {
                issue("oddity_max_mismatch", "$key.max differs from the catalog limit", "combat_stats.oddities.$key.max")
            }
        }
    }

    private fun currentRevision(userId: String, accountId: String, game: String, operatorId: String): Long =
        operatorService.current(userId, accountId, game).singleOrNull()?.entries?.get(operatorId)?.revision ?: 0L

    private fun requireOwnedAccount(userId: String, accountId: String): SubAccount =
        accountRepository.findByUserIdAndAccountId(userId, accountId)
            ?: throw OperatorApiException(HttpStatus.FORBIDDEN, "account_scope_mismatch", "Target account is not owned by the current user")

    private fun targetGame(record: ObjectNode, account: SubAccount): String {
        val game = text(record, "game")
        if (game != "universal" && game != account.game) {
            throw OperatorApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "account_game_mismatch",
                "Record game must match the target account game",
                text(record, "record_id"),
            )
        }
        return account.game
    }

    private fun includeSection(entry: ObjectNode, section: String, confirmed: Boolean): Boolean = when (sectionStatus(entry, section)) {
        "unavailable" -> false
        REVIEW -> confirmed
        else -> true
    }

    private fun sectionStatus(entry: ObjectNode, section: String): String = entry.path("section_status").path(section).asText("ready")

    private fun hasReview(entry: ObjectNode): Boolean =
        entry.path("section_status").takeIf(JsonNode::isObject)?.elements()?.asSequence()?.any { it.asText() == REVIEW } == true

    private fun hasPartial(entry: ObjectNode): Boolean =
        entry.path("section_status").takeIf(JsonNode::isObject)?.elements()?.asSequence()?.any { it.asText() == PARTIAL } == true

    private fun accounts(document: ObjectNode): List<ObjectNode> = document.withArray("accounts").map { it as ObjectNode }
    private fun records(document: ObjectNode): List<ObjectNode> = document.withArray("records").map { it as ObjectNode }
    private fun entries(record: ObjectNode): List<ObjectNode> = record.withArray("entries").map { it as ObjectNode }

    private fun text(node: JsonNode, field: String): String = node.path(field).asText()

    private fun parseTimestamp(value: String, field: String, recordId: String? = null) {
        try {
            OffsetDateTime.parse(value)
        } catch (_: Exception) {
            throw OperatorApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "schema_validation_failed",
                "Invalid $field",
                recordId,
                fieldPath = field,
            )
        }
    }

    private fun rejectUnknown(node: ObjectNode, allowed: Set<String>) {
        node.fieldNames().asSequence().firstOrNull { it !in allowed }?.let { schemaError("Unsupported request field: $it") }
    }

    private fun schemaError(message: String, recordId: String? = null): Nothing =
        throw OperatorApiException(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", message, recordId)

    private fun invalid(code: String, message: String, recordId: String, operatorId: String, field: String): Nothing =
        throw OperatorApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, recordId, operatorId, operatorId, field)

    private fun issue(code: String, message: String, field: String? = null) = OperatorV3Issue(code, message, field)

    private fun oddityLimits(rarity: Int): Map<String, Int> = when (rarity) {
        3 -> mapOf("attack" to 300, "hp" to 1560, "special" to 9)
        4 -> mapOf("attack" to 305, "hp" to 1820, "special" to 11)
        5 -> mapOf("attack" to 500, "hp" to 2600, "special" to 15)
        else -> emptyMap()
    }

    private data class ImportCommand(
        val document: ObjectNode,
        val mapping: Map<String, String>,
        val scanAccountId: String?,
        val confirmReview: Boolean,
    )

    private data class PreparedItem(
        val targetAccountId: String,
        val targetGame: String,
        val operatorId: String,
        val patch: ObjectNode?,
        val response: OperatorV3ImportItem,
        val duplicate: Boolean = false,
    )

    companion object {
        private const val ACCEPTED = "accepted"
        private const val PARTIAL = "partial"
        private const val REVIEW = "review"
        private const val REJECTED = "rejected"
        private const val UNCHANGED = "unchanged"
        const val SCAN_EVENT_NAME = "operator_scan_import"
    }
}
