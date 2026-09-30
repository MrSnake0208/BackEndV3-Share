package com.lhs.share.hub.service.recruitment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lhs.share.hub.controller.recruitment.request.RecruitmentAgentMap
import com.lhs.share.hub.controller.recruitment.request.RecruitmentBaselineSet
import com.lhs.share.hub.controller.recruitment.request.RecruitmentBatchCreate
import com.lhs.share.hub.controller.recruitment.request.RecruitmentBatchSelect
import com.lhs.share.hub.controller.recruitment.request.RecruitmentEventCreate
import com.lhs.share.hub.controller.recruitment.request.RecruitmentEventInput
import com.lhs.share.hub.controller.recruitment.request.RecruitmentEventReorder
import com.lhs.share.hub.controller.recruitment.request.RecruitmentEventSelect
import com.lhs.share.hub.controller.recruitment.request.RecruitmentEventUpdate
import com.lhs.share.hub.controller.recruitment.request.RecruitmentPoolCreate
import com.lhs.share.hub.controller.recruitment.request.RecruitmentPoolMap
import com.lhs.share.hub.controller.recruitment.request.RecruitmentPoolSelect
import com.lhs.share.hub.controller.recruitment.request.RecruitmentProgressSet
import com.lhs.share.hub.controller.recruitment.request.RecruitmentRequestDecoder
import com.lhs.share.hub.controller.recruitment.request.RecruitmentTemporaryAgentCreate
import com.lhs.share.hub.controller.recruitment.response.RecruitmentCommandResponse
import com.lhs.share.hub.repository.OperatorCatalogRepository
import com.lhs.share.hub.repository.RecruitmentRepository
import com.lhs.share.hub.repository.entity.RecruitmentAgentSnapshot
import com.lhs.share.hub.repository.entity.RecruitmentArchive
import com.lhs.share.hub.repository.entity.RecruitmentBatch
import com.lhs.share.hub.repository.entity.RecruitmentEvent
import com.lhs.share.hub.repository.entity.RecruitmentPool
import com.lhs.share.hub.repository.entity.RecruitmentPoolSnapshot
import com.lhs.share.hub.repository.entity.RecruitmentTemporaryAgent
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** All methods are invoked inside the account-fenced Hub transaction. */
@Service
class RecruitmentMutation(
    private val store: RecruitmentRepository,
    private val operators: OperatorCatalogRepository,
    private val catalog: RecruitmentCatalog,
    mapper: ObjectMapper,
) {
    private val decoder = RecruitmentRequestDecoder(mapper)

    fun apply(current: RecruitmentArchive, operation: String, data: JsonNode, now: Instant): RecruitmentMutationResult {
        if (!data.isObject) throw recruitmentInvalid("操作数据必须是对象")
        var next = current
        var result = RecruitmentCommandResponse(current.archiveRevision + 1)
        when (operation) {
            "pool_create" -> {
                val input = read<RecruitmentPoolCreate>(data)
                if (!data.has("progress")) throw recruitmentInvalid("请明确提供初始进度；未知时填写null")
                input.progress?.let { recruitmentCount(it, "progress") }
                if (current.pools.size >= 500) throw recruitmentInvalid("卡池档案已达500个上限")
                val id = validId(input.poolId ?: newId("pool_"))
                if (current.pools.any { it.poolId == id }) throw conflictId("pool")
                val snapshot = input.catalogPoolId?.let { catalog.snapshot(current.gameSnapshot, it) }
                    ?: RecruitmentPoolSnapshot(validName(input.name ?: throw recruitmentInvalid("请填写名称")), current.gameSnapshot)
                next =
                    current.copy(
                        pools = current.pools + RecruitmentPool(id, snapshot, input.progress),
                        currentPoolId =
                        current.currentPoolId ?: id,
                    )
                result = result.copy(poolId = id)
            }
            "set_current_pool" -> {
                val input = read<RecruitmentPoolSelect>(data)
                pool(current, input.poolId)
                next = current.copy(currentPoolId = input.poolId)
                result = result.copy(poolId = input.poolId)
            }
            "progress_set" -> {
                val input = read<RecruitmentProgressSet>(data)
                if (!data.has("progress")) throw recruitmentInvalid("请提供当前进度；未知时填写null")
                input.progress?.let { recruitmentCount(it, "progress") }
                next = replacePool(current, pool(current, input.poolId).copy(progress = input.progress))
            }
            "baseline_set" -> next = current.copy(baseline = recruitmentCount(read<RecruitmentBaselineSet>(data).baseline, "baseline"))
            "temporary_agent_create" -> {
                val input = read<RecruitmentTemporaryAgentCreate>(data)
                if (current.temporaryAgents.size >= 1000) throw recruitmentInvalid("临时密探已达1000个上限")
                val id = validId(input.agentId ?: newId("tmp_"))
                if (!id.startsWith("tmp_")) throw recruitmentInvalid("临时密探ID必须以tmp_开头")
                if (current.temporaryAgents.any { it.agentId == id }) throw conflictId("agent")
                next = current.copy(temporaryAgents = current.temporaryAgents + RecruitmentTemporaryAgent(id, validName(input.name)))
                result = result.copy(agentId = id)
            }
            "pool_map" -> {
                val input = read<RecruitmentPoolMap>(data)
                val pool = pool(current, input.poolId)
                if (pool.snapshot.catalogPoolId != null) throw recruitmentInvalid("只有临时卡池可以关联公共目录")
                next = replacePool(current, pool.copy(mappedSnapshot = catalog.snapshot(current.gameSnapshot, input.catalogPoolId)))
            }
            "agent_map" -> {
                val input = read<RecruitmentAgentMap>(data)
                val temporary =
                    current.temporaryAgents.firstOrNull { it.agentId == input.agentId } ?: throw recruitmentNotFound("temporary_agent")
                val target = agent(current, input.catalogAgentId)
                if (target.temporary) throw recruitmentInvalid("请选择公共目录中的绝密密探")
                next = current.copy(
                    temporaryAgents = current.temporaryAgents.map {
                        if (it.agentId == temporary.agentId) it.copy(mappedAgentId = target.agentId, mappedName = target.name) else it
                    },
                )
            }
            "event_create" -> {
                val input = read<RecruitmentEventCreate>(data)
                val events = newEvents(current, input.poolId, input.entries, now)
                val count = events.sumOf { it.pullSpan ?: 0 }
                val progress = pool(current, input.poolId).progress
                validateMode(input.mode, input.extractFromBaseline)
                if (input.mode == "current") {
                    if (progress == null) throw recruitmentInvalid("当前进度未知，请先校准进度或改用历史补录")
                    if (events.any { it.pullSpan == null } ||
                        events.first().pullSpan!! <= progress
                    ) {
                        throw recruitmentInvalid("当前出货须填写确切间隔，首条抽数必须大于已记录进度")
                    }
                    if (!data.has("tail_progress")) throw recruitmentInvalid("请提供出货后的尾抽进度；未知时填写null")
                    input.tailProgress?.let { recruitmentCount(it, "tail_progress") }
                    next = replacePool(current, pool(current, input.poolId).copy(progress = input.tailProgress))
                }
                if (input.extractFromBaseline &&
                    events.any { it.pullSpan == null }
                ) {
                    throw recruitmentInvalid("未知间隔不能从历史基准中拆出，请先确认抽数")
                }
                next = extract(next, input.extractFromBaseline, count).copy(nextEventOrder = current.nextEventOrder + events.size)
                events.forEach(store::insertEvent)
                result = result.copy(eventIds = events.map { it.eventId }, poolId = input.poolId)
            }
            "event_update" -> {
                val input = read<RecruitmentEventUpdate>(data)
                val old = liveEvent(current, input.eventId)
                if (input.entry.eventId != null && input.entry.eventId != old.eventId) throw recruitmentInvalid("不能修改记录ID")
                val changed = updateEvent(current, old, input.entry, now)
                old.batchId?.let { id ->
                    val batch = liveBatch(current, id)
                    val sum =
                        store.batchEvents(current.userId, current.accountId, id).filter {
                            it.eventId != old.eventId && it.deletedAt == null
                        }.sumOf {
                            it.pullSpan
                                ?: 0
                        } +
                            (changed.pullSpan ?: 0)
                    if (sum > batch.totalPullCount) throw recruitmentInvalid("已知间隔之和超出批次总抽数，请核对记录")
                }
                store.saveEvent(changed)
                result = result.copy(eventIds = listOf(old.eventId))
            }
            "event_delete", "event_restore" -> {
                val input = read<RecruitmentEventSelect>(data)
                val old = store.event(current.userId, current.accountId, input.eventId) ?: throw recruitmentNotFound("event")
                val restoring = operation == "event_restore"
                if (restoring) {
                    if (old.deletedAt == null ||
                        old.deletedRevision != current.archiveRevision
                    ) {
                        throw recruitmentConflict("撤销已过期，请重新读取档案")
                    }
                    old.batchId?.let { liveBatch(current, it) }
                } else if (old.deletedAt != null) {
                    throw recruitmentNotFound("event")
                }
                store.saveEvent(
                    old.copy(
                        deletedAt = if (restoring) null else now,
                        deletedRevision = if (restoring) {
                            null
                        } else {
                            current.archiveRevision +
                                1
                        },
                        updatedAt = now,
                    ),
                )
                result = result.copy(eventIds = listOf(old.eventId))
            }
            "event_reorder" -> {
                val input = read<RecruitmentEventReorder>(data)
                pool(current, input.poolId)
                if (input.eventIds.size > 20_000 ||
                    input.eventIds.toSet().size != input.eventIds.size
                ) {
                    throw recruitmentInvalid("重排ID不能重复，最多20000条")
                }
                val events = store.poolEvents(current.userId, current.accountId, input.poolId, 20_001)
                if (events.size > 20_000 ||
                    events.map { it.eventId }.toSet() != input.eventIds.toSet()
                ) {
                    throw recruitmentConflict("排序需要当前卡池完整有效记录，请刷新后重试")
                }
                // Preserve this pool's global order slots; reordering cannot change pull counts or other pools.
                val slots = events.map { it.sortOrder }.sorted()
                val byId = events.associateBy { it.eventId }
                input.eventIds.forEachIndexed { index, id ->
                    store.saveEvent(byId.getValue(id).copy(sortOrder = slots[index], updatedAt = now))
                }
                result = result.copy(eventIds = input.eventIds)
            }
            "batch_create" -> {
                val input = read<RecruitmentBatchCreate>(data)
                val total = recruitmentCount(input.totalPullCount, "total_pull_count", true)
                val id = validId(input.batchId ?: newId("batch_"))
                if (store.batch(current.userId, current.accountId, id) != null) throw conflictId("batch")
                val events = newEvents(current, input.poolId, input.entries, now).map { it.copy(batchId = id) }
                if (events.sumOf { it.pullSpan ?: 0 } > total) throw recruitmentInvalid("已知间隔之和超出批次总抽数，请核对记录")
                validateMode(input.mode, input.extractFromBaseline)
                if (input.mode == "current") {
                    val progress =
                        pool(current, input.poolId).progress
                            ?: throw recruitmentInvalid("当前进度未知，请先校准进度或改用历史补录")
                    if (total <= progress) throw recruitmentInvalid("批次抽数必须大于已记录的当前进度")
                    if (!data.has("tail_progress")) throw recruitmentInvalid("请提供出货后的尾抽进度；未知时填写null")
                    input.tailProgress?.let { recruitmentCount(it, "tail_progress") }
                    next = replacePool(current, pool(current, input.poolId).copy(progress = input.tailProgress))
                }
                next = extract(next, input.extractFromBaseline, total).copy(nextEventOrder = current.nextEventOrder + events.size)
                store.insertBatch(RecruitmentBatch(scoped(current, id), current.userId, current.accountId, id, input.poolId, total, now))
                events.forEach(store::insertEvent)
                result = result.copy(eventIds = events.map { it.eventId }, poolId = input.poolId, batchId = id)
            }
            "batch_delete", "batch_restore" -> {
                val input = read<RecruitmentBatchSelect>(data)
                val batch = store.batch(current.userId, current.accountId, input.batchId) ?: throw recruitmentNotFound("batch")
                val restoring = operation == "batch_restore"
                if (restoring) {
                    if (batch.deletedAt == null ||
                        batch.deletedRevision != current.archiveRevision
                    ) {
                        throw recruitmentConflict("批次撤销已过期，请重新读取档案")
                    }
                } else {
                    if (batch.deletedAt != null) throw recruitmentNotFound("batch")
                    if (input.confirmTotalPullCount !=
                        batch.totalPullCount
                    ) {
                        throw recruitmentInvalid("删除前请确认当前批次总抽数")
                    }
                }
                val events = store.batchEvents(current.userId, current.accountId, batch.batchId).filter {
                    if (restoring) it.deletedRevision == batch.deletedRevision else it.deletedAt == null
                }
                events.forEach {
                    store.saveEvent(
                        it.copy(
                            deletedAt = if (restoring) null else now,
                            deletedRevision = if (restoring) {
                                null
                            } else {
                                current.archiveRevision +
                                    1
                            },
                            updatedAt = now,
                        ),
                    )
                }
                store.saveBatch(
                    batch.copy(
                        deletedAt = if (restoring) null else now,
                        deletedRevision = if (restoring) {
                            null
                        } else {
                            current.archiveRevision +
                                1
                        },
                    ),
                )
                result = result.copy(eventIds = events.map { it.eventId }, batchId = batch.batchId)
            }
            else -> throw recruitmentInvalid("不支持该操作")
        }
        return RecruitmentMutationResult(next.copy(archiveRevision = current.archiveRevision + 1, updatedAt = now), result)
    }

    private fun newEvents(
        current: RecruitmentArchive,
        poolId: String,
        entries: List<RecruitmentEventInput>,
        now: Instant,
    ): List<RecruitmentEvent> {
        val pool = pool(current, poolId)
        if (entries.isEmpty() || entries.size > 120) throw recruitmentInvalid("每次请填写1至120条绝密结果")
        val seen = HashSet<String>()
        return entries.mapIndexed { index, entry ->
            val id = validId(entry.eventId ?: newId("event_"))
            if (!seen.add(id) || store.event(current.userId, current.accountId, id) != null) throw conflictId("event")
            val event = RecruitmentEvent(
                scoped(current, id), current.userId, current.accountId, id, poolId,
                pool.mappedSnapshot ?: pool.snapshot,
                agent(current, entry.agentId), entry.pullSpan, current.nextEventOrder + index, createdAt = now,
            )
            updateEvent(current, event, entry, now)
        }
    }

    private fun updateEvent(
        current: RecruitmentArchive,
        old: RecruitmentEvent,
        entry: RecruitmentEventInput,
        now: Instant,
    ): RecruitmentEvent {
        entry.pullSpan?.let { recruitmentCount(it, "pull_span", true) }
        if (entry.upStatus !in setOf("up", "non_up", "unknown")) throw recruitmentInvalid("UP状态只允许up、non_up或unknown")
        if (entry.note != null && entry.note.length > 1000) throw recruitmentInvalid("备注不能超过1000个字符")
        return old.copy(
            agentSnapshot = if (entry.agentId ==
                old.agentSnapshot.agentId
            ) {
                old.agentSnapshot
            } else {
                agent(current, entry.agentId)
            },
            pullSpan = entry.pullSpan,
            upStatus = entry.upStatus,
            acquiredDate = entry.acquiredDate,
            note = entry.note?.trim()?.takeIf {
                it.isNotEmpty()
            },
            updatedAt = now,
        )
    }

    internal fun agent(current: RecruitmentArchive, id: String): RecruitmentAgentSnapshot {
        current.temporaryAgents.firstOrNull {
            it.agentId == id
        }?.let { return RecruitmentAgentSnapshot(it.agentId, it.name, temporary = true) }
        val operator = operators.findByOperatorId(id) ?: throw recruitmentInvalid("目录中没有该密探，请先创建临时密探")
        if (operator.rarity != 5 ||
            current.gameSnapshot !in operator.games
        ) {
            throw recruitmentInvalid("请选择当前游戏可用的绝密密探")
        }
        return RecruitmentAgentSnapshot(operator.operatorId, operator.name, catalogRevision = operator.catalogVersion)
    }

    private fun pool(current: RecruitmentArchive, id: String) =
        current.pools.firstOrNull { it.poolId == id } ?: throw recruitmentNotFound("pool")
    private fun replacePool(current: RecruitmentArchive, next: RecruitmentPool) = current.copy(
        pools = current.pools.map {
            if (it.poolId ==
                next.poolId
            ) {
                next
            } else {
                it
            }
        },
    )
    private fun liveEvent(current: RecruitmentArchive, id: String) =
        store.event(current.userId, current.accountId, id)?.takeIf { it.deletedAt == null } ?: throw recruitmentNotFound("event")
    private fun liveBatch(current: RecruitmentArchive, id: String) =
        store.batch(current.userId, current.accountId, id)?.takeIf { it.deletedAt == null } ?: throw recruitmentNotFound("batch")
    private fun extract(current: RecruitmentArchive, requested: Boolean, count: Long): RecruitmentArchive {
        if (!requested) return current
        if (count <= 0 || current.baseline < count) throw recruitmentInvalid("历史基准余额不足，无法拆出这次已知抽数")
        return current.copy(baseline = current.baseline - count)
    }
    private fun validateMode(mode: String, extract: Boolean) {
        if (mode !in setOf("current", "historical") ||
            mode == "current" && extract
        ) {
            throw recruitmentInvalid("请选择当前出货或历史补录；只有历史补录可从基准拆出")
        }
    }
    private inline fun <reified T> read(data: JsonNode): T = decoder.read(data, T::class.java)
    private fun validName(name: String) = name.trim().takeIf { it.length in 1..128 } ?: throw recruitmentInvalid("名称须为1至128个字符")
    private fun validId(id: String) =
        id.takeIf { Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$").matches(it) } ?: throw recruitmentInvalid("记录ID格式不正确")
    private fun newId(prefix: String) = prefix + UUID.randomUUID().toString()
    private fun scoped(current: RecruitmentArchive, id: String) = "${current.userId}:${current.accountId}:$id"
    private fun conflictId(kind: String) =
        RecruitmentApiException(HttpStatus.CONFLICT, "recruitment_${kind}_id_conflict", "该记录ID已存在（$kind）")
}

data class RecruitmentMutationResult(val archive: RecruitmentArchive, val response: RecruitmentCommandResponse)
