package com.lhs.share.hub.service.recruitment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lhs.share.hub.repository.entity.RecruitmentPoolSnapshot
import com.lhs.share.hub.service.account.SubAccountService
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class RecruitmentCatalog(private val mapper: ObjectMapper) {
    private val resource: JsonNode by lazy {
        val file = ClassPathResource("recruitment/pools.json")
        if (file.exists()) {
            file.inputStream.use(mapper::readTree)
        } else {
            mapper.createObjectNode()
                .put("catalog_revision", "empty-v1").set<JsonNode>("pools", mapper.createArrayNode())
        }
    }

    fun catalog(game: String): JsonNode {
        if (game !in SubAccountService.SUPPORTED_GAMES) throw recruitmentInvalid("游戏只支持代号鸢或如鸢")
        val pools = mapper.createArrayNode()
        resource.path("pools").filter { it.path("game").asText() == game }.forEach { pools.add(it.deepCopy<JsonNode>()) }
        return mapper.createObjectNode().put("catalog_revision", resource.path("catalog_revision").asText("empty-v1")).set("pools", pools)
    }

    fun snapshot(game: String, poolId: String): RecruitmentPoolSnapshot {
        val pool = resource.path("pools").firstOrNull { it.path("pool_id").asText() == poolId && it.path("game").asText() == game }
            ?: throw RecruitmentApiException(
                HttpStatus.NOT_FOUND,
                "recruitment_catalog_pool_not_found",
                "当前游戏没有该公共卡池，请选择临时卡池",
            )
        fun date(field: String): LocalDate? = pool[field]?.takeUnless { it.isNull }?.asText()?.let(LocalDate::parse)
        fun strings(field: String) = pool.path(field).map(JsonNode::asText)
        return RecruitmentPoolSnapshot(
            name = pool.path("name").asText(), game = game, catalogPoolId = poolId,
            startDate = date("start_date"), endDate = date("end_date"), upAgentIds = strings("up_agent_ids"),
            upStatus = pool.path("up_status").asText("unknown"), upAgentNames = strings("up_agent_names"),
            unmappedUpAgentNames = strings("unmapped_up_agent_names"), catalogRevision = resource.path("catalog_revision").asText(),
            poolType = pool["pool_type"]?.takeUnless { it.isNull }?.asText(),
        )
    }
}
