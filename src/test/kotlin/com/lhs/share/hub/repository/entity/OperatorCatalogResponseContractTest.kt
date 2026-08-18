package com.lhs.share.hub.repository.entity

import com.fasterxml.jackson.databind.ObjectMapper
import com.lhs.share.hub.controller.operator.response.OperatorCatalogEntryResponse
import com.lhs.share.hub.controller.operator.response.OperatorCatalogResponse
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 公共图鉴契约锁定：GET /v1/operator/catalog 只回答"有哪些密探、长什么样"，
 * 不携带任何用户养成信息。每条密探不得出现 starStones（目录里每个密探都是同一份
 * "主星石/辅星石" 模板），否则前端会把模板星石当真实养成数据展示；
 * id 必须是业务密探 id（operatorId），不得泄漏 Mongo _id。
 */
class OperatorCatalogResponseContractTest {
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    private fun response() = OperatorCatalogResponse(
        catalogVersion = "2026-08-16",
        operators = listOf(
            OperatorCatalogEntryResponse.of(
                OperatorCatalogEntity(
                    id = "6a83e6760776fc2dc6dbe5bb", // Mongo _id，不应出现在 JSON
                    operatorId = "char_001_yangxiu",
                    name = "杨修",
                    rarity = 5,
                    prof = listOf("阳"),
                    subProf = emptyList(),
                    games = listOf("如鸢", "代号鸢"),
                    discs = listOf(OperatorDiscCatalog("初始能量+2", "初始+2", "金", null)),
                    starStones = listOf(
                        OperatorStarStoneCatalog("主星石", "main"),
                        OperatorStarStoneCatalog("辅星石", "assist"),
                    ),
                    catalogVersion = "2026-08-16",
                ),
            ),
        ),
    )

    @Test
    fun `catalog entry serializes business id and discs but never starStones`() {
        val json = objectMapper.writeValueAsString(response())
        assertTrue(json.contains("\"id\":\"char_001_yangxiu\""), json)
        assertFalse(json.contains("6a83e6760776fc2dc6dbe5bb"), json)
        assertFalse(json.contains("operatorId"), json)
        assertTrue(json.contains("\"otName\":\"初始能量+2\""), json)
        assertFalse(json.contains("starStones"), json)
        assertFalse(json.contains("主星石"), json)
        assertFalse(json.contains("辅星石"), json)
    }
}
