package com.lhs.share.hub.service.recruitment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lhs.share.hub.controller.recruitment.request.RecruitmentExchangeDocument
import com.lhs.share.hub.controller.recruitment.request.RecruitmentRequestDecoder
import com.lhs.share.hub.repository.entity.RecruitmentPoolSnapshot
import java.time.Instant

internal const val RECRUITMENT_EXCHANGE_SCHEMA = "yuanhub.recruitment.v1"
internal const val RECRUITMENT_EXCHANGE_MAX_EVENTS = 20_000
internal const val RECRUITMENT_EXCHANGE_MAX_BYTES = 5 * 1024 * 1024
internal val RECRUITMENT_EXCHANGE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

/** Validate the whole graph before planning any writes; catalog availability is deliberately irrelevant. */
internal class RecruitmentExchangeValidation(private val mapper: ObjectMapper) {
    private val decoder = RecruitmentRequestDecoder(mapper)

    fun read(node: JsonNode): RecruitmentExchangeDocument {
        if (mapper.writeValueAsBytes(node).size > RECRUITMENT_EXCHANGE_MAX_BYTES) fail("备份超过5MiB上限，请拆分整理后重试")
        val document = decoder.read(node, RecruitmentExchangeDocument::class.java)
        validate(document)
        node.path("pools").forEach { if (!it.has("progress")) fail("卡池进度必须显式填写，未知时为null") }
        node.path("events").forEach {
            if (!it.path("agent_snapshot").has("rarity")) fail("密探快照必须记录绝密稀有度")
        }
        return document
    }

    fun validate(document: RecruitmentExchangeDocument) {
        if (document.schema != RECRUITMENT_EXCHANGE_SCHEMA) fail("不支持的招募备份版本")
        if (document.game !in setOf("如鸢", "代号鸢")) fail("备份游戏不正确")
        id(document.sourceAccount.accountId)
        if (document.archiveRevision !in 0 until Long.MAX_VALUE) fail("源档案版本不正确")
        recruitmentCount(document.baseline, "baseline")
        if (document.pools.size > 500 || document.temporaryAgents.size > 1000 ||
            document.events.size > RECRUITMENT_EXCHANGE_MAX_EVENTS || document.batches.size > RECRUITMENT_EXCHANGE_MAX_EVENTS
        ) {
            fail("备份超过卡池/临时密探/20000条记录上限，整包不能导入")
        }
        unique(document.pools.map { it.poolId })
        unique(document.temporaryAgents.map { it.agentId })
        unique(document.events.map { it.eventId })
        unique(document.batches.map { it.batchId })
        val pools = document.pools.associateBy { it.poolId }
        val agents = document.temporaryAgents.associateBy { it.agentId }
        val batches = document.batches.associateBy { it.batchId }
        document.currentPoolId?.let { if (it !in pools) fail("当前卡池引用不存在") }
        document.pools.forEach {
            snapshot(it.snapshot, document.game)
            it.progress?.let { count -> recruitmentCount(count, "progress") }
            it.mappedSnapshot?.let { mapped ->
                snapshot(mapped, document.game)
                if (it.snapshot.catalogPoolId != null || mapped.catalogPoolId == null) fail("卡池映射关系不正确")
            }
        }
        document.temporaryAgents.forEach {
            if (!it.agentId.startsWith("tmp_")) fail("临时密探ID必须以tmp_开头")
            text(it.name, 128)
            if ((it.mappedAgentId == null) != (it.mappedName == null)) fail("密探映射ID和名称须同时提供")
            it.mappedAgentId?.let { mapped ->
                id(mapped)
                if (mapped.startsWith("tmp_")) fail("临时密探不能映射到另一临时密探")
            }
            it.mappedName?.let { name -> text(name, 128) }
        }
        document.batches.forEach {
            if (it.poolId !in pools) fail("批次引用的卡池不存在")
            recruitmentCount(it.totalPullCount, "total_pull_count", true)
            tombstone(it.deletedAt, it.deletedRevision, it.createdAt, document.archiveRevision)
            metadata(it.importBatchId, it.importedAt)
        }
        if (document.events.map { it.sortOrder }.toSet().size != document.events.size) fail("记录顺序不能重复")
        document.events.forEach {
            if (it.poolId !in pools) fail("记录引用的卡池不存在")
            if (it.sortOrder !in 1..(Long.MAX_VALUE - RECRUITMENT_EXCHANGE_MAX_EVENTS - 1)) fail("记录顺序超出范围")
            snapshot(it.poolSnapshot, document.game)
            id(it.agentSnapshot.agentId)
            text(it.agentSnapshot.name, 128)
            if (it.agentSnapshot.rarity != 5) fail("备份记录只能包含绝密密探")
            if (it.agentSnapshot.temporary) {
                if (it.agentSnapshot.agentId !in agents) fail("记录引用的临时密探不存在")
            } else if (it.agentSnapshot.agentId.startsWith("tmp_")) {
                fail("临时密探快照标记不正确")
            }
            it.agentSnapshot.catalogRevision?.let { value -> text(value, 128) }
            it.pullSpan?.let { span -> recruitmentCount(span, "pull_span", true) }
            if (it.upStatus !in setOf("up", "non_up", "unknown")) fail("记录UP状态不正确")
            it.note?.let { value -> if (value.length > 1000) fail("备注超过1000字上限") }
            text(it.source, 128)
            if (it.updatedAt < it.createdAt) fail("记录更新时间早于创建时间")
            tombstone(it.deletedAt, it.deletedRevision, it.createdAt, document.archiveRevision)
            if (it.deletedAt != null && it.deletedAt > it.updatedAt) fail("删除时间晚于记录更新时间")
            metadata(it.importBatchId, it.importedAt)
            it.batchId?.let { batchId ->
                val batch = batches[batchId] ?: fail("记录引用的批次不存在")
                if (batch.poolId != it.poolId) fail("记录与批次所属卡池不一致")
                if (batch.deletedAt != null && it.deletedAt == null) fail("已删除批次不能包含有效记录")
            }
        }
        val liveSpans = document.events.filter { it.deletedAt == null && it.batchId != null }
            .groupBy { it.batchId }.mapValues { (_, records) -> records.sumOf { it.pullSpan ?: 0 } }
        document.batches.forEach {
            if ((liveSpans[it.batchId] ?: 0) > it.totalPullCount) fail("批次内已知间隔之和超出总抽数")
        }
    }

