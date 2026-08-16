package com.lhs.share.hub.service.inventory

import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.hub.controller.inventory.request.InventoryImportRequest
import com.lhs.share.hub.controller.inventory.request.InventoryRecordRequest
import com.lhs.share.hub.controller.inventory.response.InventoryAcquiredResponse
import com.lhs.share.hub.controller.inventory.response.InventoryCurrentResponse
import com.lhs.share.hub.controller.inventory.response.InventoryExportEntryDto
import com.lhs.share.hub.controller.inventory.response.InventoryExportRecordDto
import com.lhs.share.hub.controller.inventory.response.InventoryExportResponse
import com.lhs.share.hub.controller.inventory.response.InventoryImportResult
import com.lhs.share.hub.controller.inventory.response.InventoryRecordListItemDto
import com.lhs.share.hub.repository.InventoryCurrentRepository
import com.lhs.share.hub.repository.InventoryRecordRepository
import com.lhs.share.hub.repository.entity.InventoryCurrent
import com.lhs.share.hub.repository.entity.InventoryRecord
import com.lhs.share.hub.repository.entity.ProducerInfo
import com.lhs.share.hub.repository.entity.RecordEntry
import com.lhs.share.hub.repository.entity.StockEntry
import io.github.oshai.kotlinlogging.KotlinLogging
import org.bson.Document
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant

private val log = KotlinLogging.logger { }

/**
 * 库存与奖励服务(HubBackend.inventory_current / inventory_records)
 *
 * 系统只维护两类事实:current_stock(可被背包快照覆盖的绝对库存)与
 * reward_delta(奖励增量流水,仅用于幂等、延迟上报与历史统计)。
 *
 * 写入顺序与一致性(standalone MongoDB 无事务):
 * 1. 幂等键 (userId, recordId) 由唯一索引保证,并发重复触发 DuplicateKeyException,
 *    捕获兜底后按「已存在同正文=duplicate、不同正文=conflict」处理;
 * 2. 幂等校验/冲突判定在前,随后先 insert inventory_records,再对 inventory_current
 *    做单文档更新(\$inc 或快照覆盖);单文档更新原子,跨 collection 以「先流水后库存」
 *    顺序保证不丢流水(即便库存更新失败,流水仍在,重复导入可重建)。
 */
