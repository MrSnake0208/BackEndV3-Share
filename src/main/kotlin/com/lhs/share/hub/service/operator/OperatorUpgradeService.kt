package com.lhs.share.hub.service.operator

import com.lhs.share.hub.controller.operator.request.OperatorUpgradeExecuteRequest
import com.lhs.share.hub.controller.operator.request.OperatorUpgradeRequest
import com.lhs.share.hub.controller.operator.response.OperatorUpgradeBlockingReason
import com.lhs.share.hub.controller.operator.response.OperatorUpgradeEvent
import com.lhs.share.hub.controller.operator.response.OperatorUpgradeExecuteResponse
import com.lhs.share.hub.controller.operator.response.OperatorUpgradePreviewResponse
import com.lhs.share.hub.controller.operator.response.OperatorUpgradeRequirement
import com.lhs.share.hub.repository.InventoryCurrentRepository
import com.lhs.share.hub.repository.InventoryRecordRepository
import com.lhs.share.hub.repository.InventoryRevisionRepository
import com.lhs.share.hub.repository.OperatorCurrentRepository
import com.lhs.share.hub.repository.OperatorUpgradeTransactionRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.InventoryRecord
import com.lhs.share.hub.repository.entity.InventoryRevision
import com.lhs.share.hub.repository.entity.OperatorEntry
import com.lhs.share.hub.repository.entity.OperatorUpgradeTransaction
import com.lhs.share.hub.repository.entity.ProducerInfo
import com.lhs.share.hub.repository.entity.RecordEntry
import com.lhs.share.hub.repository.entity.UpgradeConsumedEntry
import com.lhs.share.hub.repository.entity.normalized
import com.lhs.share.hub.service.account.AccountEventService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class OperatorUpgradeService(
    private val accountRepository: SubAccountRepository,
    private val catalogService: OperatorCatalogService,
    private val operatorCurrentRepository: OperatorCurrentRepository,
    private val inventoryCurrentRepository: InventoryCurrentRepository,
    private val inventoryRecordRepository: InventoryRecordRepository,
    private val inventoryRevisionRepository: InventoryRevisionRepository,
    private val transactionRepository: OperatorUpgradeTransactionRepository,
    private val accountEventService: AccountEventService,
    @param:Qualifier("hubTransactionTemplate") private val transactionTemplate: TransactionTemplate,
) {
    private val previews = ConcurrentHashMap<String, PreviewBinding>()

    fun preview(userId: String, request: OperatorUpgradeRequest): OperatorUpgradePreviewResponse {
        val calculation = calculate(userId, request, enforceExpectedOperatorRevision = true)
        val token = "upgrade_preview_${UUID.randomUUID().toString().replace("-", "")}"
        val expiresAt = Instant.now().plus(PREVIEW_SECONDS, ChronoUnit.SECONDS)
        previews.entries.removeIf { it.value.expiresAt.isBefore(Instant.now()) }
        previews[token] = PreviewBinding(userId, request, calculation.inventoryRevision, expiresAt)
        return calculation.response(token, expiresAt)
    }

    fun execute(userId: String, idempotencyKey: String, request: OperatorUpgradeExecuteRequest): OperatorUpgradeExecuteResponse {
        if (idempotencyKey.isBlank() || idempotencyKey.length > 128) invalid("Idempotency-Key must contain 1..128 characters")
        requireAccount(userId, request.accountId)
        val identity = identity(request)
        transactionRepository.findByUserIdAndAccountIdAndIdempotencyKey(userId, request.accountId, idempotencyKey)?.let {
            if (it.requestIdentity != identity) conflict("idempotency_conflict", "Idempotency-Key was used for another request")
            return OperatorUpgradeExecuteResponse.of(it)
        }
        val binding = previews[request.previewToken]
        if (binding == null || !binding.matches(userId, request) || !binding.expiresAt.isAfter(Instant.now())) {
            invalid("preview_expired", "Preview token is expired or does not match this request")
        }
        if (binding.inventoryRevision != request.expectedInventoryRevision) staleInventory()

        val transactionId = "upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        var stored: OperatorUpgradeTransaction? = null
        try {
            transactionTemplate.executeWithoutResult {
                transactionRepository.findByUserIdAndAccountIdAndIdempotencyKey(userId, request.accountId, idempotencyKey)?.let {
                    if (it.requestIdentity != identity) conflict("idempotency_conflict", "Idempotency-Key was used for another request")
                    stored = it
                    return@executeWithoutResult
                }
                val calculation = calculate(userId, request.previewRequest(), enforceExpectedOperatorRevision = true)
                if (calculation.inventoryRevision != request.expectedInventoryRevision) staleInventory()
                if (!calculation.available) {
                    conflict("insufficient_inventory", "Inventory is insufficient for this upgrade")
                }
                val now = Instant.now()
                val consumed = consume(userId, request.accountId, calculation.requirements)
                val nextEntry = upgradedEntry(calculation.entry, request.dimension, request.target, now)
                val savedCurrent = operatorCurrentRepository.compareAndSetEntries(
                    userId,
                    request.accountId,
                    request.game,
                    request.operatorId,
                    request.expectedOperatorRevision,
                    mapOf(request.operatorId to nextEntry),
                    now,
                ) ?: staleOperator()
                val savedEntry = savedCurrent.entries.getValue(request.operatorId)
                writeConsumptionRecords(userId, request.accountId, transactionId, consumed, now)
                val nextInventoryRevision = request.expectedInventoryRevision + 1
                inventoryRevisionRepository.save(
                    InventoryRevision(
                        id = revisionKey(userId, request.accountId),
                        userId = userId,
                        accountId = request.accountId,
                        revision = nextInventoryRevision,
                        updatedAt = now,
                    ),
                )
                stored = transactionRepository.save(
                    OperatorUpgradeTransaction(
                        id = transactionId,
                        userId = userId,
                        accountId = request.accountId,
                        idempotencyKey = idempotencyKey,
                        requestIdentity = identity,
                        operatorId = request.operatorId,
                        dimension = request.dimension,
                        from = calculation.from,
                        to = request.target,
                        operatorLevel = savedEntry.level,
                        operatorElite = savedEntry.elite,
                        operatorStarLevel = savedEntry.starLevel,
                        operatorRevision = savedEntry.revision,
                        consumed = consumed,
                        inventoryRevision = nextInventoryRevision,
                        createdAt = now,
                    ),
                )
            }
        } catch (exception: DuplicateKeyException) {
            transactionRepository.findByUserIdAndAccountIdAndIdempotencyKey(userId, request.accountId, idempotencyKey)?.let {
                if (it.requestIdentity != identity) conflict("idempotency_conflict", "Idempotency-Key was used for another request")
                return OperatorUpgradeExecuteResponse.of(it)
            }
            staleInventory()
        } catch (exception: DataAccessException) {
            staleInventory()
        }
        val result = checkNotNull(stored)
        previews.remove(request.previewToken)
        accountEventService.publish(
            userId,
            request.accountId,
            UPGRADE_EVENT_NAME,
            result.id,
            OperatorUpgradeEvent(
                accountId = result.accountId,
                transactionId = result.id,
                operatorId = result.operatorId,
                dimension = result.dimension,
                from = result.from,
                to = result.to,
                consumed = result.consumed,
                operatorRevision = result.operatorRevision,
                inventoryRevision = result.inventoryRevision,
                occurredAt = result.createdAt,
            ),
        )
        return OperatorUpgradeExecuteResponse.of(result)
    }

    private fun calculate(userId: String, request: OperatorUpgradeRequest, enforceExpectedOperatorRevision: Boolean): Calculation {
        val account = requireAccount(userId, request.accountId)
        if (request.game != account.game) invalid("invalid_upgrade_target", "game must match the subaccount game")
        val catalog = catalogService.getOperator(request.operatorId)
            ?: throw OperatorApiException(HttpStatus.NOT_FOUND, "operator_not_found", "Operator not found", operatorId = request.operatorId)
        if (request.game !in catalog.games) invalid("invalid_upgrade_target", "Operator is not available in this game")
        val current = operatorCurrentRepository.findByUserIdAndAccountIdAndGame(userId, request.accountId, request.game)
        val entry = current?.entries?.get(request.operatorId)?.normalized()
            ?: throw OperatorApiException(
                HttpStatus.NOT_FOUND,
                "operator_not_found",
                "Operator current state not found",
                operatorId = request.operatorId,
            )
        if (enforceExpectedOperatorRevision && entry.revision != request.expectedOperatorRevision) staleOperator()
        val from = when (request.dimension) {
            LEVEL -> entry.level
            ELITE -> entry.elite
            HUAJI -> entry.starLevel
            else -> invalid("invalid_upgrade_target", "dimension must be level, elite, or huaji")
        }
        val max = when (request.dimension) {
            LEVEL -> 100
            ELITE -> 17
            else -> if (catalog.spOf == null) 31 else 5
        }
        if (request.target !in (from + 1)..max) {
            val code = if (request.dimension == HUAJI) "invalid_star_level" else "invalid_upgrade_target"
            invalid(code, "target must be greater than current and no greater than $max")
        }
        if (request.dimension == ELITE) {
            val allowed = OperatorGrowthRules.maxEliteForLevel(entry.level)
            if (request.target > allowed) invalid("invalid_upgrade_target", "elite target exceeds the current level limit")
        }
        val baseCost = when (request.dimension) {
            LEVEL -> OperatorRequirementRules.level(from, request.target, catalog, request.skipBreakthroughMaterials)
            ELITE -> OperatorRequirementRules.elite(from, request.target, catalog)
            else -> OperatorRequirementRules.huaji(from, request.target)
        }
        val itemCurrent = inventoryCurrentRepository.findByUserIdAndAccountIdAndEntityType(userId, request.accountId, ITEM)
        val agentCurrent = inventoryCurrentRepository.findByUserIdAndAccountIdAndEntityType(userId, request.accountId, AGENT)
        val itemStock = itemCurrent?.entries.orEmpty().mapValues { it.value.count }
        val requirements = linkedMapOf<Pair<String, String>, Long>()
        baseCost.items.forEach { (id, count) -> requirements[ITEM to id] = count }
        if (baseCost.heart > 0) requirements[AGENT to request.operatorId] = baseCost.heart
        var suppliedExperience: Long? = null
        if (baseCost.experience > 0) {
            val books = OperatorRequirementRules.chooseBooks(baseCost.experience, itemStock)
            books?.items?.forEach { (id, count) -> requirements[ITEM to id] = count }
            suppliedExperience = books?.suppliedExperience
        }
        val rows = requirements.map { (key, required) ->
            val owned = if (key.first == ITEM) itemStock[key.second] ?: 0 else agentCurrent?.entries?.get(key.second)?.count ?: 0
            OperatorUpgradeRequirement(key.first, key.second, required, owned, owned - required)
        }
        val reasons = mutableListOf<OperatorUpgradeBlockingReason>()
        if (baseCost.experience > 0 && suppliedExperience == null) {
            reasons += OperatorUpgradeBlockingReason("insufficient_inventory", "Not enough operator experience books")
        }
        if (rows.any { it.balanceAfter < 0 }) {
            reasons += OperatorUpgradeBlockingReason("insufficient_inventory", "Required item or heart-paper inventory is insufficient")
        }
        val revision = inventoryRevisionRepository.findByUserIdAndAccountId(userId, request.accountId)?.revision ?: 0
        return Calculation(
            request,
            entry,
            from,
            rows,
            reasons.isEmpty(),
            reasons.distinctBy { it.code to it.message },
            revision,
            baseCost.experience.takeIf { it > 0 },
            suppliedExperience?.minus(baseCost.experience),
            baseCost.money,
        )
    }

    private fun consume(userId: String, accountId: String, requirements: List<OperatorUpgradeRequirement>): List<UpgradeConsumedEntry> {
        val byType = requirements.groupBy { it.entityType }
        return requirements.map { row ->
            val current = inventoryCurrentRepository.findByUserIdAndAccountIdAndEntityType(userId, accountId, row.entityType)
                ?: conflict("insufficient_inventory", "Inventory is insufficient")
            val old = current.entries[row.id] ?: conflict("insufficient_inventory", "Inventory is insufficient")
            if (old.count < row.required) conflict("insufficient_inventory", "Inventory is insufficient")
            UpgradeConsumedEntry(row.entityType, row.id, row.required, old.count - row.required)
        }.also { consumed ->
            byType.forEach { (entityType, _) ->
                val current = checkNotNull(inventoryCurrentRepository.findByUserIdAndAccountIdAndEntityType(userId, accountId, entityType))
                val entries = current.entries.toMutableMap()
                consumed.filter { it.entityType == entityType }.forEach { row ->
                    val old = checkNotNull(entries[row.id])
                    entries[row.id] = old.copy(count = row.balanceAfter)
                }
                inventoryCurrentRepository.save(current.copy(entries = entries, updatedAt = Instant.now()))
            }
        }
    }

    private fun writeConsumptionRecords(
        userId: String,
        accountId: String,
        transactionId: String,
        consumed: List<UpgradeConsumedEntry>,
        now: Instant,
    ) {
        consumed.groupBy { it.entityType }.forEach { (entityType, entries) ->
            inventoryRecordRepository.save(
                InventoryRecord(
                    recordId = "upgrade:$transactionId:$entityType",
                    userId = userId,
                    accountId = accountId,
                    recordType = CONSUMPTION_DELTA,
                    entityType = entityType,
                    acquisitionChannel = "密探养成",
                    effectiveAt = now,
                    producer = ProducerInfo("myshare", "upgrade-v1"),
                    entries = entries.map { RecordEntry(it.id, count = it.count) },
                    stockEffect = "applied",
                    transactionId = transactionId,
                ),
            )
        }
    }

    private fun upgradedEntry(entry: OperatorEntry, dimension: String, target: Int, now: Instant): OperatorEntry {
        val staleStats = entry.combatStats?.let { stats ->
            if (stats.observedAttack != null || stats.observedHp != null) stats.copy(observedStatus = "stale") else stats
        }
        return when (dimension) {
            LEVEL -> entry.copy(level = target, combatStats = staleStats, revision = entry.revision + 1, updatedAt = now)
            ELITE -> entry.copy(elite = target, combatStats = staleStats, revision = entry.revision + 1, updatedAt = now)
            else -> entry.copy(starLevel = target, combatStats = staleStats, revision = entry.revision + 1, updatedAt = now)
        }
    }

    private fun requireAccount(userId: String, accountId: String) = accountRepository.findByUserIdAndAccountId(userId, accountId)
        ?: throw OperatorApiException(HttpStatus.NOT_FOUND, "account_not_found", "Account not found")

    private fun identity(request: OperatorUpgradeExecuteRequest) = listOf(
        request.accountId,
        request.game,
        request.operatorId,
        request.dimension,
        request.target,
        request.expectedOperatorRevision,
        request.expectedInventoryRevision,
        request.previewToken,
    ).joinToString("|") + if (request.skipBreakthroughMaterials) "|skip_breakthrough_materials" else ""

    private fun staleOperator(): Nothing = conflict("operator_state_stale", "Operator state has changed")
    private fun staleInventory(): Nothing = conflict("inventory_state_stale", "Inventory state has changed")
    private fun conflict(code: String, message: String): Nothing = throw OperatorApiException(HttpStatus.CONFLICT, code, message)
    private fun invalid(message: String): Nothing = invalid("invalid_upgrade_target", message)
    private fun invalid(code: String, message: String): Nothing = throw OperatorApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message)
    private fun revisionKey(userId: String, accountId: String) = "$userId:$accountId"

    private data class PreviewBinding(
        val userId: String,
        val request: OperatorUpgradeRequest,
        val inventoryRevision: Long,
        val expiresAt: Instant,
    ) {
        fun matches(userId: String, execute: OperatorUpgradeExecuteRequest) =
            this.userId == userId && request == execute.previewRequest() && inventoryRevision == execute.expectedInventoryRevision
    }

    private data class Calculation(
        val request: OperatorUpgradeRequest,
        val entry: OperatorEntry,
        val from: Int,
        val requirements: List<OperatorUpgradeRequirement>,
        val available: Boolean,
        val reasons: List<OperatorUpgradeBlockingReason>,
        val inventoryRevision: Long,
        val experienceRequired: Long?,
        val experienceOverflow: Long?,
        val moneyRequired: Long,
    ) {
        fun response(token: String, expiresAt: Instant) = OperatorUpgradePreviewResponse(
            available,
            request.dimension,
            from,
            request.target,
            requirements,
            experienceRequired,
            experienceOverflow,
            moneyRequired,
            reasons,
            entry.revision,
            inventoryRevision,
            token,
            expiresAt,
        )
    }

    companion object {
        const val UPGRADE_EVENT_NAME = "operator-upgrade"
        private const val PREVIEW_SECONDS = 60L
        private const val LEVEL = "level"
        private const val ELITE = "elite"
        private const val HUAJI = "huaji"
        private const val ITEM = "item"
        private const val AGENT = "agent"
        const val CONSUMPTION_DELTA = "consumption_delta"
    }
}
