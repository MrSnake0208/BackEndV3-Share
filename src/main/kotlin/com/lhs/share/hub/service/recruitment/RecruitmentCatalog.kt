package com.lhs.share.hub.service.recruitment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lhs.share.hub.controller.recruitment.request.RecruitmentCatalogWriteRequest
import com.lhs.share.hub.controller.recruitment.response.RecruitmentCatalogAdminResponse
import com.lhs.share.hub.repository.OperatorCatalogRepository
import com.lhs.share.hub.repository.RecruitmentCatalogRepository
import com.lhs.share.hub.repository.entity.RecruitmentAgentSnapshot
import com.lhs.share.hub.repository.entity.RecruitmentCatalogPool
import com.lhs.share.hub.repository.entity.RecruitmentEvent
import com.lhs.share.hub.repository.entity.RecruitmentPool
import com.lhs.share.hub.repository.entity.RecruitmentPoolSnapshot
import com.lhs.share.hub.repository.entity.RecruitmentUpAgent
import com.lhs.share.hub.service.account.SubAccountService
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate

@Service
class RecruitmentCatalog(
    private val mapper: ObjectMapper,
    private val repository: RecruitmentCatalogRepository,
    private val operators: OperatorCatalogRepository,
) {
    private val resource: JsonNode by lazy { ClassPathResource("recruitment/pools.json").inputStream.use(mapper::readTree) }
    private val operatorSeed: Map<String, JsonNode> by lazy {
        ClassPathResource("operator/operators.json").inputStream.use(mapper::readTree).associateBy { it.path("id").asText() }
    }
    private val seeds: Map<String, RecruitmentCatalogPool> by lazy {
        resource.path("pools").associate { node ->
            val id = node.path("pool_id").asText()
            val known = node.path("up_agent_ids").map(JsonNode::asText).toSet()
            val slots = node.path("up_agent_names").mapIndexed { index, name ->
                val operatorId = known.firstOrNull { operatorSeed[it]?.path("name")?.asText() == name.asText() }
                RecruitmentUpAgent("$id:up:${index + 1}", name.asText(), operatorId)
            }
            fun date(field: String) = node[field]?.takeUnless { it.isNull }?.asText()?.let(LocalDate::parse)
            id to RecruitmentCatalogPool(
                id,
                node.path("game").asText(),
                node.path("name").asText(),
                date("start_date"),
                date("end_date"),
                node["pool_type"]?.takeUnless { it.isNull }?.asText(),
                upAgents = slots,
                upStatus = node.path("up_status").asText("unknown"),
            )
        }
    }

    fun catalog(game: String): JsonNode {
        requireGame(game)
        return response(all().filter { it.game == game })
    }

    fun listForAdmin(): RecruitmentCatalogAdminResponse {
        val names = operatorNames()
        return RecruitmentCatalogAdminResponse(all().map { it.copy(upAgents = snapshotOf(it, names).upAgents) })
    }

    private fun all(): List<RecruitmentCatalogPool> = (seeds + repository.all().associateBy { it.poolId }).values
        .sortedWith(compareByDescending<RecruitmentCatalogPool> { it.startDate }.thenBy { it.poolId })

    private fun response(pools: List<RecruitmentCatalogPool>): JsonNode {
        val names = operatorNames()
        val items = mapper.createArrayNode()
        pools.forEach { pool ->
            val item = resource.path("pools").firstOrNull { it.path("pool_id").asText() == pool.poolId }
                ?.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>() ?: mapper.createObjectNode()
            val fields = mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(pool)
            item.setAll<JsonNode>(fields)
            val snapshot = snapshotOf(pool, names)
            item.set<JsonNode>("up_agents", mapper.valueToTree(snapshot.upAgents))
            item.set<JsonNode>("up_agent_ids", mapper.valueToTree(snapshot.upAgentIds))
            item.set<JsonNode>("up_agent_names", mapper.valueToTree(snapshot.upAgentNames))
            item.set<JsonNode>("unmapped_up_agent_names", mapper.valueToTree(snapshot.unmappedUpAgentNames))
            items.add(item)
        }
        return mapper.createObjectNode().put(
            "catalog_revision",
            "${resource.path("catalog_revision").asText()}:${pools.sumOf {
                it.revision
            }}:${pools.maxOfOrNull { it.updatedAt }}",
        )
            .set("pools", items)
    }

    fun findPool(game: String, poolId: String): RecruitmentCatalogPool? = (repository.find(poolId) ?: seeds[poolId])?.takeIf {
        it.game ==
            game
    }

    fun snapshot(game: String, poolId: String, requireEnabled: Boolean = true): RecruitmentPoolSnapshot {
        val pool = findPool(game, poolId) ?: throw RecruitmentApiException(
            HttpStatus.NOT_FOUND,
            "recruitment_catalog_pool_not_found",
            "当前游戏没有该管理员卡池，请联系管理员",
        )
        if (requireEnabled && !pool.enabled) throw recruitmentInvalid("该卡池已停用，不能新增记录")
        return snapshotOf(pool)
    }

    private fun snapshotOf(pool: RecruitmentCatalogPool, names: Map<String, String> = emptyMap()): RecruitmentPoolSnapshot {
        val slots = pool.upAgents.map { slot ->
            slot.operatorId?.let { names[it] }?.let { slot.copy(name = it) } ?: slot
        }
        val active = slots.filter { it.active }
        return RecruitmentPoolSnapshot(
            name = pool.name, game = pool.game, catalogPoolId = pool.poolId, startDate = pool.startDate, endDate = pool.endDate,
            upAgentIds = active.mapNotNull { it.operatorId }, upStatus = pool.upStatus,
            upAgentNames = active.map { it.name }, unmappedUpAgentNames = active.filter { it.operatorId == null }.map { it.name },
            catalogRevision = "${resource.path("catalog_revision").asText()}:${pool.revision}",
            poolType = pool.poolType, upAgents = slots,
        )
    }

    fun slot(game: String, poolId: String, id: String, requireActive: Boolean = true): RecruitmentUpAgent? =
        findPool(game, poolId)?.let { pool ->
            val slot = pool.upAgents.firstOrNull { it.id == id } ?: return@let null
            if (requireActive && (!pool.enabled || !slot.active)) throw recruitmentInvalid("该卡池或UP密探已停用，不能新增记录")
            slot
        }

    private fun operatorNames(): Map<String, String> {
        val persisted = operators.findAllByOrderByOperatorIdAsc()
        return if (persisted.isEmpty()) {
            operatorSeed.mapValues { it.value.path("name").asText() }
        } else {
            persisted.associate {
                it.operatorId to
                    it.name
            }
        }
    }

    fun operator(game: String, id: String): RecruitmentAgentSnapshot {
        val persisted = operators.findByOperatorId(id)
        val seed = if (persisted == null && operators.count() == 0L) operatorSeed[id] else null
        val games = persisted?.games ?: seed?.path("games")?.map(JsonNode::asText).orEmpty()
        val value = persisted?.let {
            RecruitmentAgentSnapshot(it.operatorId, it.name, catalogRevision = it.catalogVersion, rarity = it.rarity)
        }
            ?: seed?.let {
                RecruitmentAgentSnapshot(
                    id,
                    it.path("name").asText(),
                    catalogRevision = "operator-seed",
                    rarity = it.path("rarity").asInt(),
                )
            }
        if (value == null) throw recruitmentInvalid("图鉴中没有该密探，请刷新图鉴或联系管理员配置占位UP")
        if (value.rarity != 5 || game !in games) throw recruitmentInvalid("请选择当前游戏可用的绝密密探")
        return value
    }

    fun projectPools(pools: List<RecruitmentPool>): List<RecruitmentPool> {
        if (pools.isEmpty()) return pools
        val latest = all().associateBy { it.poolId }
        val names = operatorNames()
        return pools.map { pool ->
            val reference = pool.mappedSnapshot ?: pool.snapshot
            val snapshot = latest[reference.catalogPoolId]?.takeIf { it.game == reference.game }?.let { snapshotOf(it, names) }
                ?: return@map pool
            if (pool.mappedSnapshot != null) pool.copy(mappedSnapshot = snapshot) else pool.copy(snapshot = snapshot)
        }
    }

    fun projectEvents(events: List<RecruitmentEvent>): List<RecruitmentEvent> {
        if (events.isEmpty()) return events
        val latest = all().associateBy { it.poolId }
        val names = operatorNames()
        return events.map { event ->
            val pool = latest[event.poolSnapshot.catalogPoolId]?.takeIf { it.game == event.poolSnapshot.game } ?: return@map event
            val snapshot = snapshotOf(pool, names)
            val slot = snapshot.upAgents.firstOrNull { it.id == event.agentSnapshot.agentId }
            val name = slot?.name ?: names[event.agentSnapshot.agentId] ?: event.agentSnapshot.name
            event.copy(poolSnapshot = snapshot, agentSnapshot = event.agentSnapshot.copy(name = name))
        }
    }

    fun create(actorUserId: String, input: RecruitmentCatalogWriteRequest): RecruitmentCatalogPool {
        if (repository.find(input.poolId) != null || seeds.containsKey(input.poolId)) throw recruitmentConflict("该卡池ID已存在")
        if (input.expectedRevision != 0L) throw recruitmentConflict("新建卡池的expected_revision须为0")
        return save(actorUserId, input, null)
    }

    fun update(actorUserId: String, poolId: String, input: RecruitmentCatalogWriteRequest): RecruitmentCatalogPool {
        if (poolId != input.poolId) throw recruitmentInvalid("路径与请求的卡池ID必须一致")
        val current = repository.find(poolId) ?: seeds[poolId] ?: throw recruitmentNotFound("catalog_pool")
        if (current.game != input.game) throw recruitmentInvalid("已创建卡池不能修改所属游戏")
        if (current.revision != input.expectedRevision) throw recruitmentConflict("卡池已被其他管理员修改，请刷新后重试")
        return save(actorUserId, input, current)
    }

    private fun save(actorUserId: String, input: RecruitmentCatalogWriteRequest, current: RecruitmentCatalogPool?): RecruitmentCatalogPool {
        requireGame(input.game)
        if (!POOL_ID.matches(input.poolId) || input.expectedRevision < 0 || input.expectedRevision == Long.MAX_VALUE) {
            throw recruitmentInvalid("卡池ID或版本格式不正确")
        }
        val name = validName(input.name)
        if (input.startDate != null && input.endDate != null && input.startDate > input.endDate) throw recruitmentInvalid("开始日期不能晚于结束日期")
        if (input.poolType != null && input.poolType.length > 64) throw recruitmentInvalid("卡池类型不能超过64字")
        if (input.upAgents.size > 500 || input.upAgents.count { it.active } > 120 ||
            input.upAgents.map { it.id }.toSet().size != input.upAgents.size
        ) {
            throw recruitmentInvalid("UP名单最多120个有效项、500个保留项，槽ID不能重复")
        }
        val slots = input.upAgents.map { slot ->
            if (!SLOT_ID.matches(slot.id) || !slot.id.startsWith("${input.poolId}:up:")) throw recruitmentInvalid("UP槽ID必须属于当前卡池且不超过128字")
            val operator = slot.operatorId?.let { operator(input.game, it) }
            slot.copy(name = operator?.name ?: validName(slot.name))
        } + current?.upAgents.orEmpty().filter { old -> input.upAgents.none { it.id == old.id } }.map { it.copy(active = false) }
        val bound = slots.filter { it.active }.mapNotNull { it.operatorId }
        if (bound.toSet().size != bound.size || slots.size > 500) throw recruitmentInvalid("有效UP不能重复选择同一密探，保留槽不能超过500项")
        val next = RecruitmentCatalogPool(
            input.poolId, input.game, name, input.startDate, input.endDate, input.poolType?.trim()?.takeIf { it.isNotEmpty() },
            input.enabled, input.expectedRevision + 1, slots,
            if (slots.any {
                    it.active
                }
            ) {
                "verified"
            } else {
                "unknown"
            },
            actorUserId, Instant.now(),
        )
        try {
            if (!repository.save(next)) throw recruitmentConflict("卡池已变化，请刷新后重试")
        } catch (_: DuplicateKeyException) {
            throw recruitmentConflict("卡池已变化，请刷新后重试")
        }
        return next
    }

    private fun validName(value: String) = value.trim().takeIf { it.length in 1..128 } ?: throw recruitmentInvalid("名称须为1至128字")
    private fun requireGame(game: String) {
        if (game !in SubAccountService.SUPPORTED_GAMES) throw recruitmentInvalid("游戏只支持代号鸢或如鸢")
    }

    private companion object {
        val POOL_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$")
        val SLOT_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    }
}