@Service
class InventoryService(
    private val currentRepository: InventoryCurrentRepository,
    private val recordRepository: InventoryRecordRepository,
    private val catalogService: EntityCatalogService,
    @Qualifier("hubMongoTemplate") private val hubMongoTemplate: MongoTemplate,
) {
    // ==================== import ====================

    /**
     * 导入整份交换文档:先完整校验,再按 effective_at 升序应用(同时间奖励先于快照)。
     *
     * 整体校验失败抛异常(整份拒绝,不部分写入);单条幂等冲突抛 409,
     * 重复(同正文)计入 duplicates 不算失败。
     */
    fun import(userId: String, request: InventoryImportRequest): InventoryImportResult {
        // 1. 协议版本校验
        if (request.version != SUPPORTED_VERSION) {
            throw ApiResultException(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "unsupported_version: ${request.version}",
            )
        }

        // 2. 完整校验整份文档(顺序:枚举 → 排序 → 逐条枚举/catalog/entries)
        val ordered = validateAndSort(request)

        // 3. 逐条应用
        var accepted = 0
        var duplicates = 0
        var historyOnly = 0
        var superseded = 0
        val warnings = mutableListOf<String>()

        ordered.forEach { record ->
            when (val effect = applyRecord(userId, request.producer, record)) {
                Effect.DUPLICATE -> duplicates++
                Effect.HISTORY_ONLY -> {
                    historyOnly++
                    accepted++
                }
                Effect.SUPERSEDED -> {
                    superseded++
                    accepted++
                }
                Effect.APPLIED -> accepted++
            }
        }

        return InventoryImportResult(
            accepted = accepted,
            duplicates = duplicates,
            historyOnly = historyOnly,
            superseded = superseded,
            warnings = warnings,
        )
    }

    /**
     * 校验并排序:枚举/catalog/entries 唯一性/count 范围/时间解析,全通过后按
     * effective_at 升序(同时间 reward_delta 优先,让快照成为同一时间的最终权威)。
     */
    private fun validateAndSort(request: InventoryImportRequest): List<ValidatedRecord> {
        val records = request.records
        records.forEach { record ->
            validateEnums(record)
            validateEntries(record)
        }
        return records
            .map { record ->
                val effectiveAt = parseInstant(record.effectiveAt, "effective_at")
                ValidatedRecord(record, effectiveAt)
            }
            .sortedWith(compareBy<ValidatedRecord> { it.effectiveAt }.thenBy { if (it.record.recordType == REWARD_DELTA) 0 else 1 })
    }

    /**
     * 枚举校验:record_type / entity_type / snapshot_scope 取值与携带约束。
     */
    private fun validateEnums(record: InventoryRecordRequest) {
        if (record.recordType != REWARD_DELTA && record.recordType != STOCK_SNAPSHOT) {
            throw ApiResultException(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "schema_validation_failed: record_type 非法 ${record.recordType}",
            )
        }
        if (record.entityType != ENTITY_ITEM && record.entityType != ENTITY_AGENT) {
            throw ApiResultException(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "schema_validation_failed: entity_type 非法 ${record.entityType}",
            )
        }
        if (record.recordType == STOCK_SNAPSHOT) {
            if (record.snapshotScope != SNAPSHOT_FULL && record.snapshotScope != SNAPSHOT_LISTED) {
                throw ApiResultException(
                    HttpStatus.UNPROCESSABLE_ENTITY.value(),
                    "schema_validation_failed: snapshot_scope 非法 ${record.snapshotScope}",
                )
            }
        } else if (record.snapshotScope != null) {
            throw ApiResultException(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "schema_validation_failed: reward_delta 不得携带 snapshot_scope",
            )
        }
    }

    /**
     * entries 校验:同一 record 内 id 唯一;(entity_type, id) 存在于目录;
     * count 范围由记录类型决定(reward_delta 1..MAX, snapshot 0..MAX)。
     */
    private fun validateEntries(record: InventoryRecordRequest) {
        val seen = mutableSetOf<String>()
        record.entries.forEach { entry ->
            if (!seen.add(entry.id)) {
                throw ApiResultException(
                    HttpStatus.UNPROCESSABLE_ENTITY.value(),
                    "schema_validation_failed: entries 内 id 重复 ${entry.id}",
                )
            }
            if (!catalogService.exists(record.entityType, entry.id)) {
                throw ApiResultException(
                    HttpStatus.UNPROCESSABLE_ENTITY.value(),
                    "unknown_entity_id: (${record.entityType}, ${entry.id})",
                )
            }
            if (record.recordType == REWARD_DELTA && entry.count < 1) {
                throw ApiResultException(
                    HttpStatus.UNPROCESSABLE_ENTITY.value(),
                    "schema_validation_failed: reward_delta count 必须 >= 1 (${entry.id})",
                )
            }
            if (entry.count < 0 || entry.count > MAX_COUNT) {
                throw ApiResultException(
                    HttpStatus.UNPROCESSABLE_ENTITY.value(),
                    "schema_validation_failed: count 超出范围 (${entry.id})",
                )
            }
        }
    }

    /**
     * 应用单条记录:先幂等检查,再按类型分派。
     * 返回该记录产生的效果(供 accepted/duplicates/history_only/superseded 统计)。
     */
    private fun applyRecord(
        userId: String,
        producer: com.lhs.share.hub.controller.inventory.request.ProducerDto,
        validated: ValidatedRecord,
    ): Effect {
        val record = validated.record

        // 幂等检查:同 (userId, recordId) 已存在 → duplicate 或 conflict
        val existing = recordRepository.findByUserIdAndRecordId(userId, record.recordId)
        if (existing != null) {
            if (sameBody(existing, record, producer)) {
                return Effect.DUPLICATE
            }
            throw ApiResultException(
                HttpStatus.CONFLICT.value(),
                "record_conflict: ${record.recordId}",
            )
        }

        val entity = InventoryRecord(
            recordId = record.recordId,
            userId = userId,
            recordType = record.recordType,
            entityType = record.entityType,
            acquisitionChannel = record.acquisitionChannel,
            snapshotScope = record.snapshotScope,
            effectiveAt = validated.effectiveAt,
            producer = ProducerInfo(platform = producer.platform, version = producer.version),
            entries = record.entries.map { RecordEntry(id = it.id, name = it.name, count = it.count) },
            stockEffect = "applied",
        )

        return when (record.recordType) {
            REWARD_DELTA -> applyRewardDelta(userId, entity)
            STOCK_SNAPSHOT -> applyStockSnapshot(userId, entity)
            else -> throw IllegalStateException("已校验,不应到达")
        }
    }

    /**
     * 判断已存在记录与新记录是否「正文相同」(协议:直接比较业务字段)。
     */
    private fun sameBody(
        existing: InventoryRecord,
        newReq: InventoryRecordRequest,
        producer: com.lhs.share.hub.controller.inventory.request.ProducerDto,
    ): Boolean {
        if (existing.recordType != newReq.recordType ||
            existing.entityType != newReq.entityType ||
            existing.acquisitionChannel != newReq.acquisitionChannel ||
            existing.snapshotScope != newReq.snapshotScope ||
            !existing.effectiveAt.equals(parseInstant(newReq.effectiveAt, "effective_at"))
        ) {
            return false
        }
        if (existing.producer.platform != producer.platform) return false
        if (existing.entries.size != newReq.entries.size) return false
        val existingById = existing.entries.associateBy { it.id }
        return newReq.entries.all { e ->
            val e2 = existingById[e.id] ?: return@all false
            e2.count == e.count && e2.name == e.name
        }
    }

    /**
     * 奖励增量:先计算效果(基线比较),再落流水,最后 \$inc 当前库存。
     *
     * 流水须保留完整 entries(历史统计聚合不区分 stock_effect);
     * stock_effect 为记录级标记:存在任一 applied 条目 → applied,否则 history_only。
     * 幂等唯一索引兜底:插入流水时若并发已插入同 (userId, recordId) 会抛
     * DuplicateKeyException,捕获后按 duplicate 处理(不二次 \$inc)。
     */
    private fun applyRewardDelta(userId: String, entity: InventoryRecord): Effect {
        val comp = computeReward(userId, entity)
        val stored = entity.copy(stockEffect = comp.effect.stock())
        try {
            recordRepository.save(stored)
        } catch (e: DuplicateKeyException) {
            log.warn(e) { "并发重复 record_id,按重复处理: userId=$userId, recordId=${entity.recordId}" }
            return Effect.DUPLICATE
        }
        applyRewardToCurrent(userId, entity.entityType, comp)
        return comp.effect
    }

    /**
     * 计算奖励增量效果(纯计算,不写库):对每个 entry 比较其有效基线
     * max(full_baseline_at, listed_baseline_at),晚于基线 → applied。
     */
    private fun computeReward(userId: String, entity: InventoryRecord): RewardComputation {
        val current = currentRepository.findByUserIdAndEntityType(userId, entity.entityType)
        val fullBaseline = current?.fullBaselineAt
        val listedBaselines = current?.entries?.mapValues { it.value.listedBaselineAt } ?: emptyMap()

        // 计算每个 entry 是否生效(晚于基线)
        val appliedEntries = entity.entries.filter { entry ->
            val baseline = maxOfNotNull(fullBaseline, listedBaselines[entry.id])
            baseline == null || entity.effectiveAt.isAfter(baseline)
        }
        return RewardComputation(
            effect = if (appliedEntries.isEmpty()) Effect.HISTORY_ONLY else Effect.APPLIED,
            appliedEntries = appliedEntries,
        )
    }

    /**
     * 把奖励增量效果应用到当前库存(applied 的 entry \$inc)。
     */
    private fun applyRewardToCurrent(userId: String, entityType: String, comp: RewardComputation) {
        if (comp.effect == Effect.APPLIED) {
            comp.appliedEntries.forEach { entry ->
                incCurrent(userId, entityType, entry.id, entry.count)
            }
        }
    }

    /**
     * \$inc 当前库存某个对象的 count,并 touch updated_at;文档不存在时 upsert。
     * 注意不改变 full_baseline_at / listed_baseline_at。
     */
    private fun incCurrent(userId: String, entityType: String, entityId: String, delta: Long) {
        val query = Query.query(
            Criteria.where("userId").`is`(userId).and("entityType").`is`(entityType),
        )
        val update = Update()
            .inc("entries.$entityId.count", delta)
            .set("updatedAt", Instant.now())
            .setOnInsert("userId", userId)
            .setOnInsert("entityType", entityType)
        hubMongoTemplate.upsert(query, update, InventoryCurrent::class.java, "inventory_current")
    }

    /**
     * 库存快照:先计算效果(基线比较),再落流水,最后应用快照。
     */
    private fun applyStockSnapshot(userId: String, entity: InventoryRecord): Effect {
        val comp = computeSnapshot(userId, entity)
        val stored = entity.copy(stockEffect = comp.effect.stock())
        try {
            recordRepository.save(stored)
        } catch (e: DuplicateKeyException) {
            log.warn(e) { "并发重复 record_id,按重复处理: userId=$userId, recordId=${entity.recordId}" }
            return Effect.DUPLICATE
        }
        if (comp.effect == Effect.APPLIED) {
            applySnapshotToCurrent(userId, entity)
        }
        return comp.effect
    }

    /**
     * 计算快照效果(纯计算,不写库):full 快照不早于现有 full 基线才生效;
     * listed 快照在所有列出对象的 listed 基线都晚于本快照时整条 superseded。
     */
    private fun computeSnapshot(userId: String, entity: InventoryRecord): SnapshotComputation {
        val current = currentRepository.findByUserIdAndEntityType(userId, entity.entityType)
        val existingFullBaseline = current?.fullBaselineAt
        val isFull = entity.snapshotScope == SNAPSHOT_FULL
        val effective = entity.effectiveAt

        val superseded = when {
            isFull -> existingFullBaseline != null && !effective.isAfter(existingFullBaseline)
            else -> {
                val baselines = current?.entries?.mapValues { it.value.listedBaselineAt } ?: emptyMap()
                entity.entries.all { entry ->
                    val b = baselines[entry.id]
                    b != null && !effective.isAfter(b)
                }
            }
        }
        return SnapshotComputation(if (superseded) Effect.SUPERSEDED else Effect.APPLIED)
    }

    /**
     * 把快照效果应用到当前库存。
     */
    private fun applySnapshotToCurrent(userId: String, entity: InventoryRecord) {
        if (entity.snapshotScope == SNAPSHOT_FULL) {
            applyFullSnapshot(userId, entity)
        } else {
            applyListedSnapshot(userId, entity)
        }
    }

    /**
     * full 快照:替换整个 entries(未列出归零)并更新 full_baseline_at;
     * 对拥有更晚 listed_baseline_at 的对象保留其更晚的局部值(item 粒度保留)。
     */
    private fun applyFullSnapshot(userId: String, entity: InventoryRecord) {
        val current = currentRepository.findByUserIdAndEntityType(userId, entity.entityType)
        val effective = entity.effectiveAt

        // 构建新 entries:以快照值为准;但保留更晚 listed 基线覆盖的 item(避免旧 full 覆盖新局部读取)
        val snapshotEntries = entity.entries.associate { it.id to StockEntry(count = it.count, listedBaselineAt = null) }
        val merged = LinkedHashMap<String, StockEntry>()
        // 快照列出的对象使用快照值(未列出即归零)
        snapshotEntries.forEach { (id, se) -> merged[id] = se }
        // 若存在更晚 listed 覆盖的对象(未出现在 full 中但 listed_baseline_at 晚于本快照),保留其值
        current?.entries?.forEach { (id, se) ->
            if (se.listedBaselineAt != null && se.listedBaselineAt.isAfter(effective) && !merged.containsKey(id)) {
                merged[id] = se
            }
        }

        val query = Query.query(
            Criteria.where("userId").`is`(userId).and("entityType").`is`(entity.entityType),
        )
        val update = Update()
            .set("fullBaselineAt", effective)
            .set("entries", merged)
            .set("updatedAt", Instant.now())
            .setOnInsert("userId", userId)
            .setOnInsert("entityType", entity.entityType)
        hubMongoTemplate.upsert(query, update, InventoryCurrent::class.java, "inventory_current")
    }

    /**
     * listed 快照:只覆盖列出的对象并更新其 listed_baseline_at,未列出对象不变。
     */
    private fun applyListedSnapshot(userId: String, entity: InventoryRecord) {
        val query = Query.query(
            Criteria.where("userId").`is`(userId).and("entityType").`is`(entity.entityType),
        )
        val update = Update().set("updatedAt", Instant.now())
            .setOnInsert("userId", userId)
            .setOnInsert("entityType", entity.entityType)
        entity.entries.forEach { entry ->
            update
                .set("entries.${entry.id}.count", entry.count)
                .set("entries.${entry.id}.listedBaselineAt", entity.effectiveAt)
        }
        hubMongoTemplate.upsert(query, update, InventoryCurrent::class.java, "inventory_current")
    }

    // ==================== 记录管理(删除/重放/列表) ====================

    /**
     * 删除单条记录并全量重放:语义等价于「该记录从未导入过」。
     *
     * 步骤:
     * 1. 归属校验(仅本人,不存在/越权统一 404);
     * 2. 删除 inventory_records 中的目标记录;
     * 3. 删除该用户该 entity_type 的 inventory_current 文档;
     * 4. 将剩余记录按 effective_at 升序(同时间奖励先于快照)逐条重放,
     *    重建当前库存,并把每条记录的实际效果回写 stock_effect。
     *
     * 协议 5.1 §6.1:错误记录由平台管理接口撤销;删除后历史统计不再包含该记录。
     */
    fun deleteRecord(userId: String, recordId: String) {
        val record = recordRepository.findByUserIdAndRecordId(userId, recordId)
            ?: throw ApiResultException(HttpStatus.NOT_FOUND.value(), "记录不存在: $recordId")
        val entityType = record.entityType

        recordRepository.deleteById(record.checkId())

        currentRepository.findByUserIdAndEntityType(userId, entityType)?.let {
            currentRepository.deleteById(it.checkId())
        }

        val remaining = recordRepository.findByUserIdOrderByEffectiveAtAsc(userId)
            .filter { it.entityType == entityType }
            .sortedWith(
                compareBy<InventoryRecord> { it.effectiveAt }
                    .thenBy { if (it.recordType == REWARD_DELTA) 0 else 1 },
            )
        remaining.forEach { replayRecord(userId, it) }
        log.info { "删除库存记录并重放完成: userId=$userId, recordId=$recordId, entityType=$entityType, 重放 ${remaining.size} 条" }
    }

    /**
     * 重放一条已落库记录:重新计算效果、回写 stock_effect、更新当前库存。
     * 不插入流水(记录已在库里)。
     */
    private fun replayRecord(userId: String, entity: InventoryRecord) {
        when (entity.recordType) {
            REWARD_DELTA -> {
                val comp = computeReward(userId, entity)
                recordRepository.save(entity.copy(stockEffect = comp.effect.stock()))
                applyRewardToCurrent(userId, entity.entityType, comp)
            }
            STOCK_SNAPSHOT -> {
                val comp = computeSnapshot(userId, entity)
                recordRepository.save(entity.copy(stockEffect = comp.effect.stock()))
                if (comp.effect == Effect.APPLIED) {
                    applySnapshotToCurrent(userId, entity)
                }
            }
            else -> throw IllegalStateException("已落库记录类型非法: ${entity.recordType}")
        }
    }

    /**
     * 导入记录列表(entityType/from/to 可选过滤;按 effective_at 倒序,最新在前)。
     */
    fun listRecords(userId: String, entityType: String?, from: Instant?, to: Instant?): List<InventoryRecordListItemDto> {
        return recordRepository.findByUserIdOrderByEffectiveAtDesc(userId)
            .filter { entityType == null || it.entityType == entityType }
            .filter { from == null || !it.effectiveAt.isBefore(from) }
            .filter { to == null || it.effectiveAt.isBefore(to) }
            .map { InventoryRecordListItemDto.of(it) }
    }

    // ==================== 查询 ====================

    /**
     * 当前库存查询(entityType 可选;直接读 inventory_current,不扫流水)。
     */
    fun current(userId: String, entityType: String?): List<InventoryCurrentResponse> {
        val list = if (entityType != null) {
            currentRepository.findByUserIdAndEntityType(userId, entityType)?.let { listOf(it) } ?: emptyList()
        } else {
            currentRepository.findByUserIdOrderByUpdatedAtDesc(userId)
        }
        return list.map { InventoryCurrentResponse.of(it) }
    }

    /**
     * 时段获得量([from, to)),只聚合 reward_delta,返回 map<entity_id, count>。
     */
    fun acquired(userId: String, entityType: String, from: Instant, to: Instant): InventoryAcquiredResponse {
        val result = aggregateRewardDelta(userId, entityType, from, to)
        return InventoryAcquiredResponse(
            entityType = entityType,
            from = from,
            to = to,
            acquired = result,
        )
    }

    /**
     * 聚合:match(user, recordType=reward_delta, entityType, effective_at in [from,to)) →
     * unwind entries → group by entries.id → sum entries.count。
     */
    private fun aggregateRewardDelta(userId: String, entityType: String, from: Instant, to: Instant): Map<String, Long> {
        val match = Document(
            mapOf(
                "userId" to userId,
                "recordType" to REWARD_DELTA,
                "entityType" to entityType,
                "effectiveAt" to Document(mapOf("\$gte" to from, "\$lt" to to)),
            ),
        )
        val unwind = Document("\$unwind", "\$entries")
        val group = Document(
            "\$group",
            Document(
                mapOf(
                    "_id" to "\$entries.id",
                    "count" to Document("\$sum", "\$entries.count"),
                ),
            ),
        )
        val result = hubMongoTemplate.getCollection("inventory_records")
            .aggregate(listOf(Document("\$match", match), unwind, group))
        val map = mutableMapOf<String, Long>()
        result.forEach { doc ->
            val id = doc.getString("_id") ?: return@forEach
            val count = (doc["count"] as? Number)?.toLong() ?: 0L
            map[id] = count
        }
        return map
    }

    /**
     * 导出:每种 entity_type 生成一条 full stock_snapshot(当前状态,由 current 直接读取);
     * 若 includeRewards,再附带区间 [from, to) 内的 reward_delta 流水(按 effective_at 升序)。
     */
    fun export(userId: String, includeRewards: Boolean, from: Instant?, to: Instant?): InventoryExportResponse {
        val now = Instant.now()
        val records = mutableListOf<InventoryExportRecordDto>()

        // 当前状态快照:每个 entity_type 一条 full 快照(record_id 用稳定合成值,便于重导入幂等)
        ENTITY_TYPES.forEach { type ->
            val current = currentRepository.findByUserIdAndEntityType(userId, type) ?: return@forEach
            val entries = current.entries.map { (id, se) ->
                InventoryExportEntryDto(id = id, name = null, count = se.count)
            }
            records.add(
                InventoryExportRecordDto(
                    recordId = "myshare:stock:$type:$userId",
                    recordType = STOCK_SNAPSHOT,
                    entityType = type,
                    acquisitionChannel = "背包",
                    effectiveAt = current.fullBaselineAt ?: current.updatedAt,
                    snapshotScope = SNAPSHOT_FULL,
                    entries = entries,
                ),
            )
        }

        // 可选:奖励流水
        if (includeRewards) {
            val rewards = recordRepository.findByUserIdOrderByEffectiveAtAsc(userId)
                .filter { it.recordType == REWARD_DELTA }
                .filter { from == null || !it.effectiveAt.isBefore(from) }
                .filter { to == null || it.effectiveAt.isBefore(to) }
            rewards.forEach { r ->
                records.add(
                    InventoryExportRecordDto(
                        recordId = r.recordId,
                        recordType = r.recordType,
                        entityType = r.entityType,
                        acquisitionChannel = r.acquisitionChannel,
                        effectiveAt = r.effectiveAt,
                        snapshotScope = r.snapshotScope,
                        entries = r.entries.map { e -> InventoryExportEntryDto(id = e.id, name = e.name, count = e.count) },
                    ),
                )
            }
        }

        return InventoryExportResponse(
            exportedAt = now,
            catalogVersion = catalogService.catalog().catalogVersion,
            records = records,
        )
    }

    // ==================== 工具 ====================

    private fun parseInstant(value: String, field: String): Instant {
        return try {
            // 协议 5.1 要求 RFC 3339,须支持带 UTC offset 的时间(如 2026-08-16T10:30:00+08:00)
            // 与纯 UTC 时间(2026-08-16T02:30:00Z)。Instant.parse 只能解析 Z 结尾,
            // 因此统一用 OffsetDateTime 解析后转 Instant。
            java.time.OffsetDateTime.parse(value).toInstant()
        } catch (e: Exception) {
            throw ApiResultException(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "schema_validation_failed: $field 非法时间 $value",
            )
        }
    }

    private fun maxOfNotNull(vararg values: Instant?): Instant? {
        var max: Instant? = null
        values.forEach { v ->
            if (v != null && (max == null || v.isAfter(max))) max = v
        }
        return max
    }

    private data class ValidatedRecord(
        val record: InventoryRecordRequest,
        val effectiveAt: Instant,
    )

    /**
     * 奖励增量计算结果
     */
    private data class RewardComputation(
        val effect: Effect,
        val appliedEntries: List<RecordEntry>,
    )

    /**
     * 快照计算结果
     */
    private data class SnapshotComputation(
        val effect: Effect,
    )

    private enum class Effect {
        APPLIED,
        DUPLICATE,
        HISTORY_ONLY,
        SUPERSEDED,
        ;

        /**
         * 效果 → stock_effect 标记(DUPLICATE 无对应标记,不落库)
         */
        fun stock(): String = when (this) {
            APPLIED -> "applied"
            HISTORY_ONLY -> "history_only"
            SUPERSEDED -> "superseded"
            DUPLICATE -> throw IllegalStateException("DUPLICATE 无对应 stock_effect")
        }
    }

    /**
     * 已加载实体的 id 必然非空(否则不存在于库中),集中校验避免空指针。
     */
    private fun InventoryRecord.checkId(): String = checkNotNull(id) { "流水实体未持久化" }

    private fun InventoryCurrent.checkId(): String = checkNotNull(id) { "库存实体未持久化" }

    companion object {
        private const val SUPPORTED_VERSION = 1
        private const val REWARD_DELTA = "reward_delta"
        private const val STOCK_SNAPSHOT = "stock_snapshot"
        private const val ENTITY_ITEM = "item"
        private const val ENTITY_AGENT = "agent"
        private const val SNAPSHOT_FULL = "full"
        private const val SNAPSHOT_LISTED = "listed"
        private const val APPLIED = "applied"
        private const val HISTORY_ONLY = "history_only"
        private const val SUPERSEDED = "superseded"
        private const val MAX_COUNT = 2147483647L
        private val ENTITY_TYPES = listOf(ENTITY_ITEM, ENTITY_AGENT)
    }
}