    private fun snapshot(value: RecruitmentPoolSnapshot, game: String) {
        text(value.name, 128)
        if (value.game != game) fail("卡池快照游戏与备份不一致")
        value.catalogPoolId?.let(::id)
        if (value.startDate != null && value.endDate != null && value.startDate > value.endDate) fail("卡池日期范围不正确")
        if (value.upStatus !in setOf("verified", "partial", "selection", "unknown")) fail("卡池UP状态不正确")
        unique(value.upAgentIds)
        if (value.upAgentIds.size > 1000 || value.upAgentNames.size > 1000 || value.unmappedUpAgentNames.size > 1000) fail("UP名单过长")
        (value.upAgentNames + value.unmappedUpAgentNames).forEach { text(it, 128) }
        value.catalogRevision?.let { text(it, 128) }
        value.poolType?.let { text(it, 128) }
    }

    private fun tombstone(deleted: Instant?, revision: Long?, created: Instant, sourceRevision: Long) {
        if ((deleted == null) != (revision == null)) fail("删除时间和版本须同时提供")
        if (deleted != null && (deleted < created || revision!! !in 0..sourceRevision)) fail("删除标记不正确")
    }
    private fun metadata(batchId: String?, imported: Instant?) {
        if ((batchId == null) != (imported == null)) fail("导入元信息须同时提供")
        batchId?.let(::id)
    }
    private fun unique(ids: List<String>) {
        ids.forEach(::id)
        if (ids.toSet().size != ids.size) fail("备份稳定ID重复")
    }
    private fun id(value: String) {
        if (!RECRUITMENT_EXCHANGE_ID.matches(value)) fail("备份ID格式不正确")
    }
    private fun text(value: String, maximum: Int) {
        if (value.isBlank() || value.length > maximum) fail("备份文本为空或超过长度上限")
    }
    private fun fail(message: String): Nothing = throw recruitmentInvalid(message)
}
